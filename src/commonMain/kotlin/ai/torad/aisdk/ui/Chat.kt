package ai.torad.aisdk.ui

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

data class ChatRequest(
    val messages: List<UIMessage>,
    val body: Map<String, JsonElement> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

interface ChatTransport {
    fun sendMessages(request: ChatRequest): Flow<UIMessage>

    fun reconnectToStream(chatId: String, headers: Map<String, String> = emptyMap()): Flow<UIMessage>? = null
}

class DirectChatTransport(
    private val handler: (ChatRequest) -> Flow<UIMessage>,
) : ChatTransport {
    override fun sendMessages(request: ChatRequest): Flow<UIMessage> = handler(request)
}

class DefaultChatTransport(
    private val delegate: ChatTransport,
) : ChatTransport {
    override fun sendMessages(request: ChatRequest): Flow<UIMessage> =
        delegate.sendMessages(request)

    override fun reconnectToStream(chatId: String, headers: Map<String, String>): Flow<UIMessage>? =
        delegate.reconnectToStream(chatId, headers)
}

class TextStreamChatTransport(
    private val handler: (ChatRequest) -> Flow<String>,
    private val assistantMessageId: (ChatRequest) -> String = { request ->
        getResponseUiMessageId(request.messages)
    },
) : ChatTransport {
    override fun sendMessages(request: ChatRequest): Flow<UIMessage> =
        transformTextToUiMessageStream(handler(request), assistantMessageId(request))
}

enum class ChatStatus {
    Ready,
    Submitted,
    Streaming,
    Error,
}

class Chat(
    val id: String = "chat",
    initialMessages: List<UIMessage> = emptyList(),
    private val transport: ChatTransport,
) {
    // All chat state — messages, status, error, and the approval-id cursor —
    // lives in a single atomic holder. Every read-modify-write goes through
    // [MutableStateFlow.update], so concurrent appends, upserts, and status
    // transitions never interleave into a torn state (a plain MutableList +
    // loose `var`s would race, and a racy MutableList is UB on Native).
    private val internalState = MutableStateFlow(
        InternalState(
            messages = initialMessages.toList(),
            nextApprovalIndex = nextApprovalIndexAfter(initialMessages),
        ),
    )

    // @Volatile for cross-thread visibility: an in-flight sendMessage/regenerate
    // collector reads this to decide whether it is still the active operation
    // before writing state. A newer sendMessage or a stop() supersedes the
    // prior op so its trailing terminal writes (Ready/Error) cannot clobber the
    // newer op's state — mirroring AgentSession's `currentJob` identity guard.
    @Volatile
    private var currentOp: Any? = null

    val status: ChatStatus
        get() = internalState.value.status

    val error: Throwable?
        get() = internalState.value.error

    val messages: List<UIMessage>
        get() = internalState.value.messages

    fun setMessages(messages: List<UIMessage>) {
        validateUiMessages(messages)
        internalState.update {
            it.copy(
                messages = messages.toList(),
                nextApprovalIndex = nextApprovalIndexAfter(messages),
            )
        }
    }

    fun clearError() {
        internalState.update {
            it.copy(
                error = null,
                status = if (it.status == ChatStatus.Error) ChatStatus.Ready else it.status,
            )
        }
    }

    fun addToolApprovalResponse(
        toolCallId: String,
        approved: Boolean,
        reason: String? = null,
        approvalId: String? = null,
    ) {
        val responsePart = UIMessagePart.ToolUI(
            toolCallId = toolCallId,
            toolName = "approval",
            state = if (approved) ToolCallState.OutputAvailable else ToolCallState.OutputDenied,
            output = JsonPrimitive(approvalId ?: toolCallId),
            error = reason,
        )
        appendToolMessage(responsePart)
    }

    fun addToolOutput(
        toolCallId: String,
        output: JsonElement,
        toolName: String = "tool",
    ) {
        appendToolMessage(
            UIMessagePart.ToolUI(
                toolCallId = toolCallId,
                toolName = toolName,
                state = ToolCallState.OutputAvailable,
                output = output,
            ),
        )
    }

    @Deprecated("Use addToolOutput instead.")
    fun addToolResult(
        toolCallId: String,
        output: JsonElement,
        toolName: String = "tool",
    ) = addToolOutput(toolCallId, output, toolName)

    fun sendMessage(message: UIMessage, body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> = flow {
        // The optimistic append stays inside the flow body to preserve the cold
        // contract: collecting starts the turn; merely calling sendMessage does
        // not mutate state. (See the L-3 note in ChatSession for why eager
        // append belongs at the session layer, not here.) The token claims this
        // op as the active one so a superseded collector can't write back.
        val op = Any()
        currentOp = op
        val request = internalState.updateAndGet {
            it.copy(
                messages = it.messages + message,
                status = ChatStatus.Submitted,
                error = null,
            )
        }.let { ChatRequest(messages = it.messages, body = body) }
        try {
            transport.sendMessages(request).collect { response ->
                if (currentOp === op) {
                    internalState.update { it.copy(status = ChatStatus.Streaming).withUpsert(response) }
                }
                emit(response)
            }
            if (currentOp === op) {
                internalState.update { it.copy(status = ChatStatus.Ready) }
            }
        } catch (t: Throwable) {
            if (currentOp === op) {
                internalState.update { it.copy(error = t, status = ChatStatus.Error) }
            }
            throw t
        }
    }

    fun regenerate(body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> {
        val lastUser = internalState.value.messages.lastOrNull { it.role == UIMessageRole.User }
            ?: return emptyFlow()
        return sendMessage(lastUser, body)
    }

    fun stop() {
        // Supersede any in-flight op so its trailing terminal writes are ignored,
        // then settle to Ready atomically.
        currentOp = null
        internalState.update { it.copy(status = ChatStatus.Ready) }
    }

    fun reconnectToStream(headers: Map<String, String> = emptyMap()): Flow<UIMessage>? =
        transport.reconnectToStream(id, headers)

    fun resumeStream(headers: Map<String, String> = emptyMap()): Flow<UIMessage> =
        reconnectToStream(headers) ?: emptyFlow()

    private fun appendToolMessage(part: UIMessagePart.ToolUI) {
        // Allocate the id and append in one atomic update so two concurrent
        // tool responses can't claim the same approval index.
        internalState.update { current ->
            val (id, nextIndex) = current.nextApprovalResponseId()
            current.copy(
                messages = current.messages + UIMessage(
                    id = id,
                    role = UIMessageRole.User,
                    parts = listOf(part),
                ),
                nextApprovalIndex = nextIndex,
            )
        }
    }

    private data class InternalState(
        val messages: List<UIMessage> = emptyList(),
        val status: ChatStatus = ChatStatus.Ready,
        val error: Throwable? = null,
        val nextApprovalIndex: Int = 1,
    ) {
        fun withUpsert(message: UIMessage): InternalState {
            val index = messages.indexOfFirst { it.id == message.id }
            val nextMessages = if (index >= 0) {
                messages.toMutableList().also { it[index] = message }
            } else {
                messages + message
            }
            return copy(messages = nextMessages)
        }

        fun nextApprovalResponseId(): Pair<String, Int> {
            val existingIds = messages.mapTo(mutableSetOf()) { it.id }
            var index = nextApprovalIndex
            while (true) {
                val candidate = "$TOOL_APPROVAL_RESPONSE_ID_PREFIX${index++}"
                if (candidate !in existingIds) return candidate to index
            }
        }
    }

    private companion object {
        const val TOOL_APPROVAL_RESPONSE_ID_PREFIX = "tool_approval_"

        fun nextApprovalIndexAfter(messages: List<UIMessage>): Int =
            messages.mapNotNull { message ->
                message.id.removePrefix(TOOL_APPROVAL_RESPONSE_ID_PREFIX)
                    .takeIf { it != message.id }
                    ?.toIntOrNull()
            }.maxOrNull()?.plus(1) ?: 1
    }
}
