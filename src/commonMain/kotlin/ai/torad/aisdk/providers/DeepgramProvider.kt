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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val DEEPGRAM_VERSION: String = "2.0.33"

public typealias DeepgramSpeechCallOptions = DeepgramSpeechModelOptions

@Serializable
public data class DeepgramSpeechModelOptions(
    val bitRate: JsonElement? = null,
    val container: String? = null,
    val encoding: String? = null,
    val sampleRate: Int? = null,
    val callback: String? = null,
    val callbackMethod: String? = null,
    val mipOptOut: Boolean? = null,
    val tag: JsonElement? = null,
)

@Serializable
public data class DeepgramTranscriptionModelOptions(
    val language: String? = null,
    val detectLanguage: Boolean? = null,
    val smartFormat: Boolean? = null,
    val punctuate: Boolean? = null,
    val paragraphs: Boolean? = null,
    val summarize: JsonElement? = null,
    val topics: Boolean? = null,
    val intents: Boolean? = null,
    val sentiment: Boolean? = null,
    val detectEntities: Boolean? = null,
    val redact: JsonElement? = null,
    val replace: String? = null,
    val search: String? = null,
    val keyterm: String? = null,
    val diarize: Boolean? = null,
    val utterances: Boolean? = null,
    val uttSplit: Float? = null,
    val fillerWords: Boolean? = null,
)

@Serializable
public data class DeepgramProviderSettings(
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    internal fun deepgramHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Token $it" }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/deepgram/$DEEPGRAM_VERSION")
    }

    internal fun deepgramOptions(providerOptions: ProviderOptions): JsonObject =
        providerOptions.toMap()["deepgram"] as? JsonObject ?: JsonObject(emptyMap())

    internal fun deepgramErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Deepgram request failed ($statusCode): $detail"
    }

    internal fun deepgramQueryValue(value: JsonElement): String =
        when (value) {
            is JsonArray -> value.joinToString(",") { deepgramQueryValue(it) }
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            else -> value.toString()
        }

    internal fun toQueryString(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) -> "${UrlOps.encode(key)}=${UrlOps.encode(value)}" }
}

public class DeepgramProvider(
    private val client: HttpClient,
    public val settings: DeepgramProviderSettings,
) : Provider {
    override val providerId: String = "deepgram"

    public operator fun invoke(modelId: ModelId = ModelId("nova-3")): TranscriptionModel = transcription(modelId)

    public fun transcription(modelId: ModelId): TranscriptionModel =
        DeepgramTranscriptionModel(client, settings, modelId.value)

    public fun speech(modelId: ModelId): SpeechModel =
        DeepgramSpeechModel(client, settings, modelId.value)

    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(ModelId(modelId))
    override fun speechModel(modelId: String): SpeechModel = speech(ModelId(modelId))
}

/** PascalCase factory — mirrors the OpenAI(...) reference pattern. */
public fun Deepgram(
    client: HttpClient,
    settings: DeepgramProviderSettings = DeepgramProviderSettings(),
): DeepgramProvider = DeepgramProvider(client, settings)

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
        val encoding = options["encoding"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val container = options["container"]?.jsonPrimitive?.contentOrNull?.lowercase()
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

        options["sampleRate"]?.jsonPrimitive?.intOrNull?.let { sampleRate ->
            applyDeepgramSampleRate(sampleRate, queryParams, warnings)
        }
        options["bitRate"]?.let { bitRate ->
            applyDeepgramBitRate(bitRate, queryParams, warnings)
        }
        options["callback"]?.jsonPrimitive?.contentOrNull?.let { queryParams["callback"] = it }
        options["callbackMethod"]?.jsonPrimitive?.contentOrNull?.let { queryParams["callback_method"] = it }
        options["mipOptOut"]?.jsonPrimitive?.booleanOrNull?.let { queryParams["mip_opt_out"] = it.toString() }
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
            setBody(aiSdkJson.encodeToString(JsonElement.serializer(), body))
        }
        return parseDeepgramBinary(response, url)
    }

    private suspend fun parseDeepgramBinary(response: HttpResponse, url: String): DeepgramBinaryResponse {
        val bytes = response.bodyAsBytes()
        val headers = with(HttpTransport) { response.flattenedHeaders() }
        if (response.status.value !in 200..299) {
            val raw = bytes.decodeToString()
            val parsed = runCatching { aiSdkJson.parseToJsonElement(raw) }.getOrNull()
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

private class DeepgramTranscriptionModel(
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
        val firstChannel = value["results"]?.jsonObject
            ?.get("channels")?.jsonArray?.firstOrNull()?.jsonObject
        val firstAlternative = firstChannel
            ?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject
        val words = firstAlternative?.get("words")?.jsonArray.orEmpty()
        return TranscriptionModelResult(
            text = firstAlternative?.get("transcript")?.jsonPrimitive?.contentOrNull.orEmpty(),
            segments = words.map { word ->
                val obj = word.jsonObject
                TranscriptSegment(
                    text = obj["word"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    startSeconds = obj["start"]?.jsonPrimitive?.floatOrNull,
                    endSeconds = obj["end"]?.jsonPrimitive?.floatOrNull,
                )
            },
            warnings = prepared.warnings,
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = response.headers,
                body = response.value,
            ),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("deepgram" to response.value))),
            language = firstChannel?.get("detected_language")?.jsonPrimitive?.contentOrNull,
            durationInSeconds = value["metadata"]?.jsonObject?.get("duration")?.jsonPrimitive?.floatOrNull,
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

private const val DEEPGRAM_BASE_URL: String = "https://api.deepgram.com"


internal data class DeepgramSpeechArgs(
    val body: JsonObject,
    val queryParams: LinkedHashMap<String, String>,
    val warnings: List<CallWarning>,
)

internal data class DeepgramTranscriptionArgs(
    val queryParams: LinkedHashMap<String, String>,
    val warnings: List<CallWarning>,
)

internal class DeepgramBinaryResponse(
    val bytes: ByteArray,
    val headers: Map<String, String>,
)
