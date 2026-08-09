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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.ceil

public const val BLACK_FOREST_LABS_VERSION: String = "1.0.34"

public typealias BlackForestLabsImageProviderOptions = BlackForestLabsImageModelOptions

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class BlackForestLabsImageModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val imagePrompt: String? = null,
    /** @since 0.3.0-beta01 */
    public val imagePromptStrength: Double? = null,
    /** @since 0.3.0-beta01 */
    public val inputImage: String? = null,
    /** @since 0.3.0-beta01 */
    public val inputImage2: String? = null,
    /** @since 0.3.0-beta01 */
    public val inputImage3: String? = null,
    /** @since 0.3.0-beta01 */
    public val inputImage4: String? = null,
    /** @since 0.3.0-beta01 */
    public val inputImage5: String? = null,
    /** @since 0.3.0-beta01 */
    public val inputImage6: String? = null,
    /** @since 0.3.0-beta01 */
    public val inputImage7: String? = null,
    /** @since 0.3.0-beta01 */
    public val inputImage8: String? = null,
    /** @since 0.3.0-beta01 */
    public val inputImage9: String? = null,
    /** @since 0.3.0-beta01 */
    public val inputImage10: String? = null,
    /** @since 0.3.0-beta01 */
    public val steps: Int? = null,
    /** @since 0.3.0-beta01 */
    public val guidance: Double? = null,
    /** @since 0.3.0-beta01 */
    public val width: Int? = null,
    /** @since 0.3.0-beta01 */
    public val height: Int? = null,
    /** @since 0.3.0-beta01 */
    public val outputFormat: String? = null,
    /** @since 0.3.0-beta01 */
    public val promptUpsampling: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val raw: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val safetyTolerance: Int? = null,
    /** @since 0.3.0-beta01 */
    public val webhookSecret: String? = null,
    /** @since 0.3.0-beta01 */
    public val webhookUrl: String? = null,
    /** @since 0.3.0-beta01 */
    public val pollIntervalMillis: Long? = null,
    /** @since 0.3.0-beta01 */
    public val pollTimeoutMillis: Long? = null,
)

/** @since 0.3.0-beta01 */
public class BlackForestLabsImageModelOptionsBuilder {
    private var imagePrompt: String? = null
    private var imagePromptStrength: Double? = null
    private var inputImage: String? = null
    private var inputImage2: String? = null
    private var inputImage3: String? = null
    private var inputImage4: String? = null
    private var inputImage5: String? = null
    private var inputImage6: String? = null
    private var inputImage7: String? = null
    private var inputImage8: String? = null
    private var inputImage9: String? = null
    private var inputImage10: String? = null
    private var steps: Int? = null
    private var guidance: Double? = null
    private var width: Int? = null
    private var height: Int? = null
    private var outputFormat: String? = null
    private var promptUpsampling: Boolean? = null
    private var raw: Boolean? = null
    private var safetyTolerance: Int? = null
    private var webhookSecret: String? = null
    private var webhookUrl: String? = null
    private var pollIntervalMillis: Long? = null
    private var pollTimeoutMillis: Long? = null

