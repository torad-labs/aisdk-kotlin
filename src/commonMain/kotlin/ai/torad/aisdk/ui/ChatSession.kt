package ai.torad.aisdk.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

data class ChatState(
    val id: String,
    val messages: List<UIMessage> = emptyList(),
    val status: ChatStatus = ChatStatus.Ready,
    val error: Throwable? = null,
) {
    val isStreaming: Boolean
        get() = status == ChatStatus.Submitted || status == ChatStatus.Streaming
}

class ChatSession(
    private val chat: Chat,
) {
    private val mutableState = MutableStateFlow(chat.toState())

    val state: StateFlow<ChatState> = mutableState.asStateFlow()

    val id: String get() = chat.id

    fun setMessages(messages: List<UIMessage>) {
        chat.setMessages(messages)
        syncState()
    }

    fun clearError() {
        chat.clearError()
        syncState()
    }

    fun addToolApprovalResponse(
        toolCallId: String,
        approved: Boolean,
        reason: String? = null,
        approvalId: String? = null,
    ) {
        chat.addToolApprovalResponse(toolCallId, approved, reason, approvalId)
        syncState()
    }

    fun addToolOutput(
        toolCallId: String,
        output: JsonElement,
        toolName: String = "tool",
    ) {
        chat.addToolOutput(toolCallId, output, toolName)
        syncState()
    }

    fun sendMessage(message: UIMessage, body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> = flow {
        // L-3 (eager vs cold): this stays a cold Flow — its contract, exercised
        // by ChatSessionTest, is that no turn starts until collection. So the
        // optimistic Submitted/append happens at collection time, not at call
        // time. The write is now an atomic `update` based on the current state
        // (not a torn read of `chat`'s loose fields), which is the real
        // concurrency fix here. AgentSession can append eagerly because submit()
        // returns a Job, not a cold Flow — making this one eager would mutate
        // state on a bare sendMessage() call with no collector, a surprising
        // side effect.
        mutableState.update {
            it.copy(
                messages = it.messages + message,
                status = ChatStatus.Submitted,
                error = null,
            )
        }
        try {
            chat.sendMessage(message, body).collect { response ->
                syncState()
                emit(response)
            }
        } finally {
            syncState()
        }
    }

    fun regenerate(body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> = flow {
        mutableState.update { it.copy(status = ChatStatus.Submitted, error = null) }
        try {
            chat.regenerate(body).collect { response ->
                syncState()
                emit(response)
            }
        } finally {
            syncState()
        }
    }

    fun stop() {
        chat.stop()
        syncState()
    }

    fun resumeStream(headers: Map<String, String> = emptyMap()): Flow<UIMessage> =
        chat.resumeStream(headers)

    private fun syncState() {
        mutableState.value = chat.toState()
    }
}

fun chatSession(
    id: String = "chat",
    initialMessages: List<UIMessage> = emptyList(),
    transport: ChatTransport,
): ChatSession = ChatSession(
    Chat(
        id = id,
        initialMessages = initialMessages,
        transport = transport,
    ),
)

fun Chat.asSession(): ChatSession = ChatSession(this)

private fun Chat.toState(): ChatState = ChatState(
    id = id,
    messages = messages,
    status = status,
    error = error,
)
