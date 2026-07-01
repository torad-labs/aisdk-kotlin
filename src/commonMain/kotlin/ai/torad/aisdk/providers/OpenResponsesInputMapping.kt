@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

internal data class ConvertedOpenResponsesInput(
    val input: JsonArray,
    val instructions: String?,
) {
    companion object {
        internal fun from(
            messages: List<ModelMessage>,
            warnings: MutableList<CallWarning>,
            fileIdPrefixes: List<String> = emptyList(),
        ): ConvertedOpenResponsesInput {
            val input = mutableListOf<JsonElement>()
            val systemMessages = mutableListOf<String>()

            for (message in messages) {
                when (message.role) {
                    MessageRole.System -> systemMessages += message.content.textContent()
                    MessageRole.User -> input += buildJsonObject {
                        put("type", JsonPrimitive("message"))
                        put("role", JsonPrimitive("user"))
                        put("content", JsonArray(message.content.mapNotNull { openResponsesUserContentPart(it, fileIdPrefixes) }))
                    }
                    MessageRole.Assistant -> {
                        val assistantContent = message.content.mapNotNull(::openResponsesAssistantContentPart)
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
                                openResponsesToolOutput(
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

        private fun List<ContentPart>.textContent(): String =
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

        private fun isOpenResponsesFileId(value: String, prefixes: List<String>): Boolean =
            prefixes.any { prefix -> prefix.isNotEmpty() && value.startsWith(prefix) } && !isOpenResponsesBase64Payload(value)

        private fun isOpenResponsesBase64Payload(value: String): Boolean =
            runCatching { Base64Codec.decode(value) }.isSuccess

        private fun openResponsesFileId(
            value: String,
            prefixes: List<String>,
            providerMetadata: ProviderMetadata,
        ): String? =
            explicitOpenResponsesFileId(providerMetadata.toMap())
                ?: value.takeIf { isOpenResponsesFileId(it, prefixes) }

        private fun explicitOpenResponsesFileId(providerMetadata: Map<String, JsonElement>?): String? {
            val openai = providerMetadata?.get("openai") as? JsonObject
            return openai?.get("file_id").metadataString()
                ?: openai?.get("fileId").metadataString()
                ?: providerMetadata?.get("file_id").metadataString()
                ?: providerMetadata?.get("fileId").metadataString()
        }

        private fun JsonElement?.metadataString(): String? =
            (this as? JsonPrimitive)?.contentOrNull

        private fun openResponsesUserContentPart(
            part: ContentPart,
            fileIdPrefixes: List<String>,
        ): JsonElement? = when (part) {
            is ContentPart.Text -> buildJsonObject {
                put("type", JsonPrimitive("input_text"))
                put("text", JsonPrimitive(part.text))
            }
            is ContentPart.Image -> buildJsonObject {
                put("type", JsonPrimitive("input_image"))
                val fileId = openResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
                if (fileId != null) {
                    put("file_id", JsonPrimitive(fileId))
                } else {
                    put("image_url", JsonPrimitive(part.url ?: "data:${part.mediaType};base64,${part.base64}"))
                }
            }
            is ContentPart.File -> if (part.mediaType.startsWith("image/")) {
                buildJsonObject {
                    put("type", JsonPrimitive("input_image"))
                    val fileId = openResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
                    if (fileId != null) {
                        put("file_id", JsonPrimitive(fileId))
                    } else {
                        put("image_url", JsonPrimitive(part.url ?: "data:${part.mediaType};base64,${part.base64}"))
                    }
                }
            } else {
                buildJsonObject {
                    put("type", JsonPrimitive("input_file"))
                    val fileId = openResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
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

        private fun openResponsesAssistantContentPart(part: ContentPart): JsonElement? = when (part) {
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

        private fun openResponsesToolOutput(
            output: ToolResultOutput,
            warnings: MutableList<CallWarning>,
        ): JsonElement = when (output) {
            is ToolResultOutput.Text -> JsonPrimitive(output.text)
            is ToolResultOutput.Error -> JsonPrimitive(output.message)
            is ToolResultOutput.ExecutionDenied -> JsonPrimitive(output.reason ?: "Tool execution denied.")
            is ToolResultOutput.Json -> JsonPrimitive(output.json.toString())
            is ToolResultOutput.ErrorJson -> JsonPrimitive(output.json.toString())
            is ToolResultOutput.Content -> JsonArray(output.value.mapNotNull { item ->
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
            })
        }
    }
}

