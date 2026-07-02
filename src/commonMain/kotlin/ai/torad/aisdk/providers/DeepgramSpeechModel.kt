package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** @since 0.3.0-beta01 */
public class DeepgramSpeechModel(
    private val client: HttpClient,
    private val settings: DeepgramProviderSettings,
    override val modelId: String,
) : SpeechModel {
    override val provider: String = "deepgram.speech"

    override suspend fun generate(params: SpeechGenerationParams): SpeechModelResult {
        params.abortSignal.throwIfAborted()
        val prepared = deepgramSpeechArgs(params)
        val response = deepgramPostJsonBinary(
            url = "$DEEPGRAM_BASE_URL/v1/speak?${settings.toQueryString(prepared.queryParams)}",
            body = prepared.body,
            headers = settings.deepgramHeaders(params.headers),
        )
        return SpeechModelResult(
            audio = GeneratedFile(
                mediaType = headerValue(response.headers, HttpHeaders.ContentType) ?: deepgramSpeechMediaType(prepared.queryParams),
                base64 = Base64Codec.encode(response.bytes),
            ),
            warnings = prepared.warnings,
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = response.headers,
            ),
        )
    }

    private fun deepgramSpeechArgs(params: SpeechGenerationParams): DeepgramSpeechArgs {
        val warnings = mutableListOf<CallWarning>()
        val body = buildJsonObject { put("text", JsonPrimitive(params.text)) }
        val queryParams = linkedMapOf("model" to modelId)
        applyDeepgramOutputFormat(params.responseFormat ?: "mp3", queryParams)

        val options = settings.deepgramOptions(params.providerOptions)
        applyDeepgramSpeechOptions(options, queryParams, warnings)

        if (params.voice != null && params.voice != modelId) {
            warnings += CallWarning(
                type = "unsupported",
                message = "Deepgram TTS models embed the voice in the model ID. The voice parameter \"${params.voice}\" was ignored.",
            )
        }
        if (params.speed != null) {
            warnings += CallWarning(
                type = "unsupported",
                message = "Deepgram TTS REST API does not support speed adjustment. Speed parameter was ignored.",
            )
        }
        if (params.instructions != null) {
            warnings += CallWarning(
                type = "unsupported",
                message = "Deepgram TTS REST API does not support instructions. Instructions parameter was ignored.",
            )
        }
        return DeepgramSpeechArgs(body, queryParams, warnings)
    }

    private fun applyDeepgramOutputFormat(
        outputFormat: String,
        queryParams: LinkedHashMap<String, String>,
    ) {
        val formatLower = outputFormat.lowercase()
        when (formatLower) {
            "mp3" -> queryParams["encoding"] = "mp3"
            "wav" -> {
                queryParams["container"] = "wav"
                queryParams["encoding"] = "linear16"
            }
            "linear16" -> {
                queryParams["encoding"] = "linear16"
                queryParams["container"] = "wav"
            }
            "mulaw" -> {
                queryParams["encoding"] = "mulaw"
                queryParams["container"] = "wav"
            }
            "alaw" -> {
                queryParams["encoding"] = "alaw"
                queryParams["container"] = "wav"
            }
            "opus", "ogg" -> {
                queryParams["encoding"] = "opus"
                queryParams["container"] = "ogg"
            }
            "flac" -> queryParams["encoding"] = "flac"
            "aac" -> queryParams["encoding"] = "aac"
            "pcm" -> {
                queryParams["encoding"] = "linear16"
                queryParams["container"] = "none"
            }
            else -> applyDeepgramCompoundOutputFormat(formatLower, queryParams)
        }
    }

    private fun applyDeepgramCompoundOutputFormat(
        outputFormat: String,
        queryParams: LinkedHashMap<String, String>,
    ) {
        val parts = outputFormat.split("_")
        if (parts.size < 2) return
        val first = parts[0]
        val sampleRate = parts[1].toIntOrNull()
        if (first in setOf("linear16", "mulaw", "alaw", "mp3", "opus", "flac", "aac")) {
            queryParams["encoding"] = first
            if (first in setOf("linear16", "mulaw", "alaw")) queryParams["container"] = "wav"
            if (first == "opus") queryParams["container"] = "ogg"
            if (sampleRate != null && deepgramSampleRateAllowed(first, sampleRate)) {
                queryParams["sample_rate"] = sampleRate.toString()
            }
        } else if (first in setOf("wav", "ogg")) {
            queryParams["container"] = first
            queryParams["encoding"] = if (first == "wav") "linear16" else "opus"
            if (sampleRate != null) queryParams["sample_rate"] = sampleRate.toString()
        }
    }

    private fun applyDeepgramSpeechOptions(
        options: JsonObject,
        queryParams: LinkedHashMap<String, String>,
        warnings: MutableList<CallWarning>,
    ) {
        val encoding = (options["encoding"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        val container = (options["container"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (encoding != null) {
            queryParams["encoding"] = encoding
            if (container != null) {
                when {
                    encoding in setOf("linear16", "mulaw", "alaw") && container in setOf("wav", "none") ->
                        queryParams["container"] = container
                    encoding in setOf("linear16", "mulaw", "alaw") ->
                        warnings += CallWarning("unsupported", "Encoding \"$encoding\" only supports containers \"wav\" or \"none\". Container \"$container\" was ignored.")
                    encoding == "opus" -> queryParams["container"] = "ogg"
                    encoding in setOf("mp3", "flac", "aac") -> {
                        warnings += CallWarning("unsupported", "Encoding \"$encoding\" does not support container parameter. Container \"$container\" was ignored.")
                        queryParams.remove("container")
                    }
                }
            } else {
                when {
                    encoding in setOf("mp3", "flac", "aac") -> queryParams.remove("container")
                    encoding in setOf("linear16", "mulaw", "alaw") && queryParams["container"] == null -> queryParams["container"] = "wav"
                    encoding == "opus" -> queryParams["container"] = "ogg"
                }
            }
            if (encoding in setOf("mp3", "opus", "aac")) queryParams.remove("sample_rate")
            if (encoding in setOf("linear16", "mulaw", "alaw", "flac")) queryParams.remove("bit_rate")
        } else if (container != null) {
            val oldEncoding = queryParams["encoding"]?.lowercase()
            val newEncoding = when (container) {
                "wav" -> "linear16"
                "ogg" -> "opus"
                "none" -> "linear16"
                else -> null
            }
            if (newEncoding != null) {
                queryParams["container"] = container
                if (newEncoding != oldEncoding) {
                    queryParams["encoding"] = newEncoding
                    if (newEncoding in setOf("mp3", "opus", "aac")) queryParams.remove("sample_rate")
                    if (newEncoding in setOf("linear16", "mulaw", "alaw", "flac")) queryParams.remove("bit_rate")
                }
            }
        }

        (options["sampleRate"] as? JsonPrimitive)?.intOrNull?.let { sampleRate ->
            applyDeepgramSampleRate(sampleRate, queryParams, warnings)
        }
        options["bitRate"]?.let { bitRate ->
            applyDeepgramBitRate(bitRate, queryParams, warnings)
        }
        (options["callback"] as? JsonPrimitive)?.contentOrNull?.let { queryParams["callback"] = it }
        (options["callbackMethod"] as? JsonPrimitive)?.contentOrNull?.let { queryParams["callback_method"] = it }
        (options["mipOptOut"] as? JsonPrimitive)?.booleanOrNull?.let { queryParams["mip_opt_out"] = it.toString() }
        options["tag"]?.let { queryParams["tag"] = settings.deepgramQueryValue(it) }
    }

    private fun applyDeepgramSampleRate(
        sampleRate: Int,
        queryParams: LinkedHashMap<String, String>,
        warnings: MutableList<CallWarning>,
    ) {
        val encoding = queryParams["encoding"]?.lowercase().orEmpty()
        when {
            encoding == "linear16" && sampleRate !in setOf(8000, 16000, 24000, 32000, 48000) ->
                warnings += CallWarning("unsupported", "Encoding \"linear16\" only supports sample rates: 8000, 16000, 24000, 32000, 48000. Sample rate $sampleRate was ignored.")
            encoding in setOf("mulaw", "alaw") && sampleRate !in setOf(8000, 16000) ->
                warnings += CallWarning("unsupported", "Encoding \"$encoding\" only supports sample rates: 8000, 16000. Sample rate $sampleRate was ignored.")
            encoding == "flac" && sampleRate !in setOf(8000, 16000, 22050, 32000, 48000) ->
                warnings += CallWarning("unsupported", "Encoding \"flac\" only supports sample rates: 8000, 16000, 22050, 32000, 48000. Sample rate $sampleRate was ignored.")
            encoding in setOf("mp3", "opus", "aac") ->
                warnings += CallWarning("unsupported", "Encoding \"$encoding\" has a fixed sample rate and does not support sample_rate parameter. Sample rate $sampleRate was ignored.")
            else -> queryParams["sample_rate"] = sampleRate.toString()
        }
    }

    private fun applyDeepgramBitRate(
        bitRate: JsonElement,
        queryParams: LinkedHashMap<String, String>,
        warnings: MutableList<CallWarning>,
    ) {
        val encoding = queryParams["encoding"]?.lowercase().orEmpty()
        val value = settings.deepgramQueryValue(bitRate)
        val number = value.toIntOrNull()
        when {
            encoding == "mp3" && number !in setOf(32000, 48000) ->
                warnings += CallWarning("unsupported", "Encoding \"mp3\" only supports bit rates: 32000, 48000. Bit rate $value was ignored.")
            encoding == "opus" && (number == null || number < 4000 || number > 650000) ->
                warnings += CallWarning("unsupported", "Encoding \"opus\" supports bit rates between 4000 and 650000. Bit rate $value was ignored.")
            encoding == "aac" && (number == null || number < 4000 || number > 192000) ->
                warnings += CallWarning("unsupported", "Encoding \"aac\" supports bit rates between 4000 and 192000. Bit rate $value was ignored.")
            encoding in setOf("linear16", "mulaw", "alaw", "flac") ->
                warnings += CallWarning("unsupported", "Encoding \"$encoding\" does not support bit_rate parameter. Bit rate $value was ignored.")
            else -> queryParams["bit_rate"] = value
        }
    }

    private fun deepgramSampleRateAllowed(encoding: String, sampleRate: Int): Boolean =
        when (encoding) {
            "linear16" -> sampleRate in setOf(8000, 16000, 24000, 32000, 48000)
            "mulaw", "alaw" -> sampleRate in setOf(8000, 16000)
            "flac" -> sampleRate in setOf(8000, 16000, 22050, 32000, 48000)
            else -> false
        }

    private fun deepgramSpeechMediaType(queryParams: Map<String, String>): String =
        when (queryParams["encoding"]) {
            "linear16", "mulaw", "alaw" -> if (queryParams["container"] == "wav") "audio/wav" else "application/octet-stream"
            "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            else -> "audio/mpeg"
        }

    private suspend fun deepgramPostJsonBinary(
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
    ): DeepgramBinaryResponse {
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(aiSdkOutputJson.encodeToString(JsonElement.serializer(), body))
        }
        return parseDeepgramBinary(response, url)
    }

    private suspend fun parseDeepgramBinary(response: HttpResponse, url: String): DeepgramBinaryResponse {
        val bytes = response.bodyAsBytes()
        val headers = with(HttpTransport) { response.flattenedHeaders() }
        if (response.status.value !in 200..299) {
            val raw = bytes.decodeToString()
            val parsed = TypedJsonOps.parseJsonElementOrNull(aiSdkJson, raw)
            throw ApiCallError(
                url = url,
                statusCode = response.status.value,
                rawBody = raw,
                headers = headers,
                message = settings.deepgramErrorMessage(response.status.value, parsed, raw),
            )
        }
        return DeepgramBinaryResponse(
            bytes = bytes,
            headers = headers,
        )
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
