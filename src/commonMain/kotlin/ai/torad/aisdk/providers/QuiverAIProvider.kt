package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.ProviderMetadata
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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

public const val QUIVERAI_VERSION: String = "1.0.0"
private const val QUIVERAI_MAX_IMAGES_PER_CALL: Int = 16


@Serializable
public data class QuiverAIImageModelOptions(
    val operation: String? = null,
    val instructions: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val presencePenalty: Double? = null,
    val maxOutputTokens: Int? = null,
    val autoCrop: Boolean? = null,
    val targetSize: Int? = null,
)

@Serializable
public data class QuiverAIProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.quiver.ai/v1",
    val headers: Map<String, String> = emptyMap(),
) {
    internal fun quiverAIOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "quiverai") ?: JsonObject(emptyMap())

    internal fun quiverAIHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String?>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        headers.forEach { (key, value) -> base[key] = value }
        callHeaders.forEach { (key, value) -> base[key] = value }
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/quiverai/$QUIVERAI_VERSION")
    }

    internal suspend fun quiverAIPostJson(
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
            errorMessage = ::quiverAIErrorMessage,
        )

    private fun quiverAIErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = (obj?.get("message") as? JsonPrimitive)?.contentOrNull ?: raw.ifBlank { "request failed" }
        return "QuiverAI request failed ($statusCode): $detail"
    }
}

