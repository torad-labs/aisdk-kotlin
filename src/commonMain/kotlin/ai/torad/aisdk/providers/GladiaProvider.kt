package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentDisposition
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject

public const val GLADIA_VERSION: String = "2.0.33"


@Serializable
public data class GladiaTranscriptionModelOptions(
    val contextPrompt: String? = null,
    val customVocabulary: JsonElement? = null,
    val customVocabularyConfig: JsonObject? = null,
    val detectLanguage: Boolean? = null,
    val enableCodeSwitching: Boolean? = null,
    val codeSwitchingConfig: JsonObject? = null,
    val language: String? = null,
    val callback: Boolean? = null,
    val callbackConfig: JsonObject? = null,
    val subtitles: Boolean? = null,
    val subtitlesConfig: JsonObject? = null,
    val diarization: Boolean? = null,
    val diarizationConfig: JsonObject? = null,
    val translation: Boolean? = null,
    val translationConfig: JsonObject? = null,
    val summarization: Boolean? = null,
    val summarizationConfig: JsonObject? = null,
    val moderation: Boolean? = null,
    val namedEntityRecognition: Boolean? = null,
    val chapterization: Boolean? = null,
    val nameConsistency: Boolean? = null,
    val customSpelling: Boolean? = null,
    val customSpellingConfig: JsonObject? = null,
    val structuredDataExtraction: Boolean? = null,
    val structuredDataExtractionConfig: JsonObject? = null,
    val sentimentAnalysis: Boolean? = null,
    val audioToLlm: Boolean? = null,
    val audioToLlmConfig: JsonObject? = null,
    val customMetadata: JsonObject? = null,
    val sentences: Boolean? = null,
    val displayMode: Boolean? = null,
    val punctuationEnhanced: Boolean? = null,
)

@Serializable
public data class GladiaProviderSettings(
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val pollingIntervalMillis: Long = 1_000L,
    val maxPollAttempts: Int = 60,
)

public class GladiaProvider(
    private val client: HttpClient,
    private val settings: GladiaProviderSettings,
) : Provider {
    override val providerId: String = "gladia"

    public operator fun invoke(): TranscriptionModel = transcription()

    public fun transcription(): TranscriptionModel = GladiaTranscriptionModel(client, settings)

    public fun textEmbeddingModel(modelId: String): Nothing =
        throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription()
}

/** PascalCase factory — mirrors the [OpenAI] reference pattern. */
public fun Gladia(
    client: HttpClient,
    settings: GladiaProviderSettings = GladiaProviderSettings(),
): GladiaProvider = GladiaProvider(client, settings)

private const val GLADIA_BASE_URL: String = "https://api.gladia.io"

private val gladiaDirectOptionMap: Map<String, String> = linkedMapOf(
    "contextPrompt" to "context_prompt",
    "customVocabulary" to "custom_vocabulary",
    "detectLanguage" to "detect_language",
    "enableCodeSwitching" to "enable_code_switching",
    "language" to "language",
    "callback" to "callback",
    "subtitles" to "subtitles",
    "diarization" to "diarization",
    "translation" to "translation",
    "summarization" to "summarization",
    "moderation" to "moderation",
    "namedEntityRecognition" to "named_entity_recognition",
    "chapterization" to "chapterization",
    "nameConsistency" to "name_consistency",
    "customSpelling" to "custom_spelling",
    "structuredDataExtraction" to "structured_data_extraction",
    "structuredDataExtractionConfig" to "structured_data_extraction_config",
    "sentimentAnalysis" to "sentiment_analysis",
    "audioToLlm" to "audio_to_llm",
    "audioToLlmConfig" to "audio_to_llm_config",
    "customMetadata" to "custom_metadata",
    "sentences" to "sentences",
    "displayMode" to "display_mode",
    "punctuationEnhanced" to "punctuation_enhanced",
)

