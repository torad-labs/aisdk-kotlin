package ai.torad.aisdk.providers

import ai.torad.aisdk.*
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

public const val ASSEMBLYAI_VERSION: String = "2.0.33"


@Serializable
public data class AssemblyAICustomSpelling(
    val from: List<String>,
    val to: String,
)

@Serializable
public data class AssemblyAITranscriptionModelOptions(
    val audioEndAt: Int? = null,
    val audioStartFrom: Int? = null,
    val autoChapters: Boolean? = null,
    val autoHighlights: Boolean? = null,
    val boostParam: String? = null,
    val contentSafety: Boolean? = null,
    val contentSafetyConfidence: Int? = null,
    val customSpelling: List<AssemblyAICustomSpelling>? = null,
    val disfluencies: Boolean? = null,
    val entityDetection: Boolean? = null,
    val filterProfanity: Boolean? = null,
    val formatText: Boolean? = null,
    val iabCategories: Boolean? = null,
    val languageCode: String? = null,
    val languageConfidenceThreshold: Float? = null,
    val languageDetection: Boolean? = null,
    val multichannel: Boolean? = null,
    val punctuate: Boolean? = null,
    val redactPii: Boolean? = null,
    val redactPiiAudio: Boolean? = null,
    val redactPiiAudioQuality: String? = null,
    val redactPiiPolicies: List<String>? = null,
    val redactPiiSub: String? = null,
    val sentimentAnalysis: Boolean? = null,
    val speakerLabels: Boolean? = null,
    val speakersExpected: Int? = null,
    val speechThreshold: Float? = null,
    val summarization: Boolean? = null,
    val summaryModel: String? = null,
    val summaryType: String? = null,
    val webhookAuthHeaderName: String? = null,
    val webhookAuthHeaderValue: String? = null,
    val webhookUrl: String? = null,
    val wordBoost: List<String>? = null,
)

@Serializable
public data class AssemblyAIProviderSettings(
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val pollingIntervalMillis: Long = 3_000L,
    /** Upper bound on transcript poll attempts (120 × 3s ≈ 6 min) so a stuck job can't hang forever. */
    val maxPollAttempts: Int = 120,
) {
    internal fun requestHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = it }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/assemblyai/$ASSEMBLYAI_VERSION")
    }
}

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
        val words = (body["words"] as? JsonArray).orEmpty()
        return TranscriptionModelResult(
            text = (body["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            segments = words.mapNotNull { word ->
                val obj = word as? JsonObject ?: return@mapNotNull null
                TranscriptSegment(
                    text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    startSeconds = (obj["start"] as? JsonPrimitive)?.floatOrNull,
                    endSeconds = (obj["end"] as? JsonPrimitive)?.floatOrNull,
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
        providerOptions.toMap()["assemblyai"] as? JsonObject ?: JsonObject(emptyMap())

    private fun errorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "AssemblyAI request failed ($statusCode): $detail"
    }
}

private const val ASSEMBLYAI_BASE_URL: String = "https://api.assemblyai.com"

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
