package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

public const val FAL_VERSION: String = "2.0.34"

public typealias FalImageProviderOptions = FalImageModelOptions
public typealias FalVideoProviderOptions = FalVideoModelOptions
public typealias FalErrorData = JsonObject

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class FalProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://fal.run",
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val transcriptionPollIntervalMillis: Long = 1_000L,
    /** @since 0.3.0-beta01 */
    public val transcriptionMaxPollAttempts: Int = 60,
    /** @since 0.3.0-beta01 */
    public val videoPollIntervalMillis: Long = 2_000L,
    /** @since 0.3.0-beta01 */
    public val videoMaxPollAttempts: Int = 150,
) {
    internal fun falHeaders(extra: Map<String, String>): Map<String, String> =
        ProviderHeaders.build(headers, extra, "ai-sdk/fal/$FAL_VERSION") { built ->
            apiKey?.takeIf { it.isNotBlank() }?.let { built[HttpHeaders.Authorization] = "Key $it" }
        }

    internal fun falOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "fal") ?: JsonObject(emptyMap())

    internal fun putJsonObjectFields(builder: JsonObjectBuilder, fields: JsonObject) {
        fields.forEach { (key, value) ->
            if (value !is JsonNull) builder.put(key, value)
        }
    }

    internal suspend fun falPostJson(
        client: HttpClient,
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
            errorMessage = falErrorMessage,
            errorFromResponse = falInProgressSignal,
        )

    internal suspend fun falGetJson(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
    ): HttpJsonResponse =
        AbortSignalRuntime.withAbortCancellation(abortSignal) {
            HttpTransport.requestJson(
                client = client,
                url = url,
                method = HttpMethod.Get,
                headers = headers,
                errorMessage = falErrorMessage,
                errorFromResponse = falInProgressSignal,
                abortSignal = abortSignal,
            )
        }

    internal suspend fun falPollJson(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
        pollIntervalMillis: Long,
        maxPollAttempts: Int,
        timeoutMessage: String,
    ): HttpJsonResponse {
        repeat(maxPollAttempts.coerceAtLeast(1)) { attempt ->
            abortSignal.throwIfAborted()
            val response = try {
                falGetJson(client, url, headers, abortSignal)
            } catch (error: InvalidResponseDataError) {
                if (error.message == "Request is still in progress") null else throw error
            }
            if (response != null) return response
            if (pollIntervalMillis > 0 && attempt < maxPollAttempts - 1) delay(pollIntervalMillis)
        }
        throw NoVideoGeneratedError(timeoutMessage)
    }

    internal suspend fun falGetBinary(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
    ): FalBinaryResponse {
        abortSignal.throwIfAborted()
        val (statusCode, flattened, bytes) = AbortSignalRuntime.withAbortCancellation(abortSignal) {
            HttpTransport.withRealTimeout(DEFAULT_REQUEST_TIMEOUT_MS) {
                val abortRegistrations = mutableListOf<AbortSignal.AbortRegistration>()
                try {
                    val response = client.request(url) {
                        abortSignal.throwIfAborted()
                        abortRegistrations += abortSignal.register { executionContext.cancel(AbortError()) }
                        method = HttpMethod.Get
                        headers.forEach { (name, value) -> header(name, value) }
                    }
                    Triple(
                        response.status.value,
                        with(HttpTransport) { response.flattenedHeaders() },
                        with(HttpTransport) { response.bodyAsBytesCapped(url) },
                    )
                } finally {
                    abortRegistrations.forEach { it.cancel() }
                }
            }
        }
        if (statusCode !in 200..299) {
            val raw = bytes.decodeToString()
            throw ApiCallError(
                url = url,
                statusCode = statusCode,
                rawBody = raw,
                headers = flattened,
                message = "fal binary request failed with status $statusCode: $raw",
            )
        }
        return FalBinaryResponse(
            bytes = bytes,
            headers = flattened,
        )
    }
}

/** @since 0.3.0-beta01 */
public class FalProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = "https://fal.run"
    private var headers: Map<String, String> = emptyMap()
    private var transcriptionPollIntervalMillis: Long = 1_000L
    private var transcriptionMaxPollAttempts: Int = 60
    private var videoPollIntervalMillis: Long = 2_000L
    private var videoMaxPollAttempts: Int = 150

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): FalProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): FalProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): FalProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun transcriptionPollIntervalMillis(value: Long): FalProviderSettingsBuilder {
        transcriptionPollIntervalMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun transcriptionMaxPollAttempts(value: Int): FalProviderSettingsBuilder {
        transcriptionMaxPollAttempts = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun videoPollIntervalMillis(value: Long): FalProviderSettingsBuilder {
        videoPollIntervalMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun videoMaxPollAttempts(value: Int): FalProviderSettingsBuilder {
        videoMaxPollAttempts = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): FalProviderSettings =
        FalProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
            transcriptionPollIntervalMillis = transcriptionPollIntervalMillis,
            transcriptionMaxPollAttempts = transcriptionMaxPollAttempts,
            videoPollIntervalMillis = videoPollIntervalMillis,
            videoMaxPollAttempts = videoMaxPollAttempts,
        )
}

/** @since 0.3.0-beta01 */
public fun FalProviderSettings(
    block: FalProviderSettingsBuilder.() -> Unit = {},
): FalProviderSettings =
    FalProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class FalImageModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val useMultipleImages: Boolean? = null,
)

