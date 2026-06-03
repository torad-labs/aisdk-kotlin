package ai.torad.aisdk.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

class Chat(
    val id: String = "chat",
    initialMessages: List<UIMessage> = emptyList(),
    private val transport: ChatTransport,
) {
    private val mutableMessages: MutableList<UIMessage> = initialMessages.toMutableList()
    private var nextToolApprovalResponseIndex: Int = nextApprovalIndexAfter(initialMessages)

    val messages: List<UIMessage>
        get() = mutableMessages.toList()

    fun setMessages(messages: List<UIMessage>) {
        validateUiMessages(messages)
        mutableMessages.clear()
        mutableMessages.addAll(messages)
        nextToolApprovalResponseIndex = nextApprovalIndexAfter(messages)
    }

    fun addToolApprovalResponse(
        toolCallId: String,
        approved: Boolean,
        reason: String? = null,
        approvalId: String? = null,
    ) {
        val responseId = nextToolApprovalResponseId()
        val responsePart = UIMessagePart.ToolUI(
            toolCallId = toolCallId,
            toolName = "approval",
            state = if (approved) ToolCallState.OutputAvailable else ToolCallState.OutputDenied,
            output = JsonPrimitive(approvalId ?: toolCallId),
            error = reason,
        )
        mutableMessages += UIMessage(
            id = responseId,
            role = UIMessageRole.User,
            parts = listOf(responsePart),
        )
    }

    fun sendMessage(message: UIMessage, body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> = flow {
        mutableMessages += message
        val request = ChatRequest(messages = mutableMessages.toList(), body = body)
        transport.sendMessages(request).collect { response ->
            upsertMessage(response)
            emit(response)
        }
    }

    fun reconnectToStream(headers: Map<String, String> = emptyMap()): Flow<UIMessage>? =
        transport.reconnectToStream(id, headers)

    private fun upsertMessage(message: UIMessage) {
        val index = mutableMessages.indexOfFirst { it.id == message.id }
        if (index >= 0) {
            mutableMessages[index] = message
        } else {
            mutableMessages += message
        }
    }

    private fun nextToolApprovalResponseId(): String {
        val existingIds = mutableMessages.map { it.id }.toSet()
        while (true) {
            val candidate = "$TOOL_APPROVAL_RESPONSE_ID_PREFIX${nextToolApprovalResponseIndex++}"
            if (candidate !in existingIds) return candidate
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
