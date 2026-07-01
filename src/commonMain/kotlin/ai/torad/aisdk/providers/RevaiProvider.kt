package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.ProviderMetadata
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

public const val REVAI_VERSION: String = "2.0.33"


@Serializable
@Poko
public class RevaiTranscriptionModelOptions internal constructor(
    public val metadata: String? = null,
    public val notification_config: JsonObject? = null,
    public val delete_after_seconds: Int? = null,
    public val verbatim: Boolean? = null,
    public val rush: Boolean? = null,
    public val test_mode: Boolean? = null,
    public val segments_to_transcribe: JsonElement? = null,
    public val speaker_names: JsonElement? = null,
    public val skip_diarization: Boolean? = null,
    public val skip_postprocessing: Boolean? = null,
    public val skip_punctuation: Boolean? = null,
    public val remove_disfluencies: Boolean? = null,
    public val remove_atmospherics: Boolean? = null,
    public val filter_profanity: Boolean? = null,
    public val speaker_channels_count: Int? = null,
    public val speakers_count: Int? = null,
    public val diarization_type: String? = null,
    public val custom_vocabulary_id: String? = null,
    public val custom_vocabularies: JsonElement? = null,
    public val strict_custom_vocabulary: Boolean? = null,
    public val summarization_config: JsonObject? = null,
    public val translation_config: JsonObject? = null,
    public val language: String? = null,
    public val forced_alignment: Boolean? = null,
)

public class RevaiTranscriptionModelOptionsBuilder {
    private var metadata: String? = null
    private var notification_config: JsonObject? = null
    private var delete_after_seconds: Int? = null
    private var verbatim: Boolean? = null
    private var rush: Boolean? = null
    private var test_mode: Boolean? = null
    private var segments_to_transcribe: JsonElement? = null
    private var speaker_names: JsonElement? = null
    private var skip_diarization: Boolean? = null
    private var skip_postprocessing: Boolean? = null
    private var skip_punctuation: Boolean? = null
    private var remove_disfluencies: Boolean? = null
    private var remove_atmospherics: Boolean? = null
    private var filter_profanity: Boolean? = null
    private var speaker_channels_count: Int? = null
    private var speakers_count: Int? = null
    private var diarization_type: String? = null
    private var custom_vocabulary_id: String? = null
    private var custom_vocabularies: JsonElement? = null
    private var strict_custom_vocabulary: Boolean? = null
    private var summarization_config: JsonObject? = null
    private var translation_config: JsonObject? = null
    private var language: String? = null
    private var forced_alignment: Boolean? = null

    public fun metadata(value: String?): RevaiTranscriptionModelOptionsBuilder {
        metadata = value
        return this
    }

    public fun notification_config(value: JsonObject?): RevaiTranscriptionModelOptionsBuilder {
        notification_config = value
        return this
    }

    public fun delete_after_seconds(value: Int?): RevaiTranscriptionModelOptionsBuilder {
        delete_after_seconds = value
        return this
    }

    public fun verbatim(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        verbatim = value
        return this
    }

    public fun rush(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        rush = value
        return this
    }

    public fun test_mode(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        test_mode = value
        return this
    }

    public fun segments_to_transcribe(value: JsonElement?): RevaiTranscriptionModelOptionsBuilder {
        segments_to_transcribe = value
        return this
    }

    public fun speaker_names(value: JsonElement?): RevaiTranscriptionModelOptionsBuilder {
        speaker_names = value
        return this
    }

    public fun skip_diarization(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        skip_diarization = value
        return this
    }

    public fun skip_postprocessing(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        skip_postprocessing = value
        return this
    }

    public fun skip_punctuation(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        skip_punctuation = value
        return this
    }

    public fun remove_disfluencies(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        remove_disfluencies = value
        return this
    }

    public fun remove_atmospherics(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        remove_atmospherics = value
        return this
    }

    public fun filter_profanity(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        filter_profanity = value
        return this
    }

    public fun speaker_channels_count(value: Int?): RevaiTranscriptionModelOptionsBuilder {
        speaker_channels_count = value
        return this
    }

    public fun speakers_count(value: Int?): RevaiTranscriptionModelOptionsBuilder {
        speakers_count = value
        return this
    }

    public fun diarization_type(value: String?): RevaiTranscriptionModelOptionsBuilder {
        diarization_type = value
        return this
    }

    public fun custom_vocabulary_id(value: String?): RevaiTranscriptionModelOptionsBuilder {
        custom_vocabulary_id = value
        return this
    }

    public fun custom_vocabularies(value: JsonElement?): RevaiTranscriptionModelOptionsBuilder {
        custom_vocabularies = value
        return this
    }

    public fun strict_custom_vocabulary(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        strict_custom_vocabulary = value
        return this
    }