/** @since 0.3.0-beta01 */
public class FalImageModelOptionsBuilder {
    private var useMultipleImages: Boolean? = null

    /** @since 0.3.0-beta01 */
    public fun useMultipleImages(value: Boolean?): FalImageModelOptionsBuilder {
        useMultipleImages = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): FalImageModelOptions =
        FalImageModelOptions(useMultipleImages = useMultipleImages)
}

/** @since 0.3.0-beta01 */
public fun FalImageModelOptions(
    block: FalImageModelOptionsBuilder.() -> Unit = {},
): FalImageModelOptions =
    FalImageModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class FalSpeechModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val voice_setting: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val audio_setting: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val language_boost: String? = null,
    /** @since 0.3.0-beta01 */
    public val pronunciation_dict: JsonObject? = null,
)

/** @since 0.3.0-beta01 */
public class FalSpeechModelOptionsBuilder {
    private var voice_setting: JsonObject? = null
    private var audio_setting: JsonObject? = null
    private var language_boost: String? = null
    private var pronunciation_dict: JsonObject? = null

    /** @since 0.3.0-beta01 */
    public fun voice_setting(value: JsonObject?): FalSpeechModelOptionsBuilder {
        voice_setting = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun audio_setting(value: JsonObject?): FalSpeechModelOptionsBuilder {
        audio_setting = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun language_boost(value: String?): FalSpeechModelOptionsBuilder {
        language_boost = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pronunciation_dict(value: JsonObject?): FalSpeechModelOptionsBuilder {
        pronunciation_dict = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): FalSpeechModelOptions =
        FalSpeechModelOptions(
            voice_setting = voice_setting,
            audio_setting = audio_setting,
            language_boost = language_boost,
            pronunciation_dict = pronunciation_dict,
        )
}

/** @since 0.3.0-beta01 */
public fun FalSpeechModelOptions(
    block: FalSpeechModelOptionsBuilder.() -> Unit = {},
): FalSpeechModelOptions =
    FalSpeechModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class FalTranscriptionModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val language: String? = "en",
    /** @since 0.3.0-beta01 */
    public val diarize: Boolean? = true,
    /** @since 0.3.0-beta01 */
    public val chunkLevel: String? = "segment",
    /** @since 0.3.0-beta01 */
    public val version: String? = "3",
    /** @since 0.3.0-beta01 */
    public val batchSize: Int? = 64,
    /** @since 0.3.0-beta01 */
    public val numSpeakers: Int? = null,
)

/** @since 0.3.0-beta01 */
public class FalTranscriptionModelOptionsBuilder {
    private var language: String? = "en"
    private var diarize: Boolean? = true
    private var chunkLevel: String? = "segment"
    private var version: String? = "3"
    private var batchSize: Int? = 64
    private var numSpeakers: Int? = null

    /** @since 0.3.0-beta01 */
    public fun language(value: String?): FalTranscriptionModelOptionsBuilder {
        language = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun diarize(value: Boolean?): FalTranscriptionModelOptionsBuilder {
        diarize = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun chunkLevel(value: String?): FalTranscriptionModelOptionsBuilder {
        chunkLevel = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun version(value: String?): FalTranscriptionModelOptionsBuilder {
        version = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun batchSize(value: Int?): FalTranscriptionModelOptionsBuilder {
        batchSize = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun numSpeakers(value: Int?): FalTranscriptionModelOptionsBuilder {
        numSpeakers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): FalTranscriptionModelOptions =
        FalTranscriptionModelOptions(
            language = language,
            diarize = diarize,
            chunkLevel = chunkLevel,
            version = version,
            batchSize = batchSize,
            numSpeakers = numSpeakers,
        )
}

/** @since 0.3.0-beta01 */
public fun FalTranscriptionModelOptions(
    block: FalTranscriptionModelOptionsBuilder.() -> Unit = {},
): FalTranscriptionModelOptions =
    FalTranscriptionModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class FalVideoModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val loop: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val motionStrength: Float? = null,
    /** @since 0.3.0-beta01 */
    public val pollIntervalMs: Long? = null,
    /** @since 0.3.0-beta01 */
    public val pollTimeoutMs: Long? = null,
    /** @since 0.3.0-beta01 */
    public val resolution: String? = null,
    /** @since 0.3.0-beta01 */
    public val negativePrompt: String? = null,
    /** @since 0.3.0-beta01 */
    public val promptOptimizer: Boolean? = null,
)

/** @since 0.3.0-beta01 */
public class FalVideoModelOptionsBuilder {
    private var loop: Boolean? = null
    private var motionStrength: Float? = null
    private var pollIntervalMs: Long? = null
    private var pollTimeoutMs: Long? = null
    private var resolution: String? = null
    private var negativePrompt: String? = null
    private var promptOptimizer: Boolean? = null

    /** @since 0.3.0-beta01 */
    public fun loop(value: Boolean?): FalVideoModelOptionsBuilder {
        loop = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun motionStrength(value: Float?): FalVideoModelOptionsBuilder {
        motionStrength = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollIntervalMs(value: Long?): FalVideoModelOptionsBuilder {
        pollIntervalMs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollTimeoutMs(value: Long?): FalVideoModelOptionsBuilder {
        pollTimeoutMs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun resolution(value: String?): FalVideoModelOptionsBuilder {
        resolution = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun negativePrompt(value: String?): FalVideoModelOptionsBuilder {
        negativePrompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun promptOptimizer(value: Boolean?): FalVideoModelOptionsBuilder {
        promptOptimizer = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): FalVideoModelOptions =
        FalVideoModelOptions(
            loop = loop,
            motionStrength = motionStrength,
            pollIntervalMs = pollIntervalMs,
            pollTimeoutMs = pollTimeoutMs,
            resolution = resolution,
            negativePrompt = negativePrompt,
            promptOptimizer = promptOptimizer,
        )
}

/** @since 0.3.0-beta01 */
public fun FalVideoModelOptions(
    block: FalVideoModelOptionsBuilder.() -> Unit = {},
): FalVideoModelOptions =
    FalVideoModelOptionsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class FalProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: FalProviderSettings,
) : Provider {
    override val providerId: String = "fal"

    /** @since 0.3.0-beta01 */
    public fun image(modelId: ModelId): ImageModel = FalImageModel(client, settings, modelId.value)
    /** @since 0.3.0-beta01 */
    public fun speech(modelId: ModelId): SpeechModel = FalSpeechModel(client, settings, modelId.value)
    /** @since 0.3.0-beta01 */
    public fun transcription(modelId: ModelId): TranscriptionModel = FalTranscriptionModel(client, settings, modelId.value)
    /** @since 0.3.0-beta01 */
    public fun video(modelId: ModelId): VideoModel = FalVideoModel(client, settings, modelId.value)
    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))
    override fun speechModel(modelId: String): SpeechModel = speech(ModelId(modelId))
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(ModelId(modelId))
    override fun videoModel(modelId: String): VideoModel = video(ModelId(modelId))
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

/**
 * PascalCase factory — mirrors `OpenAI(...)`, the Layer-3 reference pattern.
 * @since 0.3.0-beta01
 */
public fun Fal(
    client: HttpClient,
    settings: FalProviderSettings = FalProviderSettings(),
): FalProvider = FalProvider(client, settings)

internal class FalBinaryResponse(
    val bytes: ByteArray,
    val headers: Map<String, String>,
) {
    fun headerValue(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

internal data class FalImageRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

internal data class FalSpeechRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

/**
 * fal returns 4xx with `detail == "Request is still in progress"` while a job
 * is queued; the poll loop treats that exception's message as a retry signal,
 * so it must stay a plain [AiSdkException] (not an [APICallError]). Any other
 * non-2xx falls through to the default rich [APICallError].
 */
private val falInProgressSignal: ResponseErrorFactory = { _, parsed, _, _ ->
    val detail = ((parsed as? JsonObject)?.get("detail") as? JsonPrimitive)?.contentOrNull
    if (detail == "Request is still in progress") InvalidResponseDataError(null, detail.orEmpty()) else null
}

private val falErrorMessage: ErrorMessageExtractor = { _, parsed, raw ->
    val obj = parsed as? JsonObject
    val validation = obj?.get("detail") as? JsonArray
    when {
        obj == null -> raw
        validation != null -> validation.joinToString("\n") { item ->
            val detail = item as? JsonObject ?: return@joinToString ""
            val loc = (JsonAccess.arr(detail, "loc"))
                ?.joinToString(".") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            val msg = (detail["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            listOfNotNull(loc?.takeIf { it.isNotBlank() }, msg.takeIf { it.isNotBlank() }).joinToString(": ")
        }
        else -> ((JsonAccess.obj(obj, "error"))?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
            ?: raw
    }
}
