@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.request.request
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * xAI carries Live-Search citations as a top-level `citations` array on the chunk (per its
 * docs, on the last one). The OpenAI chat shape has no field for it, so the streaming path
 * needs this mapper to reach the same `source` parts the buffered path appends.
 */
internal fun XaiCitationStreamEvents(chunk: JsonObject): List<StreamEvent> =
    JsonAccess.arr(chunk, "citations").orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .map { url ->
            StreamEvent.SourcePart(
                id = GenerateId(),
                sourceType = StreamEvent.SourcePart.SourceType.Url,
                url = url,
            )
        }

internal class XaiChatLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(
            params.toBuilder().providerOptions(transformXaiChatProviderOptions(params.providerOptions)).build()
        )
            .withXaiCitations()

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(
            params.toBuilder().providerOptions(transformXaiChatProviderOptions(params.providerOptions)).build()
        )

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(
            params.toBuilder().providerOptions(transformXaiChatProviderOptions(params.providerOptions)).build()
        )

    private fun transformXaiChatProviderOptions(options: ProviderOptions): ProviderOptions {
        val map = options.toMap()
        val xai = JsonAccess.obj(map, "xai") ?: return options
        val transformed = buildJsonObject {
            for ((key, value) in xai) {
                when (key) {
                    "reasoningEffort" -> put("reasoning_effort", value)
                    "topLogprobs" -> {
                        put("top_logprobs", value)
                        if ("logprobs" !in xai) put("logprobs", JsonPrimitive(true))
                    }
                    "logprobs" -> {
                        put(key, value)
                    }
                    // Documented wire key is parallel_tool_calls (not parallel_function_calling).
                    "parallel_function_calling", "parallelFunctionCalling" ->
                        put("parallel_tool_calls", value)
                    "searchParameters" -> put("search_parameters", XaiSnakeCaseJson(value))
                    else -> put(key, value)
                }
            }
        }
        return ProviderOptions.Raw(JsonObject(map + ("xai" to (transformed as JsonElement))))
    }

    private fun LanguageModelResult.withXaiCitations(): LanguageModelResult {
        val citations = ((response.body as? JsonObject)?.get("citations") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .map { url ->
                ContentPart.Source(
                    sourceType = StreamEvent.SourcePart.SourceType.Url,
                    url = url,
                )
            }
        return if (citations.isEmpty()) {
            this
        } else {
            LanguageModelResult(
                text = text,
                toolCalls = toolCalls,
                finishReason = finishReason,
                usage = usage,
                providerMetadata = providerMetadata,
                content = content + citations,
                rawFinishReason = rawFinishReason,
                warnings = warnings,
                request = request,
                response = response,
            )
        }
    }
}
