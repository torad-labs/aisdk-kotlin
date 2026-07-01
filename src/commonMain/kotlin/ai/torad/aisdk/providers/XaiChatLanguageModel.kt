@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.ProviderMetadata
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.ceil

internal class XaiChatLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params.toBuilder().providerOptions(transformXaiChatProviderOptions(params.providerOptions)).build())
            .withXaiCitations()

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(params.toBuilder().providerOptions(transformXaiChatProviderOptions(params.providerOptions)).build())

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params.toBuilder().providerOptions(transformXaiChatProviderOptions(params.providerOptions)).build()).let {
            LanguageModelStreamResult(
                stream = it.stream.map { event -> event },
                request = it.request,
                response = it.response,
            )
        }

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
                    "searchParameters" -> put("search_parameters", XaiProviderSettings.xaiSnakeCaseJson(value))
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

