package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal class FalImageModel(
    private val client: HttpClient,
    private val settings: FalProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "fal.image"
    override val maxImagesPerCall: Int = 1

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        val prepared = falImageRequestBody(params)
        val response = settings.falPostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/$modelId",
            body = prepared.body,
            headers = settings.falHeaders(params.headers),
        )
        val value = response.value.jsonObject
        val targetImages = falResponseImages(value)
        if (targetImages.isEmpty()) throw NoImageGeneratedError("No fal image URL in response")

        val downloaded = targetImages.map { image ->
            val url = (image["url"] as? JsonPrimitive)?.contentOrNull
                ?: throw NoImageGeneratedError("No fal image URL in response")
            val bytes = settings.falGetBinary(client, url, emptyMap(), params.abortSignal)
            GeneratedFile(
                mediaType = (image["content_type"] as? JsonPrimitive)?.contentOrNull
                    ?: bytes.headerValue(HttpHeaders.ContentType)
                    ?: "image/png",
                base64 = Base64Codec.encode(bytes.bytes),
                filename = (image["file_name"] as? JsonPrimitive)?.contentOrNull,
                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("fal" to falImageMetadata(image)))),
                url = url,
            )
        }

        return ImageModelResult(
            images = downloaded,
            warnings = prepared.warnings,
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = response.headers,
                body = response.value
            ),
            providerMetadata = ProviderMetadata.Raw(
                JsonObject(mapOf("fal" to falImageProviderMetadata(value, targetImages)))
            ),
        )
    }

    private fun falImageRequestBody(params: ImageGenerationParams): FalImageRequest {
        val warnings = mutableListOf<CallWarning>()
        val options = settings.falOptions(params.providerOptions)
        val normalizedOptions = falImageOptions(options, warnings)
        val useMultipleImages = (options["useMultipleImages"] as? JsonPrimitive)?.booleanOrNull == true

        return FalImageRequest(
            body = buildJsonObject {
                put("prompt", JsonPrimitive(params.prompt))
                params.seed?.let { put("seed", JsonPrimitive(it)) }
                falImageSize(params.size, params.aspectRatio)?.let { put("image_size", it) }
                put("num_images", JsonPrimitive(params.n))
                if (params.files.isNotEmpty()) {
                    if (useMultipleImages) {
                        put("image_urls", JsonArray(params.files.map(::falImageGenerationFileUrl)))
                    } else {
                        put("image_url", falImageGenerationFileUrl(params.files.first()))
                        if (params.files.size > 1) {
                            warnings += CallWarning(
                                "other",
                                "Multiple input images provided but useMultipleImages is not enabled. Only the first image will be used.",
                            )
                        }
                    }
                }
                params.mask?.let { put("mask_url", falImageGenerationFileUrl(it)) }
                settings.putJsonObjectFields(this, normalizedOptions)
            },
            warnings = warnings,
        )
    }

    private fun falImageOptions(
        options: JsonObject,
        warnings: MutableList<CallWarning>,
    ): JsonObject {
        val deprecated = mutableListOf<String>()
        val result = linkedMapOf<String, JsonElement>()
        fun putMapped(camel: String, api: String) {
            val snake = api
            val value = options[snake] ?: options[camel]
            if (options[snake] != null) deprecated += snake
            if (value != null && value !is JsonNull) result[api] = value
        }

        putMapped("imageUrl", "image_url")
        putMapped("maskUrl", "mask_url")
        putMapped("guidanceScale", "guidance_scale")
        putMapped("numInferenceSteps", "num_inference_steps")
        putMapped("enableSafetyChecker", "enable_safety_checker")
        putMapped("outputFormat", "output_format")
        putMapped("syncMode", "sync_mode")
        putMapped("safetyTolerance", "safety_tolerance")
        listOf("strength", "acceleration").forEach { key ->
            options[key]?.takeUnless { it is JsonNull }?.let { result[key] = it }
        }
        val known = setOf(
            "imageUrl", "maskUrl", "guidanceScale", "numInferenceSteps", "enableSafetyChecker", "outputFormat", "syncMode",
            "safetyTolerance", "useMultipleImages", "image_url", "mask_url", "guidance_scale", "num_inference_steps",
            "enable_safety_checker", "output_format", "sync_mode", "safety_tolerance", "strength", "acceleration",
        )
        for ((key, value) in options) {
            if (key !in known && value !is JsonNull) result[key] = value
        }
        if (deprecated.isNotEmpty()) {
            warnings += CallWarning(
                "other",
                "The following provider options use deprecated snake_case and will be removed in @ai-sdk/fal v2.0. " +
                    "Please use camelCase instead: ${deprecated.joinToString(", ") { key -> "'$key' (use '${snakeToCamel(key)}')" }}",
            )
        }
        return JsonObject(result)
    }

    private fun falImageSize(size: String?, aspectRatio: String?): JsonElement? {
        if (size != null) {
            val width = size.substringBefore('x').toIntOrNull()
            val height = size.substringAfter('x', missingDelimiterValue = "").toIntOrNull()
            if (width != null && height != null) {
                return buildJsonObject {
                    put("width", JsonPrimitive(width))
                    put("height", JsonPrimitive(height))
                }
            }
        }
        return when (aspectRatio) {
            "1:1" -> JsonPrimitive("square_hd")
            "16:9" -> JsonPrimitive("landscape_16_9")
            "9:16" -> JsonPrimitive("portrait_16_9")
            "4:3" -> JsonPrimitive("landscape_4_3")
            "3:4" -> JsonPrimitive("portrait_4_3")
            "16:10" -> buildJsonObject {
                put("width", JsonPrimitive(1280))
                put("height", JsonPrimitive(800))
            }
            "10:16" -> buildJsonObject {
                put("width", JsonPrimitive(800))
                put("height", JsonPrimitive(1280))
            }
            "21:9" -> buildJsonObject {
                put("width", JsonPrimitive(2560))
                put("height", JsonPrimitive(1080))
            }
            "9:21" -> buildJsonObject {
                put("width", JsonPrimitive(1080))
                put("height", JsonPrimitive(2560))
            }
            else -> null
        }
    }

    private fun falImageGenerationFileUrl(file: ImageGenerationFile): JsonPrimitive =
        JsonPrimitive(
            file.url ?: "data:${file.mediaType ?: "application/octet-stream"};base64,${file.base64.orEmpty()}"
        )

    private fun snakeToCamel(key: String): String =
        key.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }

    private fun falResponseImages(value: JsonObject): List<JsonObject> {
        (JsonAccess.arr(value, "images"))?.let { images -> return images.mapNotNull { it as? JsonObject } }
        (JsonAccess.obj(value, "image"))?.let { return listOf(it) }
        return emptyList()
    }

    private fun falImageProviderMetadata(value: JsonObject, images: List<JsonObject>): JsonObject = buildJsonObject {
        put(
            "images",
            JsonArray(
                images.mapIndexed { index, image ->
                    buildJsonObject {
                        settings.putJsonObjectFields(this, falImageMetadata(image))
                        val nsfw = (JsonAccess.arr(value, "has_nsfw_concepts"))?.getOrNull(index)
                            ?: (JsonAccess.arr(value, "nsfw_content_detected"))?.getOrNull(index)
                        nsfw?.let { put("nsfw", it) }
                    }
                }
            ),
        )
        for ((key, item) in value) {
            if (key !in setOf("images", "image", "prompt", "has_nsfw_concepts", "nsfw_content_detected") && item !is JsonNull) {
                put(key, item)
            }
        }
    }

    private fun falImageMetadata(image: JsonObject): JsonObject = buildJsonObject {
        listOf("width", "height", "file_data", "file_size").forEach { key ->
            image[key]?.takeUnless { it is JsonNull }?.let { put(key, it) }
        }
        image["content_type"]?.takeUnless { it is JsonNull }?.let { put("contentType", it) }
        image["file_name"]?.takeUnless { it is JsonNull }?.let { put("fileName", it) }
    }
}
