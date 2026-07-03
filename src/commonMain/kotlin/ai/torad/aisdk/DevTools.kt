package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.jvm.JvmOverloads
import kotlin.time.TimeSource

@Poko
/** @since 0.3.0-beta01 */
public class DevToolsStep(
    /** @since 0.3.0-beta01 */
    public val id: String,
    /** @since 0.3.0-beta01 */
    public val runId: String,
    /** @since 0.3.0-beta01 */
    public val stepNumber: Int,
    /** @since 0.3.0-beta01 */
    public val type: String,
    /** @since 0.3.0-beta01 */
    public val modelId: String,
    /** @since 0.3.0-beta01 */
    public val provider: String,
    /** @since 0.3.0-beta01 */
    public val input: JsonElement,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions,
)

@Poko
/** @since 0.3.0-beta01 */
public class DevToolsStepResult(
    /** @since 0.3.0-beta01 */
    public val durationMs: Long,
    /** @since 0.3.0-beta01 */
    public val output: JsonElement?,
    /** @since 0.3.0-beta01 */
    public val usage: Usage?,
    /** @since 0.3.0-beta01 */
    public val error: String?,
    /** @since 0.3.0-beta01 */
    public val rawRequest: JsonElement? = null,
    /** @since 0.3.0-beta01 */
    public val rawResponse: JsonElement? = null,
    /** @since 0.3.0-beta01 */
    public val rawChunks: List<JsonElement> = emptyList(),
)

/** @since 0.3.0-beta01 */
public interface DevToolsRecorder {
    public suspend fun createRun(runId: String)
    public suspend fun createStep(step: DevToolsStep)
    public suspend fun updateStepResult(stepId: String, result: DevToolsStepResult)
}

/** @since 0.3.0-beta01 */
public class InMemoryDevToolsRecorder : DevToolsRecorder {
    private val _runs: MutableList<String> = mutableListOf()
    private val _steps: MutableList<DevToolsStep> = mutableListOf()
    private val _results: MutableMap<String, DevToolsStepResult> = linkedMapOf()

    /** @since 0.3.0-beta01 */
    public val runs: List<String> get() = _runs

    /** @since 0.3.0-beta01 */
    public val steps: List<DevToolsStep> get() = _steps

    /** @since 0.3.0-beta01 */
    public val results: Map<String, DevToolsStepResult> get() = _results

    override suspend fun createRun(runId: String) {
        _runs += runId
    }

    override suspend fun createStep(step: DevToolsStep) {
        _steps += step
    }

    override suspend fun updateStepResult(stepId: String, result: DevToolsStepResult) {
        _results[stepId] = result
    }
}