    public fun summarization_config(value: JsonObject?): RevaiTranscriptionModelOptionsBuilder {
        summarization_config = value
        return this
    }

    public fun translation_config(value: JsonObject?): RevaiTranscriptionModelOptionsBuilder {
        translation_config = value
        return this
    }

    public fun language(value: String?): RevaiTranscriptionModelOptionsBuilder {
        language = value
        return this
    }

    public fun forced_alignment(value: Boolean?): RevaiTranscriptionModelOptionsBuilder {
        forced_alignment = value
        return this
    }

    public fun build(): RevaiTranscriptionModelOptions =
        RevaiTranscriptionModelOptions(
            metadata = metadata,
            notification_config = notification_config,
            delete_after_seconds = delete_after_seconds,
            verbatim = verbatim,
            rush = rush,
            test_mode = test_mode,
            segments_to_transcribe = segments_to_transcribe,
            speaker_names = speaker_names,
            skip_diarization = skip_diarization,
            skip_postprocessing = skip_postprocessing,
            skip_punctuation = skip_punctuation,
            remove_disfluencies = remove_disfluencies,
            remove_atmospherics = remove_atmospherics,
            filter_profanity = filter_profanity,
            speaker_channels_count = speaker_channels_count,
            speakers_count = speakers_count,
            diarization_type = diarization_type,
            custom_vocabulary_id = custom_vocabulary_id,
            custom_vocabularies = custom_vocabularies,
            strict_custom_vocabulary = strict_custom_vocabulary,
            summarization_config = summarization_config,
            translation_config = translation_config,
            language = language,
            forced_alignment = forced_alignment,
        )
}

public fun RevaiTranscriptionModelOptions(
    block: RevaiTranscriptionModelOptionsBuilder.() -> Unit = {},
): RevaiTranscriptionModelOptions =
    RevaiTranscriptionModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
public class RevaiProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
    public val pollingIntervalMillis: Long = 1_000L,
    public val maxPollAttempts: Int = 60,
) {
    internal fun revaiHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/revai/$REVAI_VERSION")
    }
}

public class RevaiProviderSettingsBuilder {
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var pollingIntervalMillis: Long = 1_000L
    private var maxPollAttempts: Int = 60

    public fun apiKey(value: String?): RevaiProviderSettingsBuilder {
        apiKey = value
        return this
    }

    public fun headers(value: Map<String, String>): RevaiProviderSettingsBuilder {
        headers = value
        return this
    }

    public fun pollingIntervalMillis(value: Long): RevaiProviderSettingsBuilder {
        pollingIntervalMillis = value
        return this
    }

    public fun maxPollAttempts(value: Int): RevaiProviderSettingsBuilder {
        maxPollAttempts = value
        return this
    }

    public fun build(): RevaiProviderSettings =
        RevaiProviderSettings(
            apiKey = apiKey,
            headers = headers,
            pollingIntervalMillis = pollingIntervalMillis,
            maxPollAttempts = maxPollAttempts,
        )
}

public fun RevaiProviderSettings(
    block: RevaiProviderSettingsBuilder.() -> Unit = {},
): RevaiProviderSettings =
    RevaiProviderSettingsBuilder().apply(block).build()

