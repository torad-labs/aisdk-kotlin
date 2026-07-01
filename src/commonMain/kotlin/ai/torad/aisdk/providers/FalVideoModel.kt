package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val falVideoNonPassthroughKeys = setOf(
    "loop",
    "motionStrength",
    "pollIntervalMs",
    "pollTimeoutMs",
    "resolution",
    "negativePrompt",
    "promptOptimizer",
)

internal class FalVideoModel(
    private val client: HttpClient,
    private val settings: FalProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = "fal.video"
    override val maxVideosPerCall: Int = 1

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val options = settings.falOptions(params.providerOptions)
        val body = falVideoRequestBody(params, options)
        val queue = settings.falPostJson(
            client = client,
            url = "https://queue.fal.run/fal-ai/${falNormalizedVideoModelId(modelId)}",
            body = body,
            headers = settings.falHeaders(params.headers),
        )
        val responseUrl = (queue.value.jsonObject["response_url"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(queue.value, "No response URL returned from queue endpoint")
        val pollIntervalMillis = (options["pollIntervalMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: settings.videoPollIntervalMillis
        val result = settings.falPollJson(
            client = client,
            url = responseUrl,
            headers = settings.falHeaders(params.headers),
            abortSignal = params.abortSignal,
            pollIntervalMillis = pollIntervalMillis,
            maxPollAttempts = (options["pollTimeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?.let { timeout -> (timeout / pollIntervalMillis.coerceAtLeast(1)).toInt().coerceAtLeast(1) }
                ?: settings.videoMaxPollAttempts,
            timeoutMessage = "Video generation request timed out",
        )
        val value = result.value.jsonObject
        val video = (JsonAccess.obj(value, "video")) ?: throw NoVideoGeneratedError("No video URL in response")
        val videoUrl = (video["url"] as? JsonPrimitive)?.contentOrNull
            ?: throw NoVideoGeneratedError("No video URL in response")
        val mediaType = (video["content_type"] as? JsonPrimitive)?.contentOrNull ?: "video/mp4"
        return VideoModelResult(
            videos = listOf(
                GeneratedFile(
                    mediaType = mediaType,
                    base64 = "",
                    url = videoUrl,
                    providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("fal" to falVideoMetadata(video)))),
                ),
            ),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = result.headers, body = result.value),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("fal" to falVideoProviderMetadata(value, video)))),
        )
    }

    private fun falVideoRequestBody(
        params: VideoGenerationParams,
        options: JsonObject,
    ): JsonObject = buildJsonObject {
        params.prompt.takeIf { it.isNotBlank() }?.let { put("prompt", JsonPrimitive(it)) }
        params.image?.let { put("image_url", JsonPrimitive(it.url ?: "data:${it.mediaType};base64,${it.base64}")) }
        params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
        params.durationSeconds?.let { put("duration", JsonPrimitive("${formatFalSeconds(it)}s")) }
        params.seed?.let { put("seed", JsonPrimitive(it)) }
        (options["resolution"] ?: params.resolution?.let(::JsonPrimitive))?.let { put("resolution", it) }
        options["loop"]?.takeUnless { it is JsonNull }?.let { put("loop", it) }
        options["motionStrength"]?.takeUnless { it is JsonNull }?.let { put("motion_strength", it) }
        options["negativePrompt"]?.takeUnless { it is JsonNull }?.let { put("negative_prompt", it) }
        options["promptOptimizer"]?.takeUnless { it is JsonNull }?.let { put("prompt_optimizer", it) }
        for ((key, value) in options) {
            if (key !in falVideoNonPassthroughKeys && value !is JsonNull) put(key, value)
        }
    }

    private fun formatFalSeconds(value: Float): String {
        val int = value.toInt()
        return if (value == int.toFloat()) int.toString() else value.toString()
    }

    private fun falNormalizedVideoModelId(modelId: String): String =
        modelId.removePrefix("fal-ai/").removePrefix("fal/")

    private fun falVideoProviderMetadata(
        value: JsonObject,
        video: JsonObject,
    ): JsonObject = buildJsonObject {
        put("videos", buildJsonArray { add(falVideoMetadata(video)) })
        listOf("seed", "timings", "has_nsfw_concepts", "prompt").forEach { key ->
            value[key]?.takeUnless { it is JsonNull }?.let { put(key, it) }
        }
    }

    private fun falVideoMetadata(video: JsonObject): JsonObject = buildJsonObject {
        video["url"]?.let { put("url", it) }
        listOf("width", "height", "duration", "fps").forEach { key ->
            video[key]?.takeUnless { it is JsonNull }?.let { put(key, it) }
        }
        video["content_type"]?.takeUnless { it is JsonNull }?.let { put("contentType", it) }
    }
}