@JvmOverloads
/** @since 0.3.0-beta01 */
public fun DevToolsMiddleware(
    recorder: DevToolsRecorder = InMemoryDevToolsRecorder(),
    environment: String = "development",
    runId: String = IdGenerator.generate(prefix = "run"),
    idGenerator: () -> String = { IdGenerator.generate(prefix = "step") },
): LanguageModelMiddleware {
    if (environment == "production") {
        throw UnsupportedFunctionalityError(
            "devtools in production",
            "@ai-sdk/devtools should not be used in production. " +
                "Remove devToolsMiddleware from your model configuration for production builds."
        )
    }

    return object : LanguageModelMiddleware {
        private val stateMutex = Mutex()
        private var runCreated = false
        private var stepCounter = 0

        override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult {
            ensureRunCreated()
            val step = createStep(context, type = "generate")
            val mark = TimeSource.Monotonic.markNow()
            recorder.createStep(step)
            return try {
                val result = context.doGenerate(context.params)
                recorder.updateStepResult(
                    step.id,
                    DevToolsStepResult(
                        durationMs = mark.elapsedNow().inWholeMilliseconds,
                        output = DevToolsJson.generateOutput(result),
                        usage = result.usage,
                        error = null,
                        rawRequest = result.request.body,
                        rawResponse = result.response.body,
                    ),
                )
                result
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                recorder.updateStepResult(
                    step.id,
                    DevToolsStepResult(
                        durationMs = mark.elapsedNow().inWholeMilliseconds,
                        output = null,
                        usage = null,
                        error = ErrorMessages.of(error),
                    ),
                )
                throw error
            }
        }

        override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> = flow {
            ensureRunCreated()
            val step = createStep(context, type = "stream")
            val mark = TimeSource.Monotonic.markNow()
            val collector = DevToolsStreamCollector()
            recorder.createStep(step)
            try {
                context.doStream(context.params).collect { event ->
                    collector.accept(event)
                    emit(event)
                }
                recorder.updateStepResult(
                    step.id,
                    DevToolsStepResult(
                        durationMs = mark.elapsedNow().inWholeMilliseconds,
                        output = collector.output(),
                        usage = collector.usage,
                        error = null,
                        rawResponse = collector.fullStream(),
                        rawChunks = collector.rawChunks,
                    ),
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                recorder.updateStepResult(
                    step.id,
                    DevToolsStepResult(
                        durationMs = mark.elapsedNow().inWholeMilliseconds,
                        output = collector.output(),
                        usage = collector.usage,
                        error = ErrorMessages.of(error),
                        rawResponse = collector.fullStream(),
                        rawChunks = collector.rawChunks,
                    ),
                )
                throw error
            }
        }

        private suspend fun ensureRunCreated() {
            stateMutex.withLock {
                if (!runCreated) {
                    recorder.createRun(runId)
                    runCreated = true
                }
            }
        }

        private suspend fun createStep(context: MiddlewareCallContext, type: String): DevToolsStep {
            val stepNumber = stateMutex.withLock {
                stepCounter += 1
                stepCounter
            }
            return DevToolsStep(
                id = idGenerator(),
                runId = runId,
                stepNumber = stepNumber,
                type = type,
                modelId = context.model.modelId,
                provider = context.model.provider,
                input = DevToolsJson.callParamsInput(context.params),
                providerOptions = context.params.providerOptions,
            )
        }
    }
}

private class DevToolsStreamCollector {
    private val currentText = linkedMapOf<String, String>()
    private val currentReasoning = linkedMapOf<String, String>()
    private val textParts = mutableListOf<JsonElement>()
    private val reasoningParts = mutableListOf<JsonElement>()
    private val toolCalls = mutableListOf<JsonElement>()
    private val streamEvents = mutableListOf<JsonElement>()
    val rawChunks = mutableListOf<JsonElement>()
    internal var usage: Usage? = null
    private var finishReason: FinishReason? = null

    fun accept(event: StreamEvent) {
        streamEvents += DevToolsJson.streamEventJson(event)
        when (event) {
            is StreamEvent.TextStart -> currentText[event.id] = ""
            is StreamEvent.TextDelta -> currentText[event.id] = currentText[event.id].orEmpty() + event.text
            is StreamEvent.TextEnd -> textParts += buildJsonObject {
                put("id", JsonPrimitive(event.id))
                put("text", JsonPrimitive(currentText[event.id].orEmpty()))
            }
            is StreamEvent.ReasoningStart -> currentReasoning[event.id] = ""
            is StreamEvent.ReasoningDelta -> currentReasoning[event.id] = currentReasoning[event.id].orEmpty() + event.text
            is StreamEvent.ReasoningEnd -> reasoningParts += buildJsonObject {
                put("id", JsonPrimitive(event.id))
                put("text", JsonPrimitive(currentReasoning[event.id].orEmpty()))
            }
            is StreamEvent.ToolCall -> toolCalls += buildJsonObject {
                put("toolCallId", JsonPrimitive(event.toolCallId))
                put("toolName", JsonPrimitive(event.toolName))
                put("input", event.inputJson)
            }
            is StreamEvent.Raw -> rawChunks += event.rawValue
            is StreamEvent.Finish -> {
                finishReason = event.finishReason
                usage = event.usage
            }
            is StreamEvent.StreamStart,
            is StreamEvent.ResponseMetadata,
            is StreamEvent.StepStart,
            is StreamEvent.SourcePart,
            is StreamEvent.FilePart,
            is StreamEvent.Data,
            is StreamEvent.ToolInputStart,
            is StreamEvent.ToolInputDelta,
            is StreamEvent.ToolInputEnd,
            is StreamEvent.ToolResult,
            is StreamEvent.ToolError,
            is StreamEvent.ToolApprovalRequest,
            is StreamEvent.ToolOutputDenied,
            is StreamEvent.StepFinish,
            StreamEvent.Abort,
            is StreamEvent.Error,
            -> Unit
        }
    }

    fun output(): JsonElement = buildJsonObject {
        put("textParts", JsonArray(textParts))
        put("reasoningParts", JsonArray(reasoningParts))
        put("toolCalls", JsonArray(toolCalls))
        finishReason?.let { put("finishReason", JsonPrimitive(it.name)) }
    }

    fun fullStream(): JsonElement = JsonArray(streamEvents)
}

internal object DevToolsJson {
    fun callParamsInput(params: LanguageModelCallParams): JsonElement = buildJsonObject {
        put("messages", JsonPrimitive(params.messages.size))
        put("tools", JsonArray(params.tools.map { JsonPrimitive(it.name) }))
        put("toolChoice", JsonPrimitive(params.toolChoice.toString()))
        params.maxOutputTokens?.let { put("maxOutputTokens", JsonPrimitive(it)) }
        params.temperature?.let { put("temperature", JsonPrimitive(it)) }
        params.topP?.let { put("topP", JsonPrimitive(it)) }
        params.topK?.let { put("topK", JsonPrimitive(it)) }
        params.presencePenalty?.let { put("presencePenalty", JsonPrimitive(it)) }
        params.frequencyPenalty?.let { put("frequencyPenalty", JsonPrimitive(it)) }
    }

    fun generateOutput(result: LanguageModelResult): JsonElement = buildJsonObject {
        put("text", JsonPrimitive(result.text))
        put("finishReason", JsonPrimitive(result.finishReason.name))
        put("toolCalls", JsonArray(result.toolCalls.map { JsonPrimitive(it.toolName) }))
        put("responseId", result.response.id?.let(::JsonPrimitive) ?: JsonPrimitive(""))
    }

    fun streamEventJson(event: StreamEvent): JsonElement = when (event) {
        is StreamEvent.TextStart -> buildJsonObject {
            put("type", JsonPrimitive("text-start"))
            put("id", JsonPrimitive(event.id))
        }
        is StreamEvent.TextDelta -> buildJsonObject {
            put("type", JsonPrimitive("text-delta"))
            put("id", JsonPrimitive(event.id))
            put("text", JsonPrimitive(event.text))
        }
        is StreamEvent.TextEnd -> buildJsonObject {
            put("type", JsonPrimitive("text-end"))
            put("id", JsonPrimitive(event.id))
        }
        is StreamEvent.ReasoningStart -> buildJsonObject {
            put("type", JsonPrimitive("reasoning-start"))
            put("id", JsonPrimitive(event.id))
        }
        is StreamEvent.ReasoningDelta -> buildJsonObject {
            put("type", JsonPrimitive("reasoning-delta"))
            put("id", JsonPrimitive(event.id))
            put("text", JsonPrimitive(event.text))
        }
        is StreamEvent.ReasoningEnd -> buildJsonObject {
            put("type", JsonPrimitive("reasoning-end"))
            put("id", JsonPrimitive(event.id))
        }
        is StreamEvent.ToolCall -> buildJsonObject {
            put("type", JsonPrimitive("tool-call"))
            put("toolCallId", JsonPrimitive(event.toolCallId))
            put("toolName", JsonPrimitive(event.toolName))
            put("input", event.inputJson)
        }
        is StreamEvent.Finish -> buildJsonObject {
            put("type", JsonPrimitive("finish"))
            put("finishReason", JsonPrimitive(event.finishReason.name))
        }
        is StreamEvent.Raw -> buildJsonObject {
            put("type", JsonPrimitive("raw"))
            put("rawValue", event.rawValue)
        }
        is StreamEvent.Data -> buildJsonObject {
            put("type", JsonPrimitive("data-${event.name}"))
            event.id?.let { put("id", JsonPrimitive(it)) }
            put("data", event.data)
            if (event.transient) put("transient", JsonPrimitive(true))
        }
        is StreamEvent.StreamStart,
        is StreamEvent.ResponseMetadata,
        is StreamEvent.StepStart,
        is StreamEvent.SourcePart,
        is StreamEvent.FilePart,
        is StreamEvent.ToolInputStart,
        is StreamEvent.ToolInputDelta,
        is StreamEvent.ToolInputEnd,
        is StreamEvent.ToolResult,
        is StreamEvent.ToolError,
        is StreamEvent.ToolApprovalRequest,
        is StreamEvent.ToolOutputDenied,
        is StreamEvent.StepFinish,
        StreamEvent.Abort,
        is StreamEvent.Error,
        -> buildJsonObject { put("type", JsonPrimitive("event")) }
    }
}
