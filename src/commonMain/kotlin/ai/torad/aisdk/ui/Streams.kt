package ai.torad.aisdk.ui

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.UiMessageStreamError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement

data class TextStreamResponse(
    val textStream: Flow<String>,
    val status: Int = 200,
    val headers: Map<String, String> = textStreamHeaders(),
)

data class UIMessageStreamResponse(
    val stream: Flow<UIMessage>,
    val status: Int = 200,
    val headers: Map<String, String> = uiMessageStreamHeaders(),
)

interface ServerResponseWriter {
    fun setStatus(status: Int)
    fun setHeader(name: String, value: String)
    suspend fun write(chunk: String)
}

fun textStreamHeaders(): Map<String, String> =
    mapOf("Content-Type" to "text/plain; charset=utf-8")

fun uiMessageStreamHeaders(): Map<String, String> =
    mapOf("Content-Type" to "text/event-stream; charset=utf-8")

fun textStreamFromEvents(events: Flow<StreamEvent>): Flow<String> =
    events.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

fun createTextStreamResponse(
    textStream: Flow<String>,
    status: Int = 200,
    headers: Map<String, String> = textStreamHeaders(),
): TextStreamResponse = TextStreamResponse(textStream, status, headers)

suspend fun pipeTextStreamToResponse(
    textStream: Flow<String>,
    response: ServerResponseWriter,
    status: Int = 200,
    headers: Map<String, String> = textStreamHeaders(),
) {
    response.setStatus(status)
    headers.forEach { (name, value) -> response.setHeader(name, value) }
    textStream.collect { response.write(it) }
}

fun createUiMessageStreamResponse(
    stream: Flow<UIMessage>,
    status: Int = 200,
    headers: Map<String, String> = uiMessageStreamHeaders(),
): UIMessageStreamResponse = UIMessageStreamResponse(stream, status, headers)

suspend fun pipeUiMessageStreamToResponse(
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

interface UIMessageStreamWriter {
    suspend fun write(message: UIMessage)
    suspend fun merge(stream: Flow<UIMessage>)
    suspend fun error(message: String)
}

fun createUiMessageStream(
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

fun readUiMessageStream(stream: Flow<UIMessage>): Flow<UIMessage> = stream

fun getResponseUiMessageId(messages: List<UIMessage>, createId: () -> String = { "msg_${messages.size + 1}" }): String =
    messages.lastOrNull { it.role == UIMessageRole.Assistant }?.id ?: createId()

fun handleUiMessageStreamFinish(
    messages: List<UIMessage>,
    onFinish: (List<UIMessage>) -> Unit,
) {
    onFinish(messages)
}

fun validateUiMessages(messages: List<UIMessage>) {
    val ids = mutableSetOf<String>()
    for (message in messages) {
        require(message.id.isNotBlank()) { "UIMessage.id must not be blank" }
        require(ids.add(message.id)) { "Duplicate UIMessage id `${message.id}`" }
        require(message.parts.isNotEmpty()) { "UIMessage.parts must not be empty" }
    }
}

fun transformTextToUiMessageStream(
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
    if (buffer.isNotEmpty()) {
        emit(
            UIMessage(
                id = assistantMessageId,
                role = UIMessageRole.Assistant,
                parts = listOf(UIMessagePart.Text(buffer.toString(), TextUIPartState.Done)),
                metadata = metadata,
            ),
        )
    }
}

fun lastAssistantMessageIsCompleteWithToolCalls(messages: List<UIMessage>): Boolean {
    val last = messages.lastOrNull { it.role == UIMessageRole.Assistant } ?: return true
    return last.parts.filterIsInstance<UIMessagePart.ToolUI>().all {
        it.state == ToolCallState.OutputAvailable ||
            it.state == ToolCallState.OutputDenied ||
            it.state == ToolCallState.OutputError
    }
}

fun lastAssistantMessageIsCompleteWithApprovalResponses(messages: List<UIMessage>): Boolean =
    messages.lastOrNull { it.role == UIMessageRole.Assistant }
        ?.parts
        ?.filterIsInstance<UIMessagePart.ToolUI>()
        ?.none { it.state == ToolCallState.ApprovalRequested }
        ?: true

fun uiMessageStreamError(message: String, cause: Throwable? = null): UiMessageStreamError =
    UiMessageStreamError(message, cause)
