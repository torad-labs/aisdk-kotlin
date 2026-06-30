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
public data class AssemblyAICustomSpelling(
    val from: List<String>,
    val to: String,
)

@Serializable
@Poko
public class AssemblyAITranscriptionModelOptions internal constructor(
    public val audioEndAt: Int? = null,
    public val audioStartFrom: Int? = null,
    public val autoChapters: Boolean? = null,
    public val autoHighlights: Boolean? = null,
    public val boostParam: String? = null,
    public val contentSafety: Boolean? = null,
    public val contentSafetyConfidence: Int? = null,
    public val customSpelling: List<AssemblyAICustomSpelling>? = null,
    public val disfluencies: Boolean? = null,
    public val entityDetection: Boolean? = null,
    public val filterProfanity: Boolean? = null,
    public val formatText: Boolean? = null,
    public val iabCategories: Boolean? = null,
    public val languageCode: String? = null,
    public val languageConfidenceThreshold: Float? = null,
    public val languageDetection: Boolean? = null,
    public val multichannel: Boolean? = null,
    public val punctuate: Boolean? = null,
    public val redactPii: Boolean? = null,
    public val redactPiiAudio: Boolean? = null,
    public val redactPiiAudioQuality: String? = null,
    public val redactPiiPolicies: List<String>? = null,
    public val redactPiiSub: String? = null,
    public val sentimentAnalysis: Boolean? = null,
    public val speakerLabels: Boolean? = null,
    public val speakersExpected: Int? = null,
    public val speechThreshold: Float? = null,
    public val summarization: Boolean? = null,
    public val summaryModel: String? = null,
    public val summaryType: String? = null,
    public val webhookAuthHeaderName: String? = null,
    public val webhookAuthHeaderValue: String? = null,
    public val webhookUrl: String? = null,
    public val wordBoost: List<String>? = null,
)

public class AssemblyAITranscriptionModelOptionsBuilder internal constructor() {
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

    public fun audioEndAt(value: Int?) {
        audioEndAt = value
    }

    public fun audioStartFrom(value: Int?) {
        audioStartFrom = value
    }

    public fun autoChapters(value: Boolean?) {
        autoChapters = value
    }

    public fun autoHighlights(value: Boolean?) {
        autoHighlights = value
    }

    public fun boostParam(value: String?) {
        boostParam = value
    }

    public fun contentSafety(value: Boolean?) {
        contentSafety = value
    }

    public fun contentSafetyConfidence(value: Int?) {
        contentSafetyConfidence = value
    }

    public fun customSpelling(value: List<AssemblyAICustomSpelling>?) {
        customSpelling = value
    }

    public fun disfluencies(value: Boolean?) {
        disfluencies = value
    }

    public fun entityDetection(value: Boolean?) {
        entityDetection = value
    }

    public fun filterProfanity(value: Boolean?) {
        filterProfanity = value
    }

    public fun formatText(value: Boolean?) {
        formatText = value
    }

    public fun iabCategories(value: Boolean?) {
        iabCategories = value
    }

    public fun languageCode(value: String?) {
        languageCode = value
    }

    public fun languageConfidenceThreshold(value: Float?) {
        languageConfidenceThreshold = value
    }

    public fun languageDetection(value: Boolean?) {
        languageDetection = value
    }

    public fun multichannel(value: Boolean?) {
        multichannel = value
    }

    public fun punctuate(value: Boolean?) {
        punctuate = value
    }

    public fun redactPii(value: Boolean?) {
        redactPii = value
    }

    public fun redactPiiAudio(value: Boolean?) {
        redactPiiAudio = value
    }

    public fun redactPiiAudioQuality(value: String?) {
        redactPiiAudioQuality = value
    }

    public fun redactPiiPolicies(value: List<String>?) {
        redactPiiPolicies = value
    }

    public fun redactPiiSub(value: String?) {
        redactPiiSub = value
    }

    public fun sentimentAnalysis(value: Boolean?) {
        sentimentAnalysis = value
    }

    public fun speakerLabels(value: Boolean?) {
        speakerLabels = value
    }

    public fun speakersExpected(value: Int?) {
        speakersExpected = value
    }

    public fun speechThreshold(value: Float?) {
        speechThreshold = value
    }

    public fun summarization(value: Boolean?) {
        summarization = value
    }

    public fun summaryModel(value: String?) {
        summaryModel = value
    }

    public fun summaryType(value: String?) {
        summaryType = value
    }

    public fun webhookAuthHeaderName(value: String?) {
        webhookAuthHeaderName = value
    }

    public fun webhookAuthHeaderValue(value: String?) {
        webhookAuthHeaderValue = value
    }

    public fun webhookUrl(value: String?) {
        webhookUrl = value
    }

    public fun wordBoost(value: List<String>?) {
        wordBoost = value
    }

    internal fun build(): AssemblyAITranscriptionModelOptions =
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

public fun AssemblyAITranscriptionModelOptions(
    block: AssemblyAITranscriptionModelOptionsBuilder.() -> Unit = {},
): AssemblyAITranscriptionModelOptions =
    AssemblyAITranscriptionModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
public class AssemblyAIProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
    public val pollingIntervalMillis: Long = 3_000L,
    /** Upper bound on transcript poll attempts (120 × 3s ≈ 6 min) so a stuck job can't hang forever. */
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

public class AssemblyAIProviderSettingsBuilder internal constructor() {
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var pollingIntervalMillis: Long = 3_000L
    private var maxPollAttempts: Int = 120

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

    internal fun build(): AssemblyAIProviderSettings =
        AssemblyAIProviderSettings(
            apiKey = apiKey,
            headers = headers,
            pollingIntervalMillis = pollingIntervalMillis,
            maxPollAttempts = maxPollAttempts,
        )
}

public fun AssemblyAIProviderSettings(
    block: AssemblyAIProviderSettingsBuilder.() -> Unit = {},
): AssemblyAIProviderSettings =
    AssemblyAIProviderSettingsBuilder().apply(block).build()

public class AssemblyAIProvider(
    private val client: HttpClient,
    public val settings: AssemblyAIProviderSettings,
) : Provider {
    override val providerId: String = "assemblyai"

    public operator fun invoke(modelId: ModelId = ModelId("best")): TranscriptionModel = transcription(modelId)

    public fun transcription(modelId: ModelId): TranscriptionModel =
        AssemblyAITranscriptionModel(client, settings, modelId.value)

    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(ModelId(modelId))
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** PascalCase factory — mirrors the OpenAI reference pattern. */
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
