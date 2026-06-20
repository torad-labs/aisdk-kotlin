package ai.torad.aisdk.react

import ai.torad.aisdk.Completion
import ai.torad.aisdk.ExperimentalAiSdkApi
import ai.torad.aisdk.StructuredObject
import ai.torad.aisdk.StructuredObjectOptions
import ai.torad.aisdk.UseCompletionOptions
import ai.torad.aisdk.ui.Chat
import ai.torad.aisdk.ui.ChatStatus
import ai.torad.aisdk.ui.ChatTransport
import ai.torad.aisdk.ui.DirectChatTransport
import ai.torad.aisdk.ui.UIMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement

@ExperimentalAiSdkApi
public typealias UIMessage = ai.torad.aisdk.ui.UIMessage

@ExperimentalAiSdkApi
public typealias UseCompletionOptions = ai.torad.aisdk.UseCompletionOptions

@ExperimentalAiSdkApi
public typealias CompletionRequestOptions = ai.torad.aisdk.CompletionRequestOptions

@ExperimentalAiSdkApi
public typealias Experimental_UseObjectOptions<RESULT, INPUT> = StructuredObjectOptions<RESULT, INPUT>

@ExperimentalAiSdkApi
public data class UseChatOptions(
    val chat: Chat? = null,
    val id: String = "chat",
    val initialMessages: List<UIMessage> = emptyList(),
    val transport: ChatTransport = DirectChatTransport { emptyFlow() },
    val resume: Boolean = false,
)

@ExperimentalAiSdkApi
public class UseChatHelpers internal constructor(
    private val chat: Chat,
) {
    public val id: String get() = chat.id
    public val messages: List<UIMessage> get() = chat.messages
    public val status: ChatStatus get() = chat.status
    public val error: Throwable? get() = chat.error

    public fun setMessages(messages: List<UIMessage>): Unit = chat.setMessages(messages)

    public fun sendMessage(message: UIMessage, body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> =
        chat.sendMessage(message, body)

    public fun regenerate(body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> =
        chat.regenerate(body)

    public fun stop(): Unit = chat.stop()
    public fun resumeStream(): Flow<UIMessage> = chat.resumeStream()
    public fun clearError(): Unit = chat.clearError()

    public fun addToolOutput(toolCallId: String, output: JsonElement, toolName: String = "tool"): Unit =
        chat.addToolOutput(toolCallId, output, toolName)

    @Deprecated("Use addToolOutput instead.")
    public fun addToolResult(toolCallId: String, output: JsonElement, toolName: String = "tool"): Unit =
        chat.addToolOutput(toolCallId, output, toolName)

    public fun addToolApprovalResponse(
        toolCallId: String,
        approved: Boolean,
        reason: String? = null,
        approvalId: String? = null,
    ): Unit = chat.addToolApprovalResponse(toolCallId, approved, reason, approvalId)
}

@ExperimentalAiSdkApi
public fun useChat(options: UseChatOptions = UseChatOptions()): UseChatHelpers {
    val chat = options.chat ?: Chat(
        id = options.id,
        initialMessages = options.initialMessages,
        transport = options.transport,
    )
    return UseChatHelpers(chat)
}

@ExperimentalAiSdkApi
public class UseCompletionHelpers internal constructor(
    private val completionState: Completion,
) {
    public val completion: String get() = completionState.completion
    public val error: Throwable? get() = completionState.error
    public val input: String get() = completionState.input
    public val isLoading: Boolean get() = completionState.loading

    public suspend fun complete(prompt: String, options: CompletionRequestOptions = CompletionRequestOptions()): String? =
        completionState.complete(prompt, options)

    public fun stop(): Unit = completionState.stop()
    public fun setCompletion(completion: String): Unit = completionState.setCompletion(completion)
    public fun setInput(input: String) {
        completionState.setInput(input)
    }

    public suspend fun handleSubmit(): String? = completionState.handleSubmit()
}

@ExperimentalAiSdkApi
public fun useCompletion(options: UseCompletionOptions = UseCompletionOptions()): UseCompletionHelpers =
    UseCompletionHelpers(Completion(options))

@ExperimentalAiSdkApi
public class Experimental_UseObjectHelpers<RESULT, INPUT> internal constructor(
    private val structuredObject: StructuredObject<RESULT, INPUT>,
) {
    public val value: RESULT? get() = structuredObject.value
    public val rawValue: JsonElement? get() = structuredObject.rawValue
    public val error: Throwable? get() = structuredObject.error
    public val isLoading: Boolean get() = structuredObject.loading

    public suspend fun submit(input: INPUT): Unit = structuredObject.submit(input)
    public fun stop(): Unit = structuredObject.stop()
    public fun clear(): Unit = structuredObject.clear()
}

@ExperimentalAiSdkApi
public fun <RESULT, INPUT> experimental_useObject(
    options: StructuredObjectOptions<RESULT, INPUT>,
): Experimental_UseObjectHelpers<RESULT, INPUT> =
    Experimental_UseObjectHelpers(StructuredObject(options))
