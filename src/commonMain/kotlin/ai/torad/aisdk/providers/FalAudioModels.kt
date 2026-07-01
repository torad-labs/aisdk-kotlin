package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject

internal class FalSpeechModel(
    private val client: HttpClient,
    private val settings: FalProviderSettings,
    override val modelId: String,
) : SpeechModel {
    override val provider: String = "fal.speech"

    override suspend fun generate(params: SpeechGenerationParams): SpeechModelResult {
        params.abortSignal.throwIfAborted()
        val prepared = falSpeechRequestBody(params)
        val response = settings.falPostJson(
            client = client,
            // Honor settings.baseURL like FalImageModel — the old hardcoded fal.run bypassed any
            // custom endpoint (test proxy / self-hosted / staging gateway) for speech only.
            url = "${settings.baseURL.trimEnd('/')}/$modelId",
            body = prepared.body,
            headers = settings.falHeaders(params.headers),
        )
        val audioObj = JsonAccess.obj(response.value.jsonObject, "audio")
        val audioUrl = (audioObj?.get("url") as? JsonPrimitive)?.contentOrNull
            ?: throw NoSpeechGeneratedError("fal speech response is missing audio.url")
        val audio = settings.falGetBinary(client, audioUrl, emptyMap(), params.abortSignal)
        return SpeechModelResult(
            audio = GeneratedFile(
                mediaType = audio.headerValue(HttpHeaders.ContentType) ?: "audio/mpeg",
                base64 = Base64Codec.encode(audio.bytes),
                url = audioUrl,
            ),
            warnings = prepared.warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
        )
    }

    private fun falSpeechRequestBody(params: SpeechGenerationParams): FalSpeechRequest {
        val warnings = mutableListOf<CallWarning>()
        if (!params.instructions.isNullOrBlank()) {
            warnings += CallWarning("unsupported", "instructions")
        }
        val outputFormat = when (params.responseFormat) {
            null, "url" -> "url"
            "hex" -> "hex"
            else -> {
                warnings += CallWarning("unsupported", "outputFormat")
                "url"
            }
        }
        return FalSpeechRequest(
            body = buildJsonObject {
                put("text", JsonPrimitive(params.text))
                put("output_format", JsonPrimitive(outputFormat))
                params.voice?.let { put("voice", JsonPrimitive(it)) }
                params.speed?.let { put("speed", JsonPrimitive(it)) }
                settings.putJsonObjectFields(this, settings.falOptions(params.providerOptions))
            },
            warnings = warnings,
        )
    }
}

internal class FalTranscriptionModel(
    private val client: HttpClient,
    private val settings: FalProviderSettings,
    override val modelId: String,
) : TranscriptionModel {
    override val provider: String = "fal.transcription"

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        params.abortSignal.throwIfAborted()
        val body = falTranscriptionRequestBody(params)
        val queue = settings.falPostJson(
            client = client,
            url = "https://queue.fal.run/fal-ai/$modelId",
            body = body,
            headers = settings.falHeaders(params.headers),
        )
        val requestId = (queue.value.jsonObject["request_id"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(queue.value, "fal transcription queue response is missing request_id")
        val result = settings.falPollJson(
            client = client,
            url = "https://queue.fal.run/fal-ai/$modelId/requests/$requestId",
            headers = settings.falHeaders(params.headers),
            abortSignal = params.abortSignal,
            pollIntervalMillis = settings.transcriptionPollIntervalMillis,
            maxPollAttempts = settings.transcriptionMaxPollAttempts,
            timeoutMessage = "Transcription request timed out after ${settings.transcriptionPollIntervalMillis * settings.transcriptionMaxPollAttempts}ms",
        )
        val value = result.value.jsonObject
        val chunks = JsonAccess.arr(value, "chunks") ?: JsonArray(emptyList())
        return TranscriptionModelResult(
            text = (value["text"] as? JsonPrimitive)?.contentOrNull,
            segments = chunks.mapNotNull { chunk ->
                val obj = chunk as? JsonObject ?: return@mapNotNull null
                val timestamp = JsonAccess.arr(obj, "timestamp")
                TranscriptSegment(
                    text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    startSeconds = (timestamp?.getOrNull(0) as? JsonPrimitive)?.floatOrNull,
                    endSeconds = (timestamp?.getOrNull(1) as? JsonPrimitive)?.floatOrNull,
                )
            },
            response = LanguageModelResponseMetadata(modelId = modelId, headers = result.headers, body = result.value),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("fal" to result.value))),
        )
    }

    private fun falTranscriptionRequestBody(params: TranscriptionParams): JsonObject {
        val options = settings.falOptions(params.providerOptions)
        return buildJsonObject {
            put("task", JsonPrimitive("transcribe"))
            put("diarize", options["diarize"] ?: JsonPrimitive(true))
            put("chunk_level", options["chunkLevel"] ?: options["chunk_level"] ?: JsonPrimitive("word"))
            params.language?.let { put("language", JsonPrimitive(it)) }
            options["language"]?.let { put("language", it) }
            options["version"]?.let { put("version", it) }
            (options["batchSize"] ?: options["batch_size"])?.let { put("batch_size", it) }
            (options["numSpeakers"] ?: options["num_speakers"])?.let { put("num_speakers", it) }
            put("audio_url", JsonPrimitive("data:${params.audio.mediaType};base64,${params.audio.base64}"))
        }
    }
}
