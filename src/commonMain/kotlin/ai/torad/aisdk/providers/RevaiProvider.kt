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

public const val REVAI_VERSION: String = "2.0.33"

public typealias RevaiTranscriptionModelId = String

@Serializable
public data class RevaiTranscriptionModelOptions(
    val metadata: String? = null,
    val notification_config: JsonObject? = null,
    val delete_after_seconds: Int? = null,
    val verbatim: Boolean? = null,
    val rush: Boolean? = null,
    val test_mode: Boolean? = null,
    val segments_to_transcribe: JsonElement? = null,
    val speaker_names: JsonElement? = null,
    val skip_diarization: Boolean? = null,
    val skip_postprocessing: Boolean? = null,
    val skip_punctuation: Boolean? = null,
    val remove_disfluencies: Boolean? = null,
    val remove_atmospherics: Boolean? = null,
    val filter_profanity: Boolean? = null,
    val speaker_channels_count: Int? = null,
    val speakers_count: Int? = null,
    val diarization_type: String? = null,
    val custom_vocabulary_id: String? = null,
    val custom_vocabularies: JsonElement? = null,
    val strict_custom_vocabulary: Boolean? = null,
    val summarization_config: JsonObject? = null,
    val translation_config: JsonObject? = null,
    val language: String? = null,
    val forced_alignment: Boolean? = null,
)

@Serializable
public data class RevaiProviderSettings(
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val pollingIntervalMillis: Long = 1_000L,
    val maxPollAttempts: Int = 60,
)

public class RevaiProvider(
    private val client: HttpClient,
    public val settings: RevaiProviderSettings,
) : Provider {
    override val providerId: String = "revai"

    public operator fun invoke(modelId: RevaiTranscriptionModelId = "machine"): TranscriptionModel = transcription(modelId)

    public fun transcription(modelId: RevaiTranscriptionModelId): TranscriptionModel =
        RevaiTranscriptionModel(client, settings, modelId)

    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** PascalCase factory mirroring the OpenAI reference pattern. */
public fun Revai(
    client: HttpClient,
    settings: RevaiProviderSettings = RevaiProviderSettings(),
): RevaiProvider = RevaiProvider(client, settings)

private class RevaiTranscriptionModel(
    private val client: HttpClient,
    private val settings: RevaiProviderSettings,
    override val modelId: String,
) : TranscriptionModel {
    override val provider: String = "revai.transcription"

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        params.abortSignal.throwIfAborted()
        val submit = revaiPostMultipart(
            client = client,
            url = "$REVAI_BASE_URL/speechtotext/v1/jobs",
            params = params,
            modelId = modelId,
            headers = revaiHeaders(settings, params.headers),
        )
        var job = submit.value.jsonObject
        if (job["status"]?.jsonPrimitive?.contentOrNull == "failed") {
            throw NoTranscriptGeneratedError("Failed to submit transcription job to Rev.ai")
        }
        val jobId = job["id"]?.jsonPrimitive?.contentOrNull
            ?: throw InvalidResponseDataError(submit.value, "Rev.ai transcription job response is missing id")

        repeat(settings.maxPollAttempts.coerceAtLeast(1)) { attempt ->
            params.abortSignal.throwIfAborted()
            val status = job["status"]?.jsonPrimitive?.contentOrNull
            if (status == "transcribed") return@repeat
            if (attempt > 0 || status != "transcribed") {
                val poll = revaiGetJson(
                    client = client,
                    url = "$REVAI_BASE_URL/speechtotext/v1/jobs/$jobId",
                    headers = revaiHeaders(settings, params.headers),
                )
                job = poll.value.jsonObject
                when (job["status"]?.jsonPrimitive?.contentOrNull) {
                    "transcribed" -> return@repeat
                    "failed" -> throw NoTranscriptGeneratedError("Rev.ai transcription job failed")
                }
            }
            if (job["status"]?.jsonPrimitive?.contentOrNull != "transcribed" && settings.pollingIntervalMillis > 0 && attempt < settings.maxPollAttempts - 1) {
                delay(settings.pollingIntervalMillis)
            }
        }
        if (job["status"]?.jsonPrimitive?.contentOrNull != "transcribed") {
            throw NoTranscriptGeneratedError("Rev.ai transcription job polling timed out")
        }

        val transcript = revaiGetJson(
            client = client,
            url = "$REVAI_BASE_URL/speechtotext/v1/jobs/$jobId/transcript",
            headers = revaiHeaders(settings, params.headers),
        )
        val mapped = mapRevaiTranscript(transcript.value)
        return TranscriptionModelResult(
            text = mapped.text,
            segments = mapped.segments,
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = transcript.headers,
                body = transcript.value,
            ),
            providerMetadata = mapOf("revai" to transcript.value),
            language = job["language"]?.jsonPrimitive?.contentOrNull,
            durationInSeconds = mapped.durationInSeconds,
        )
    }
}

