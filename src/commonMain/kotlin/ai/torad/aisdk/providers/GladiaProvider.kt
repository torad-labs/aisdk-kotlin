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
/** @since 0.3.0-beta01 */
public class GladiaTranscriptionModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val contextPrompt: String? = null,
    /** @since 0.3.0-beta01 */
    public val customVocabulary: JsonElement? = null,
    /** @since 0.3.0-beta01 */
    public val customVocabularyConfig: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val detectLanguage: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val enableCodeSwitching: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val codeSwitchingConfig: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val language: String? = null,
    /** @since 0.3.0-beta01 */
    public val callback: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val callbackConfig: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val subtitles: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val subtitlesConfig: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val diarization: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val diarizationConfig: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val translation: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val translationConfig: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val summarization: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val summarizationConfig: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val moderation: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val namedEntityRecognition: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val chapterization: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val nameConsistency: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val customSpelling: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val customSpellingConfig: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val structuredDataExtraction: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val structuredDataExtractionConfig: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val sentimentAnalysis: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val audioToLlm: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val audioToLlmConfig: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val customMetadata: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val sentences: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val displayMode: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val punctuationEnhanced: Boolean? = null,
)

/** @since 0.3.0-beta01 */
public class GladiaTranscriptionModelOptionsBuilder {
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

