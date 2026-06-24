package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

public const val ELEVENLABS_VERSION: String = "2.0.33"


@Serializable
public data class ElevenLabsSpeechModelOptions(
    val languageCode: String? = null,
    val voiceSettings: JsonObject? = null,
    val pronunciationDictionaryLocators: List<JsonObject>? = null,
    val seed: Int? = null,
    val previousText: String? = null,
    val nextText: String? = null,
    val previousRequestIds: List<String>? = null,
    val nextRequestIds: List<String>? = null,
    val applyTextNormalization: String? = null,
    val applyLanguageTextNormalization: Boolean? = null,
    val enableLogging: Boolean? = null,
)

@Serializable
public data class ElevenLabsTranscriptionModelOptions(
    val languageCode: String? = null,
    val tagAudioEvents: Boolean? = null,
    val numSpeakers: Int? = null,
    val timestampsGranularity: String? = null,
    val diarize: Boolean? = null,
    val fileFormat: String? = null,
)

@Serializable
public data class ElevenLabsProviderSettings(
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    internal fun elevenLabsHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base["xi-api-key"] = it }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/elevenlabs/$ELEVENLABS_VERSION")
    }

    internal fun elevenLabsOptions(providerOptions: ProviderOptions): JsonObject =
        providerOptions.toMap()["elevenlabs"] as? JsonObject ?: JsonObject(emptyMap())
}

public class ElevenLabsProvider(
    private val client: HttpClient,
    public val settings: ElevenLabsProviderSettings,
) : Provider {
    override val providerId: String = "elevenlabs"

    public operator fun invoke(modelId: ModelId = ModelId("scribe_v1")): TranscriptionModel = transcription(modelId)

    public fun transcription(modelId: ModelId): TranscriptionModel =
        ElevenLabsTranscriptionModel(client, settings, modelId.value)

    public fun speech(modelId: ModelId): SpeechModel =
        ElevenLabsSpeechModel(client, settings, modelId.value)

    public fun textEmbeddingModel(modelId: String): Nothing =
        throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(ModelId(modelId))
    override fun speechModel(modelId: String): SpeechModel = speech(ModelId(modelId))
}

/** PascalCase factory — mirrors `OpenAI(...)`. */
public fun ElevenLabs(
    client: HttpClient,
    settings: ElevenLabsProviderSettings = ElevenLabsProviderSettings(),
): ElevenLabsProvider = ElevenLabsProvider(client, settings)

private class ElevenLabsSpeechModel(
    private val client: HttpClient,
    private val settings: ElevenLabsProviderSettings,
    override val modelId: String,
) : SpeechModel {
    override val provider: String = "elevenlabs.speech"

    override suspend fun generate(params: SpeechGenerationParams): SpeechModelResult {
        val warnings = mutableListOf<CallWarning>()
        if (params.instructions != null) {
            warnings += CallWarning("unsupported", "ElevenLabs speech models do not support instructions. Instructions parameter was ignored.")
        }
        val options = settings.elevenLabsOptions(params.providerOptions)
        val queryParams = linkedMapOf(
            "output_format" to elevenLabsOutputFormat(params.responseFormat ?: "mp3_44100_128"),
        )
        (options["enableLogging"] as? JsonPrimitive)?.contentOrNull?.let { queryParams["enable_logging"] = it }
        val body = buildJsonObject {
            put("text", JsonPrimitive(params.text))
            put("model_id", JsonPrimitive(modelId))
            // params.language wins; the languageCode provider-option is only a fallback (upstream order).
            (params.language ?: (options["languageCode"] as? JsonPrimitive)?.contentOrNull)
                ?.let { put("language_code", JsonPrimitive(it)) }
            val voiceSettings = buildJsonObject {
                params.speed?.let { put("speed", JsonPrimitive(it)) }
                (options["voiceSettings"] as? JsonObject)?.let { settings ->
                    settings["stability"]?.let { put("stability", it) }
                    settings["similarityBoost"]?.let { put("similarity_boost", it) }
                    settings["style"]?.let { put("style", it) }
                    settings["useSpeakerBoost"]?.let { put("use_speaker_boost", it) }
                }
            }
            if (voiceSettings.isNotEmpty()) put("voice_settings", voiceSettings)
            (options["pronunciationDictionaryLocators"] as? JsonArray)?.let { locators ->
                put(
                    "pronunciation_dictionary_locators",
                    JsonArray(
                        locators.mapNotNull { locator ->
                            val obj = locator as? JsonObject ?: return@mapNotNull null
                            buildJsonObject {
                                obj["pronunciationDictionaryId"]?.let { put("pronunciation_dictionary_id", it) }
                                obj["versionId"]?.let { put("version_id", it) }
                            }
                        },
                    ),
                )
            }
            options["seed"]?.let { put("seed", it) }
            options["previousText"]?.let { put("previous_text", it) }
            options["nextText"]?.let { put("next_text", it) }
            options["previousRequestIds"]?.let { put("previous_request_ids", it) }
            options["nextRequestIds"]?.let { put("next_request_ids", it) }
            options["applyTextNormalization"]?.let { put("apply_text_normalization", it) }
            options["applyLanguageTextNormalization"]?.let { put("apply_language_text_normalization", it) }
        }
        val url =
            "https://api.elevenlabs.io/v1/text-to-speech/${params.voice ?: ELEVENLABS_DEFAULT_VOICE_ID}?${queryParams.toQueryString()}"
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            settings.elevenLabsHeaders(params.headers).forEach { (name, value) -> header(name, value) }
            setBody(aiSdkOutputJson.encodeToString(JsonElement.serializer(), body))
        }.parseElevenLabsBinary(url, queryParams["output_format"].orEmpty())
        return SpeechModelResult(
            audio = GeneratedFile(
                mediaType = response.mediaType,
                base64 = Base64Codec.encode(response.bytes),
            ),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
        )
    }

    private suspend fun HttpResponse.parseElevenLabsBinary(url: String, outputFormat: String): ElevenLabsBinaryResponse {
        val bytes = bodyAsBytes()
        val headers = with(HttpTransport) { flattenedHeaders() }
        if (status.value !in 200..299) {
            val raw = bytes.decodeToString()
            throw ApiCallError(
                url = url,
                statusCode = status.value,
                rawBody = raw,
                headers = headers,
                message = "ElevenLabs request failed (${status.value}): ${raw.ifBlank { "request failed" }}",
            )
        }
        return ElevenLabsBinaryResponse(
            bytes = bytes,
            mediaType = headers.headerValue(HttpHeaders.ContentType) ?: elevenLabsMediaType(outputFormat),
            headers = headers,
        )
    }

    private fun elevenLabsOutputFormat(format: String): String =
        when (format) {
            "mp3" -> "mp3_44100_128"
            "mp3_32" -> "mp3_44100_32"
            "mp3_64" -> "mp3_44100_64"
            "mp3_96" -> "mp3_44100_96"
            "mp3_128" -> "mp3_44100_128"
            "mp3_192" -> "mp3_44100_192"
            "pcm" -> "pcm_44100"
            "ulaw" -> "ulaw_8000"
            else -> format
        }

    private fun elevenLabsMediaType(format: String): String =
        when {
            format.startsWith("pcm") || format.startsWith("ulaw") -> "application/octet-stream"
            else -> "audio/mpeg"
        }

    private fun Map<String, String>.toQueryString(): String =
        entries.joinToString("&") { (key, value) -> "${UrlOps.encode(key)}=${UrlOps.encode(value)}" }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

