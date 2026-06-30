package ai.torad.aisdk

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val capturedWarnings = AtomicReference<List<CallWarning>>(emptyList())
    private val capturedResponse = AtomicReference(initialResponse)
    private val replay = MemoizedStreamReplay(sourceStream, ::commit)

    public val fullStream: Flow<StreamEvent> = replay.flow

    public val textStream: Flow<String> = fullStream
        .filterIsInstance<StreamEvent.TextDelta>()
        .map { it.text }

    public val warnings: Flow<List<CallWarning>> = flow {
        ensureCollected()
        emit(capturedWarnings.load())
    }

    public val response: Flow<LanguageModelResponseMetadata> = flow {
        ensureCollected()
        emit(capturedResponse.load())
    }

    public fun toTextStreamResponse(): ai.torad.aisdk.ui.TextStreamResponse =
        ai.torad.aisdk.ui.CreateTextStreamResponse(textStream)

    public fun toUiMessageStream(assistantMessageId: String): Flow<ai.torad.aisdk.ui.UIMessage> =
        ai.torad.aisdk.ui.StreamToUiMessages(fullStream, assistantMessageId)

    public fun toUiMessageStreamResponse(assistantMessageId: String): ai.torad.aisdk.ui.UIMessageStreamResponse =
        ai.torad.aisdk.ui.CreateUiMessageStreamResponse(toUiMessageStream(assistantMessageId))

    private suspend fun ensureCollected() {
        fullStream.collect { }
    }

    private fun commit(buffer: List<StreamEvent>) {
        capturedWarnings.store(
            buffer.asSequence()
                .filterIsInstance<StreamEvent.StreamStart>()
                .map { it.warnings }
                .firstOrNull { it.isNotEmpty() }
                ?: emptyList(),
        )
        var response = initialResponse
        for (event in buffer) {
            if (event is StreamEvent.ResponseMetadata) {
                response = response.merge(event.toLanguageModelResponseMetadata())
            }
        }
        capturedResponse.store(response)
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class MemoizedStreamReplay(
    private val upstream: Flow<StreamEvent>,
    private val onTerminalSnapshot: (List<StreamEvent>) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val producer = AtomicReference<Job?>(null)
    private val mutex = Mutex()
    private val buffer = mutableListOf<StreamEvent>()
    private val progress = MutableStateFlow(ReplayProgress())

    val flow: Flow<StreamEvent> = flow {
        start()
        var nextIndex = 0
        while (true) {
            val snapshot = snapshotFrom(nextIndex)
            for (event in snapshot.events) {
                emit(event)
                nextIndex++
            }
            when (val terminal = snapshot.terminal) {
                ReplayTerminal.Complete -> return@flow
                is ReplayTerminal.Error -> throw terminal.throwable
                null -> progress.first { it.size > nextIndex || it.terminal != null }
            }
        }
    }

    private fun start() {
        while (true) {
            if (producer.load() != null) return
            val job = scope.launch(start = CoroutineStart.LAZY) { collectUpstream() }
            if (producer.compareAndSet(null, job)) {
                job.start()
                return
            }
            job.cancel()
        }
    }

    private suspend fun collectUpstream() {
        try {
            upstream.collect { event ->
                mutex.withLock {
                    buffer += event
                    progress.value = ReplayProgress(size = buffer.size)
                }
            }
            complete(ReplayTerminal.Complete)
        } catch (t: Throwable) {
            complete(ReplayTerminal.Error(t))
        }
    }

    private suspend fun snapshotFrom(index: Int): ReplaySnapshot =
        mutex.withLock {
            ReplaySnapshot(
                events = if (index < buffer.size) buffer.subList(index, buffer.size).toList() else emptyList(),
                terminal = progress.value.terminal,
            )
        }

    private suspend fun complete(terminal: ReplayTerminal) {
        mutex.withLock {
            onTerminalSnapshot(buffer.toList())
            progress.value = ReplayProgress(size = buffer.size, terminal = terminal)
        }
    }
}

private data class ReplayProgress(
    val size: Int = 0,
    val terminal: ReplayTerminal? = null,
)

private data class ReplaySnapshot(
    val events: List<StreamEvent>,
    val terminal: ReplayTerminal?,
)

private sealed interface ReplayTerminal {
    data object Complete : ReplayTerminal
    data class Error(val throwable: Throwable) : ReplayTerminal
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