public class RevaiProvider(
    private val client: HttpClient,
    public val settings: RevaiProviderSettings,
) : Provider {
    override val providerId: String = "revai"

    public operator fun invoke(modelId: ModelId = ModelId("machine")): TranscriptionModel = transcription(modelId)

    public fun transcription(modelId: ModelId): TranscriptionModel =
        RevaiTranscriptionModel(client, settings, modelId.value)

    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(ModelId(modelId))
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
            url = "$REVAI_BASE_URL/speechtotext/v1/jobs",
            params = params,
            headers = settings.revaiHeaders(params.headers),
        )
        var job = submit.value.jsonObject
        if ((job["status"] as? JsonPrimitive)?.contentOrNull == "failed") {
            throw NoTranscriptGeneratedError("Failed to submit transcription job to Rev.ai")
        }
        val jobId = (job["id"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(submit.value, "Rev.ai transcription job response is missing id")

        repeat(settings.maxPollAttempts.coerceAtLeast(1)) { attempt ->
            params.abortSignal.throwIfAborted()
            val status = (job["status"] as? JsonPrimitive)?.contentOrNull
            if (status == "transcribed") return@repeat
            if (attempt > 0 || status != "transcribed") {
                val poll = revaiGetJson(
                    url = "$REVAI_BASE_URL/speechtotext/v1/jobs/$jobId",
                    headers = settings.revaiHeaders(params.headers),
                )
                job = poll.value.jsonObject
                when ((job["status"] as? JsonPrimitive)?.contentOrNull) {
                    "transcribed" -> return@repeat
                    "failed" -> throw NoTranscriptGeneratedError("Rev.ai transcription job failed")
                }
            }
            if ((job["status"] as? JsonPrimitive)?.contentOrNull != "transcribed" && settings.pollingIntervalMillis > 0 && attempt < settings.maxPollAttempts - 1) {
                delay(settings.pollingIntervalMillis)
            }
        }
        if ((job["status"] as? JsonPrimitive)?.contentOrNull != "transcribed") {
            throw NoTranscriptGeneratedError("Rev.ai transcription job polling timed out")
        }

        val transcript = revaiGetJson(
            url = "$REVAI_BASE_URL/speechtotext/v1/jobs/$jobId/transcript",
            headers = settings.revaiHeaders(params.headers),
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
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("revai" to transcript.value))),
            language = (job["language"] as? JsonPrimitive)?.contentOrNull,
            durationInSeconds = mapped.durationInSeconds,
        )
    }

    private suspend fun revaiPostMultipart(
        url: String,
        params: TranscriptionParams,
        headers: Map<String, String>,
    ): HttpJsonResponse {
        val filename = params.audio.filename ?: "audio.${MediaTypes.toExtension(params.audio.mediaType)}"
        val config = revaiConfigBody(params)
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
                        append("config", aiSdkOutputJson.encodeToString(JsonElement.serializer(), config))
                    },
                ),
            )
        }
        return with(HttpTransport) { response.toJsonResponse(url = url, errorMessage = ::revaiErrorMessage) }
    }

    private suspend fun revaiGetJson(
        url: String,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Get,
            headers = headers,
            errorMessage = ::revaiErrorMessage,
        )

    private fun revaiConfigBody(params: TranscriptionParams): JsonObject {
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

    // Concatenate one monologue's element values, skipping non-object elements (Wave 7b). Extracted
    // so mapRevaiTranscript stays under the cyclomatic-complexity threshold after the skip guards.
    private fun revaiMonologueText(monologue: JsonElement): String =
        ((monologue as? JsonObject)?.get("elements") as? JsonArray).orEmpty()
            .joinToString("") { element ->
                ((element as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty()
            }

    // Build one monologue's segments and the running max end-timestamp, skipping non-object elements
    // (Wave 7b). Extracted so mapRevaiTranscript stays under the cyclomatic-complexity threshold.
    private fun revaiMonologueSegments(
        monologue: JsonElement,
        baseDuration: Float,
    ): Pair<List<TranscriptSegment>, Float> {
        val segments = mutableListOf<TranscriptSegment>()
        var durationInSeconds = baseDuration
        var currentText = ""
        var segmentStart = 0f
        var hasStarted = false
        val elements = ((monologue as? JsonObject)?.get("elements") as? JsonArray).orEmpty()
        for (obj in elements.filterIsInstance<JsonObject>()) {
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") continue
            // Accumulate ONLY text elements — a "punct" element (comma/period/space) between two
            // words must not prepend into the next word's segment text (e.g. ",World").
            currentText += (obj["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val end = (obj["end_ts"] as? JsonPrimitive)?.floatOrNull
            if (end != null && end > durationInSeconds) durationInSeconds = end
            if (!hasStarted) {
                (obj["ts"] as? JsonPrimitive)?.floatOrNull?.let {
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
        currentText.trim().takeIf { hasStarted && it.isNotBlank() }?.let { text ->
            val end = if (durationInSeconds > segmentStart) durationInSeconds else segmentStart + 1f
            segments += TranscriptSegment(text = text, startSeconds = segmentStart, endSeconds = end)
        }
        return segments to durationInSeconds
    }

    private fun mapRevaiTranscript(value: JsonElement): RevaiTranscriptMapping {
        val monologues = ((value as? JsonObject)?.get("monologues") as? JsonArray).orEmpty()
        val text = monologues.joinToString(" ") { revaiMonologueText(it) }
        val segments = mutableListOf<TranscriptSegment>()
        var durationInSeconds = 0f
        for (monologue in monologues) {
            val (monologueSegments, newDuration) = revaiMonologueSegments(monologue, durationInSeconds)
            segments += monologueSegments
            durationInSeconds = newDuration
        }
        return RevaiTranscriptMapping(text = text, segments = segments, durationInSeconds = durationInSeconds)
    }

    private fun revaiOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "revai") ?: JsonObject(emptyMap())

    private fun revaiErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Rev.ai request failed ($statusCode): $detail"
    }
}

private const val REVAI_BASE_URL: String = "https://api.rev.ai"


internal data class RevaiTranscriptMapping(
    val text: String,
    val segments: List<TranscriptSegment>,
    val durationInSeconds: Float,
)

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
