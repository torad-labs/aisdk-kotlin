@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

internal fun ConvertedOpenResponsesInput(
    messages: List<ModelMessage>,
    warnings: MutableList<CallWarning>,
    fileIdPrefixes: List<String> = emptyList(),
): ConvertedOpenResponsesInput {
    val input = mutableListOf<JsonElement>()
    val systemMessages = mutableListOf<String>()

    for (message in messages) {
        when (message.role) {
            MessageRole.System -> systemMessages += message.content.TextContent()
            MessageRole.User -> input += buildJsonObject {
                put("type", JsonPrimitive("message"))
                put("role", JsonPrimitive("user"))
                put("content", JsonArray(message.content.mapNotNull { OpenResponsesUserContentPart(it, fileIdPrefixes) }))
            }
            MessageRole.Assistant -> {
                val assistantContent = message.content.mapNotNull(::OpenResponsesAssistantContentPart)
                if (assistantContent.isNotEmpty()) {
                    input += buildJsonObject {
                        put("type", JsonPrimitive("message"))
                        put("role", JsonPrimitive("assistant"))
                        put("content", JsonArray(assistantContent))
                    }
                }
                message.content.filterIsInstance<ContentPart.ToolCall>().forEach { toolCall ->
                    input += buildJsonObject {
                        put("type", JsonPrimitive("function_call"))
                        put("call_id", JsonPrimitive(toolCall.toolCallId))
                        put("name", JsonPrimitive(toolCall.toolName))
                        put("arguments", JsonPrimitive(toolCall.input.toString()))
                    }
                }
            }
            MessageRole.Tool -> message.content.filterIsInstance<ContentPart.ToolResult>().forEach { toolResult ->
                input += buildJsonObject {
                    put("type", JsonPrimitive("function_call_output"))
                    put("call_id", JsonPrimitive(toolResult.toolCallId))
                    put(
                        "output",
                        OpenResponsesToolOutput(
                            ToolResultOutputs.toolResultOutputFromWire(toolResult.modelVisible),
                            warnings,
                        ),
                    )
                }
            }
        }
    }

    return ConvertedOpenResponsesInput(
        input = JsonArray(input),
        instructions = systemMessages.takeIf { it.isNotEmpty() }?.joinToString("\n"),
    )
}

private fun List<ContentPart>.TextContent(): String =
    joinToString("") { part ->
        when (part) {
            is ContentPart.Text -> part.text
            is ContentPart.Reasoning -> part.text
            is ContentPart.ToolCall,
            is ContentPart.ToolResult,
            is ContentPart.ToolApprovalRequest,
            is ContentPart.ToolApprovalResponse,
            is ContentPart.Source,
            is ContentPart.File,
            is ContentPart.Image,
            is ContentPart.Raw,
            -> ""
        }
    }

private fun IsOpenResponsesFileId(value: String, prefixes: List<String>): Boolean =
    prefixes.any { prefix -> prefix.isNotEmpty() && value.startsWith(prefix) } && !IsOpenResponsesBase64Payload(value)

private fun IsOpenResponsesBase64Payload(value: String): Boolean =
    runCatching { Base64Codec.decode(value) }.isSuccess

private fun OpenResponsesFileId(
    value: String,
    prefixes: List<String>,
    providerMetadata: ProviderMetadata,
): String? =
    ExplicitOpenResponsesFileId(providerMetadata.toMap())
        ?: value.takeIf { IsOpenResponsesFileId(it, prefixes) }

private fun ExplicitOpenResponsesFileId(providerMetadata: Map<String, JsonElement>?): String? {
    val openai = providerMetadata?.get("openai") as? JsonObject
    return openai?.get("file_id").MetadataString()
        ?: openai?.get("fileId").MetadataString()
        ?: providerMetadata?.get("file_id").MetadataString()
        ?: providerMetadata?.get("fileId").MetadataString()
}