    /** @since 0.3.0-beta01 */
    public fun contextPrompt(value: String?): GladiaTranscriptionModelOptionsBuilder {
        contextPrompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun customVocabulary(value: JsonElement?): GladiaTranscriptionModelOptionsBuilder {
        customVocabulary = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun customVocabularyConfig(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        customVocabularyConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun detectLanguage(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        detectLanguage = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun enableCodeSwitching(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        enableCodeSwitching = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun codeSwitchingConfig(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        codeSwitchingConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun language(value: String?): GladiaTranscriptionModelOptionsBuilder {
        language = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun callback(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        callback = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun callbackConfig(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        callbackConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun subtitles(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        subtitles = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun subtitlesConfig(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        subtitlesConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun diarization(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        diarization = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun diarizationConfig(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        diarizationConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun translation(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        translation = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun translationConfig(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        translationConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun summarization(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        summarization = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun summarizationConfig(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        summarizationConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun moderation(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        moderation = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun namedEntityRecognition(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        namedEntityRecognition = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun chapterization(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        chapterization = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun nameConsistency(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        nameConsistency = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun customSpelling(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        customSpelling = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun customSpellingConfig(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        customSpellingConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun structuredDataExtraction(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        structuredDataExtraction = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun structuredDataExtractionConfig(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        structuredDataExtractionConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun sentimentAnalysis(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        sentimentAnalysis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun audioToLlm(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        audioToLlm = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun audioToLlmConfig(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        audioToLlmConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun customMetadata(value: JsonObject?): GladiaTranscriptionModelOptionsBuilder {
        customMetadata = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun sentences(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        sentences = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun displayMode(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        displayMode = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun punctuationEnhanced(value: Boolean?): GladiaTranscriptionModelOptionsBuilder {
        punctuationEnhanced = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): GladiaTranscriptionModelOptions =
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

/** @since 0.3.0-beta01 */
public fun GladiaTranscriptionModelOptions(
    block: GladiaTranscriptionModelOptionsBuilder.() -> Unit = {},
): GladiaTranscriptionModelOptions =
    GladiaTranscriptionModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class GladiaProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val pollingIntervalMillis: Long = 1_000L,
    /** @since 0.3.0-beta01 */
    public val maxPollAttempts: Int = 60,
)

/** @since 0.3.0-beta01 */
public class GladiaProviderSettingsBuilder {
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var pollingIntervalMillis: Long = 1_000L
    private var maxPollAttempts: Int = 60

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): GladiaProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): GladiaProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollingIntervalMillis(value: Long): GladiaProviderSettingsBuilder {
        pollingIntervalMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxPollAttempts(value: Int): GladiaProviderSettingsBuilder {
        maxPollAttempts = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): GladiaProviderSettings =
        GladiaProviderSettings(
            apiKey = apiKey,
            headers = headers,
            pollingIntervalMillis = pollingIntervalMillis,
            maxPollAttempts = maxPollAttempts,
        )
}

/** @since 0.3.0-beta01 */
public fun GladiaProviderSettings(
    block: GladiaProviderSettingsBuilder.() -> Unit = {},
): GladiaProviderSettings =
    GladiaProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class GladiaProvider(
    private val client: HttpClient,
    private val settings: GladiaProviderSettings,
) : Provider {
    override val providerId: String = "gladia"

    public operator fun invoke(): TranscriptionModel = transcription()

    /** @since 0.3.0-beta01 */
    public fun transcription(): TranscriptionModel = GladiaTranscriptionModel(client, settings)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing =
        throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription()
}

/**
 * PascalCase factory — mirrors the [OpenAI] reference pattern.
 * @since 0.3.0-beta01
 */
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
        val detectedLanguage = (
            JsonAccess.arr(transcript, "languages")
                ?.firstOrNull() as? JsonPrimitive
            )
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
            durationInSeconds = (
                (JsonAccess.obj(resultObj, "metadata"))
                    ?.get("audio_duration") as? JsonPrimitive
                )
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
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "${ContentDisposition.File}; filename=\"$filename\""
                                )
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
                else -> throw InvalidResponseDataError(
                    response.value,
                    "Gladia transcription response has unsupported status"
                )
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
            put(
                "custom_vocabulary_config",
                buildJsonObject {
                    config["vocabulary"]?.let { put("vocabulary", it) }
                    config["defaultIntensity"]?.let { put("default_intensity", it) }
                }
            )
        }
        options["codeSwitchingConfig"]?.jsonObjectOrNull()?.let { config ->
            put(
                "code_switching_config",
                buildJsonObject {
                    config["languages"]?.let { put("languages", it) }
                }
            )
        }
        options["callbackConfig"]?.jsonObjectOrNull()?.let { config ->
            put(
                "callback_config",
                buildJsonObject {
                    config["url"]?.let { put("url", it) }
                    config["method"]?.let { put("method", it) }
                }
            )
        }
        options["subtitlesConfig"]?.jsonObjectOrNull()?.let { config ->
            put(
                "subtitles_config",
                buildJsonObject {
                    config["formats"]?.let { put("formats", it) }
                    config["minimumDuration"]?.let { put("minimum_duration", it) }
                    config["maximumDuration"]?.let { put("maximum_duration", it) }
                    config["maximumCharactersPerRow"]?.let { put("maximum_characters_per_row", it) }
                    config["maximumRowsPerCaption"]?.let { put("maximum_rows_per_caption", it) }
                    config["style"]?.let { put("style", it) }
                }
            )
        }
        options["diarizationConfig"]?.jsonObjectOrNull()?.let { config ->
            put(
                "diarization_config",
                buildJsonObject {
                    config["numberOfSpeakers"]?.let { put("number_of_speakers", it) }
                    config["minSpeakers"]?.let { put("min_speakers", it) }
                    config["maxSpeakers"]?.let { put("max_speakers", it) }
                    config["enhanced"]?.let { put("enhanced", it) }
                }
            )
        }
        options["translationConfig"]?.jsonObjectOrNull()?.let { config ->
            put(
                "translation_config",
                buildJsonObject {
                    config["targetLanguages"]?.let { put("target_languages", it) }
                    config["model"]?.let { put("model", it) }
                    config["matchOriginalUtterances"]?.let { put("match_original_utterances", it) }
                }
            )
        }
        options["summarizationConfig"]?.jsonObjectOrNull()?.let { config ->
            put(
                "summarization_config",
                buildJsonObject {
                    config["type"]?.let { put("type", it) }
                }
            )
        }
        options["customSpellingConfig"]?.jsonObjectOrNull()?.let { config ->
            put(
                "custom_spelling_config",
                buildJsonObject {
                    config["spellingDictionary"]?.let { put("spelling_dictionary", it) }
                }
            )
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
