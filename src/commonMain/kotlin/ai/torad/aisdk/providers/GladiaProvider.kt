package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
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
import kotlinx.serialization.json.jsonObject

public const val GLADIA_VERSION: String = "2.0.33"


@Serializable
@Poko
public class GladiaTranscriptionModelOptions internal constructor(
    public val contextPrompt: String? = null,
    public val customVocabulary: JsonElement? = null,
    public val customVocabularyConfig: JsonObject? = null,
    public val detectLanguage: Boolean? = null,
    public val enableCodeSwitching: Boolean? = null,
    public val codeSwitchingConfig: JsonObject? = null,
    public val language: String? = null,
    public val callback: Boolean? = null,
    public val callbackConfig: JsonObject? = null,
    public val subtitles: Boolean? = null,
    public val subtitlesConfig: JsonObject? = null,
    public val diarization: Boolean? = null,
    public val diarizationConfig: JsonObject? = null,
    public val translation: Boolean? = null,
    public val translationConfig: JsonObject? = null,
    public val summarization: Boolean? = null,
    public val summarizationConfig: JsonObject? = null,
    public val moderation: Boolean? = null,
    public val namedEntityRecognition: Boolean? = null,
    public val chapterization: Boolean? = null,
    public val nameConsistency: Boolean? = null,
    public val customSpelling: Boolean? = null,
    public val customSpellingConfig: JsonObject? = null,
    public val structuredDataExtraction: Boolean? = null,
    public val structuredDataExtractionConfig: JsonObject? = null,
    public val sentimentAnalysis: Boolean? = null,
    public val audioToLlm: Boolean? = null,
    public val audioToLlmConfig: JsonObject? = null,
    public val customMetadata: JsonObject? = null,
    public val sentences: Boolean? = null,
    public val displayMode: Boolean? = null,
    public val punctuationEnhanced: Boolean? = null,
)

public class GladiaTranscriptionModelOptionsBuilder internal constructor() {
    private var contextPrompt: String? = null
    private var customVocabulary: JsonElement? = null
    private var customVocabularyConfig: JsonObject? = null
    private var detectLanguage: Boolean? = null
    private var enableCodeSwitching: Boolean? = null
    private var codeSwitchingConfig: JsonObject? = null
    private var language: String? = null
    private var callback: Boolean? = null
    private var callbackConfig: JsonObject? = null
    private var subtitles: Boolean? = null
    private var subtitlesConfig: JsonObject? = null
    private var diarization: Boolean? = null
    private var diarizationConfig: JsonObject? = null
    private var translation: Boolean? = null
    private var translationConfig: JsonObject? = null
    private var summarization: Boolean? = null
    private var summarizationConfig: JsonObject? = null
    private var moderation: Boolean? = null
    private var namedEntityRecognition: Boolean? = null
    private var chapterization: Boolean? = null
    private var nameConsistency: Boolean? = null
    private var customSpelling: Boolean? = null
    private var customSpellingConfig: JsonObject? = null
    private var structuredDataExtraction: Boolean? = null
    private var structuredDataExtractionConfig: JsonObject? = null
    private var sentimentAnalysis: Boolean? = null
    private var audioToLlm: Boolean? = null
    private var audioToLlmConfig: JsonObject? = null
    private var customMetadata: JsonObject? = null
    private var sentences: Boolean? = null
    private var displayMode: Boolean? = null
    private var punctuationEnhanced: Boolean? = null

    public fun contextPrompt(value: String?) {
        contextPrompt = value
    }

    public fun customVocabulary(value: JsonElement?) {
        customVocabulary = value
    }

    public fun customVocabularyConfig(value: JsonObject?) {
        customVocabularyConfig = value
    }

    public fun detectLanguage(value: Boolean?) {
        detectLanguage = value
    }

    public fun enableCodeSwitching(value: Boolean?) {
        enableCodeSwitching = value
    }

    public fun codeSwitchingConfig(value: JsonObject?) {
        codeSwitchingConfig = value
    }

    public fun language(value: String?) {
        language = value
    }

    public fun callback(value: Boolean?) {
        callback = value
    }

    public fun callbackConfig(value: JsonObject?) {
        callbackConfig = value
    }

    public fun subtitles(value: Boolean?) {
        subtitles = value
    }

    public fun subtitlesConfig(value: JsonObject?) {
        subtitlesConfig = value
    }

    public fun diarization(value: Boolean?) {
        diarization = value
    }

    public fun diarizationConfig(value: JsonObject?) {
        diarizationConfig = value
    }

    public fun translation(value: Boolean?) {
        translation = value
    }

    public fun translationConfig(value: JsonObject?) {
        translationConfig = value
    }

    public fun summarization(value: Boolean?) {
        summarization = value
    }

    public fun summarizationConfig(value: JsonObject?) {
        summarizationConfig = value
    }

    public fun moderation(value: Boolean?) {
        moderation = value
    }

    public fun namedEntityRecognition(value: Boolean?) {
        namedEntityRecognition = value
    }

    public fun chapterization(value: Boolean?) {
        chapterization = value
    }

    public fun nameConsistency(value: Boolean?) {
        nameConsistency = value
    }

    public fun customSpelling(value: Boolean?) {
        customSpelling = value
    }

