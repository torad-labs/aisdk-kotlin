package ai.torad.aisdk.ui

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.aiSdkOutputJson
import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

@Poko
/** @since 0.3.0-beta01 */
public class TextStreamResponse(
    /** @since 0.3.0-beta01 */
    public val textStream: Flow<String>,
    /** @since 0.3.0-beta01 */
    public val status: Int = 200,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = UiMessageStreams.textStreamHeaders(),
)

@Poko
/** @since 0.3.0-beta01 */
public class UIMessageStreamResponse(
    /** @since 0.3.0-beta01 */
    public val stream: Flow<UIMessage>,
    /** @since 0.3.0-beta01 */
    public val status: Int = 200,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = UiMessageStreams.uiMessageStreamHeaders(),
)

/** @since 0.3.0-beta01 */
public interface ServerResponseWriter {
    /** @since 0.3.0-beta01 */
    public fun setStatus(status: Int)

    /** @since 0.3.0-beta01 */
    public fun setHeader(name: String, value: String)
    public suspend fun write(chunk: String)
}

/** @since 0.3.0-beta01 */
public fun TextStreamFromEvents(events: Flow<StreamEvent>): Flow<String> =
    events.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

/** @since 0.3.0-beta01 */
public fun CreateTextStreamResponse(
    textStream: Flow<String>,
    status: Int = 200,
    headers: Map<String, String> = UiMessageStreams.textStreamHeaders(),
): TextStreamResponse = TextStreamResponse(textStream, status, headers)

/** @since 0.3.0-beta01 */
public fun CreateUiMessageStreamResponse(
    stream: Flow<UIMessage>,
    status: Int = 200,
    headers: Map<String, String> = UiMessageStreams.uiMessageStreamHeaders(),
): UIMessageStreamResponse = UIMessageStreamResponse(stream, status, headers)

/** @since 0.3.0-beta01 */
public interface UIMessageStreamWriter {
    public suspend fun write(message: UIMessage)
    public suspend fun merge(stream: Flow<UIMessage>)
    public suspend fun error(message: String)
}

/** @since 0.3.0-beta01 */
public fun CreateUiMessageStream(
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
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        send(onError(t))
    }
}

/** @since 0.3.0-beta01 */
public fun ReadUiMessageStream(stream: Flow<UIMessage>): Flow<UIMessage> = stream

/** @since 0.3.0-beta01 */
public sealed class SafeValidateUIMessagesResult {
    @Poko
    /** @since 0.3.0-beta01 */
    public class Success(public val messages: List<UIMessage>) : SafeValidateUIMessagesResult()

    @Poko
    /** @since 0.3.0-beta01 */
    public class Failure(public val error: Throwable) : SafeValidateUIMessagesResult()
}

/** @since 0.3.0-beta01 */
public fun TransformTextToUiMessageStream(
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

/** @since 0.3.0-beta01 */
public fun UiMessageStreamError(message: String, cause: Throwable? = null): ai.torad.aisdk.UiMessageStreamError =
    ai.torad.aisdk.UiMessageStreamError(message, cause)

/** @since 0.3.0-beta01 */
public object UiMessageStreams {
    /** @since 0.3.0-beta01 */
    public fun textStreamHeaders(): Map<String, String> =
        mapOf("Content-Type" to "text/plain; charset=utf-8")

    /** @since 0.3.0-beta01 */
    public fun uiMessageStreamHeaders(): Map<String, String> =
        mapOf("Content-Type" to "text/event-stream; charset=utf-8")

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

    public suspend fun pipeUiMessageStreamToResponse(
        stream: Flow<UIMessage>,
        response: ServerResponseWriter,
        // Default to real SSE framing (`data: <json>\n\n`) matching the text/event-stream
        // content-type — the old `it.toString()` wrote Kotlin debug output that no SSE client
        // can parse. Callers needing a different wire shape still pass their own encoder.
        encoder: (UIMessage) -> String = { "data: ${aiSdkOutputJson.encodeToString(it)}\n\n" },
        status: Int = 200,
        headers: Map<String, String> = uiMessageStreamHeaders(),
    ) {
        response.setStatus(status)
        headers.forEach { (name, value) -> response.setHeader(name, value) }
        stream.collect { response.write(encoder(it)) }
    }

    /** @since 0.3.0-beta01 */
    public fun getResponseUiMessageId(
        messages: List<UIMessage>,
        createId: () -> String = {
            "msg_${messages.size + 1}"
        }
    ): String =
        messages.lastOrNull { it.role == UIMessageRole.Assistant }?.id ?: createId()

    /** @since 0.3.0-beta01 */
    public fun handleUiMessageStreamFinish(
        messages: List<UIMessage>,
        onFinish: (List<UIMessage>) -> Unit,
    ) {
        onFinish(messages)
    }

    /** @since 0.3.0-beta01 */
    public fun validateUiMessages(messages: List<UIMessage>) {
        // An empty list is valid (it is the default constructor state) — `setMessages(emptyList())`
        // is the canonical "clear chat" op. Validate only id uniqueness / non-blank / non-empty parts.
        val ids = mutableSetOf<String>()
        for (message in messages) {
            require(message.id.isNotBlank()) { "UIMessage.id must not be blank" }
            require(ids.add(message.id)) { "Duplicate UIMessage id `${message.id}`" }
            require(message.parts.isNotEmpty()) { "UIMessage.parts must not be empty" }
        }
    }

    /** @since 0.3.0-beta01 */
    public fun validateUIMessages(messages: List<UIMessage>): Unit = validateUiMessages(messages)

    /** @since 0.3.0-beta01 */
    public fun safeValidateUIMessages(messages: List<UIMessage>?): SafeValidateUIMessagesResult =
        try {
            require(messages != null) { "messages parameter must be provided" }
            validateUiMessages(messages)
            SafeValidateUIMessagesResult.Success(messages)
        } catch (t: Throwable) {
            SafeValidateUIMessagesResult.Failure(t)
        }

    /** @since 0.3.0-beta01 */
    public fun lastAssistantMessageIsCompleteWithToolCalls(messages: List<UIMessage>): Boolean {
        val last = messages.lastOrNull { it.role == UIMessageRole.Assistant } ?: return true
        return last.parts.filterIsInstance<UIMessagePart.ToolUI>().all {
            it.state == ToolCallState.OutputAvailable ||
                it.state == ToolCallState.OutputDenied ||
                it.state == ToolCallState.OutputError
        }
    }

    /** @since 0.3.0-beta01 */
    public fun lastAssistantMessageIsCompleteWithApprovalResponses(messages: List<UIMessage>): Boolean =
        messages.lastOrNull { it.role == UIMessageRole.Assistant }
            ?.parts
            ?.filterIsInstance<UIMessagePart.ToolUI>()
            ?.none { it.state == ToolCallState.ApprovalRequested }
            ?: true
}
