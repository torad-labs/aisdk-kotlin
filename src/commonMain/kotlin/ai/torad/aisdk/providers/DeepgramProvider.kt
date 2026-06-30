package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
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
import kotlinx.serialization.json.jsonObject

public const val DEEPGRAM_VERSION: String = "2.0.33"

public typealias DeepgramSpeechCallOptions = DeepgramSpeechModelOptions

@Serializable
@Poko
public class DeepgramSpeechModelOptions internal constructor(
    public val bitRate: JsonElement? = null,
    public val container: String? = null,
    public val encoding: String? = null,
    public val sampleRate: Int? = null,
    public val callback: String? = null,
    public val callbackMethod: String? = null,
    public val mipOptOut: Boolean? = null,
    public val tag: JsonElement? = null,
)

public class DeepgramSpeechModelOptionsBuilder {
    private var bitRate: JsonElement? = null
    private var container: String? = null
    private var encoding: String? = null
    private var sampleRate: Int? = null
    private var callback: String? = null
    private var callbackMethod: String? = null
    private var mipOptOut: Boolean? = null
    private var tag: JsonElement? = null

    public fun bitRate(value: JsonElement?): DeepgramSpeechModelOptionsBuilder {
        bitRate = value
        return this
    }

    public fun container(value: String?): DeepgramSpeechModelOptionsBuilder {
        container = value
        return this
    }

    public fun encoding(value: String?): DeepgramSpeechModelOptionsBuilder {
        encoding = value
        return this
    }

    public fun sampleRate(value: Int?): DeepgramSpeechModelOptionsBuilder {
        sampleRate = value
        return this
    }

    public fun callback(value: String?): DeepgramSpeechModelOptionsBuilder {
        callback = value
        return this
    }

    public fun callbackMethod(value: String?): DeepgramSpeechModelOptionsBuilder {
        callbackMethod = value
        return this
    }

    public fun mipOptOut(value: Boolean?): DeepgramSpeechModelOptionsBuilder {
        mipOptOut = value
        return this
    }

    public fun tag(value: JsonElement?): DeepgramSpeechModelOptionsBuilder {
        tag = value
        return this
    }

    public fun build(): DeepgramSpeechModelOptions =
        DeepgramSpeechModelOptions(
            bitRate = bitRate,
            container = container,
            encoding = encoding,
            sampleRate = sampleRate,
            callback = callback,
            callbackMethod = callbackMethod,
            mipOptOut = mipOptOut,
            tag = tag,
        )
}

public fun DeepgramSpeechModelOptions(
    block: DeepgramSpeechModelOptionsBuilder.() -> Unit = {},
): DeepgramSpeechModelOptions =
    DeepgramSpeechModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
public class DeepgramTranscriptionModelOptions internal constructor(
    public val language: String? = null,
    public val detectLanguage: Boolean? = null,
    public val smartFormat: Boolean? = null,
    public val punctuate: Boolean? = null,
    public val paragraphs: Boolean? = null,
    public val summarize: JsonElement? = null,
    public val topics: Boolean? = null,
    public val intents: Boolean? = null,
    public val sentiment: Boolean? = null,
    public val detectEntities: Boolean? = null,
    public val redact: JsonElement? = null,
    public val replace: String? = null,
    public val search: String? = null,
    public val keyterm: String? = null,
    public val diarize: Boolean? = null,
    public val utterances: Boolean? = null,
    public val uttSplit: Float? = null,
    public val fillerWords: Boolean? = null,
)

public class DeepgramTranscriptionModelOptionsBuilder {
    private var language: String? = null
    private var detectLanguage: Boolean? = null
    private var smartFormat: Boolean? = null
    private var punctuate: Boolean? = null
    private var paragraphs: Boolean? = null
    private var summarize: JsonElement? = null
    private var topics: Boolean? = null
    private var intents: Boolean? = null
    private var sentiment: Boolean? = null
    private var detectEntities: Boolean? = null
    private var redact: JsonElement? = null
    private var replace: String? = null
    private var search: String? = null
    private var keyterm: String? = null
    private var diarize: Boolean? = null
    private var utterances: Boolean? = null
    private var uttSplit: Float? = null
    private var fillerWords: Boolean? = null

    public fun language(value: String?): DeepgramTranscriptionModelOptionsBuilder {
        language = value
        return this
    }

    public fun detectLanguage(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        detectLanguage = value
        return this
    }

    public fun smartFormat(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        smartFormat = value
        return this
    }

    public fun punctuate(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        punctuate = value
        return this
    }

    public fun paragraphs(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        paragraphs = value
        return this
    }

    public fun summarize(value: JsonElement?): DeepgramTranscriptionModelOptionsBuilder {
        summarize = value
        return this
    }

    public fun topics(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        topics = value
        return this
    }

    public fun intents(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        intents = value
        return this
    }

    public fun sentiment(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        sentiment = value
        return this
    }

    public fun detectEntities(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        detectEntities = value
        return this
    }

    public fun redact(value: JsonElement?): DeepgramTranscriptionModelOptionsBuilder {
        redact = value
        return this
    }

    public fun replace(value: String?): DeepgramTranscriptionModelOptionsBuilder {
        replace = value
        return this
    }

    public fun search(value: String?): DeepgramTranscriptionModelOptionsBuilder {
        search = value
        return this
    }

    public fun keyterm(value: String?): DeepgramTranscriptionModelOptionsBuilder {
        keyterm = value
        return this
    }

    public fun diarize(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        diarize = value
        return this
    }

    public fun utterances(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        utterances = value
        return this
    }

    public fun uttSplit(value: Float?): DeepgramTranscriptionModelOptionsBuilder {
        uttSplit = value
        return this
    }

    public fun fillerWords(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        fillerWords = value
        return this
    }

    public fun build(): DeepgramTranscriptionModelOptions =
        DeepgramTranscriptionModelOptions(
            language = language,
            detectLanguage = detectLanguage,
            smartFormat = smartFormat,
            punctuate = punctuate,
            paragraphs = paragraphs,
            summarize = summarize,
            topics = topics,
            intents = intents,
            sentiment = sentiment,
            detectEntities = detectEntities,
            redact = redact,
            replace = replace,
            search = search,
            keyterm = keyterm,
            diarize = diarize,
            utterances = utterances,
            uttSplit = uttSplit,
            fillerWords = fillerWords,
        )
}

public fun DeepgramTranscriptionModelOptions(
    block: DeepgramTranscriptionModelOptionsBuilder.() -> Unit = {},
): DeepgramTranscriptionModelOptions =
    DeepgramTranscriptionModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
public class DeepgramProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun deepgramHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Token $it" }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/deepgram/$DEEPGRAM_VERSION")
    }

    internal fun deepgramOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "deepgram") ?: JsonObject(emptyMap())

    internal fun deepgramErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
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

public class DeepgramProviderSettingsBuilder {
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()

    public fun apiKey(value: String?): DeepgramProviderSettingsBuilder {
        apiKey = value
        return this
    }

    public fun headers(value: Map<String, String>): DeepgramProviderSettingsBuilder {
        headers = value
        return this
    }

    public fun build(): DeepgramProviderSettings =
        DeepgramProviderSettings(
            apiKey = apiKey,
            headers = headers,
        )
}

public fun DeepgramProviderSettings(
    block: DeepgramProviderSettingsBuilder.() -> Unit = {},
): DeepgramProviderSettings =
    DeepgramProviderSettingsBuilder().apply(block).build()

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
