package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
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

public const val ASSEMBLYAI_VERSION: String = "2.0.33"


@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class AssemblyAICustomSpelling internal constructor(
    /** @since 0.3.0-beta01 */
    public val from: List<String>,
    /** @since 0.3.0-beta01 */
    public val to: String,
)

/** @since 0.3.0-beta01 */
public class AssemblyAICustomSpellingBuilder {
    private var from: List<String>? = null
    private var to: String? = null

    /** @since 0.3.0-beta01 */
    public fun from(value: List<String>): AssemblyAICustomSpellingBuilder {
        from = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun to(value: String): AssemblyAICustomSpellingBuilder {
        to = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AssemblyAICustomSpelling =
        AssemblyAICustomSpelling(
            from = requireNotNull(from) { "AssemblyAICustomSpelling.from is required" },
            to = requireNotNull(to) { "AssemblyAICustomSpelling.to is required" },
        )
}

/** @since 0.3.0-beta01 */
public fun AssemblyAICustomSpelling(
    block: AssemblyAICustomSpellingBuilder.() -> Unit = {},
): AssemblyAICustomSpelling =
    AssemblyAICustomSpellingBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class AssemblyAITranscriptionModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val audioEndAt: Int? = null,
    /** @since 0.3.0-beta01 */
    public val audioStartFrom: Int? = null,
    /** @since 0.3.0-beta01 */
    public val autoChapters: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val autoHighlights: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val boostParam: String? = null,
    /** @since 0.3.0-beta01 */
    public val contentSafety: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val contentSafetyConfidence: Int? = null,
    /** @since 0.3.0-beta01 */
    public val customSpelling: List<AssemblyAICustomSpelling>? = null,
    /** @since 0.3.0-beta01 */
    public val disfluencies: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val entityDetection: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val filterProfanity: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val formatText: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val iabCategories: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val languageCode: String? = null,
    /** @since 0.3.0-beta01 */
    public val languageConfidenceThreshold: Float? = null,
    /** @since 0.3.0-beta01 */
    public val languageDetection: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val multichannel: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val punctuate: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val redactPii: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val redactPiiAudio: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val redactPiiAudioQuality: String? = null,
    /** @since 0.3.0-beta01 */
    public val redactPiiPolicies: List<String>? = null,
    /** @since 0.3.0-beta01 */
    public val redactPiiSub: String? = null,
    /** @since 0.3.0-beta01 */
    public val sentimentAnalysis: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val speakerLabels: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val speakersExpected: Int? = null,
    /** @since 0.3.0-beta01 */
    public val speechThreshold: Float? = null,
    /** @since 0.3.0-beta01 */
    public val summarization: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val summaryModel: String? = null,
    /** @since 0.3.0-beta01 */
    public val summaryType: String? = null,
    /** @since 0.3.0-beta01 */
    public val webhookAuthHeaderName: String? = null,
    /** @since 0.3.0-beta01 */
    public val webhookAuthHeaderValue: String? = null,
    /** @since 0.3.0-beta01 */
    public val webhookUrl: String? = null,
    /** @since 0.3.0-beta01 */
    public val wordBoost: List<String>? = null,
)

/** @since 0.3.0-beta01 */
public class AssemblyAITranscriptionModelOptionsBuilder {
    private var audioEndAt: Int? = null
    private var audioStartFrom: Int? = null
    private var autoChapters: Boolean? = null
    private var autoHighlights: Boolean? = null
    private var boostParam: String? = null
    private var contentSafety: Boolean? = null
    private var contentSafetyConfidence: Int? = null
    private var customSpelling: List<AssemblyAICustomSpelling>? = null
    private var disfluencies: Boolean? = null
    private var entityDetection: Boolean? = null
    private var filterProfanity: Boolean? = null
    private var formatText: Boolean? = null
    private var iabCategories: Boolean? = null
    private var languageCode: String? = null
    private var languageConfidenceThreshold: Float? = null
    private var languageDetection: Boolean? = null
    private var multichannel: Boolean? = null
    private var punctuate: Boolean? = null
    private var redactPii: Boolean? = null
    private var redactPiiAudio: Boolean? = null
    private var redactPiiAudioQuality: String? = null
    private var redactPiiPolicies: List<String>? = null
    private var redactPiiSub: String? = null
    private var sentimentAnalysis: Boolean? = null
    private var speakerLabels: Boolean? = null
    private var speakersExpected: Int? = null
    private var speechThreshold: Float? = null
    private var summarization: Boolean? = null
    private var summaryModel: String? = null
    private var summaryType: String? = null
    private var webhookAuthHeaderName: String? = null
    private var webhookAuthHeaderValue: String? = null
    private var webhookUrl: String? = null
    private var wordBoost: List<String>? = null