private fun JsonElement?.MetadataString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun OpenResponsesUserContentPart(
    part: ContentPart,
    fileIdPrefixes: List<String>,
): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("type", JsonPrimitive("input_text"))
        put("text", JsonPrimitive(part.text))
    }
    is ContentPart.Image -> buildJsonObject {
        put("type", JsonPrimitive("input_image"))
        val fileId = OpenResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
        if (fileId != null) {
            put("file_id", JsonPrimitive(fileId))
        } else {
            put("image_url", JsonPrimitive(part.url ?: "data:${part.mediaType};base64,${part.base64}"))
        }
    }
    is ContentPart.File -> if (part.mediaType.startsWith("image/")) {
        buildJsonObject {
            put("type", JsonPrimitive("input_image"))
            val fileId = OpenResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
            if (fileId != null) {
                put("file_id", JsonPrimitive(fileId))
            } else {
                put("image_url", JsonPrimitive(part.url ?: "data:${part.mediaType};base64,${part.base64}"))
            }
        }
    } else {
        buildJsonObject {
            put("type", JsonPrimitive("input_file"))
            val fileId = OpenResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
            if (fileId != null) {
                put("filename", JsonPrimitive(part.filename ?: "data"))
                put("file_id", JsonPrimitive(fileId))
            } else if (part.url != null) {
                put("file_url", JsonPrimitive(part.url))
            } else {
                put("filename", JsonPrimitive(part.filename ?: "data"))
                put("file_data", JsonPrimitive("data:${part.mediaType};base64,${part.base64}"))
            }
        }
    }
    is ContentPart.Reasoning,
    is ContentPart.ToolCall,
    is ContentPart.ToolResult,
    is ContentPart.ToolApprovalRequest,
    is ContentPart.ToolApprovalResponse,
    is ContentPart.Source,
    is ContentPart.Raw,
    -> null
}

private fun OpenResponsesAssistantContentPart(part: ContentPart): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("type", JsonPrimitive("output_text"))
        put("text", JsonPrimitive(part.text))
    }
    is ContentPart.Reasoning,
    is ContentPart.ToolCall,
    is ContentPart.ToolResult,
    is ContentPart.ToolApprovalRequest,
    is ContentPart.ToolApprovalResponse,
    is ContentPart.Source,
    is ContentPart.File,
    is ContentPart.Image,
    is ContentPart.Raw,
    -> null
}

private fun OpenResponsesToolOutput(
    output: ToolResultOutput,
    warnings: MutableList<CallWarning>,
): JsonElement = when (output) {
    is ToolResultOutput.Text -> JsonPrimitive(output.text)
    is ToolResultOutput.Error -> JsonPrimitive(output.message)
    is ToolResultOutput.ExecutionDenied -> JsonPrimitive(output.reason ?: "Tool execution denied.")
    is ToolResultOutput.Json -> JsonPrimitive(output.json.toString())
    is ToolResultOutput.ErrorJson -> JsonPrimitive(output.json.toString())
    is ToolResultOutput.Content -> JsonArray(
        output.value.mapNotNull { item ->
            val obj = item as? JsonObject
            when ((obj?.get("type") as? JsonPrimitive)?.contentOrNull) {
                "text" -> buildJsonObject {
                    put("type", JsonPrimitive("input_text"))
                    put("text", obj["text"] ?: JsonPrimitive(""))
                }
                "image-data" -> buildJsonObject {
                    put("type", JsonPrimitive("input_image"))
                    val mediaType = (obj["mediaType"] as? JsonPrimitive)?.contentOrNull
                        ?: "application/octet-stream"
                    val data = (obj["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    put("image_url", JsonPrimitive("data:$mediaType;base64,$data"))
                }
                "image-url" -> buildJsonObject {
                    put("type", JsonPrimitive("input_image"))
                    put("image_url", obj["url"] ?: JsonPrimitive(""))
                }
                "file-data" -> buildJsonObject {
                    put("type", JsonPrimitive("input_file"))
                    put("filename", obj["filename"] ?: JsonPrimitive("data"))
                    val mediaType = (obj["mediaType"] as? JsonPrimitive)?.contentOrNull
                        ?: "application/octet-stream"
                    val data = (obj["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    put("file_data", JsonPrimitive("data:$mediaType;base64,$data"))
                }
                else -> {
                    warnings += CallWarning("other", "unsupported tool content part type: ${obj?.get("type")}")
                    null
                }
            }
        }
    )
}

internal data class ConvertedOpenResponsesInput(
    val input: JsonArray,
    val instructions: String?,
)
