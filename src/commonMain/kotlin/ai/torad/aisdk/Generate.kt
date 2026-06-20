package ai.torad.aisdk

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

public data class GenerateTextResult<TOutput>(
    val output: TOutput,
    val text: String,
    val toolCalls: List<ContentPart.ToolCall>,
    val finishReason: FinishReason,
    val usage: Usage,
    val content: List<ContentPart> = buildList {
        if (text.isNotEmpty()) add(ContentPart.Text(text))
        addAll(toolCalls)
    },
    val toolResults: List<ContentPart.ToolResult> = content.filterIsInstance<ContentPart.ToolResult>(),
    val reasoning: List<ContentPart.Reasoning> = content.filterIsInstance<ContentPart.Reasoning>(),
    val reasoningText: String? = reasoning.takeIf { it.isNotEmpty() }?.joinToString("") { it.text },
    val files: List<ContentPart.File> = content.filterIsInstance<ContentPart.File>(),
    val sources: List<ContentPart.Source> = content.filterIsInstance<ContentPart.Source>(),
    val totalUsage: Usage = usage,
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    val steps: List<StepResult> = emptyList(),
    val rawFinishReason: String? = null,
) {
    val staticToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { !it.dynamic }
    val dynamicToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { it.dynamic }
    val staticToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { !it.dynamic }
    val dynamicToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { it.dynamic }
}

/**
 * Streaming generation result — memoised replay. The upstream is collected at
 * most once; later collectors replay the captured events.
 */
@OptIn(ExperimentalAtomicApi::class)
public class StreamTextResult(
    sourceStream: Flow<StreamEvent>,
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    private val initialResponse: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
) {
    private val upstream = sourceStream

    private val primaryResult = AtomicReference<CompletableDeferred<List<StreamEvent>>?>(null)
    private var capturedWarnings: List<CallWarning> = emptyList()
    private var capturedResponse: LanguageModelResponseMetadata = initialResponse

    public val fullStream: Flow<StreamEvent> = flow {
        while (true) {
            val mine = CompletableDeferred<List<StreamEvent>>()
            if (primaryResult.compareAndSet(null, mine)) {
                val buffer = mutableListOf<StreamEvent>()
                try {
                    upstream.collect { event ->
                        currentCoroutineContext().ensureActive()
                        buffer += event
                        emit(event)
                    }
                } catch (t: Throwable) {
                    primaryResult.compareAndSet(mine, null)
                    mine.completeExceptionally(t)
                    throw t
                }
                commit(buffer)
                mine.complete(buffer.toList())
                return@flow
            }
            val existing = primaryResult.load()
            if (existing != null) {
                existing.await().forEach { emit(it) }
                return@flow
            }
        }
    }

    public val textStream: Flow<String> = fullStream
        .filterIsInstance<StreamEvent.TextDelta>()
        .map { it.text }

    public val warnings: Flow<List<CallWarning>> = flow {
        ensureCollected()
        emit(capturedWarnings)
    }

    public val response: Flow<LanguageModelResponseMetadata> = flow {
        ensureCollected()
        emit(capturedResponse)
    }

    public fun toTextStreamResponse(): ai.torad.aisdk.ui.TextStreamResponse =
        ai.torad.aisdk.ui.createTextStreamResponse(textStream)

    public fun toUiMessageStream(assistantMessageId: String): Flow<ai.torad.aisdk.ui.UIMessage> =
        ai.torad.aisdk.ui.streamToUiMessages(fullStream, assistantMessageId)

    public fun toUiMessageStreamResponse(assistantMessageId: String): ai.torad.aisdk.ui.UIMessageStreamResponse =
        ai.torad.aisdk.ui.createUiMessageStreamResponse(toUiMessageStream(assistantMessageId))

    private suspend fun ensureCollected() {
        fullStream.collect { }
    }

    private fun commit(buffer: List<StreamEvent>) {
        capturedWarnings = buffer.asSequence()
            .filterIsInstance<StreamEvent.StreamStart>()
            .map { it.warnings }
            .firstOrNull { it.isNotEmpty() }
            ?: emptyList()
        var response = initialResponse
        for (event in buffer) {
            if (event is StreamEvent.ResponseMetadata) {
                response = response.merge(event.toLanguageModelResponseMetadata())
            }
        }
        capturedResponse = response
    }
}

public data class GenerateObjectResult<TOutput>(
    val value: TOutput,
    val text: String,
    val reasoning: String? = null,
    val finishReason: FinishReason,
    val usage: Usage,
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
) {
    val output: TOutput get() = value
    val generatedObject: TOutput get() = value
}

private fun StreamEvent.ResponseMetadata.toLanguageModelResponseMetadata(): LanguageModelResponseMetadata =
    LanguageModelResponseMetadata(
        id = id,
        timestampMillis = timestampMillis,
        modelId = modelId,
        headers = headers,
        body = body,
    )

private fun LanguageModelResponseMetadata.merge(
    other: LanguageModelResponseMetadata,
): LanguageModelResponseMetadata = LanguageModelResponseMetadata(
    id = other.id ?: id,
    timestampMillis = other.timestampMillis ?: timestampMillis,
    modelId = other.modelId ?: modelId,
    headers = headers + other.headers,
    body = other.body ?: body,
)
