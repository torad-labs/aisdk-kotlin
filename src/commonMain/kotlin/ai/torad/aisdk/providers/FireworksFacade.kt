@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeHttp.getFacadeBinary
import ai.torad.aisdk.providers.FacadeHttp.postFacadeBinary
import ai.torad.aisdk.providers.FacadeHttp.postFacadeJson
import ai.torad.aisdk.providers.FacadeHttp.providerFacadeHeaders
import ai.torad.aisdk.providers.FacadeHttp.putProviderSpecificOptions
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

public const val FIREWORKS_VERSION: String = "2.0.53"

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class FireworksThinkingOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val type: String? = null,
    /** @since 0.3.0-beta01 */
    public val budgetTokens: Int? = null,
)

/** @since 0.3.0-beta01 */
public class FireworksThinkingOptionsBuilder {
    private var type: String? = null
    private var budgetTokens: Int? = null

    /** @since 0.3.0-beta01 */
    public fun type(value: String?): FireworksThinkingOptionsBuilder {
        type = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun budgetTokens(value: Int?): FireworksThinkingOptionsBuilder {
        budgetTokens = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): FireworksThinkingOptions =
        FireworksThinkingOptions(
            type = type,
            budgetTokens = budgetTokens,
        )
}

/** @since 0.3.0-beta01 */
public fun FireworksThinkingOptions(
    block: FireworksThinkingOptionsBuilder.() -> Unit = {},
): FireworksThinkingOptions =
    FireworksThinkingOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class FireworksLanguageModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val thinking: FireworksThinkingOptions? = null,
    /** @since 0.3.0-beta01 */
    public val reasoningHistory: String? = null,
    /** @since 0.3.0-beta01 */
    public val raw: Map<String, JsonElement> = emptyMap(),
)

/** @since 0.3.0-beta01 */
public class FireworksLanguageModelOptionsBuilder {
    private var thinking: FireworksThinkingOptions? = null
    private var reasoningHistory: String? = null
    private var raw: Map<String, JsonElement> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun thinking(value: FireworksThinkingOptions?): FireworksLanguageModelOptionsBuilder {
        thinking = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun reasoningHistory(value: String?): FireworksLanguageModelOptionsBuilder {
        reasoningHistory = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun raw(value: Map<String, JsonElement>): FireworksLanguageModelOptionsBuilder {
        raw = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): FireworksLanguageModelOptions =
        FireworksLanguageModelOptions(
            thinking = thinking,
            reasoningHistory = reasoningHistory,
            raw = raw,
        )
}

/** @since 0.3.0-beta01 */
public fun FireworksLanguageModelOptions(
    block: FireworksLanguageModelOptionsBuilder.() -> Unit = {},
): FireworksLanguageModelOptions =
    FireworksLanguageModelOptionsBuilder().apply(block).build()

public typealias FireworksProviderOptions = FireworksLanguageModelOptions

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class FireworksEmbeddingModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val raw: Map<String, JsonElement> = emptyMap(),
)

/** @since 0.3.0-beta01 */
public class FireworksEmbeddingModelOptionsBuilder {
    private var raw: Map<String, JsonElement> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun raw(value: Map<String, JsonElement>): FireworksEmbeddingModelOptionsBuilder {
        raw = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): FireworksEmbeddingModelOptions =
        FireworksEmbeddingModelOptions(raw = raw)
}

/** @since 0.3.0-beta01 */
public fun FireworksEmbeddingModelOptions(
    block: FireworksEmbeddingModelOptionsBuilder.() -> Unit = {},
): FireworksEmbeddingModelOptions =
    FireworksEmbeddingModelOptionsBuilder().apply(block).build()

public typealias FireworksEmbeddingProviderOptions = FireworksEmbeddingModelOptions

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class FireworksErrorData(
    /** @since 0.3.0-beta01 */
    public val error: String,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class FireworksProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.fireworks.ai/inference/v1",
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun toCompatible(
        name: String,
        version: String,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings.forFacade(name, version, baseURL, apiKey, headers, capabilities)
}

/** @since 0.3.0-beta01 */
public class FireworksProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.fireworks.ai/inference/v1"
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): FireworksProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): FireworksProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): FireworksProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): FireworksProviderSettings =
        FireworksProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun FireworksProviderSettings(
    block: FireworksProviderSettingsBuilder.() -> Unit = {},
): FireworksProviderSettings =
    FireworksProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class FireworksProvider(
    private val client: HttpClient,
    private val settings: FireworksProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("fireworks", FIREWORKS_VERSION),
    )
    override val providerId: String = "fireworks"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    override fun languageModel(modelId: String): LanguageModel = chatModel(ModelId(modelId))

    /** @since 0.3.0-beta01 */
    public fun chatModel(modelId: ModelId): LanguageModel = FireworksLanguageModel(compatible.chatModel(modelId.value))

    /** @since 0.3.0-beta01 */
    public fun completionModel(modelId: ModelId): LanguageModel = compatible.completionModel(modelId.value)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embeddingModel(modelId.value)

    /** @since 0.3.0-beta01 */
    public fun image(modelId: ModelId): ImageModel = imageModel(modelId.value)
    override fun embeddingModel(modelId: String): EmbeddingModel = compatible.embeddingModel(modelId)
    override fun imageModel(modelId: String): ImageModel = FireworksImageModel(client, settings, modelId)
}