    /** @since 0.3.0-beta01 */
    public fun imagePrompt(value: String?): BlackForestLabsImageModelOptionsBuilder {
        imagePrompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun imagePromptStrength(value: Double?): BlackForestLabsImageModelOptionsBuilder {
        imagePromptStrength = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun inputImage(value: String?): BlackForestLabsImageModelOptionsBuilder {
        inputImage = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun inputImage2(value: String?): BlackForestLabsImageModelOptionsBuilder {
        inputImage2 = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun inputImage3(value: String?): BlackForestLabsImageModelOptionsBuilder {
        inputImage3 = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun inputImage4(value: String?): BlackForestLabsImageModelOptionsBuilder {
        inputImage4 = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun inputImage5(value: String?): BlackForestLabsImageModelOptionsBuilder {
        inputImage5 = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun inputImage6(value: String?): BlackForestLabsImageModelOptionsBuilder {
        inputImage6 = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun inputImage7(value: String?): BlackForestLabsImageModelOptionsBuilder {
        inputImage7 = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun inputImage8(value: String?): BlackForestLabsImageModelOptionsBuilder {
        inputImage8 = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun inputImage9(value: String?): BlackForestLabsImageModelOptionsBuilder {
        inputImage9 = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun inputImage10(value: String?): BlackForestLabsImageModelOptionsBuilder {
        inputImage10 = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun steps(value: Int?): BlackForestLabsImageModelOptionsBuilder {
        steps = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun guidance(value: Double?): BlackForestLabsImageModelOptionsBuilder {
        guidance = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun width(value: Int?): BlackForestLabsImageModelOptionsBuilder {
        width = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun height(value: Int?): BlackForestLabsImageModelOptionsBuilder {
        height = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun outputFormat(value: String?): BlackForestLabsImageModelOptionsBuilder {
        outputFormat = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun promptUpsampling(value: Boolean?): BlackForestLabsImageModelOptionsBuilder {
        promptUpsampling = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun raw(value: Boolean?): BlackForestLabsImageModelOptionsBuilder {
        raw = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun safetyTolerance(value: Int?): BlackForestLabsImageModelOptionsBuilder {
        safetyTolerance = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun webhookSecret(value: String?): BlackForestLabsImageModelOptionsBuilder {
        webhookSecret = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun webhookUrl(value: String?): BlackForestLabsImageModelOptionsBuilder {
        webhookUrl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollIntervalMillis(value: Long?): BlackForestLabsImageModelOptionsBuilder {
        pollIntervalMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollTimeoutMillis(value: Long?): BlackForestLabsImageModelOptionsBuilder {
        pollTimeoutMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): BlackForestLabsImageModelOptions =
        BlackForestLabsImageModelOptions(
            imagePrompt = imagePrompt,
            imagePromptStrength = imagePromptStrength,
            inputImage = inputImage,
            inputImage2 = inputImage2,
            inputImage3 = inputImage3,
            inputImage4 = inputImage4,
            inputImage5 = inputImage5,
            inputImage6 = inputImage6,
            inputImage7 = inputImage7,
            inputImage8 = inputImage8,
            inputImage9 = inputImage9,
            inputImage10 = inputImage10,
            steps = steps,
            guidance = guidance,
            width = width,
            height = height,
            outputFormat = outputFormat,
            promptUpsampling = promptUpsampling,
            raw = raw,
            safetyTolerance = safetyTolerance,
            webhookSecret = webhookSecret,
            webhookUrl = webhookUrl,
            pollIntervalMillis = pollIntervalMillis,
            pollTimeoutMillis = pollTimeoutMillis,
        )
}

/** @since 0.3.0-beta01 */
public fun BlackForestLabsImageModelOptions(
    block: BlackForestLabsImageModelOptionsBuilder.() -> Unit = {},
): BlackForestLabsImageModelOptions =
    BlackForestLabsImageModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class BlackForestLabsProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.bfl.ai/v1",
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val pollIntervalMillis: Long? = null,
    /** @since 0.3.0-beta01 */
    public val pollTimeoutMillis: Long? = null,
) {
    internal fun bflHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String?>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base["x-key"] = it }
        headers.forEach { (key, value) -> base[key] = value }
        callHeaders.forEach { (key, value) -> base[key] = value }
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/black-forest-labs/$BLACK_FOREST_LABS_VERSION")
    }
}

/** @since 0.3.0-beta01 */
public class BlackForestLabsProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.bfl.ai/v1"
    private var headers: Map<String, String> = emptyMap()
    private var pollIntervalMillis: Long? = null
    private var pollTimeoutMillis: Long? = null

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): BlackForestLabsProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): BlackForestLabsProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): BlackForestLabsProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollIntervalMillis(value: Long?): BlackForestLabsProviderSettingsBuilder {
        pollIntervalMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollTimeoutMillis(value: Long?): BlackForestLabsProviderSettingsBuilder {
        pollTimeoutMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): BlackForestLabsProviderSettings =
        BlackForestLabsProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
            pollIntervalMillis = pollIntervalMillis,
            pollTimeoutMillis = pollTimeoutMillis,
        )
}

/** @since 0.3.0-beta01 */
public fun BlackForestLabsProviderSettings(
    block: BlackForestLabsProviderSettingsBuilder.() -> Unit = {},
): BlackForestLabsProviderSettings =
    BlackForestLabsProviderSettingsBuilder().apply(block).build()

/**
 * Sealed (not `sealed interface`; see the repo's `no-sealed-interface` tenet — this
 * type is a single-implementation service facade, not a `@Serializable` wire type or
 * a private state machine, so the class form is what stays compliant) so the SDK
 * keeps the freedom to add members without breaking an external implementer.
 * @since 0.3.0-beta01
 */
public sealed class BlackForestLabsProvider : Provider {
    /** @since 0.3.0-beta01 */
    public abstract fun image(modelId: ModelId): ImageModel
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(
        providerId,
        "embeddingModel",
        modelId
    )
}

/** @since 0.3.0-beta01 */
public fun BlackForestLabs(
    client: HttpClient,
    settings: BlackForestLabsProviderSettings = BlackForestLabsProviderSettings(),
): BlackForestLabsProvider = DefaultBlackForestLabsProvider(client, settings)

// Named (not anonymous) because a sealed class's direct subtypes must be named declarations.
private object UnconfiguredBlackForestLabsProvider : BlackForestLabsProvider() {
    override val providerId: String = "black-forest-labs"
    override fun image(modelId: ModelId): ImageModel =
        throw UnsupportedFunctionalityError(
            "black-forest-labs",
            "Black Forest Labs provider is not configured. Use BlackForestLabs(client, settings)."
        )
}

/** @since 0.3.0-beta01 */
public val blackForestLabs: BlackForestLabsProvider = UnconfiguredBlackForestLabsProvider

private class DefaultBlackForestLabsProvider(
    private val client: HttpClient,
    private val settings: BlackForestLabsProviderSettings,
) : BlackForestLabsProvider() {
    override val providerId: String = "black-forest-labs"
    override fun image(modelId: ModelId): ImageModel = BlackForestLabsImageModel(client, settings, modelId.value)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(
        providerId,
        "languageModel",
        modelId
    )
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(
        providerId,
        "embeddingModel",
        modelId
    )
}

private class BlackForestLabsImageModel(
    private val client: HttpClient,
    private val settings: BlackForestLabsProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "black-forest-labs.image"
    override val maxImagesPerCall: Int = 1

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        val options = bflOptions(params.providerOptions)
        val args = bflRequestBody(modelId, params, options)
        val headers = settings.bflHeaders(params.headers)
        val submit = bflPostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/$modelId",
            body = args.body,
            headers = headers,
        )
        val submitBody = submit.value.jsonObject
        val requestId = (submitBody["id"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(null, "Black Forest Labs submit response is missing id")
        val pollingUrl = (submitBody["polling_url"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(null, "Black Forest Labs submit response is missing polling_url")
        val pollResult = bflPollForImage(
            client = client,
            pollingUrl = bflPollUrl(pollingUrl, requestId),
            headers = headers,
            abortSignal = params.abortSignal,
            pollIntervalMillis = (options["pollIntervalMillis"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: settings.pollIntervalMillis
                ?: DEFAULT_BFL_POLL_INTERVAL_MILLIS,
            pollTimeoutMillis = (options["pollTimeoutMillis"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: settings.pollTimeoutMillis
                ?: DEFAULT_BFL_POLL_TIMEOUT_MILLIS,
        )
        // NO caller headers on the asset fetch. `pollResult.imageUrl` comes from the provider's poll
        // response and BFL serves it from a signed delivery origin, not `api.bfl.ai` — forwarding
        // `headers` sent the `x-key` API key (plus anything the host configured) to a third-party
        // host on every generation. The signed URL is the credential; the request needs none.
        // Matches Fal/Luma/Replicate/xAI, which all download with no headers.
        val downloaded = bflDownloadImage(client, pollResult.imageUrl, emptyMap(), params.abortSignal)
        return ImageModelResult(
            images = listOf(downloaded.file),
            warnings = args.warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = downloaded.headers),
            providerMetadata = ProviderMetadata.Raw(
                JsonObject(
                    mapOf(
                        "blackForestLabs" to bflProviderMetadata(submitBody, pollResult.result),
                    )
                )
            ),
        )
    }

    private fun bflRequestBody(
        modelId: String,
        params: ImageGenerationParams,
        options: JsonObject,
    ): BflArgs {
        val warnings = mutableListOf<CallWarning>()
        val acceptsAspectRatio = bflAcceptsAspectRatio(modelId)
        val (sizeWidth, sizeHeight) = bflParseSize(params.size)
        val width = (options["width"] as? JsonPrimitive)?.intOrNull ?: sizeWidth
        val height = (options["height"] as? JsonPrimitive)?.intOrNull ?: sizeHeight
        val finalAspectRatio = when {
            !acceptsAspectRatio -> null
            params.aspectRatio != null -> params.aspectRatio
            else -> params.size?.let(::bflSizeToAspectRatio)
        }
        if (acceptsAspectRatio && params.size != null && params.aspectRatio == null) {
            warnings += CallWarning(
                type = "unsupported",
                message = "Deriving aspect_ratio from size. Use width and height provider options to specify dimensions for models that support them.",
            )
        } else if (acceptsAspectRatio && params.size != null && params.aspectRatio != null) {
            warnings += CallWarning(
                type = "unsupported",
                message = "Black Forest Labs ignores size when aspectRatio is provided. Use width and height provider options to specify dimensions for models that support them.",
            )
        } else if (!acceptsAspectRatio && params.aspectRatio != null && width == null && height == null) {
            warnings += CallWarning(
                type = "unsupported",
                message = "aspect_ratio is not accepted by $modelId; provide width/height instead.",
            )
        }
        return BflArgs(
            body = buildJsonObject {
                put("prompt", JsonPrimitive(params.prompt))
                if (acceptsAspectRatio) {
                    putStringIfNotNull("aspect_ratio", finalAspectRatio)
                }
                putIntIfNotNull("seed", params.seed)
                putIntIfNotNull("width", width)
                putIntIfNotNull("height", height)
                putIntIfNotNull("steps", (options["steps"] as? JsonPrimitive)?.intOrNull)
                putDoubleIfNotNull("guidance", (options["guidance"] as? JsonPrimitive)?.doubleOrNull)
                putDoubleIfNotNull(
                    "image_prompt_strength",
                    (options["imagePromptStrength"] as? JsonPrimitive)?.doubleOrNull,
                )
                putStringIfNotNull("image_prompt", (options["imagePrompt"] as? JsonPrimitive)?.contentOrNull)
                putBflInputImages(modelId, params.files, options)
                putStringIfNotNull("mask", params.mask?.bflValue())
                putStringIfNotNull("output_format", (options["outputFormat"] as? JsonPrimitive)?.contentOrNull)
                // FLUX.2 schema uses `disable_pup` (inverted); older FLUX.1 keeps prompt_upsampling.
                val promptUpsampling = (options["promptUpsampling"] as? JsonPrimitive)?.booleanOrNull
                if (promptUpsampling != null) {
                    if (modelId.contains("flux-2") || modelId.contains("flux.2")) {
                        put("disable_pup", JsonPrimitive(!promptUpsampling))
                    } else {
                        put("prompt_upsampling", JsonPrimitive(promptUpsampling))
                    }
                }
                putBooleanIfNotNull("raw", (options["raw"] as? JsonPrimitive)?.booleanOrNull)
                putIntIfNotNull("safety_tolerance", (options["safetyTolerance"] as? JsonPrimitive)?.intOrNull)
                putStringIfNotNull("webhook_secret", (options["webhookSecret"] as? JsonPrimitive)?.contentOrNull)
                putStringIfNotNull("webhook_url", (options["webhookUrl"] as? JsonPrimitive)?.contentOrNull)
            },
            warnings = warnings,
        )
    }

    private fun bflAcceptsAspectRatio(modelId: String): Boolean {
        val id = modelId.lowercase()
        // Docs: FLUX.2 t2i and classic FLUX.1 [pro] use width/height; schnell/dev/kontext accept ratio.
        if (id.contains("flux-2") || id.contains("flux.2")) return false
        if (id == "flux-pro" || id == "flux-pro-1.0" || id == "flux-pro-1.1" || id.startsWith("flux-pro-1.0")) {
            // fill/canny/depth variants still use their own schemas; keep ratio off for plain pro.
            if (id.contains("fill") || id.contains("canny") || id.contains("depth") || id.contains("redux")) {
                return false
            }
            return false
        }
        return true
    }

    private fun JsonObjectBuilder.putBflInputImages(
        modelId: String,
        files: List<ImageGenerationFile>,
        options: JsonObject,
    ) {
        // Docs stop at input_image_8 (8 images total including the first).
        if (files.size > 8) throw InvalidArgumentError("files", "Black Forest Labs supports up to 8 input images.")
        val inputImageField = if (modelId == "flux-pro-1.0-fill") "image" else "input_image"
        // inputImage..inputImage10 map 1:1 onto the input_image..input_image_10 wire keys;
        // params.files own the same slots and are written last so they win when both are set.
        for (slot in 1..BFL_INPUT_IMAGE_OPTION_SLOTS) {
            putStringIfNotNull(
                "$inputImageField${if (slot == 1) "" else "_$slot"}",
                (options[if (slot == 1) "inputImage" else "inputImage$slot"] as? JsonPrimitive)?.contentOrNull,
            )
        }
        files.forEachIndexed { index, file ->
            val suffix = if (index == 0) "" else "_${index + 1}"
            putStringIfNotNull("$inputImageField$suffix", file.bflValue())
        }
    }

    private fun ImageGenerationFile.bflValue(): String? =
        url?.takeIf { it.isNotBlank() } ?: base64?.takeIf { it.isNotBlank() }

    private suspend fun bflPostJson(
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
            errorMessage = ::bflErrorMessage,
        )

    private suspend fun bflGetJson(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
    ): HttpJsonResponse {
        abortSignal.throwIfAborted()
        return AbortSignalRuntime.withAbortCancellation(abortSignal) {
            HttpTransport.requestJson(
                client = client,
                url = url,
                method = HttpMethod.Get,
                headers = headers,
                errorMessage = ::bflErrorMessage,
                abortSignal = abortSignal,
            )
        }
    }

    private suspend fun bflPollForImage(
        client: HttpClient,
        pollingUrl: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
        pollIntervalMillis: Long,
        pollTimeoutMillis: Long,
    ): BflPollResult {
        val interval = pollIntervalMillis.coerceAtLeast(1L)
        val maxPollAttempts = ceil(
            pollTimeoutMillis.coerceAtLeast(1L).toDouble() / interval.toDouble()
        ).toInt().coerceAtLeast(1)
        repeat(maxPollAttempts) { attempt ->
            abortSignal.throwIfAborted()
            val poll = bflGetJson(client, pollingUrl, headers, abortSignal).value.jsonObject
            val status = (poll["status"] as? JsonPrimitive)?.contentOrNull
                ?: (poll["state"] as? JsonPrimitive)?.contentOrNull
                ?: throw InvalidResponseDataError(null, "Missing status in Black Forest Labs poll response")
            when (status) {
                "Ready" -> {
                    val result = (JsonAccess.obj(poll, "result"))
                        ?: throw InvalidResponseDataError(
                            null,
                            "Black Forest Labs poll response is Ready but missing result.sample",
                        )
                    val imageUrl = (result["sample"] as? JsonPrimitive)?.contentOrNull
                        ?: throw InvalidResponseDataError(
                            null,
                            "Black Forest Labs poll response is Ready but missing result.sample",
                        )
                    return BflPollResult(imageUrl = imageUrl, result = result)
                }
                "Error", "Failed", "Content Moderated", "Request Moderated", "Task not found" -> {
                    val reasons = ((JsonAccess.obj(poll, "details"))?.get("Moderation Reasons") as? JsonArray)
                        ?.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    throw NoImageGeneratedError(
                        "Black Forest Labs generation $status" +
                            (reasons?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
                    )
                }
            }
            if (pollIntervalMillis > 0 && attempt < maxPollAttempts - 1) delay(pollIntervalMillis)
        }
        throw NoImageGeneratedError("Black Forest Labs generation timed out.")
    }

    private suspend fun bflDownloadImage(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
    ): BflDownloadedImage {
        abortSignal.throwIfAborted()
        val (statusCode, headersMap, bytes) = HttpTransport.withRealTimeout(DEFAULT_REQUEST_TIMEOUT_MS) {
            val response = client.request(url) {
                method = HttpMethod.Get
                headers.forEach { (name, value) -> header(name, value) }
            }
            Triple(
                response.status.value,
                with(HttpTransport) { response.flattenedHeaders() },
                with(HttpTransport) { response.bodyAsBytesCapped(url) },
            )
        }
        if (statusCode !in 200..299) {
            val raw = bytes.decodeToString()
            val detail = raw.ifBlank { "request failed" }
            throw ApiCallError(
                url = url,
                statusCode = statusCode,
                rawBody = raw,
                headers = headersMap,
                message = "Black Forest Labs image download failed ($statusCode): $detail",
            )
        }
        return BflDownloadedImage(
            file = GeneratedFile(
                mediaType = headersMap.bflHeaderValue(HttpHeaders.ContentType) ?: "image/png",
                base64 = Base64Codec.encode(bytes),
            ),
            headers = headersMap,
        )
    }

    private fun bflProviderMetadata(submit: JsonObject, result: JsonObject): JsonElement = buildJsonObject {
        put(
            "images",
            JsonArray(
                listOf(
                    buildJsonObject {
                        putIfPresent("seed", result["seed"])
                        putIfPresent("start_time", result["start_time"])
                        putIfPresent("end_time", result["end_time"])
                        putIfPresent("duration", result["duration"])
                        putIfPresent("cost", submit["cost"])
                        putIfPresent("inputMegapixels", submit["input_mp"])
                        putIfPresent("outputMegapixels", submit["output_mp"])
                    }
                )
            )
        )
    }

    private fun bflOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "blackForestLabs") ?: JsonObject(emptyMap())

    private fun bflPollUrl(pollingUrl: String, requestId: String): String {
        val hasId = pollingUrl.substringAfter(
            '?',
            missingDelimiterValue = ""
        ).split('&').any { it.substringBefore('=') == "id" }
        if (hasId) return pollingUrl
        val separator = if ('?' in pollingUrl) "&" else "?"
        return "$pollingUrl${separator}id=$requestId"
    }

    private fun bflParseSize(size: String?): Pair<Int?, Int?> {
        if (size == null) return null to null
        val width = size.substringBefore('x', missingDelimiterValue = "").toIntOrNull()
        val height = size.substringAfter('x', missingDelimiterValue = "").toIntOrNull()
        return width to height
    }

    private fun bflSizeToAspectRatio(size: String): String? {
        val (width, height) = bflParseSize(size)
        if (width == null || height == null || width <= 0 || height <= 0) return null
        val divisor = bflGcd(width, height)
        return "${width / divisor}:${height / divisor}"
    }

    private fun bflGcd(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val next = x % y
            x = y
            y = next
        }
        return x.coerceAtLeast(1)
    }

    private fun bflErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = obj?.get("detail")
        val detailContent = (detail as? JsonPrimitive)?.contentOrNull
        // FastAPI-style validation errors: detail = [{loc, msg, type}, ...]
        val detailFromArray = (detail as? JsonArray)?.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            (entry["msg"] as? JsonPrimitive)?.contentOrNull
                ?: (entry["message"] as? JsonPrimitive)?.contentOrNull
        }?.filter { it.isNotBlank() }?.joinToString("; ").orEmpty().ifBlank { null }
        val messageContent = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        val message = when {
            detailContent != null -> detailContent
            detailFromArray != null -> detailFromArray
            detail != null && detail !is JsonNull -> detail.toString()
            messageContent != null -> messageContent
            else -> raw.ifBlank { "request failed" }
        }
        return "Black Forest Labs request failed ($statusCode): $message"
    }

    private fun JsonObjectBuilder.putIfPresent(key: String, value: JsonElement?) {
        if (value != null && value !is JsonNull) put(key, value)
    }

    private fun JsonObjectBuilder.putStringIfNotNull(key: String, value: String?) {
        if (value != null) put(key, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.putIntIfNotNull(key: String, value: Int?) {
        if (value != null) put(key, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.putDoubleIfNotNull(key: String, value: Double?) {
        if (value != null) put(key, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.putBooleanIfNotNull(key: String, value: Boolean?) {
        if (value != null) put(key, JsonPrimitive(value))
    }

    private fun Map<String, String>.bflHeaderValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

private const val DEFAULT_BFL_POLL_INTERVAL_MILLIS: Long = 500L
private const val DEFAULT_BFL_POLL_TIMEOUT_MILLIS: Long = 60_000L

// BlackForestLabsImageModelOptions exposes inputImage..inputImage10.
private const val BFL_INPUT_IMAGE_OPTION_SLOTS: Int = 10

internal data class BflArgs(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

internal data class BflPollResult(
    val imageUrl: String,
    val result: JsonObject,
)

internal data class BflDownloadedImage(
    val file: GeneratedFile,
    val headers: Map<String, String>,
)
