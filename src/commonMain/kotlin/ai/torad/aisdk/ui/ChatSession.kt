package ai.torad.aisdk.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

public data class ChatState(
    val id: String,
    val messages: List<UIMessage> = emptyList(),
    val status: ChatStatus = ChatStatus.Ready,
    val error: Throwable? = null,
) {
    val isStreaming: Boolean
        get() = status == ChatStatus.Submitted || status == ChatStatus.Streaming
}

public class ChatSession(
    private val chat: Chat,
) {
    private val mutableState = MutableStateFlow(chat.toState())

    public val state: StateFlow<ChatState> = mutableState.asStateFlow()

    public val id: String get() = chat.id

    public fun setMessages(messages: List<UIMessage>) {
        chat.setMessages(messages)
        syncState()
    }

    public fun clearError() {
        chat.clearError()
        syncState()
    }

    public fun addToolApprovalResponse(
        toolCallId: String,
        approved: Boolean,
        reason: String? = null,
        approvalId: String? = null,
    ) {
        chat.addToolApprovalResponse(toolCallId, approved, reason, approvalId)
        syncState()
    }

    public fun addToolOutput(
        toolCallId: String,
        output: JsonElement,
        toolName: String = "tool",
    ) {
        chat.addToolOutput(toolCallId, output, toolName)
        syncState()
    }

    public fun sendMessage(message: UIMessage, body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> = flow {
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

    public fun regenerate(body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> = flow {
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

    public fun stop() {
        chat.stop()
        syncState()
    }

    public fun resumeStream(headers: Map<String, String> = emptyMap()): Flow<UIMessage> =
        chat.resumeStream(headers)

    private fun syncState() {
        mutableState.value = chat.toState()
    }
}

// Faux-constructor factory (was top-level `fun chatSession(...)`): overloads the
// ChatSession class name to build one from transport config.
public fun ChatSession(
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

// Public Chat extension (was top-level `fun Chat.asSession()`), now a
// member-extension. Callers use member-import or `with(ChatSessionFactory) { ... }`.
public object ChatSessionFactory {
    public fun Chat.asSession(): ChatSession = ChatSession(this)
}
