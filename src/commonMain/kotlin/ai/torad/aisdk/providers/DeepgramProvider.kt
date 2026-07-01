package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

public const val DEEPGRAM_VERSION: String = "2.0.33"

public typealias DeepgramSpeechCallOptions = DeepgramSpeechModelOptions

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class DeepgramSpeechModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val bitRate: JsonElement? = null,
    /** @since 0.3.0-beta01 */
    public val container: String? = null,
    /** @since 0.3.0-beta01 */
    public val encoding: String? = null,
    /** @since 0.3.0-beta01 */
    public val sampleRate: Int? = null,
    /** @since 0.3.0-beta01 */
    public val callback: String? = null,
    /** @since 0.3.0-beta01 */
    public val callbackMethod: String? = null,
    /** @since 0.3.0-beta01 */
    public val mipOptOut: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val tag: JsonElement? = null,
)

/** @since 0.3.0-beta01 */
public class DeepgramSpeechModelOptionsBuilder {
    private var bitRate: JsonElement? = null
    private var container: String? = null
    private var encoding: String? = null
    private var sampleRate: Int? = null
    private var callback: String? = null
    private var callbackMethod: String? = null
    private var mipOptOut: Boolean? = null
    private var tag: JsonElement? = null

    /** @since 0.3.0-beta01 */
    public fun bitRate(value: JsonElement?): DeepgramSpeechModelOptionsBuilder {
        bitRate = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun container(value: String?): DeepgramSpeechModelOptionsBuilder {
        container = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun encoding(value: String?): DeepgramSpeechModelOptionsBuilder {
        encoding = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun sampleRate(value: Int?): DeepgramSpeechModelOptionsBuilder {
        sampleRate = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun callback(value: String?): DeepgramSpeechModelOptionsBuilder {
        callback = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun callbackMethod(value: String?): DeepgramSpeechModelOptionsBuilder {
        callbackMethod = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun mipOptOut(value: Boolean?): DeepgramSpeechModelOptionsBuilder {
        mipOptOut = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun tag(value: JsonElement?): DeepgramSpeechModelOptionsBuilder {
        tag = value
        return this
    }

    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public fun DeepgramSpeechModelOptions(
    block: DeepgramSpeechModelOptionsBuilder.() -> Unit = {},
): DeepgramSpeechModelOptions =
    DeepgramSpeechModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class DeepgramTranscriptionModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val language: String? = null,
    /** @since 0.3.0-beta01 */
    public val detectLanguage: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val smartFormat: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val punctuate: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val paragraphs: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val summarize: JsonElement? = null,
    /** @since 0.3.0-beta01 */
    public val topics: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val intents: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val sentiment: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val detectEntities: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val redact: JsonElement? = null,
    /** @since 0.3.0-beta01 */
    public val replace: String? = null,
    /** @since 0.3.0-beta01 */
    public val search: String? = null,
    /** @since 0.3.0-beta01 */
    public val keyterm: String? = null,
    /** @since 0.3.0-beta01 */
    public val diarize: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val utterances: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val uttSplit: Float? = null,
    /** @since 0.3.0-beta01 */
    public val fillerWords: Boolean? = null,
)

/** @since 0.3.0-beta01 */
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

    /** @since 0.3.0-beta01 */
    public fun language(value: String?): DeepgramTranscriptionModelOptionsBuilder {
        language = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun detectLanguage(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        detectLanguage = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun smartFormat(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        smartFormat = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun punctuate(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        punctuate = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun paragraphs(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        paragraphs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun summarize(value: JsonElement?): DeepgramTranscriptionModelOptionsBuilder {
        summarize = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topics(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        topics = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun intents(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        intents = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun sentiment(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        sentiment = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun detectEntities(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        detectEntities = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun redact(value: JsonElement?): DeepgramTranscriptionModelOptionsBuilder {
        redact = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun replace(value: String?): DeepgramTranscriptionModelOptionsBuilder {
        replace = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun search(value: String?): DeepgramTranscriptionModelOptionsBuilder {
        search = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun keyterm(value: String?): DeepgramTranscriptionModelOptionsBuilder {
        keyterm = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun diarize(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        diarize = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun utterances(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        utterances = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun uttSplit(value: Float?): DeepgramTranscriptionModelOptionsBuilder {
        uttSplit = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun fillerWords(value: Boolean?): DeepgramTranscriptionModelOptionsBuilder {
        fillerWords = value
        return this
    }

    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public fun DeepgramTranscriptionModelOptions(
    block: DeepgramTranscriptionModelOptionsBuilder.() -> Unit = {},
): DeepgramTranscriptionModelOptions =
    DeepgramTranscriptionModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class DeepgramProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public class DeepgramProviderSettingsBuilder {
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): DeepgramProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): DeepgramProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): DeepgramProviderSettings =
        DeepgramProviderSettings(
            apiKey = apiKey,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun DeepgramProviderSettings(
    block: DeepgramProviderSettingsBuilder.() -> Unit = {},
): DeepgramProviderSettings =
    DeepgramProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class DeepgramProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: DeepgramProviderSettings,
) : Provider {
    override val providerId: String = "deepgram"

    public operator fun invoke(modelId: ModelId = ModelId("nova-3")): TranscriptionModel = transcription(modelId)

    /** @since 0.3.0-beta01 */
    public fun transcription(modelId: ModelId): TranscriptionModel =
        DeepgramTranscriptionModel(client, settings, modelId.value)

    /** @since 0.3.0-beta01 */
    public fun speech(modelId: ModelId): SpeechModel =
        DeepgramSpeechModel(client, settings, modelId.value)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(ModelId(modelId))
    override fun speechModel(modelId: String): SpeechModel = speech(ModelId(modelId))
}

/**
 * PascalCase factory — mirrors the OpenAI(...) reference pattern.
 * @since 0.3.0-beta01
 */
public fun Deepgram(
    client: HttpClient,
    settings: DeepgramProviderSettings = DeepgramProviderSettings(),
): DeepgramProvider = DeepgramProvider(client, settings)

internal const val DEEPGRAM_BASE_URL: String = "https://api.deepgram.com"

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
