package ai.torad.aisdk

import ai.torad.aisdk.ui.TextUIPartState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

public data class LlamaIndexEngineResponse(
    val delta: String,
)

public data class StreamCallbacks(
    val onStart: (suspend () -> Unit)? = null,
    val onFinal: (suspend (completion: String) -> Unit)? = null,
    val onToken: (suspend (token: String) -> Unit)? = null,
    val onText: (suspend (text: String) -> Unit)? = null,
    val onFinish: (suspend (finalState: JsonElement?) -> Unit)? = null,
    val onError: (suspend (error: Throwable) -> Unit)? = null,
    val onAbort: (suspend () -> Unit)? = null,
)

public fun toUIMessageStream(
    stream: Flow<LlamaIndexEngineResponse>,
    callbacks: StreamCallbacks? = null,
    assistantMessageId: String = "1",
): Flow<UIMessage> = llamaIndexToUIMessageStream(stream, callbacks, assistantMessageId)

public fun llamaIndexToUIMessageStream(
    stream: Flow<LlamaIndexEngineResponse>,
    callbacks: StreamCallbacks? = null,
    assistantMessageId: String = "1",
): Flow<UIMessage> = flow {
    callbacks?.onStart?.invoke()
    val trimStart = trimStartOfLlamaIndexStream()
    val buffer = StringBuilder()

    stream.collect { response ->
        val delta = trimStart(response.delta)
        if (delta.isEmpty()) return@collect
        buffer.append(delta)
        callbacks?.onToken?.invoke(delta)
        callbacks?.onText?.invoke(delta)
        emit(
            UIMessage(
                id = assistantMessageId,
                role = UIMessageRole.Assistant,
                parts = listOf(UIMessagePart.Text(buffer.toString(), TextUIPartState.Streaming)),
            ),
        )
    }

    callbacks?.onFinal?.invoke(buffer.toString())
    emit(
        UIMessage(
            id = assistantMessageId,
            role = UIMessageRole.Assistant,
            parts = listOf(UIMessagePart.Text(buffer.toString(), TextUIPartState.Done)),
        ),
    )
}

private fun trimStartOfLlamaIndexStream(): (String) -> String {
    var isStreamStart = true
    return { text ->
        if (isStreamStart) {
            val trimmed = text.trimStart()
            if (trimmed.isNotEmpty()) {
                isStreamStart = false
            }
            trimmed
        } else {
            text
        }
    }
}
