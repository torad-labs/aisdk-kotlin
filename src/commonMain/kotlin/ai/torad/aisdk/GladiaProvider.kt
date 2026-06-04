package ai.torad.aisdk

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val GLADIA_VERSION: String = "2.0.33"

typealias GladiaTranscriptionModelId = String

@Serializable
data class GladiaTranscriptionModelOptions(
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
data class GladiaProviderSettings(
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val pollingIntervalMillis: Long = 1_000L,
    val maxPollAttempts: Int = 60,
)

interface GladiaProvider : Provider {
    operator fun invoke(): TranscriptionModel = transcription()
    fun transcription(): TranscriptionModel
    fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription()
}

fun createGladia(
    client: HttpClient,
    settings: GladiaProviderSettings = GladiaProviderSettings(),
): GladiaProvider = DefaultGladiaProvider(client, settings)

val gladia: GladiaProvider = object : GladiaProvider {
    override val providerId: String = "gladia"
    override fun transcription(): TranscriptionModel =
        throw AiSdkException("Gladia provider is not configured. Use createGladia(client, settings).")
}

private class DefaultGladiaProvider(
    private val client: HttpClient,
    private val settings: GladiaProviderSettings,
) : GladiaProvider {
    override val providerId: String = "gladia"
    override fun transcription(): TranscriptionModel = GladiaTranscriptionModel(client, settings)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class GladiaTranscriptionModel(
    private val client: HttpClient,
    private val settings: GladiaProviderSettings,
) : TranscriptionModel {
    override val modelId: String = "default"
    override val provider: String = "gladia.transcription"

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        params.abortSignal.throwIfAborted()
        val upload = gladiaPostMultipart(
            client = client,
            url = "$GLADIA_BASE_URL/v2/upload",
            params = params,
            headers = gladiaHeaders(settings, params.headers),
        )
        val audioUrl = upload.value.jsonObject["audio_url"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("Gladia upload response is missing audio_url")

        params.abortSignal.throwIfAborted()
        val initBody = gladiaInitBody(audioUrl, params)
        val init = gladiaPostJson(
            client = client,
            url = "$GLADIA_BASE_URL/v2/pre-recorded",
            body = initBody,
            headers = gladiaHeaders(settings, params.headers),
        )
        val resultUrl = init.value.jsonObject["result_url"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("Gladia transcription init response is missing result_url")

        val result = gladiaPollResult(
            client = client,
            resultUrl = resultUrl,
            settings = settings,
            headers = params.headers,
            abortSignal = params.abortSignal,
        )
        val responseBody = result.value.jsonObject
        val transcript = responseBody["result"]?.jsonObject?.get("transcription")?.jsonObject
            ?: throw AiSdkException("Transcription result is empty")
        val utterances = transcript["utterances"]?.jsonArray.orEmpty()
        return TranscriptionModelResult(
            text = transcript["full_transcript"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            segments = utterances.map { utterance ->
                val obj = utterance.jsonObject
                TranscriptSegment(
                    text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    startSeconds = obj["start"]?.jsonPrimitive?.floatOrNull,
                    endSeconds = obj["end"]?.jsonPrimitive?.floatOrNull,
                )
            },
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = result.headers,
                body = result.value,
            ),
            providerMetadata = mapOf("gladia" to result.value),
        )
    }
}

private const val GLADIA_BASE_URL: String = "https://api.gladia.io"


private suspend fun gladiaPostMultipart(
    client: HttpClient,
    url: String,
    params: TranscriptionParams,
    headers: Map<String, String>,
): HttpJsonResponse {
    val filename = params.audio.filename ?: "audio.${mediaTypeToExtension(params.audio.mediaType)}"
    val response = client.request(url) {
        method = HttpMethod.Post
        headers.forEach { (name, value) -> header(name, value) }
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        "audio",
                        convertBase64ToByteArray(params.audio.base64),
                        Headers.build {
                            append(HttpHeaders.ContentType, params.audio.mediaType)
                            append(HttpHeaders.ContentDisposition, "${ContentDisposition.File}; filename=\"$filename\"")
                        },
                    )
                },
            ),
        )
    }
    return response.toJsonResponse(url = url, errorMessage = ::gladiaErrorMessage)
}

private suspend fun gladiaPostJson(
    client: HttpClient,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
): HttpJsonResponse =
    requestJson(
        client = client,
        url = url,
        method = HttpMethod.Post,
        headers = headers,
        body = body,
        requestBodyValues = body,
        errorMessage = ::gladiaErrorMessage,
    )

private suspend fun gladiaGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
): HttpJsonResponse =
    requestJson(
        client = client,
        url = url,
        method = HttpMethod.Get,
        headers = headers,
        errorMessage = ::gladiaErrorMessage,
    )

private suspend fun gladiaPollResult(
    client: HttpClient,
    resultUrl: String,
    settings: GladiaProviderSettings,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): HttpJsonResponse {
    repeat(settings.maxPollAttempts.coerceAtLeast(1)) { attempt ->
        abortSignal.throwIfAborted()
        val response = gladiaGetJson(
            client = client,
            url = resultUrl,
            headers = gladiaHeaders(settings, headers),
        )
        when (response.value.jsonObject["status"]?.jsonPrimitive?.contentOrNull) {
            "done" -> return response
            "error" -> throw AiSdkException("Transcription job failed")
            "queued", "processing" -> if (settings.pollingIntervalMillis > 0 && attempt < settings.maxPollAttempts - 1) {
                delay(settings.pollingIntervalMillis)
            }
            else -> throw AiSdkException("Gladia transcription response has unsupported status")
        }
    }
    throw AiSdkException("Transcription job polling timed out")
}

private fun gladiaInitBody(
    audioUrl: String,
    params: TranscriptionParams,
): JsonObject {
    val options = gladiaOptions(params.providerOptions)
    return buildJsonObject {
        putGladiaDirectOptions(options)
        putGladiaNestedOptions(options)
        if (!options.containsKey("language")) {
            params.language?.let { put("language", JsonPrimitive(it)) }
        }
        put("audio_url", JsonPrimitive(audioUrl))
    }
}

private fun JsonObjectBuilder.putGladiaDirectOptions(options: JsonObject) {
    for ((source, target) in gladiaDirectOptionMap) {
        val value = options[source] ?: continue
        if (value !is JsonNull) put(target, value)
    }
}

private fun JsonObjectBuilder.putGladiaNestedOptions(options: JsonObject) {
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

private fun gladiaHeaders(settings: GladiaProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val base = linkedMapOf<String, String>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base["x-gladia-key"] = it }
    base.putAll(settings.headers)
    base.putAll(callHeaders)
    return withUserAgentSuffix(base, "ai-sdk/gladia/$GLADIA_VERSION")
}

private fun gladiaOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["gladia"] as? JsonObject ?: JsonObject(emptyMap())

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun gladiaErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject
    val detail = obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
        ?: raw.ifBlank { "request failed" }
    return "Gladia request failed ($statusCode): $detail"
}