private class ElevenLabsTranscriptionModel(
    private val client: HttpClient,
    private val settings: ElevenLabsProviderSettings,
    override val modelId: String,
) : TranscriptionModel {
    override val provider: String = "elevenlabs.transcription"

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        val options = settings.elevenLabsOptions(params.providerOptions)
        val rawResponse = client.request("https://api.elevenlabs.io/v1/speech-to-text") {
            method = HttpMethod.Post
            settings.elevenLabsHeaders(params.headers).forEach { (name, value) -> header(name, value) }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model_id", modelId)
                        append("diarize", (options["diarize"] as? JsonPrimitive)?.contentOrNull ?: "true")
                        ((options["languageCode"] as? JsonPrimitive)?.contentOrNull ?: params.language)?.let {
                            append("language_code", it)
                        }
                        (options["tagAudioEvents"] as? JsonPrimitive)?.contentOrNull?.let {
                            append("tag_audio_events", it)
                        }
                        (options["numSpeakers"] as? JsonPrimitive)?.contentOrNull?.let { append("num_speakers", it) }
                        (options["timestampsGranularity"] as? JsonPrimitive)?.contentOrNull?.let {
                            append("timestamps_granularity", it)
                        }
                        (options["fileFormat"] as? JsonPrimitive)?.contentOrNull?.let { append("file_format", it) }
                        append(
                            "file",
                            Base64Codec.decode(params.audio.base64),
                            Headers.build {
                                append(HttpHeaders.ContentType, params.audio.mediaType)
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "${ContentDisposition.File}; filename=\"${params.audio.filename ?: "audio"}\"",
                                )
                            },
                        )
                    },
                ),
            )
        }
        val response = with(HttpTransport) { rawResponse.toJsonResponse(url = "https://api.elevenlabs.io/v1/speech-to-text", errorMessage = ::elevenLabsErrorMessage) }
        val value = response.value.jsonObject
        val words = (value["words"] as? JsonArray).orEmpty()
        return TranscriptionModelResult(
            text = (value["text"] as? JsonPrimitive)?.contentOrNull,
            segments = words.mapNotNull { word ->
                val obj = word as? JsonObject ?: return@mapNotNull null
                TranscriptSegment(
                    text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    startSeconds = (obj["start"] as? JsonPrimitive)?.floatOrNull,
                    endSeconds = (obj["end"] as? JsonPrimitive)?.floatOrNull,
                )
            },
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            language = (value["language_code"] as? JsonPrimitive)?.contentOrNull,
            durationInSeconds = ((words.lastOrNull() as? JsonObject)?.get("end") as? JsonPrimitive)?.floatOrNull,
        )
    }

    private fun elevenLabsErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = ((obj?.get("detail") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("detail") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "ElevenLabs request failed ($statusCode): $detail"
    }
}

private const val ELEVENLABS_DEFAULT_VOICE_ID: String = "21m00Tcm4TlvDq8ikWAM"


internal class ElevenLabsBinaryResponse(
    val bytes: ByteArray,
    val mediaType: String,
    val headers: Map<String, String>,
)
