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


@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class LMNTSpeechModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val model: String? = null,
    /** @since 0.3.0-beta01 */
    public val format: String? = null,
    /** @since 0.3.0-beta01 */
    public val sampleRate: Int? = null,
    /** @since 0.3.0-beta01 */
    public val speed: Float? = null,
    /** @since 0.3.0-beta01 */
    public val seed: Int? = null,
    /** @since 0.3.0-beta01 */
    public val conversational: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val length: Float? = null,
    /** @since 0.3.0-beta01 */
    public val topP: Float? = null,
    /** @since 0.3.0-beta01 */
    public val temperature: Float? = null,
)

/** @since 0.3.0-beta01 */
public class LMNTSpeechModelOptionsBuilder {
    private var model: String? = null
    private var format: String? = null
    private var sampleRate: Int? = null
    private var speed: Float? = null
    private var seed: Int? = null
    private var conversational: Boolean? = null
    private var length: Float? = null
    private var topP: Float? = null
    private var temperature: Float? = null

    /** @since 0.3.0-beta01 */
    public fun model(value: String?): LMNTSpeechModelOptionsBuilder {
        model = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun format(value: String?): LMNTSpeechModelOptionsBuilder {
        format = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun sampleRate(value: Int?): LMNTSpeechModelOptionsBuilder {
        sampleRate = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun speed(value: Float?): LMNTSpeechModelOptionsBuilder {
        speed = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun seed(value: Int?): LMNTSpeechModelOptionsBuilder {
        seed = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun conversational(value: Boolean?): LMNTSpeechModelOptionsBuilder {
        conversational = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun length(value: Float?): LMNTSpeechModelOptionsBuilder {
        length = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topP(value: Float?): LMNTSpeechModelOptionsBuilder {
        topP = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun temperature(value: Float?): LMNTSpeechModelOptionsBuilder {
        temperature = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LMNTSpeechModelOptions =
        LMNTSpeechModelOptions(
            model = model,
            format = format,
            sampleRate = sampleRate,
            speed = speed,
            seed = seed,
            conversational = conversational,
            length = length,
            topP = topP,
            temperature = temperature,
        )
}

/** @since 0.3.0-beta01 */
public fun LMNTSpeechModelOptions(
    block: LMNTSpeechModelOptionsBuilder.() -> Unit = {},
): LMNTSpeechModelOptions =
    LMNTSpeechModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class LMNTProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun lmntHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base["x-api-key"] = it }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/lmnt/$LMNT_VERSION")
    }
}

/** @since 0.3.0-beta01 */
public class LMNTProviderSettingsBuilder {
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): LMNTProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): LMNTProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LMNTProviderSettings =
        LMNTProviderSettings(
            apiKey = apiKey,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun LMNTProviderSettings(
    block: LMNTProviderSettingsBuilder.() -> Unit = {},
): LMNTProviderSettings =
    LMNTProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class LMNTProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: LMNTProviderSettings,
) : Provider {
    override val providerId: String = "lmnt"

    public operator fun invoke(modelId: ModelId = ModelId("aurora")): SpeechModel = speech(modelId)

    /** @since 0.3.0-beta01 */
    public fun speech(modelId: ModelId): SpeechModel = LMNTSpeechModel(client, settings, modelId.value)

    override fun speechModel(modelId: String): SpeechModel = speech(ModelId(modelId))
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/**
 * PascalCase factory — mirrors the OpenAI(...) reference pattern.
 * @since 0.3.0-beta01
 */
public fun LMNT(
    client: HttpClient,
    settings: LMNTProviderSettings = LMNTProviderSettings(),
): LMNTProvider = LMNTProvider(client, settings)

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
            params.language?.let { put("language", JsonPrimitive(it)) }
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
            settings.lmntHeaders(params.headers).forEach { (name, value) -> header(name, value) }
            setBody(aiSdkOutputJson.encodeToString(JsonElement.serializer(), body))
        }.parseLMNTBinary(url, responseFormat)
        return SpeechModelResult(
            audio = GeneratedFile(
                mediaType = response.mediaType,
                base64 = Base64Codec.encode(response.bytes),
            ),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
        )
    }

    private suspend fun HttpResponse.parseLMNTBinary(url: String, responseFormat: String): LMNTBinaryResponse {
        val bytes = bodyAsBytes()
        val headers = with(HttpTransport) { flattenedHeaders() }
        if (status.value !in 200..299) {
            val raw = bytes.decodeToString()
            throw ApiCallError(
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

    private fun lmntOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "lmnt") ?: JsonObject(emptyMap())

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

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}


internal class LMNTBinaryResponse(
    val bytes: ByteArray,
    val mediaType: String,
    val headers: Map<String, String>,
)

private val lmntSupportedFormats: Set<String> = setOf("aac", "mp3", "mulaw", "raw", "wav")
