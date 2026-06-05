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

public const val ASSEMBLYAI_VERSION: String = "2.0.33"

public typealias AssemblyAITranscriptionModelId = String

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
)

public interface AssemblyAIProvider : Provider {
    public operator fun invoke(modelId: AssemblyAITranscriptionModelId = "best"): TranscriptionModel = transcription(modelId)
    public fun transcription(modelId: AssemblyAITranscriptionModelId): TranscriptionModel
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
}

public fun createAssemblyAI(
    client: HttpClient,
    settings: AssemblyAIProviderSettings = AssemblyAIProviderSettings(),
): AssemblyAIProvider = DefaultAssemblyAIProvider(client, settings)

public val assemblyai: AssemblyAIProvider = object : AssemblyAIProvider {
    override val providerId: String = "assemblyai"
    override fun transcription(modelId: String): TranscriptionModel =
        throw AiSdkException("AssemblyAI provider is not configured. Use createAssemblyAI(client, settings).")
}

private class DefaultAssemblyAIProvider(
    private val client: HttpClient,
    private val settings: AssemblyAIProviderSettings,
) : AssemblyAIProvider {
    override val providerId: String = "assemblyai"
    override fun transcription(modelId: String): TranscriptionModel = AssemblyAITranscriptionModel(client, settings, modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class AssemblyAITranscriptionModel(
    private val client: HttpClient,
    private val settings: AssemblyAIProviderSettings,
    override val modelId: String,
) : TranscriptionModel {
    override val provider: String = "assemblyai.transcription"

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        params.abortSignal.throwIfAborted()
        val upload = assemblyAIPostBinary(
            client = client,
            url = "$ASSEMBLYAI_BASE_URL/v2/upload",
            bytes = convertBase64ToByteArray(params.audio.base64),
            headers = assemblyAIHeaders(settings, params.headers),
        )
        val uploadUrl = upload.value.jsonObject["upload_url"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("AssemblyAI upload response is missing upload_url")

        params.abortSignal.throwIfAborted()
        val submitBody = assemblyAISubmitBody(
            modelId = modelId,
            uploadUrl = uploadUrl,
            params = params,
        )
        val submit = assemblyAIPostJson(
            client = client,
            url = "$ASSEMBLYAI_BASE_URL/v2/transcript",
            body = submitBody,
            headers = assemblyAIHeaders(settings, params.headers),
        )
        val transcriptId = submit.value.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("AssemblyAI transcript submit response is missing id")

        val transcript = assemblyAIPollTranscript(
            client = client,
            settings = settings,
            transcriptId = transcriptId,
            headers = params.headers,
            abortSignal = params.abortSignal,
        )
        val body = transcript.value.jsonObject
        val words = body["words"]?.jsonArray.orEmpty()
        return TranscriptionModelResult(
            text = body["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            segments = words.map { word ->
                val obj = word.jsonObject
                TranscriptSegment(
                    text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    startSeconds = obj["start"]?.jsonPrimitive?.floatOrNull,
                    endSeconds = obj["end"]?.jsonPrimitive?.floatOrNull,
                )
            },
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = transcript.headers,
                body = transcript.value,
            ),
            providerMetadata = mapOf("assemblyai" to transcript.value),
        )
    }
}

private const val ASSEMBLYAI_BASE_URL: String = "https://api.assemblyai.com"


private suspend fun assemblyAIPostBinary(
    client: HttpClient,
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
    return response.toJsonResponse(url = url, errorMessage = ::assemblyAIErrorMessage)
}

private suspend fun assemblyAIPostJson(
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
        errorMessage = ::assemblyAIErrorMessage,
    )

private suspend fun assemblyAIGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
): HttpJsonResponse =
    requestJson(
        client = client,
        url = url,
        method = HttpMethod.Get,
        headers = headers,
        errorMessage = ::assemblyAIErrorMessage,
    )

private suspend fun assemblyAIPollTranscript(
    client: HttpClient,
    settings: AssemblyAIProviderSettings,
    transcriptId: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): HttpJsonResponse {
    while (true) {
        abortSignal.throwIfAborted()
        val response = assemblyAIGetJson(
            client = client,
            url = "$ASSEMBLYAI_BASE_URL/v2/transcript/$transcriptId",
            headers = assemblyAIHeaders(settings, headers),
        )
        val body = response.value.jsonObject
        when (body["status"]?.jsonPrimitive?.contentOrNull) {
            "completed" -> return response
            "error" -> throw AiSdkException("Transcription failed: ${body["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"}")
            "queued", "processing" -> if (settings.pollingIntervalMillis > 0) delay(settings.pollingIntervalMillis)
            else -> throw AiSdkException("AssemblyAI transcript response has unsupported status")
        }
    }
}

private fun assemblyAISubmitBody(
    modelId: String,
    uploadUrl: String,
    params: TranscriptionParams,
): JsonObject {
    val options = assemblyAIOptions(params.providerOptions)
    return buildJsonObject {
        put("speech_model", JsonPrimitive(modelId))
        putAssemblyAIOptions(options, params.language)
        put("audio_url", JsonPrimitive(uploadUrl))
    }
}

private fun JsonObjectBuilder.putAssemblyAIOptions(options: JsonObject, fallbackLanguage: String?) {
    for ((source, target) in assemblyAIOptionKeyMap) {
        val value = options[source] ?: continue
        if (value !is JsonNull) put(target, value)
    }
    if (!options.containsKey("languageCode")) {
        fallbackLanguage?.let { put("language_code", JsonPrimitive(it)) }
    }
}

private val assemblyAIOptionKeyMap: Map<String, String> = linkedMapOf(
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

private fun assemblyAIHeaders(settings: AssemblyAIProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val base = linkedMapOf<String, String>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = it }
    base.putAll(settings.headers)
    base.putAll(callHeaders)
    return withUserAgentSuffix(base, "ai-sdk/assemblyai/$ASSEMBLYAI_VERSION")
}

private fun assemblyAIOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["assemblyai"] as? JsonObject ?: JsonObject(emptyMap())

private fun assemblyAIErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject
    val detail = obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
        ?: raw.ifBlank { "request failed" }
    return "AssemblyAI request failed ($statusCode): $detail"
}