    public fun customSpellingConfig(value: JsonObject?) {
        customSpellingConfig = value
    }

    public fun structuredDataExtraction(value: Boolean?) {
        structuredDataExtraction = value
    }

    public fun structuredDataExtractionConfig(value: JsonObject?) {
        structuredDataExtractionConfig = value
    }

    public fun sentimentAnalysis(value: Boolean?) {
        sentimentAnalysis = value
    }

    public fun audioToLlm(value: Boolean?) {
        audioToLlm = value
    }

    public fun audioToLlmConfig(value: JsonObject?) {
        audioToLlmConfig = value
    }

    public fun customMetadata(value: JsonObject?) {
        customMetadata = value
    }

    public fun sentences(value: Boolean?) {
        sentences = value
    }

    public fun displayMode(value: Boolean?) {
        displayMode = value
    }

    public fun punctuationEnhanced(value: Boolean?) {
        punctuationEnhanced = value
    }

    internal fun build(): GladiaTranscriptionModelOptions =
        GladiaTranscriptionModelOptions(
            contextPrompt = contextPrompt,
            customVocabulary = customVocabulary,
            customVocabularyConfig = customVocabularyConfig,
            detectLanguage = detectLanguage,
            enableCodeSwitching = enableCodeSwitching,
            codeSwitchingConfig = codeSwitchingConfig,
            language = language,
            callback = callback,
            callbackConfig = callbackConfig,
            subtitles = subtitles,
            subtitlesConfig = subtitlesConfig,
            diarization = diarization,
            diarizationConfig = diarizationConfig,
            translation = translation,
            translationConfig = translationConfig,
            summarization = summarization,
            summarizationConfig = summarizationConfig,
            moderation = moderation,
            namedEntityRecognition = namedEntityRecognition,
            chapterization = chapterization,
            nameConsistency = nameConsistency,
            customSpelling = customSpelling,
            customSpellingConfig = customSpellingConfig,
            structuredDataExtraction = structuredDataExtraction,
            structuredDataExtractionConfig = structuredDataExtractionConfig,
            sentimentAnalysis = sentimentAnalysis,
            audioToLlm = audioToLlm,
            audioToLlmConfig = audioToLlmConfig,
            customMetadata = customMetadata,
            sentences = sentences,
            displayMode = displayMode,
            punctuationEnhanced = punctuationEnhanced,
        )
}

public fun GladiaTranscriptionModelOptions(
    block: GladiaTranscriptionModelOptionsBuilder.() -> Unit = {},
): GladiaTranscriptionModelOptions =
    GladiaTranscriptionModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
public class GladiaProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
    public val pollingIntervalMillis: Long = 1_000L,
    public val maxPollAttempts: Int = 60,
)

public class GladiaProviderSettingsBuilder internal constructor() {
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var pollingIntervalMillis: Long = 1_000L
    private var maxPollAttempts: Int = 60

    public fun apiKey(value: String?) {
        apiKey = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    public fun pollingIntervalMillis(value: Long) {
        pollingIntervalMillis = value
    }

    public fun maxPollAttempts(value: Int) {
        maxPollAttempts = value
    }

    internal fun build(): GladiaProviderSettings =
        GladiaProviderSettings(
            apiKey = apiKey,
            headers = headers,
            pollingIntervalMillis = pollingIntervalMillis,
            maxPollAttempts = maxPollAttempts,
        )
}

public fun GladiaProviderSettings(
    block: GladiaProviderSettingsBuilder.() -> Unit = {},
): GladiaProviderSettings =
    GladiaProviderSettingsBuilder().apply(block).build()

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
        val resultObj = JsonAccess.obj(responseBody, "result")
        val transcript = (resultObj?.get("transcription") as? JsonObject)
            ?: throw NoTranscriptGeneratedError("Transcription result is empty")
        val utterances = (JsonAccess.arr(transcript, "utterances")).orEmpty()
        val detectedLanguage = (JsonAccess.arr(transcript, "languages")
            ?.firstOrNull() as? JsonPrimitive)
            ?.contentOrNull
        return TranscriptionModelResult(
            text = (transcript["full_transcript"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            segments = utterances.mapNotNull { utterance ->
                val obj = utterance as? JsonObject ?: return@mapNotNull null
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
            language = detectedLanguage,
            durationInSeconds = ((JsonAccess.obj(resultObj, "metadata"))
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
        // The result_url is server-provided. Only attach credentialed headers
        // (x-gladia-key, custom headers) when it is same-origin with the Gladia
        // API; otherwise a malicious result_url would exfiltrate the API key.
        // Mirrors upstream's isSameOrigin guard.
        val pollHeaders =
            if (McpUrl.origin(resultUrl) == McpUrl.origin(GLADIA_BASE_URL)) headers(callHeaders) else emptyMap()
        repeat(settings.maxPollAttempts.coerceAtLeast(1)) { attempt ->
            abortSignal.throwIfAborted()
            val response = getJson(
                url = resultUrl,
                requestHeaders = pollHeaders,
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
        JsonAccess.obj(providerOptions.toMap(), "gladia") ?: JsonObject(emptyMap())

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun errorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Gladia request failed ($statusCode): $detail"
    }
}
