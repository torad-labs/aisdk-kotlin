package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure value mapping for Amazon Bedrock: token-usage accounting, finish-reason
 * translation, media/document format detection, cache-point/citation extraction,
 * and model-id path encoding. No HTTP, no request/response shaping.
 */
internal object BedrockMapping {
    fun bedrockUsage(element: JsonElement?): Usage {
        val obj = element as? JsonObject ?: return Usage()
        val input = obj["inputTokens"]?.jsonPrimitive?.intOrNull ?: 0
        val output = obj["outputTokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cacheRead = obj["cacheReadInputTokens"]?.jsonPrimitive?.intOrNull
            ?: obj["cacheReadInputTokenCount"]?.jsonPrimitive?.intOrNull
            ?: 0
        val cacheWrite = obj["cacheWriteInputTokens"]?.jsonPrimitive?.intOrNull
            ?: obj["cacheWriteInputTokenCount"]?.jsonPrimitive?.intOrNull
            ?: 0
        val safeCacheRead = cacheRead.coerceIn(0, input)
        val safeCacheWrite = cacheWrite.coerceIn(0, input - safeCacheRead)
        return Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = input,
                noCache = input - safeCacheRead - safeCacheWrite,
                cacheRead = safeCacheRead,
                cacheWrite = safeCacheWrite,
            ),
            outputTokens = Usage.OutputTokenBreakdown(total = output),
            raw = element,
        )
    }

    fun bedrockOpenAILikeUsage(element: JsonElement?): Usage {
        val obj = element as? JsonObject ?: return Usage()
        return Usage(
            promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = obj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    fun mapBedrockFinishReason(reason: String?, isJsonResponseFromTool: Boolean = false): FinishReason =
        if (isJsonResponseFromTool) {
            FinishReason.Stop
        } else {
            when (reason) {
                "end_turn", "stop_sequence" -> FinishReason.Stop
                "tool_use" -> FinishReason.ToolCalls
                "max_tokens" -> FinishReason.Length
                "content_filtered", "guardrail_intervened" -> FinishReason.ContentFilter
                else -> FinishReason.Other
            }
        }

    fun mapOpenAILikeFinishReason(reason: String?): FinishReason = when (reason) {
        "stop" -> FinishReason.Stop
        "length" -> FinishReason.Length
        "tool_calls" -> FinishReason.ToolCalls
        "content_filter" -> FinishReason.ContentFilter
        else -> FinishReason.Other
    }

    fun bedrockImageFileBase64(file: ImageGenerationFile): String =
        file.base64 ?: throw UnsupportedFunctionalityError("url-based images", "URL-based images are not supported for Amazon Bedrock image editing. Provide base64 data directly.")

    fun bedrockImageFormat(mediaType: String): String =
        mediaType.substringAfter("image/", "png").substringBefore("+").substringBefore(";")

    fun bedrockDocumentFormat(mediaType: String): String = when (mediaType) {
        "application/pdf" -> "pdf"
        "text/csv" -> "csv"
        "text/html" -> "html"
        "text/plain" -> "txt"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/msword" -> "doc"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.ms-excel" -> "xls"
        else -> mediaType.substringAfterLast('/').substringAfterLast('.')
    }

    fun bedrockCachePoint(metadata: Map<String, JsonElement>?): JsonElement? =
        (metadata?.get("bedrock") as? JsonObject)?.get("cachePoint")

    fun bedrockCitationsEnabled(metadata: Map<String, JsonElement>?): Boolean =
        ((metadata?.get("bedrock") as? JsonObject)?.get("citations") as? JsonObject)
            ?.get("enabled")?.jsonPrimitive?.contentOrNull == "true"

    fun bedrockEncodeModelId(modelId: String): String =
        modelId.flatMap { ch ->
            if (ch.isLetterOrDigit() || ch in "-_.~") {
                listOf(ch.toString())
            } else {
                listOf("%" + ch.code.toString(16).uppercase().padStart(2, '0'))
            }
        }.joinToString("")
}
