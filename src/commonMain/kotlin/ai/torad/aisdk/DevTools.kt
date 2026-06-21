package ai.torad.aisdk

import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

public data class DevToolsStep(
    val id: String,
    val runId: String,
    val stepNumber: Int,
    val type: String,
    val modelId: String,
    val provider: String,
    val input: JsonElement,
    val providerOptions: ProviderOptions,
)

public data class DevToolsStepResult(
    val durationMs: Long,
    val output: JsonElement?,
    val usage: Usage?,
    val error: String?,
    val rawRequest: JsonElement? = null,
    val rawResponse: JsonElement? = null,
    val rawChunks: List<JsonElement> = emptyList(),
)

public interface DevToolsRecorder {
    public suspend fun createRun(runId: String)
    public suspend fun createStep(step: DevToolsStep)
    public suspend fun updateStepResult(stepId: String, result: DevToolsStepResult)
}

public class InMemoryDevToolsRecorder : DevToolsRecorder {
    public val runs: MutableList<String> = mutableListOf()
    public val steps: MutableList<DevToolsStep> = mutableListOf()
    public val results: MutableMap<String, DevToolsStepResult> = linkedMapOf()

    override suspend fun createRun(runId: String) {
        runs += runId
    }

    override suspend fun createStep(step: DevToolsStep) {
        steps += step
    }

    override suspend fun updateStepResult(stepId: String, result: DevToolsStepResult) {
        results[stepId] = result
    }
}

public fun DevToolsMiddleware(
    recorder: DevToolsRecorder = InMemoryDevToolsRecorder(),
    environment: String = "development",
    runId: String = IdGenerator.generate(prefix = "run"),
    idGenerator: () -> String = { IdGenerator.generate(prefix = "step") },
): LanguageModelMiddleware {
    if (environment == "production") {
        throw UnsupportedFunctionalityError("devtools in production", "@ai-sdk/devtools should not be used in production. " +
            "Remove devToolsMiddleware from your model configuration for production builds.")
    }

    return object : LanguageModelMiddleware {
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
            if (!runCreated) {
                recorder.createRun(runId)
                runCreated = true
            }
        }

        private fun createStep(context: MiddlewareCallContext, type: String): DevToolsStep {
            stepCounter += 1
            return DevToolsStep(
                id = idGenerator(),
                runId = runId,
                stepNumber = stepCounter,
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
            else -> Unit
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
    else -> buildJsonObject { put("type", JsonPrimitive("event")) }
    }
}