private class GladiaTranscriptionModel(
    private val client: HttpClient,
    private val settings: GladiaProviderSettings,
) : TranscriptionModel {
    override val modelId: String = "default"
    override val provider: String = "gladia.transcription"

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        params.abortSignal.throwIfAborted()
        val upload = postMultipart(
            url = "$GLADIA_BASE_URL/v2/upload",
            params = params,
            requestHeaders = headers(params.headers),
        )
        val audioUrl = (upload.value.jsonObject["audio_url"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(upload.value, "Gladia upload response is missing audio_url")

        params.abortSignal.throwIfAborted()
        val body = buildInitBody(audioUrl, params)
        val init = postJson(
            url = "$GLADIA_BASE_URL/v2/pre-recorded",
            body = body,
            requestHeaders = headers(params.headers),
        )
        val resultUrl = (init.value.jsonObject["result_url"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(init.value, "Gladia transcription init response is missing result_url")

        val result = pollResult(
            resultUrl = resultUrl,
            callHeaders = params.headers,
            abortSignal = params.abortSignal,
        )
        val responseBody = result.value.jsonObject
        val resultObj = responseBody["result"] as? JsonObject
        val transcript = (resultObj?.get("transcription") as? JsonObject)
            ?: throw NoTranscriptGeneratedError("Transcription result is empty")
        val utterances = (transcript["utterances"] as? JsonArray).orEmpty()
        return TranscriptionModelResult(
            text = (transcript["full_transcript"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            segments = utterances.map { utterance ->
                val obj = utterance.jsonObject
                TranscriptSegment(
                    text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    startSeconds = (obj["start"] as? JsonPrimitive)?.floatOrNull,
                    endSeconds = (obj["end"] as? JsonPrimitive)?.floatOrNull,
                )
            },
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = result.headers,
                body = result.value,
            ),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("gladia" to result.value))),
            language = ((transcript["languages"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull,
            durationInSeconds = ((resultObj?.get("metadata") as? JsonObject)
                ?.get("audio_duration") as? JsonPrimitive)
                ?.floatOrNull,
        )
    }

    private suspend fun postMultipart(
        url: String,
        params: TranscriptionParams,
        requestHeaders: Map<String, String>,
    ): HttpJsonResponse {
        val filename = params.audio.filename ?: "audio.${MediaTypes.toExtension(params.audio.mediaType)}"
        val response = client.request(url) {
            method = HttpMethod.Post
            requestHeaders.forEach { (name, value) -> header(name, value) }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "audio",
                            Base64Codec.decode(params.audio.base64),
                            Headers.build {
                                append(HttpHeaders.ContentType, params.audio.mediaType)
                                append(HttpHeaders.ContentDisposition, "${ContentDisposition.File}; filename=\"$filename\"")
                            },
                        )
                    },
                ),
            )
        }
        return with(HttpTransport) { response.toJsonResponse(url = url, errorMessage = ::errorMessage) }
    }

    private suspend fun postJson(
        url: String,
        body: JsonObject,
        requestHeaders: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Post,
            headers = requestHeaders,
            body = body,
            requestBodyValues = body,
            errorMessage = ::errorMessage,
        )

    private suspend fun getJson(
        url: String,
        requestHeaders: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Get,
            headers = requestHeaders,
            errorMessage = ::errorMessage,
        )

    private suspend fun pollResult(
        resultUrl: String,
        callHeaders: Map<String, String>,
        abortSignal: AbortSignal,
    ): HttpJsonResponse {
        repeat(settings.maxPollAttempts.coerceAtLeast(1)) { attempt ->
            abortSignal.throwIfAborted()
            val response = getJson(
                url = resultUrl,
                requestHeaders = headers(callHeaders),
            )
            when ((response.value.jsonObject["status"] as? JsonPrimitive)?.contentOrNull) {
                "done" -> return response
                "error" -> throw NoTranscriptGeneratedError("Transcription job failed")
                "queued", "processing" -> if (settings.pollingIntervalMillis > 0 && attempt < settings.maxPollAttempts - 1) {
                    delay(settings.pollingIntervalMillis)
                }
                else -> throw InvalidResponseDataError(response.value, "Gladia transcription response has unsupported status")
            }
        }
        throw NoTranscriptGeneratedError("Transcription job polling timed out")
    }

    private fun buildInitBody(
        audioUrl: String,
        params: TranscriptionParams,
    ): JsonObject {
        val options = options(params.providerOptions)
        return buildJsonObject {
            putDirectOptions(options)
            putNestedOptions(options)
            if (!options.containsKey("language")) {
                params.language?.let { put("language", JsonPrimitive(it)) }
            }
            put("audio_url", JsonPrimitive(audioUrl))
        }
    }

    private fun JsonObjectBuilder.putDirectOptions(options: JsonObject) {
        for ((source, target) in gladiaDirectOptionMap) {
            val value = options[source] ?: continue
            if (value !is JsonNull) put(target, value)
        }
    }

    private fun JsonObjectBuilder.putNestedOptions(options: JsonObject) {
        options["customVocabularyConfig"]?.jsonObjectOrNull()?.let { config ->
            put("custom_vocabulary_config", buildJsonObject {
                config["vocabulary"]?.let { put("vocabulary", it) }
                config["defaultIntensity"]?.let { put("default_intensity", it) }
            })
        }
        options["codeSwitchingConfig"]?.jsonObjectOrNull()?.let { config ->
            put("code_switching_config", buildJsonObject {
                config["languages"]?.let { put("languages", it) }
            })
        }
        options["callbackConfig"]?.jsonObjectOrNull()?.let { config ->
            put("callback_config", buildJsonObject {
                config["url"]?.let { put("url", it) }
                config["method"]?.let { put("method", it) }
            })
        }
        options["subtitlesConfig"]?.jsonObjectOrNull()?.let { config ->
            put("subtitles_config", buildJsonObject {
                config["formats"]?.let { put("formats", it) }
                config["minimumDuration"]?.let { put("minimum_duration", it) }
                config["maximumDuration"]?.let { put("maximum_duration", it) }
                config["maximumCharactersPerRow"]?.let { put("maximum_characters_per_row", it) }
                config["maximumRowsPerCaption"]?.let { put("maximum_rows_per_caption", it) }
                config["style"]?.let { put("style", it) }
            })
        }
        options["diarizationConfig"]?.jsonObjectOrNull()?.let { config ->
            put("diarization_config", buildJsonObject {
                config["numberOfSpeakers"]?.let { put("number_of_speakers", it) }
                config["minSpeakers"]?.let { put("min_speakers", it) }
                config["maxSpeakers"]?.let { put("max_speakers", it) }
                config["enhanced"]?.let { put("enhanced", it) }
            })
        }
        options["translationConfig"]?.jsonObjectOrNull()?.let { config ->
            put("translation_config", buildJsonObject {
                config["targetLanguages"]?.let { put("target_languages", it) }
                config["model"]?.let { put("model", it) }
                config["matchOriginalUtterances"]?.let { put("match_original_utterances", it) }
            })
        }
        options["summarizationConfig"]?.jsonObjectOrNull()?.let { config ->
            put("summarization_config", buildJsonObject {
                config["type"]?.let { put("type", it) }
            })
        }
        options["customSpellingConfig"]?.jsonObjectOrNull()?.let { config ->
            put("custom_spelling_config", buildJsonObject {
                config["spellingDictionary"]?.let { put("spelling_dictionary", it) }
            })
        }
    }

    private fun headers(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        settings.apiKey?.takeIf { it.isNotBlank() }?.let { base["x-gladia-key"] = it }
        base.putAll(settings.headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/gladia/$GLADIA_VERSION")
    }

    private fun options(providerOptions: ProviderOptions): JsonObject =
        providerOptions.toMap()["gladia"] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun errorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Gladia request failed ($statusCode): $detail"
    }
}