/** @since 0.3.0-beta01 */
public fun Fireworks(
    client: HttpClient,
    settings: FireworksProviderSettings = FireworksProviderSettings(),
): FireworksProvider = FireworksProvider(client, settings)

private class FireworksLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(
            params.toBuilder().providerOptions(transformFireworksProviderOptions(params.providerOptions)).build()
        )

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(
            params.toBuilder().providerOptions(transformFireworksProviderOptions(params.providerOptions)).build()
        )

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(
            params.toBuilder().providerOptions(transformFireworksProviderOptions(params.providerOptions)).build()
        )

    private fun transformFireworksProviderOptions(options: ProviderOptions): ProviderOptions {
        val map = options.toMap()
        val fireworksOptions = JsonAccess.obj(map, "fireworks") ?: return options
        val transformed = buildJsonObject {
            for ((key, value) in fireworksOptions) {
                when (key) {
                    "thinking" -> put("thinking", transformFireworksThinking(value))
                    "reasoningHistory" -> put("reasoning_history", value)
                    else -> put(key, value)
                }
            }
        }
        return ProviderOptions.Raw(JsonObject(map + ("fireworks" to (transformed as JsonElement))))
    }

    private fun transformFireworksThinking(value: JsonElement): JsonElement {
        val objectValue = value as? JsonObject ?: return value
        return buildJsonObject {
            for ((key, nestedValue) in objectValue) {
                when (key) {
                    "budgetTokens" -> put("budget_tokens", nestedValue)
                    else -> put(key, nestedValue)
                }
            }
        }
    }
}

