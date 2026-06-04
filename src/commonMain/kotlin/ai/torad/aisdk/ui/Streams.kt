package ai.torad.aisdk.ui

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.UiMessageStreamError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement

public data class TextStreamResponse(
    val textStream: Flow<String>,
    val status: Int = 200,
    val headers: Map<String, String> = textStreamHeaders(),
)

public data class UIMessageStreamResponse(
    val stream: Flow<UIMessage>,
    val status: Int = 200,
    val headers: Map<String, String> = uiMessageStreamHeaders(),
)

public interface ServerResponseWriter {
    public fun setStatus(status: Int)
    public fun setHeader(name: String, value: String)
    public suspend fun write(chunk: String)
}

public fun textStreamHeaders(): Map<String, String> =
    mapOf("Content-Type" to "text/plain; charset=utf-8")

public fun uiMessageStreamHeaders(): Map<String, String> =
    mapOf("Content-Type" to "text/event-stream; charset=utf-8")

public fun textStreamFromEvents(events: Flow<StreamEvent>): Flow<String> =
    events.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

public fun createTextStreamResponse(
    textStream: Flow<String>,
    status: Int = 200,
    headers: Map<String, String> = textStreamHeaders(),
): TextStreamResponse = TextStreamResponse(textStream, status, headers)

public suspend fun pipeTextStreamToResponse(
    textStream: Flow<String>,
    response: ServerResponseWriter,
    status: Int = 200,
    headers: Map<String, String> = textStreamHeaders(),
) {
    response.setStatus(status)
    headers.forEach { (name, value) -> response.setHeader(name, value) }
    textStream.collect { response.write(it) }
}

public fun createUiMessageStreamResponse(
    stream: Flow<UIMessage>,
    status: Int = 200,
    headers: Map<String, String> = uiMessageStreamHeaders(),
): UIMessageStreamResponse = UIMessageStreamResponse(stream, status, headers)

public suspend fun pipeUiMessageStreamToResponse(
    stream: Flow<UIMessage>,
    response: ServerResponseWriter,
    encoder: (UIMessage) -> String = { it.toString() },
    status: Int = 200,
    headers: Map<String, String> = uiMessageStreamHeaders(),
) {
    response.setStatus(status)
    headers.forEach { (name, value) -> response.setHeader(name, value) }
    stream.collect { response.write(encoder(it)) }
}

public interface UIMessageStreamWriter {
    public suspend fun write(message: UIMessage)
    public suspend fun merge(stream: Flow<UIMessage>)
    public suspend fun error(message: String)
}

public fun createUiMessageStream(
    onError: (Throwable) -> UIMessage = { throwable ->
        UIMessage(
            id = "error",
            role = UIMessageRole.Assistant,
            parts = listOf(UIMessagePart.Error(throwable.message ?: "stream failed")),
        )
    },
    execute: suspend UIMessageStreamWriter.() -> Unit,
): Flow<UIMessage> = channelFlow {
    val writer = object : UIMessageStreamWriter {
        override suspend fun write(message: UIMessage) {
            send(message)
        }

        override suspend fun merge(stream: Flow<UIMessage>) {
            stream.collect { send(it) }
        }

        override suspend fun error(message: String) {
            send(
                UIMessage(
                    id = "error",
                    role = UIMessageRole.Assistant,
                    parts = listOf(UIMessagePart.Error(message)),
                ),
            )
        }
    }
    try {
        writer.execute()
    } catch (t: Throwable) {
        send(onError(t))
    }
}

public fun readUiMessageStream(stream: Flow<UIMessage>): Flow<UIMessage> = stream

public fun getResponseUiMessageId(messages: List<UIMessage>, createId: () -> String = { "msg_${messages.size + 1}" }): String =
    messages.lastOrNull { it.role == UIMessageRole.Assistant }?.id ?: createId()

public fun handleUiMessageStreamFinish(
    messages: List<UIMessage>,
    onFinish: (List<UIMessage>) -> Unit,
) {
    onFinish(messages)
}

public fun validateUiMessages(messages: List<UIMessage>) {
    require(messages.isNotEmpty()) { "Messages array must not be empty" }
    val ids = mutableSetOf<String>()
    for (message in messages) {
        require(message.id.isNotBlank()) { "UIMessage.id must not be blank" }
        require(ids.add(message.id)) { "Duplicate UIMessage id `${message.id}`" }
        require(message.parts.isNotEmpty()) { "UIMessage.parts must not be empty" }
    }
}

public sealed interface SafeValidateUIMessagesResult {
    public data class Success(val messages: List<UIMessage>) : SafeValidateUIMessagesResult
    public data class Failure(val error: Throwable) : SafeValidateUIMessagesResult
}

public fun validateUIMessages(messages: List<UIMessage>): Unit = validateUiMessages(messages)

public fun safeValidateUIMessages(messages: List<UIMessage>?): SafeValidateUIMessagesResult =
    try {
        require(messages != null) { "messages parameter must be provided" }
        validateUiMessages(messages)
        SafeValidateUIMessagesResult.Success(messages)
    } catch (t: Throwable) {
        SafeValidateUIMessagesResult.Failure(t)
    }

public fun transformTextToUiMessageStream(
    textStream: Flow<String>,
    assistantMessageId: String,
    metadata: Map<String, JsonElement>? = null,
): Flow<UIMessage> = flow {
    val buffer = StringBuilder()
    textStream.collect { delta ->
        buffer.append(delta)
        emit(
            UIMessage(
                id = assistantMessageId,
                role = UIMessageRole.Assistant,
                parts = listOf(UIMessagePart.Text(buffer.toString(), TextUIPartState.Streaming)),
                metadata = metadata,
            ),
        )
    }
    emit(
        UIMessage(
            id = assistantMessageId,
            role = UIMessageRole.Assistant,
            parts = listOf(UIMessagePart.Text(buffer.toString(), TextUIPartState.Done)),
            metadata = metadata,
        ),
    )
}

public fun lastAssistantMessageIsCompleteWithToolCalls(messages: List<UIMessage>): Boolean {
    val last = messages.lastOrNull { it.role == UIMessageRole.Assistant } ?: return true
    return last.parts.filterIsInstance<UIMessagePart.ToolUI>().all {
        it.state == ToolCallState.OutputAvailable ||
            it.state == ToolCallState.OutputDenied ||
            it.state == ToolCallState.OutputError
    }
}

public fun lastAssistantMessageIsCompleteWithApprovalResponses(messages: List<UIMessage>): Boolean =
    messages.lastOrNull { it.role == UIMessageRole.Assistant }
        ?.parts
        ?.filterIsInstance<UIMessagePart.ToolUI>()
        ?.none { it.state == ToolCallState.ApprovalRequested }
        ?: true

public fun uiMessageStreamError(message: String, cause: Throwable? = null): UiMessageStreamError =
    UiMessageStreamError(message, cause)
