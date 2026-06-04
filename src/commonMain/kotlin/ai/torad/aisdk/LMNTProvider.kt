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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val LMNT_VERSION: String = "2.0.33"

public typealias LMNTSpeechModelId = String

@Serializable
public data class LMNTSpeechModelOptions(
    val model: String? = null,
    val format: String? = null,
    val sampleRate: Int? = null,
    val speed: Float? = null,
    val seed: Int? = null,
    val conversational: Boolean? = null,
    val length: Float? = null,
    val topP: Float? = null,
    val temperature: Float? = null,
)

@Serializable
public data class LMNTProviderSettings(
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

public interface LMNTProvider : Provider {
    public operator fun invoke(modelId: LMNTSpeechModelId = "aurora"): SpeechModel = speech(modelId)
    public fun speech(modelId: LMNTSpeechModelId): SpeechModel
    override fun speechModel(modelId: String): SpeechModel = speech(modelId)
}

public fun createLMNT(
    client: HttpClient,
    settings: LMNTProviderSettings = LMNTProviderSettings(),
): LMNTProvider = DefaultLMNTProvider(client, settings)

public val lmnt: LMNTProvider = object : LMNTProvider {
    override val providerId: String = "lmnt"
    override fun speech(modelId: String): SpeechModel =
        throw AiSdkException("LMNT provider is not configured. Use createLMNT(client, settings).")
}

private class DefaultLMNTProvider(
    private val client: HttpClient,
    private val settings: LMNTProviderSettings,
) : LMNTProvider {
    override val providerId: String = "lmnt"
    override fun speech(modelId: String): SpeechModel = LMNTSpeechModel(client, settings, modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class LMNTSpeechModel(
    private val client: HttpClient,
    private val settings: LMNTProviderSettings,
    override val modelId: String,
) : SpeechModel {
    override val provider: String = "lmnt.speech"

    override suspend fun generate(params: SpeechGenerationParams): SpeechModelResult {
        val warnings = mutableListOf<CallWarning>()
        val responseFormat = lmntResponseFormat(params.responseFormat, warnings)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("text", JsonPrimitive(params.text))
            put("voice", JsonPrimitive(params.voice ?: "ava"))
            put("response_format", JsonPrimitive(responseFormat))
            params.speed?.let { put("speed", JsonPrimitive(it)) }
            val options = lmntOptions(params.providerOptions)
            options["conversational"]?.let { put("conversational", it) }
            options["length"]?.let { put("length", it) }
            options["seed"]?.let { put("seed", it) }
            options["speed"]?.let { put("speed", it) }
            options["temperature"]?.let { put("temperature", it) }
            options["topP"]?.let { put("top_p", it) }
            options["sampleRate"]?.let { put("sample_rate", it) }
        }
        val url = "https://api.lmnt.com/v1/ai/speech/bytes"
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            lmntHeaders(settings, params.headers).forEach { (name, value) -> header(name, value) }
            setBody(aiSdkJson.encodeToString(JsonElement.serializer(), body))
        }.parseLMNTBinary(url, responseFormat)
        return SpeechModelResult(
            audio = GeneratedFile(
                mediaType = response.mediaType,
                base64 = convertByteArrayToBase64(response.bytes),
            ),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
        )
    }
}


private data class LMNTBinaryResponse(
    val bytes: ByteArray,
    val mediaType: String,
    val headers: Map<String, String>,
)

private suspend fun HttpResponse.parseLMNTBinary(url: String, responseFormat: String): LMNTBinaryResponse {
    val bytes = bodyAsBytes()
    val headers = flattenedHeaders()
    if (status.value !in 200..299) {
        val raw = bytes.decodeToString()
        throw apiCallError(
            url = url,
            statusCode = status.value,
            rawBody = raw,
            headers = headers,
            message = "LMNT request failed (${status.value}): ${raw.ifBlank { "request failed" }}",
        )
    }
    return LMNTBinaryResponse(
        bytes = bytes,
        mediaType = headers.headerValue(HttpHeaders.ContentType) ?: lmntMediaType(responseFormat),
        headers = headers,
    )
}

private fun lmntHeaders(settings: LMNTProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val base = linkedMapOf<String, String>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base["x-api-key"] = it }
    base.putAll(settings.headers)
    base.putAll(callHeaders)
    return withUserAgentSuffix(base, "ai-sdk/lmnt/$LMNT_VERSION")
}

private fun lmntOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["lmnt"] as? JsonObject ?: JsonObject(emptyMap())

private fun lmntResponseFormat(value: String?, warnings: MutableList<CallWarning>): String {
    val format = value ?: "mp3"
    if (format in lmntSupportedFormats) return format
    warnings += CallWarning(
        type = "unsupported",
        message = "Unsupported output format: $format. Using mp3 instead.",
    )
    return "mp3"
}

private fun lmntMediaType(format: String): String =
    when (format) {
        "aac" -> "audio/aac"
        "mulaw", "raw" -> "application/octet-stream"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

private val lmntSupportedFormats = setOf("aac", "mp3", "mulaw", "raw", "wav")

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