/** @since 0.3.0-beta01 */
public class FireworksImageModel(
    private val client: HttpClient,
    private val settings: FireworksProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "fireworks.image"
    override val maxImagesPerCall: Int = 1

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        val backend = fireworksImageBackend(modelId)
        val warnings = fireworksImageWarnings(params, backend)
        val body = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            put("samples", JsonPrimitive(params.n))
            params.size?.split('x')?.takeIf { it.size == 2 }?.let { parts ->
                put("width", JsonPrimitive(parts[0]))
                put("height", JsonPrimitive(parts[1]))
            }
            putProviderSpecificOptions(params.providerOptions.toMap(), "fireworks")
        }
        val requestHeaders = providerFacadeHeaders(
            apiKey = settings.apiKey,
            headers = settings.headers,
            callHeaders = params.headers,
            userAgent = "ai-sdk/fireworks/$FIREWORKS_VERSION",
        )
        return if (backend.urlFormat == FireworksImageUrlFormat.WorkflowsAsync) {
            generateAsync(body, requestHeaders, warnings, params.abortSignal)
        } else {
            val response = postFacadeBinary(
                client = client,
                url = fireworksImageUrl(settings.baseURL, modelId, backend),
                body = body,
                headers = requestHeaders,
            )
            ImageModelResult(
                images = listOf(response.toGeneratedFile(modelId)),
                warnings = warnings,
                response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
            )
        }
    }

    private suspend fun generateAsync(
        body: JsonObject,
        requestHeaders: Map<String, String>,
        warnings: List<CallWarning>,
        abortSignal: AbortSignal,
    ): ImageModelResult {
        val submitResponse = AbortSignalRuntime.withAbortCancellation(abortSignal) {
            postFacadeJson(
                client = client,
                url = fireworksImageUrl(settings.baseURL, modelId, fireworksImageBackend(modelId)),
                body = body,
                headers = requestHeaders,
                abortSignal = abortSignal,
            )
        }
        val requestId = (submitResponse.value.jsonObject["request_id"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(
                submitResponse.value,
                "Fireworks image generation response is missing request_id",
            )
        val imageUrl = pollForImageUrl(requestId, requestHeaders, abortSignal)
        val imageResponse = AbortSignalRuntime.withAbortCancellation(abortSignal) {
            getFacadeBinary(client, imageUrl, requestHeaders, abortSignal = abortSignal)
        }
        return ImageModelResult(
            images = listOf(imageResponse.toGeneratedFile(modelId)),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = imageResponse.headers),
        )
    }

    private suspend fun pollForImageUrl(
        requestId: String,
        requestHeaders: Map<String, String>,
        abortSignal: AbortSignal,
    ): String {
        val pollUrl = "${settings.baseURL.trimEnd('/')}/workflows/$modelId/get_result"
        var pollDelayMillis = FIREWORKS_POLL_INTERVAL_MILLIS
        repeat(FIREWORKS_MAX_POLL_ATTEMPTS) { attempt ->
            abortSignal.throwIfAborted()
            val response = AbortSignalRuntime.withAbortCancellation(abortSignal) {
                postFacadeJson(
                    client = client,
                    url = pollUrl,
                    body = buildJsonObject { put("id", JsonPrimitive(requestId)) },
                    headers = requestHeaders,
                    abortSignal = abortSignal,
                )
            }
            val status = (response.value.jsonObject["status"] as? JsonPrimitive)?.contentOrNull
            when (status) {
                "Ready" -> {
                    val sample = (JsonAccess.obj(response.value.jsonObject, "result"))?.get("sample")
                    return (sample as? JsonPrimitive)?.contentOrNull
                        ?: throw InvalidResponseDataError(
                            response.value,
                            "Fireworks poll response is Ready but missing result.sample",
                        )
                }
                "Error", "Failed" -> throw APICallError(
                    message = "Fireworks image generation failed with status: $status",
                    url = pollUrl,
                )
            }
            if (attempt < FIREWORKS_MAX_POLL_ATTEMPTS - 1) {
                delay(pollDelayMillis)
                pollDelayMillis = ((pollDelayMillis * 3) / 2).coerceAtMost(FIREWORKS_MAX_POLL_INTERVAL_MILLIS)
            }
        }
        throw APICallError(
            message = "Fireworks image generation timed out after polling",
            url = pollUrl,
        )
    }

    private fun fireworksImageBackend(modelId: String): FireworksImageBackend =
        when (modelId) {
            "accounts/fireworks/models/flux-kontext-pro",
            "accounts/fireworks/models/flux-kontext-max",
            -> FireworksImageBackend(FireworksImageUrlFormat.WorkflowsAsync)
            "accounts/fireworks/models/playground-v2-5-1024px-aesthetic",
            "accounts/fireworks/models/japanese-stable-diffusion-xl",
            "accounts/fireworks/models/playground-v2-1024px-aesthetic",
            "accounts/fireworks/models/stable-diffusion-xl-1024-v1-0",
            "accounts/fireworks/models/SSD-1B",
            -> FireworksImageBackend(FireworksImageUrlFormat.ImageGeneration, supportsSize = true)
            else -> FireworksImageBackend(FireworksImageUrlFormat.Workflows)
        }

    private fun fireworksImageUrl(baseURL: String, modelId: String, backend: FireworksImageBackend): String {
        val base = baseURL.trimEnd('/')
        return when (backend.urlFormat) {
            FireworksImageUrlFormat.ImageGeneration -> "$base/image_generation/$modelId"
            FireworksImageUrlFormat.WorkflowsAsync -> "$base/workflows/$modelId"
            FireworksImageUrlFormat.Workflows -> "$base/workflows/$modelId/text_to_image"
        }
    }

    private fun fireworksImageWarnings(params: ImageGenerationParams, backend: FireworksImageBackend): List<CallWarning> =
        buildList {
            if (!backend.supportsSize && params.size != null) {
                add(CallWarning("unsupported", "This Fireworks model does not support size; use aspectRatio."))
            }
            if (backend.supportsSize && params.aspectRatio != null) {
                add(CallWarning("unsupported", "This Fireworks model does not support aspectRatio."))
            }
        }
}

internal enum class FireworksImageUrlFormat {
    Workflows,
    WorkflowsAsync,
    ImageGeneration,
}

internal data class FireworksImageBackend(
    val urlFormat: FireworksImageUrlFormat,
    val supportsSize: Boolean = false,
)

private const val FIREWORKS_POLL_INTERVAL_MILLIS: Long = 500
private const val FIREWORKS_MAX_POLL_INTERVAL_MILLIS: Long = 30_000
private const val FIREWORKS_MAX_POLL_ATTEMPTS: Int = 240
