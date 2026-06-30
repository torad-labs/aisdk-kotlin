package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val HUME_VERSION: String = "2.0.33"

@Serializable
public data class HumeSpeechModelOptions(
    val context: JsonObject? = null,
)

@Serializable
@Poko
public class HumeProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun humeHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base["X-Hume-Api-Key"] = it }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/hume/$HUME_VERSION")
    }
}

public class HumeProviderSettingsBuilder internal constructor() {
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()

    public fun apiKey(value: String?) {
        apiKey = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    internal fun build(): HumeProviderSettings =
        HumeProviderSettings(
            apiKey = apiKey,
            headers = headers,
        )
}

public fun HumeProviderSettings(
    block: HumeProviderSettingsBuilder.() -> Unit = {},
): HumeProviderSettings =
    HumeProviderSettingsBuilder().apply(block).build()

public class HumeProvider(
    private val client: HttpClient,
    public val settings: HumeProviderSettings,
) : Provider {
    override val providerId: String = "hume"

    public operator fun invoke(): SpeechModel = speech()

    public fun speech(): SpeechModel = HumeSpeechModel(client, settings)

    override fun speechModel(modelId: String): SpeechModel = speech()

    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** PascalCase factory — mirrors the OpenAI reference pattern. */
public fun Hume(
    client: HttpClient,
    settings: HumeProviderSettings = HumeProviderSettings(),
): HumeProvider = HumeProvider(client, settings)

private class HumeSpeechModel(
    private val client: HttpClient,
    private val settings: HumeProviderSettings,
) : SpeechModel {
    override val modelId: String = ""
    override val provider: String = "hume.speech"

    override suspend fun generate(params: SpeechGenerationParams): SpeechModelResult {
        val warnings = mutableListOf<CallWarning>()
        val format = humeOutputFormat(params.responseFormat, warnings)
        val body = buildJsonObject {
            put(
                "utterances",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("text", JsonPrimitive(params.text))
                            params.speed?.let { put("speed", JsonPrimitive(it)) }
                            params.instructions?.let { put("description", JsonPrimitive(it)) }
                            put(
                                "voice",
                                buildJsonObject {
                                    put("id", JsonPrimitive(params.voice ?: HUME_DEFAULT_VOICE_ID))
                                    put("provider", JsonPrimitive("HUME_AI"))
                                },
                            )
                        },
                    ),
                ),
            )
            put("format", buildJsonObject { put("type", JsonPrimitive(format)) })
            (JsonAccess.obj(humeOptions(params.providerOptions), "context"))?.let { context ->
                put("context", humeContext(context))
            }
        }
        val url = "https://api.hume.ai/v0/tts/file"
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            settings.humeHeaders(params.headers).forEach { (name, value) -> header(name, value) }
            setBody(aiSdkOutputJson.encodeToString(JsonElement.serializer(), body))
        }.let { parseHumeBinary(it, url, format) }
        return SpeechModelResult(
            audio = GeneratedFile(
                mediaType = response.mediaType,
                base64 = Base64Codec.encode(response.bytes),
            ),
            warnings = warnings,
            response = LanguageModelResponseMetadata(headers = response.headers),
        )
    }

    private suspend fun parseHumeBinary(response: HttpResponse, url: String, format: String): HumeBinaryResponse {
        val bytes = response.bodyAsBytes()
        val headers = with(HttpTransport) { response.flattenedHeaders() }
        if (response.status.value !in 200..299) {
            val raw = bytes.decodeToString()
            throw ApiCallError(
                url = url,
                statusCode = response.status.value,
                rawBody = raw,
                headers = headers,
                message = "Hume request failed (${response.status.value}): ${raw.ifBlank { "request failed" }}",
            )
        }
        return HumeBinaryResponse(
            bytes = bytes,
            mediaType = headerValue(headers, HttpHeaders.ContentType) ?: humeMediaType(format),
            headers = headers,
        )
    }

    private fun humeOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "hume") ?: JsonObject(emptyMap())

    private fun humeContext(context: JsonObject): JsonObject =
        when {
            "generationId" in context -> buildJsonObject {
                context["generationId"]?.let { put("generation_id", it) }
            }
            "utterances" in context -> buildJsonObject {
                put(
                    "utterances",
                    JsonArray(
                        (JsonAccess.arr(context, "utterances")).orEmpty().mapNotNull { item ->
                            val utterance = item as? JsonObject ?: return@mapNotNull null
                            buildJsonObject {
                                utterance["text"]?.let { put("text", it) }
                                utterance["description"]?.let { put("description", it) }
                                utterance["speed"]?.let { put("speed", it) }
                                utterance["trailingSilence"]?.let { put("trailing_silence", it) }
                                utterance["voice"]?.let { put("voice", it) }
                            }
                        },
                    ),
                )
            }
            else -> context
        }

    private fun humeOutputFormat(format: String?, warnings: MutableList<CallWarning>): String {
        val value = format ?: "mp3"
        if (value in humeSupportedFormats) return value
        warnings += CallWarning("unsupported", "Unsupported output format: $value. Using mp3 instead.")
        return "mp3"
    }

    private fun humeMediaType(format: String): String =
        when (format) {
            "wav" -> "audio/wav"
            "pcm" -> "application/octet-stream"
            else -> "audio/mpeg"
        }

    private fun headerValue(map: Map<String, String>, name: String): String? =
        map.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

private const val HUME_DEFAULT_VOICE_ID: String = "d8ab67c6-953d-4bd8-9370-8fa53a0f1453"

private val humeSupportedFormats: Set<String> = setOf("mp3", "pcm", "wav")


internal class HumeBinaryResponse(
    val bytes: ByteArray,
    val mediaType: String,
    val headers: Map<String, String>,
)
