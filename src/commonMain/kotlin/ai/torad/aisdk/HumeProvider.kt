package ai.torad.aisdk

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

const val HUME_VERSION: String = "2.0.33"

@Serializable
data class HumeSpeechModelOptions(
    val context: JsonObject? = null,
)

@Serializable
data class HumeProviderSettings(
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

interface HumeProvider : Provider {
    operator fun invoke(): SpeechModel = speech()
    fun speech(): SpeechModel
    override fun speechModel(modelId: String): SpeechModel = speech()
}

fun createHume(
    client: HttpClient,
    settings: HumeProviderSettings = HumeProviderSettings(),
): HumeProvider = DefaultHumeProvider(client, settings)

val hume: HumeProvider = object : HumeProvider {
    override val providerId: String = "hume"
    override fun speech(): SpeechModel =
        throw AiSdkException("Hume provider is not configured. Use createHume(client, settings).")
}

private class DefaultHumeProvider(
    private val client: HttpClient,
    private val settings: HumeProviderSettings,
) : HumeProvider {
    override val providerId: String = "hume"
    override fun speech(): SpeechModel = HumeSpeechModel(client, settings)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

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
            humeOptions(params.providerOptions)["context"]?.jsonObject?.let { context ->
                put("context", humeContext(context))
            }
        }
        val response = client.request("https://api.hume.ai/v0/tts/file") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            humeHeaders(settings, params.headers).forEach { (name, value) -> header(name, value) }
            setBody(aiSdkJson.encodeToString(JsonElement.serializer(), body))
        }.parseHumeBinary(format)
        return SpeechModelResult(
            audio = GeneratedFile(
                mediaType = response.mediaType,
                base64 = convertByteArrayToBase64(response.bytes),
            ),
            warnings = warnings,
            response = LanguageModelResponseMetadata(headers = response.headers),
        )
    }
}

private const val HUME_DEFAULT_VOICE_ID: String = "d8ab67c6-953d-4bd8-9370-8fa53a0f1453"


private data class HumeBinaryResponse(
    val bytes: ByteArray,
    val mediaType: String,
    val headers: Map<String, String>,
)

private suspend fun HttpResponse.parseHumeBinary(format: String): HumeBinaryResponse {
    val bytes = bodyAsBytes()
    if (status.value !in 200..299) {
        throw AiSdkException("Hume request failed (${status.value}): ${bytes.decodeToString().ifBlank { "request failed" }}")
    }
    val headers = headers.entries().associate { it.key to it.value.joinToString(",") }
    return HumeBinaryResponse(
        bytes = bytes,
        mediaType = headers.headerValue(HttpHeaders.ContentType) ?: humeMediaType(format),
        headers = headers,
    )
}

private fun humeHeaders(settings: HumeProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val base = linkedMapOf<String, String>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base["X-Hume-Api-Key"] = it }
    base.putAll(settings.headers)
    base.putAll(callHeaders)
    return withUserAgentSuffix(base, "ai-sdk/hume/$HUME_VERSION")
}

private fun humeOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["hume"] as? JsonObject ?: JsonObject(emptyMap())

private fun humeContext(context: JsonObject): JsonObject =
    when {
        "generationId" in context -> buildJsonObject {
            context["generationId"]?.let { put("generation_id", it) }
        }
        "utterances" in context -> buildJsonObject {
            put(
                "utterances",
                JsonArray(
                    context["utterances"]?.jsonArray.orEmpty().map { item ->
                        val utterance = item.jsonObject
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

private val humeSupportedFormats = setOf("mp3", "pcm", "wav")

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