    /** @since 0.3.0-beta01 */
    public fun audioEndAt(value: Int?): AssemblyAITranscriptionModelOptionsBuilder {
        audioEndAt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun audioStartFrom(value: Int?): AssemblyAITranscriptionModelOptionsBuilder {
        audioStartFrom = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun autoChapters(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        autoChapters = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun autoHighlights(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        autoHighlights = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun boostParam(value: String?): AssemblyAITranscriptionModelOptionsBuilder {
        boostParam = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun contentSafety(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        contentSafety = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun contentSafetyConfidence(value: Int?): AssemblyAITranscriptionModelOptionsBuilder {
        contentSafetyConfidence = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun customSpelling(value: List<AssemblyAICustomSpelling>?): AssemblyAITranscriptionModelOptionsBuilder {
        customSpelling = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun disfluencies(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        disfluencies = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun entityDetection(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        entityDetection = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun filterProfanity(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        filterProfanity = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun formatText(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        formatText = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun iabCategories(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        iabCategories = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun languageCode(value: String?): AssemblyAITranscriptionModelOptionsBuilder {
        languageCode = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun languageConfidenceThreshold(value: Float?): AssemblyAITranscriptionModelOptionsBuilder {
        languageConfidenceThreshold = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun languageDetection(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        languageDetection = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun multichannel(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        multichannel = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun punctuate(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        punctuate = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun redactPii(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        redactPii = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun redactPiiAudio(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        redactPiiAudio = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun redactPiiAudioQuality(value: String?): AssemblyAITranscriptionModelOptionsBuilder {
        redactPiiAudioQuality = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun redactPiiPolicies(value: List<String>?): AssemblyAITranscriptionModelOptionsBuilder {
        redactPiiPolicies = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun redactPiiSub(value: String?): AssemblyAITranscriptionModelOptionsBuilder {
        redactPiiSub = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun sentimentAnalysis(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        sentimentAnalysis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun speakerLabels(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        speakerLabels = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun speakersExpected(value: Int?): AssemblyAITranscriptionModelOptionsBuilder {
        speakersExpected = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun speechThreshold(value: Float?): AssemblyAITranscriptionModelOptionsBuilder {
        speechThreshold = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun summarization(value: Boolean?): AssemblyAITranscriptionModelOptionsBuilder {
        summarization = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun summaryModel(value: String?): AssemblyAITranscriptionModelOptionsBuilder {
        summaryModel = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun summaryType(value: String?): AssemblyAITranscriptionModelOptionsBuilder {
        summaryType = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun webhookAuthHeaderName(value: String?): AssemblyAITranscriptionModelOptionsBuilder {
        webhookAuthHeaderName = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun webhookAuthHeaderValue(value: String?): AssemblyAITranscriptionModelOptionsBuilder {
        webhookAuthHeaderValue = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun webhookUrl(value: String?): AssemblyAITranscriptionModelOptionsBuilder {
        webhookUrl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun wordBoost(value: List<String>?): AssemblyAITranscriptionModelOptionsBuilder {
        wordBoost = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AssemblyAITranscriptionModelOptions =
        AssemblyAITranscriptionModelOptions(
            audioEndAt = audioEndAt,
            audioStartFrom = audioStartFrom,
            autoChapters = autoChapters,
            autoHighlights = autoHighlights,
            boostParam = boostParam,
            contentSafety = contentSafety,
            contentSafetyConfidence = contentSafetyConfidence,
            customSpelling = customSpelling,
            disfluencies = disfluencies,
            entityDetection = entityDetection,
            filterProfanity = filterProfanity,
            formatText = formatText,
            iabCategories = iabCategories,
            languageCode = languageCode,
            languageConfidenceThreshold = languageConfidenceThreshold,
            languageDetection = languageDetection,
            multichannel = multichannel,
            punctuate = punctuate,
            redactPii = redactPii,
            redactPiiAudio = redactPiiAudio,
            redactPiiAudioQuality = redactPiiAudioQuality,
            redactPiiPolicies = redactPiiPolicies,
            redactPiiSub = redactPiiSub,
            sentimentAnalysis = sentimentAnalysis,
            speakerLabels = speakerLabels,
            speakersExpected = speakersExpected,
            speechThreshold = speechThreshold,
            summarization = summarization,
            summaryModel = summaryModel,
            summaryType = summaryType,
            webhookAuthHeaderName = webhookAuthHeaderName,
            webhookAuthHeaderValue = webhookAuthHeaderValue,
            webhookUrl = webhookUrl,
            wordBoost = wordBoost,
        )
}

/** @since 0.3.0-beta01 */
public fun AssemblyAITranscriptionModelOptions(
    block: AssemblyAITranscriptionModelOptionsBuilder.() -> Unit = {},
): AssemblyAITranscriptionModelOptions =
    AssemblyAITranscriptionModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class AssemblyAIProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val pollingIntervalMillis: Long = 3_000L,
    /**
     * Upper bound on transcript poll attempts (120 × 3s ≈ 6 min) so a stuck job can't hang forever.
     * @since 0.3.0-beta01
     */
    public val maxPollAttempts: Int = 120,
) {
    internal fun requestHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = it }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/assemblyai/$ASSEMBLYAI_VERSION")
    }
}

/** @since 0.3.0-beta01 */
public class AssemblyAIProviderSettingsBuilder {
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var pollingIntervalMillis: Long = 3_000L
    private var maxPollAttempts: Int = 120

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): AssemblyAIProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): AssemblyAIProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollingIntervalMillis(value: Long): AssemblyAIProviderSettingsBuilder {
        pollingIntervalMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxPollAttempts(value: Int): AssemblyAIProviderSettingsBuilder {
        maxPollAttempts = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AssemblyAIProviderSettings =
        AssemblyAIProviderSettings(
            apiKey = apiKey,
            headers = headers,
            pollingIntervalMillis = pollingIntervalMillis,
            maxPollAttempts = maxPollAttempts,
        )
}

/** @since 0.3.0-beta01 */
public fun AssemblyAIProviderSettings(
    block: AssemblyAIProviderSettingsBuilder.() -> Unit = {},
): AssemblyAIProviderSettings =
    AssemblyAIProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class AssemblyAIProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: AssemblyAIProviderSettings,
) : Provider {
    override val providerId: String = "assemblyai"

    public operator fun invoke(modelId: ModelId = ModelId("best")): TranscriptionModel = transcription(modelId)

    /** @since 0.3.0-beta01 */
    public fun transcription(modelId: ModelId): TranscriptionModel =
        AssemblyAITranscriptionModel(client, settings, modelId.value)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(ModelId(modelId))
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/**
 * PascalCase factory — mirrors the OpenAI reference pattern.
 * @since 0.3.0-beta01
 */
public fun AssemblyAI(
    client: HttpClient,
    settings: AssemblyAIProviderSettings = AssemblyAIProviderSettings(),
): AssemblyAIProvider = AssemblyAIProvider(client, settings)

private class AssemblyAITranscriptionModel(
    private val client: HttpClient,
    private val settings: AssemblyAIProviderSettings,
    override val modelId: String,
) : TranscriptionModel {
    override val provider: String = "assemblyai.transcription"

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        params.abortSignal.throwIfAborted()
        val upload = postBinary(
            url = "$ASSEMBLYAI_BASE_URL/v2/upload",
            bytes = Base64Codec.decode(params.audio.base64),
            headers = settings.requestHeaders(params.headers),
        )
        val uploadUrl = (upload.value.jsonObject["upload_url"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(upload.value, "AssemblyAI upload response is missing upload_url")

        params.abortSignal.throwIfAborted()
        val submitBody = submitBody(
            uploadUrl = uploadUrl,
            params = params,
        )
        val submit = postJson(
            url = "$ASSEMBLYAI_BASE_URL/v2/transcript",
            body = submitBody,
            headers = settings.requestHeaders(params.headers),
        )
        val transcriptId = (submit.value.jsonObject["id"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(submit.value, "AssemblyAI transcript submit response is missing id")

        val transcript = pollTranscript(
            transcriptId = transcriptId,
            headers = params.headers,
            abortSignal = params.abortSignal,
        )
        val body = transcript.value.jsonObject
        val words = (JsonAccess.arr(body, "words")).orEmpty()
        return TranscriptionModelResult(
            text = (body["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            segments = words.mapNotNull { word ->
                val obj = word as? JsonObject ?: return@mapNotNull null
                TranscriptSegment(
                    text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    // AssemblyAI word timestamps are in milliseconds; segment fields are seconds.
                    startSeconds = (obj["start"] as? JsonPrimitive)?.floatOrNull?.let { it / MILLIS_PER_SECOND },
                    endSeconds = (obj["end"] as? JsonPrimitive)?.floatOrNull?.let { it / MILLIS_PER_SECOND },
                )
            },
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = transcript.headers,
                body = transcript.value,
            ),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("assemblyai" to transcript.value))),
            language = (body["language_code"] as? JsonPrimitive)?.contentOrNull,
            durationInSeconds = (body["audio_duration"] as? JsonPrimitive)?.floatOrNull,
        )
    }

    private suspend fun postBinary(
        url: String,
        bytes: ByteArray,
        headers: Map<String, String>,
    ): HttpJsonResponse {
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.OctetStream)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(bytes)
        }
        return with(HttpTransport) { response.toJsonResponse(url = url, errorMessage = ::errorMessage) }
    }

    private suspend fun postJson(
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Post,
            headers = headers,
            body = body,
            requestBodyValues = body,
            errorMessage = ::errorMessage,
        )

    private suspend fun getJson(
        url: String,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Get,
            headers = headers,
            errorMessage = ::errorMessage,
        )

    private suspend fun pollTranscript(
        transcriptId: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
    ): HttpJsonResponse {
        // Bounded poll (like RevAI/Gladia) so an indefinitely "queued"/"processing" job can't hang
        // the coroutine forever when the caller hasn't set a timeout AbortSignal.
        repeat(settings.maxPollAttempts) { attempt ->
            abortSignal.throwIfAborted()
            val response = getJson(
                url = "$ASSEMBLYAI_BASE_URL/v2/transcript/$transcriptId",
                headers = settings.requestHeaders(headers),
            )
            val body = response.value.jsonObject
            when ((body["status"] as? JsonPrimitive)?.contentOrNull) {
                "completed" -> return response
                "error" -> {
                    val detail = (body["error"] as? JsonPrimitive)?.contentOrNull ?: "Unknown error"
                    throw NoTranscriptGeneratedError("Transcription failed: $detail")
                }
                "queued", "processing" ->
                    if (settings.pollingIntervalMillis > 0 && attempt < settings.maxPollAttempts - 1) {
                        delay(settings.pollingIntervalMillis)
                    }
                else -> throw InvalidResponseDataError(null, "AssemblyAI transcript response has unsupported status")
            }
        }
        throw NoTranscriptGeneratedError("AssemblyAI transcription polling timed out after ${settings.maxPollAttempts} attempts")
    }

    private fun submitBody(
        uploadUrl: String,
        params: TranscriptionParams,
    ): JsonObject {
        val options = options(params.providerOptions)
        return buildJsonObject {
            put("speech_model", JsonPrimitive(modelId))
            putOptions(options, params.language)
            put("audio_url", JsonPrimitive(uploadUrl))
        }
    }

    private fun JsonObjectBuilder.putOptions(options: JsonObject, fallbackLanguage: String?) {
        for ((source, target) in optionKeyMap) {
            val value = options[source] ?: continue
            if (value !is JsonNull) put(target, value)
        }
        if (!options.containsKey("languageCode")) {
            fallbackLanguage?.let { put("language_code", JsonPrimitive(it)) }
        }
    }

    private fun options(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "assemblyai") ?: JsonObject(emptyMap())

    private fun errorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "AssemblyAI request failed ($statusCode): $detail"
    }
}

private const val ASSEMBLYAI_BASE_URL: String = "https://api.assemblyai.com"

/** AssemblyAI reports word timestamps in milliseconds; segments use seconds. */
private const val MILLIS_PER_SECOND: Float = 1000f

private val optionKeyMap: Map<String, String> = linkedMapOf(
    "audioEndAt" to "audio_end_at",
    "audioStartFrom" to "audio_start_from",
    "autoChapters" to "auto_chapters",
    "autoHighlights" to "auto_highlights",
    "boostParam" to "boost_param",
    "contentSafety" to "content_safety",
    "contentSafetyConfidence" to "content_safety_confidence",
    "customSpelling" to "custom_spelling",
    "disfluencies" to "disfluencies",
    "entityDetection" to "entity_detection",
    "filterProfanity" to "filter_profanity",
    "formatText" to "format_text",
    "iabCategories" to "iab_categories",
    "languageCode" to "language_code",
    "languageConfidenceThreshold" to "language_confidence_threshold",
    "languageDetection" to "language_detection",
    "multichannel" to "multichannel",
    "punctuate" to "punctuate",
    "redactPii" to "redact_pii",
    "redactPiiAudio" to "redact_pii_audio",
    "redactPiiAudioQuality" to "redact_pii_audio_quality",
    "redactPiiPolicies" to "redact_pii_policies",
    "redactPiiSub" to "redact_pii_sub",
    "sentimentAnalysis" to "sentiment_analysis",
    "speakerLabels" to "speaker_labels",
    "speakersExpected" to "speakers_expected",
    "speechThreshold" to "speech_threshold",
    "summarization" to "summarization",
    "summaryModel" to "summary_model",
    "summaryType" to "summary_type",
    "webhookAuthHeaderName" to "webhook_auth_header_name",
    "webhookAuthHeaderValue" to "webhook_auth_header_value",
    "webhookUrl" to "webhook_url",
    "wordBoost" to "word_boost",
)
