package ai.torad.aisdk.ui

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@Poko
public class ChatRequest internal constructor(
    public val messages: List<UIMessage>,
    public val body: Map<String, JsonElement> = emptyMap(),
    public val headers: Map<String, String> = emptyMap(),
)

public class ChatRequestBuilder {
    private var messages: List<UIMessage>? = null
    private var body: Map<String, JsonElement> = emptyMap()
    private var headers: Map<String, String> = emptyMap()

    public fun messages(value: List<UIMessage>): ChatRequestBuilder {
        messages = value
        return this
    }

    public fun body(value: Map<String, JsonElement>): ChatRequestBuilder {
        body = value
        return this
    }

    public fun headers(value: Map<String, String>): ChatRequestBuilder {
        headers = value
        return this
    }

    public fun build(): ChatRequest =
        ChatRequest(
            messages = requireNotNull(messages) { "ChatRequest.messages is required" },
            body = body,
            headers = headers,
        )
}

public fun ChatRequest(
    block: ChatRequestBuilder.() -> Unit = {},
): ChatRequest =
    ChatRequestBuilder().apply(block).build()

public interface ChatTransport {
    public fun sendMessages(request: ChatRequest): Flow<UIMessage>

    public fun reconnectToStream(chatId: String, headers: Map<String, String> = emptyMap()): Flow<UIMessage>? = null
}

public class DirectChatTransport(
    private val handler: (ChatRequest) -> Flow<UIMessage>,
) : ChatTransport {
    override fun sendMessages(request: ChatRequest): Flow<UIMessage> = handler(request)
}

public class DefaultChatTransport(
    private val delegate: ChatTransport,
) : ChatTransport {
    override fun sendMessages(request: ChatRequest): Flow<UIMessage> =
        delegate.sendMessages(request)

    override fun reconnectToStream(chatId: String, headers: Map<String, String>): Flow<UIMessage>? =
        delegate.reconnectToStream(chatId, headers)
}

public class TextStreamChatTransport(
    private val handler: (ChatRequest) -> Flow<String>,
    private val assistantMessageId: (ChatRequest) -> String = { request ->
        UiMessageStreams.getResponseUiMessageId(request.messages)
    },
) : ChatTransport {
    override fun sendMessages(request: ChatRequest): Flow<UIMessage> =
        TransformTextToUiMessageStream(handler(request), assistantMessageId(request))
}

public enum class ChatStatus {
    Ready,
    Submitted,
    Streaming,
    Error,
}

@OptIn(ExperimentalAtomicApi::class)
public class Chat(
    public val id: String = "chat",
    initialMessages: List<UIMessage> = emptyList(),
    private val transport: ChatTransport,
) {
    // All chat state — messages, status, error, and the approval-id cursor —
    // lives in a single atomic holder. Every read-modify-write goes through
    // [applyState], so concurrent appends, upserts, and status transitions
    // never interleave into a torn state.
    private val internalState = MutableStateFlow(
        InternalState(
            messages = initialMessages.toList(),
            nextApprovalIndex = nextApprovalIndexAfter(initialMessages),
        ),
    )

    // Observable state view — always reflects the latest InternalState.
    private val _state = MutableStateFlow(internalState.value.toChatState())

    public val state: StateFlow<ChatState> = _state.asStateFlow()

    // Cross-thread visibility via AtomicReference: an in-flight sendMessage/regenerate
    // collector reads this to decide whether it is still the active operation
    // before writing state.
    private val currentOpRef = AtomicReference<Any?>(null)
    private val currentOpJobRef = AtomicReference<Job?>(null as Job?)

    public val status: ChatStatus
        get() = internalState.value.status

    public val error: Throwable?
        get() = internalState.value.error

    public val messages: List<UIMessage>
        get() = internalState.value.messages

    internal fun toState(): ChatState = ChatState(
        id = id,
        messages = messages,
        status = status,
        error = error,
    )

    // Atomically updates internalState and syncs the public StateFlow.
    private fun applyState(block: InternalState.() -> InternalState): InternalState =
        internalState.updateAndGet(block).also { _state.value = it.toChatState() }

    private fun InternalState.toChatState(): ChatState = ChatState(
        id = this@Chat.id,
        messages = messages,
        status = status,
        error = error,
    )

    public fun setMessages(messages: List<UIMessage>) {
        UiMessageStreams.validateUiMessages(messages)
        applyState {
            copy(
                messages = messages.toList(),
                nextApprovalIndex = nextApprovalIndexAfter(messages),
            )
        }
    }

    public fun clearError() {
        applyState {
            copy(
                error = null,
                status = if (status == ChatStatus.Error) ChatStatus.Ready else status,
            )
        }
    }

    public fun addToolApprovalResponse(
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
            approvalId = approvalId ?: toolCallId,
        )
        appendToolMessage(responsePart)
    }

    public fun addToolOutput(
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
    public fun addToolResult(
        toolCallId: String,
        output: JsonElement,
        toolName: String = "tool",
    ): Unit = addToolOutput(toolCallId, output, toolName)

    public fun sendMessage(message: UIMessage, body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> =
        sendInternal(body) { it + message }

    public fun regenerate(body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> {
        // Re-run from the existing history with the trailing assistant turn(s) dropped. Do NOT
        // re-append the last user message — it is already present, and appending it (as the old
        // code did via sendMessage) duplicated its id and sent a doubled user turn to the model.
        if (internalState.value.messages.none { it.role == UIMessageRole.User }) return emptyFlow()
        return sendInternal(body) { msgs -> msgs.dropLastWhile { it.role == UIMessageRole.Assistant } }
    }

    private fun sendInternal(
        body: Map<String, JsonElement>,
        transformMessages: (List<UIMessage>) -> List<UIMessage>,
    ): Flow<UIMessage> = flow {
        val op = Any()
        val opJob = currentCoroutineContext()[Job]
        currentOpJobRef.store(opJob)
        currentOpRef.store(op)
        val request = applyState {
            copy(messages = transformMessages(messages), status = ChatStatus.Submitted, error = null)
        }.let {
            ChatRequest {
                messages(it.messages)
                body(body)
            }
        }
        try {
            transport.sendMessages(request).collect { response ->
                if (currentOpRef.load() === op) {
                    applyState { copy(status = ChatStatus.Streaming).withUpsert(response) }
                }
                emit(response)
            }
            if (currentOpRef.load() === op) {
                applyState { copy(status = ChatStatus.Ready) }
            }
        } catch (t: CancellationException) {
            if (currentOpRef.load() === op) {
                applyState { copy(error = null, status = ChatStatus.Ready) }
            }
            throw t
        } catch (t: Throwable) {
            if (currentOpRef.load() === op) {
                applyState { copy(error = t, status = ChatStatus.Error) }
            }
            throw t
        } finally {
            if (currentOpJobRef.load() === opJob) {
                currentOpJobRef.store(null)
            }
        }
    }

    public fun stop() {
        currentOpJobRef.load()?.cancel()
        currentOpJobRef.store(null)
        currentOpRef.store(null)
        applyState { copy(status = ChatStatus.Ready) }
    }

    public fun reconnectToStream(headers: Map<String, String> = emptyMap()): Flow<UIMessage>? =
        transport.reconnectToStream(id, headers)

    public fun resumeStream(headers: Map<String, String> = emptyMap()): Flow<UIMessage> =
        reconnectToStream(headers) ?: emptyFlow()

    private fun appendToolMessage(part: UIMessagePart.ToolUI) {
        applyState {
            val (msgId, nextIndex) = nextApprovalResponseId()
            copy(
                messages = messages + UIMessage(
                    id = msgId,
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
