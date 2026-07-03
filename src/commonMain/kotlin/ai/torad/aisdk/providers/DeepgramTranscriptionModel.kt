package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject

internal class DeepgramTranscriptionModel(
    private val client: HttpClient,
    private val settings: DeepgramProviderSettings,
    override val modelId: String,
) : TranscriptionModel {
    override val provider: String = "deepgram.transcription"

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        params.abortSignal.throwIfAborted()
        val prepared = deepgramTranscriptionQuery(params)
        val response = deepgramPostBinaryJson(
            url = "$DEEPGRAM_BASE_URL/v1/listen?${settings.toQueryString(prepared.queryParams)}",
            bytes = Base64Codec.decode(params.audio.base64),
            mediaType = params.audio.mediaType,
            headers = settings.deepgramHeaders(params.headers),
        )
        val value = response.value.jsonObject
        val results = JsonAccess.obj(value, "results")
        val firstChannel = (results?.get("channels") as? JsonArray)?.firstOrNull() as? JsonObject
        val firstAlternative = (firstChannel?.get("alternatives") as? JsonArray)?.firstOrNull() as? JsonObject
        val words = (firstAlternative?.get("words") as? JsonArray).orEmpty()
        return TranscriptionModelResult(
            text = (firstAlternative?.get("transcript") as? JsonPrimitive)?.contentOrNull.orEmpty(),
            segments = words.mapNotNull { word ->
                val obj = word as? JsonObject ?: return@mapNotNull null
                TranscriptSegment(
                    text = (obj["word"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    startSeconds = (obj["start"] as? JsonPrimitive)?.floatOrNull,
                    endSeconds = (obj["end"] as? JsonPrimitive)?.floatOrNull,
                )
            },
            warnings = prepared.warnings,
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = response.headers,
                body = response.value,
            ),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("deepgram" to response.value))),
            language = (firstChannel?.get("detected_language") as? JsonPrimitive)?.contentOrNull,
            durationInSeconds = (JsonAccess.obj(value, "metadata")?.get("duration") as? JsonPrimitive)?.floatOrNull,
        )
    }

    private fun deepgramTranscriptionQuery(params: TranscriptionParams): DeepgramTranscriptionArgs {
        val queryParams = linkedMapOf(
            "model" to modelId,
            "diarize" to "true",
        )
        val options = settings.deepgramOptions(params.providerOptions)
        putDeepgramTranscriptionOption(options, "detectEntities", "detect_entities", queryParams)
        putDeepgramTranscriptionOption(options, "detectLanguage", "detect_language", queryParams)
        putDeepgramTranscriptionOption(options, "fillerWords", "filler_words", queryParams)
        putDeepgramTranscriptionOption(options, "language", "language", queryParams)
        if (!options.containsKey("language")) params.language?.let { queryParams["language"] = it }
        putDeepgramTranscriptionOption(options, "punctuate", "punctuate", queryParams)
        putDeepgramTranscriptionOption(options, "redact", "redact", queryParams)
        putDeepgramTranscriptionOption(options, "search", "search", queryParams)
        putDeepgramTranscriptionOption(options, "smartFormat", "smart_format", queryParams)
        putDeepgramTranscriptionOption(options, "summarize", "summarize", queryParams)
        putDeepgramTranscriptionOption(options, "topics", "topics", queryParams)
        putDeepgramTranscriptionOption(options, "utterances", "utterances", queryParams)
        putDeepgramTranscriptionOption(options, "uttSplit", "utt_split", queryParams)
        putDeepgramTranscriptionOption(options, "diarize", "diarize", queryParams)
        putDeepgramTranscriptionOption(options, "paragraphs", "paragraphs", queryParams)
        putDeepgramTranscriptionOption(options, "intents", "intents", queryParams)
        putDeepgramTranscriptionOption(options, "sentiment", "sentiment", queryParams)
        putDeepgramTranscriptionOption(options, "replace", "replace", queryParams)
        putDeepgramTranscriptionOption(options, "keyterm", "keyterm", queryParams)
        return DeepgramTranscriptionArgs(queryParams = queryParams, warnings = emptyList())
    }

    private fun putDeepgramTranscriptionOption(
        options: JsonObject,
        source: String,
        target: String,
        queryParams: LinkedHashMap<String, String>,
    ) {
        options[source]?.let { queryParams[target] = settings.deepgramQueryValue(it) }
    }

    private suspend fun deepgramPostBinaryJson(
        url: String,
        bytes: ByteArray,
        mediaType: String,
        headers: Map<String, String>,
    ): HttpJsonResponse {
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.parse(mediaType))
            headers.forEach { (name, value) -> header(name, value) }
            setBody(bytes)
        }
        return with(HttpTransport) { response.toJsonResponse(url = url, errorMessage = settings::deepgramErrorMessage) }
    }
}
