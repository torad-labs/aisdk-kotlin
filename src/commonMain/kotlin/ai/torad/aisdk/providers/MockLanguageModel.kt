package ai.torad.aisdk.providers

import ai.torad.aisdk.CallWarning
import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.LanguageModel
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.LanguageModelRequestMetadata
import ai.torad.aisdk.LanguageModelResponseMetadata
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.ProviderMetadata
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Test stub for a [LanguageModel]. Drives the agent loop deterministically
 * by replaying a list of [ScriptedResponse]s — one per [stream] call.
 *
 * Lets tests verify the loop, the tool dispatch, the lifecycle hooks, the
 * stop conditions, and the message-parts conversion without spinning up a
 * real model.
 */
public class MockLanguageModel(
    override val modelId: String = "mock/test",
    override val provider: String = "mock",
    private val responses: List<ScriptedResponse>,
) : LanguageModel {

    private var callIndex: Int = 0

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val response = nextResponse()
        val text = response.events
            .filterIsInstance<StreamEvent.TextDelta>()
            .joinToString("") { it.text }
        val toolCalls = response.events
            .filterIsInstance<StreamEvent.ToolCall>()
            .map { ContentPart.ToolCall(it.toolCallId, it.toolName, it.inputJson) }
        val reasoning = response.events
            .filterIsInstance<StreamEvent.ReasoningDelta>()
            .joinToString("") { it.text }
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(ContentPart.Reasoning(it)) }
            ?: emptyList()
        val sources = response.events
            .filterIsInstance<StreamEvent.SourcePart>()
            .map { ContentPart.Source(it.sourceType, it.url, it.title, it.providerMetadata) }
        val files = response.events
            .filterIsInstance<StreamEvent.FilePart>()
            .map { ContentPart.File(it.mediaType, it.base64, providerMetadata = it.providerMetadata) }
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = response.finishReason,
            usage = response.usage,
            providerMetadata = response.providerMetadata,
            content = buildList {
                if (text.isNotEmpty()) add(ContentPart.Text(text))
                addAll(reasoning)
                addAll(sources)
                addAll(files)
                addAll(toolCalls)
            },
            rawFinishReason = response.rawFinishReason,
            warnings = response.warnings,
            request = response.request,
            response = response.response,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val response = nextResponse()
        for (event in response.events) emit(event)
        emit(
            StreamEvent.StepFinish(
                stepNumber = callIndex,
                finishReason = response.finishReason,
                usage = response.usage,
                providerMetadata = response.providerMetadata,
            )
        )
    }

    private fun nextResponse(): ScriptedResponse {
        val response = responses[callIndex.coerceAtMost(responses.size - 1)]
        callIndex += 1
        return response
    }
}

// Top-level factories for concise tests and examples.

/** A model that just emits the given text once and finishes. */
public fun MockLanguageModelTextOnly(text: String): MockLanguageModel = MockLanguageModel(
    responses = listOf(
        ScriptedResponse(
            events = listOf(
                StreamEvent.TextStart("t1"),
                StreamEvent.TextDelta("t1", text),
                StreamEvent.TextEnd("t1"),
            ),
            finishReason = FinishReason.Stop,
            usage = Usage.of(promptTokens = 1, completionTokens = text.length),
        ),
    ),
)

/**
 * A model whose first call requests a tool, second call (after the
 * tool result is appended) returns a final text response.
 */
public fun MockLanguageModelToolThenText(
    toolName: String,
    toolInput: JsonObject,
    finalText: String,
    toolCallId: String = "call_1",
): MockLanguageModel = MockLanguageModel(
    responses = listOf(
        ScriptedResponse(
            events = listOf(
                StreamEvent.ToolInputStart(id = "ti1", toolName = toolName),
                StreamEvent.ToolInputDelta(id = "ti1", delta = toolInput.toString()),
                StreamEvent.ToolInputEnd(id = "ti1"),
                StreamEvent.ToolCall(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    inputJson = toolInput,
                ),
            ),
            finishReason = FinishReason.ToolCalls,
            usage = Usage.of(promptTokens = 5, completionTokens = 10),
        ),
        ScriptedResponse(
            events = listOf(
                StreamEvent.TextStart("t1"),
                StreamEvent.TextDelta("t1", finalText),
                StreamEvent.TextEnd("t1"),
            ),
            finishReason = FinishReason.Stop,
            usage = Usage.of(promptTokens = 8, completionTokens = finalText.length),
        ),
    ),
)

/** Convenience for a tool-call input expressed as JSON literal map. */
public fun MockToolInput(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) put(k, JsonPrimitive(v))
}

/** One scripted response — what a single `stream` call should emit. */
public data class ScriptedResponse(
    val events: List<StreamEvent>,
    val finishReason: FinishReason = FinishReason.Stop,
    val usage: Usage = Usage.of(promptTokens = 1, completionTokens = 1),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    val rawFinishReason: String? = null,
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
)