public class QuiverAIProvider(
    private val client: HttpClient,
    public val settings: QuiverAIProviderSettings,
) : Provider {
    override val providerId: String = "quiverai"

    public fun image(modelId: ModelId): ImageModel = QuiverAIImageModel(client, settings, modelId.value)
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

/** PascalCase factory — mirrors `OpenAI(...)`. */
public fun QuiverAI(
    client: HttpClient,
    settings: QuiverAIProviderSettings = QuiverAIProviderSettings(),
): QuiverAIProvider = QuiverAIProvider(client, settings)

private class QuiverAIImageModel(
    private val client: HttpClient,
    private val settings: QuiverAIProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "quiverai.image"
    override val maxImagesPerCall: Int = QUIVERAI_MAX_IMAGES_PER_CALL

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        val options = settings.quiverAIOptions(params.providerOptions)
        val operation = (options["operation"] as? JsonPrimitive)?.contentOrNull ?: "generate"
        val body = quiverAIRequestBody(modelId, params, operation, options)
        val response = settings.quiverAIPostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}${quiverAIOperationPath(operation)}",
            body = body,
            headers = settings.quiverAIHeaders(params.headers),
        )
        val root = response.value.jsonObject
        val data = (JsonAccess.arr(root, "data"))
            ?: throw InvalidResponseDataError(response.value, "QuiverAI response is missing data")
        return ImageModelResult(
            images = data.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val svg = (obj["svg"] as? JsonPrimitive)?.contentOrNull
                    ?: throw InvalidResponseDataError(item, "QuiverAI image item is missing svg")
                GeneratedFile(
                    mediaType = (obj["mime_type"] as? JsonPrimitive)?.contentOrNull ?: "image/svg+xml",
                    base64 = Base64Codec.encode(svg.encodeToByteArray()),
                )
            },
            warnings = quiverAIWarnings(params),
            response = LanguageModelResponseMetadata(
                id = (root["id"] as? JsonPrimitive)?.contentOrNull,
                timestampMillis = (root["created"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.times(1000),
                modelId = modelId,
                headers = response.headers,
                body = response.value,
            ),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("quiverai" to quiverAIProviderMetadata(root)))),
            usage = quiverAIUsage(root["usage"]),
        )
    }

    private fun quiverAIRequestBody(
        modelId: String,
        params: ImageGenerationParams,
        operation: String,
        options: JsonObject,
    ): JsonObject {
        val shared = buildJsonObject {
            putDoubleIfNotNull("temperature", (options["temperature"] as? JsonPrimitive)?.doubleOrNull)
            putDoubleIfNotNull("top_p", (options["topP"] as? JsonPrimitive)?.doubleOrNull)
            putDoubleIfNotNull("presence_penalty", (options["presencePenalty"] as? JsonPrimitive)?.doubleOrNull)
            putIntIfNotNull("max_output_tokens", (options["maxOutputTokens"] as? JsonPrimitive)?.intOrNull)
            put("stream", JsonPrimitive(false))
        }
        return when (operation) {
            "generate" -> quiverAIGenerateBody(modelId, params, options, shared)
            "vectorize" -> quiverAIVectorizeBody(modelId, params, options, shared)
            else -> throw InvalidArgumentError("providerOptions.quiverai.operation", "must be `generate` or `vectorize`")
        }
    }

    private fun quiverAIGenerateBody(
        modelId: String,
        params: ImageGenerationParams,
        options: JsonObject,
        shared: JsonObject,
    ): JsonObject {
        if (params.prompt.isBlank()) {
            throw InvalidArgumentError("prompt", "QuiverAI image generation requires a non-empty prompt for generateImage.")
        }
        val maxReferences = if (modelId == "arrow-1.1-max") 16 else 4
        if (params.files.size > maxReferences) {
            throw InvalidArgumentError("files", "QuiverAI generate supports up to $maxReferences reference images for model \"$modelId\".")
        }
        return buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("n", JsonPrimitive(params.n))
            put("prompt", JsonPrimitive(params.prompt))
            putAll(shared)
            putStringIfNotNull("instructions", (options["instructions"] as? JsonPrimitive)?.contentOrNull)
            if (params.files.isNotEmpty()) {
                put("references", JsonArray(params.files.map { it.toQuiverAIImageReference() }))
            }
        }
    }

    private fun quiverAIVectorizeBody(
        modelId: String,
        params: ImageGenerationParams,
        options: JsonObject,
        shared: JsonObject,
    ): JsonObject {
        if (params.files.isEmpty()) {
            throw InvalidArgumentError(
                "files",
                "QuiverAI vectorize requires an input image. Pass an image in generateImage files and set providerOptions.quiverai.operation to \"vectorize\".",
            )
        }
        if (params.files.size > 1) throw InvalidArgumentError("files", "QuiverAI vectorize accepts a single input image.")
        return buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("n", JsonPrimitive(params.n))
            put("image", params.files.single().toQuiverAIImageReference())
            putAll(shared)
            putBooleanIfNotNull("auto_crop", (options["autoCrop"] as? JsonPrimitive)?.booleanOrNull)
            putIntIfNotNull("target_size", (options["targetSize"] as? JsonPrimitive)?.intOrNull)
        }
    }

    private fun quiverAIWarnings(params: ImageGenerationParams): List<CallWarning> = buildList {
        if (params.size != null) add(CallWarning("unsupported", "QuiverAI SVG generation does not support the `size` option. The setting was ignored."))
        if (params.aspectRatio != null) add(CallWarning("unsupported", "QuiverAI SVG generation does not support the `aspectRatio` option. The setting was ignored."))
        if (params.seed != null) add(CallWarning("unsupported", "QuiverAI SVG generation does not support the `seed` option. The setting was ignored."))
        if (params.mask != null) add(CallWarning("unsupported", "QuiverAI SVG generation does not support masks. The mask was ignored."))
    }

    private fun ImageGenerationFile.toQuiverAIImageReference(): JsonObject = buildJsonObject {
        url?.takeIf { it.isNotBlank() }?.let {
            put("url", JsonPrimitive(it))
            return@buildJsonObject
        }
        val data = base64?.takeIf { it.isNotBlank() }
            ?: throw InvalidArgumentError("files", "QuiverAI image references must include either url or base64 data.")
        put("base64", JsonPrimitive(data))
    }

    private fun quiverAIProviderMetadata(root: JsonObject): JsonElement = buildJsonObject {
        val data = (JsonAccess.arr(root, "data")).orEmpty()
        val imageEntries = data.mapIndexed { index, item ->
            buildJsonObject {
                put("index", JsonPrimitive(index))
                val mimeType = ((item as? JsonObject)?.get("mime_type") as? JsonPrimitive)?.contentOrNull
                    ?: "image/svg+xml"
                put("mimeType", JsonPrimitive(mimeType))
            }
        }
        put("images", JsonArray(imageEntries))
        root["usage"]?.takeIf { it !is JsonNull }?.let { usage ->
            put("usage", usage)
        }
    }

    private fun quiverAIUsage(value: JsonElement?): ImageModelUsage {
        val usage = value as? JsonObject ?: return ImageModelUsage()
        return ImageModelUsage(
            inputTokens = (usage["input_tokens"] as? JsonPrimitive)?.intOrNull,
            outputTokens = (usage["output_tokens"] as? JsonPrimitive)?.intOrNull,
            totalTokens = (usage["total_tokens"] as? JsonPrimitive)?.intOrNull,
        )
    }

    private fun quiverAIOperationPath(operation: String): String =
        if (operation == "generate") "/svgs/generations" else "/svgs/vectorizations"

    private fun JsonObjectBuilder.putAll(values: JsonObject) {
        values.forEach { (key, value) -> put(key, value) }
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
}