private const val REVAI_BASE_URL: String = "https://api.rev.ai"


private data class RevaiTranscriptMapping(
    val text: String,
    val segments: List<TranscriptSegment>,
    val durationInSeconds: Float,
)

private suspend fun revaiPostMultipart(
    client: HttpClient,
    url: String,
    params: TranscriptionParams,
    modelId: String,
    headers: Map<String, String>,
): HttpJsonResponse {
    val filename = params.audio.filename ?: "audio.${MediaTypes.toExtension(params.audio.mediaType)}"
    val config = revaiConfigBody(modelId, params)
    val response = client.request(url) {
        method = HttpMethod.Post
        headers.forEach { (name, value) -> header(name, value) }
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        "media",
                        Base64Codec.decode(params.audio.base64),
                        Headers.build {
                            append(HttpHeaders.ContentType, params.audio.mediaType)
                            append(HttpHeaders.ContentDisposition, "${ContentDisposition.File}; filename=\"$filename\"")
                        },
                    )
                    append("config", aiSdkJson.encodeToString(JsonElement.serializer(), config))
                },
            ),
        )
    }
    return response.toJsonResponse(url = url, errorMessage = ::revaiErrorMessage)
}

private suspend fun revaiGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
): HttpJsonResponse =
    requestJson(
        client = client,
        url = url,
        method = HttpMethod.Get,
        headers = headers,
        errorMessage = ::revaiErrorMessage,
    )

private fun revaiConfigBody(
    modelId: String,
    params: TranscriptionParams,
): JsonObject {
    val options = revaiOptions(params.providerOptions)
    return buildJsonObject {
        put("transcriber", JsonPrimitive(modelId))
        putRevaiOptions(options)
        if (!options.containsKey("language")) {
            params.language?.let { put("language", JsonPrimitive(it)) }
        }
    }
}

private fun JsonObjectBuilder.putRevaiOptions(options: JsonObject) {
    for (key in revaiOptionKeys) {
        val value = options[key] ?: continue
        if (value !is JsonNull) put(key, value)
    }
}

private val revaiOptionKeys: Set<String> = linkedSetOf(
    "metadata",
    "notification_config",
    "delete_after_seconds",
    "verbatim",
    "rush",
    "test_mode",
    "segments_to_transcribe",
    "speaker_names",
    "skip_diarization",
    "skip_postprocessing",
    "skip_punctuation",
    "remove_disfluencies",
    "remove_atmospherics",
    "filter_profanity",
    "speaker_channels_count",
    "speakers_count",
    "diarization_type",
    "custom_vocabulary_id",
    "custom_vocabularies",
    "strict_custom_vocabulary",
    "summarization_config",
    "translation_config",
    "language",
    "forced_alignment",
)

private fun mapRevaiTranscript(value: JsonElement): RevaiTranscriptMapping {
    val monologues = value.jsonObject["monologues"]?.jsonArray.orEmpty()
    val text = monologues.joinToString(" ") { monologue ->
        monologue.jsonObject["elements"]?.jsonArray.orEmpty()
            .joinToString("") { element -> element.jsonObject["value"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }
    val segments = mutableListOf<TranscriptSegment>()
    var durationInSeconds = 0f
    for (monologue in monologues) {
        var currentText = ""
        var segmentStart = 0f
        var hasStarted = false
        for (element in monologue.jsonObject["elements"]?.jsonArray.orEmpty()) {
            val obj = element.jsonObject
            currentText += obj["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                val end = obj["end_ts"]?.jsonPrimitive?.floatOrNull
                if (end != null && end > durationInSeconds) durationInSeconds = end
                if (!hasStarted) {
                    obj["ts"]?.jsonPrimitive?.floatOrNull?.let {
                        segmentStart = it
                        hasStarted = true
                    }
                }
                if (end != null && hasStarted) {
                    currentText.trim().takeIf { it.isNotBlank() }?.let { text ->
                        segments += TranscriptSegment(text = text, startSeconds = segmentStart, endSeconds = end)
                    }
                    currentText = ""
                    hasStarted = false
                }
            }
        }
        currentText.trim().takeIf { hasStarted && it.isNotBlank() }?.let { text ->
            val end = if (durationInSeconds > segmentStart) durationInSeconds else segmentStart + 1f
            segments += TranscriptSegment(text = text, startSeconds = segmentStart, endSeconds = end)
        }
    }
    return RevaiTranscriptMapping(text = text, segments = segments, durationInSeconds = durationInSeconds)
}

private fun revaiHeaders(settings: RevaiProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val base = linkedMapOf<String, String>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
    base.putAll(settings.headers)
    base.putAll(callHeaders)
    return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/revai/$REVAI_VERSION")
}

private fun revaiOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["revai"] as? JsonObject ?: JsonObject(emptyMap())

private fun revaiErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject
    val detail = obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
        ?: raw.ifBlank { "request failed" }
    return "Rev.ai request failed ($statusCode): $detail"
}
