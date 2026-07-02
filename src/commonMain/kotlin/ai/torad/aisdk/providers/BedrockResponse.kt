package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Decodes Amazon Bedrock `converse` / `invoke` responses into the SDK result
 * shape: the non-streaming chat result and the embedding vector/token readers.
 * The streaming counterpart lives in [BedrockStreamState].
 */
internal object BedrockResponse {
    fun bedrockChatGenerateResult(
        response: JsonObject,
        requestBody: JsonObject,
        responseHeaders: Map<String, String>,
        responseBody: JsonElement,
        warnings: List<CallWarning>,
        generateId: () -> String,
        usesJsonResponseTool: Boolean,
    ): LanguageModelResult {
        val content = mutableListOf<ContentPart>()
        val toolCalls = mutableListOf<ContentPart.ToolCall>()
        var isJsonResponseFromTool = false
        val outputMessage = (JsonAccess.obj(response, "output"))?.get("message") as? JsonObject
        val parts = (outputMessage?.get("content") as? JsonArray) ?: JsonArray(emptyList())
        for (part in parts) {
            val obj = part as? JsonObject ?: continue
            (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { content += ContentPart.Text(it) }
            (JsonAccess.obj(obj, "reasoningContent"))?.let { reasoning ->
                (JsonAccess.obj(reasoning, "reasoningText"))?.let {
                    content += ContentPart.Reasoning(
                        text = (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("bedrock" to buildJsonObject {
                            it["signature"]?.let { signature -> put("signature", signature) }
                        }))),
                    )
                }
                (JsonAccess.obj(reasoning, "redactedReasoning"))?.let {
                    content += ContentPart.Reasoning(
                        text = "",
                        providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("bedrock" to buildJsonObject {
                            it["data"]?.let { data -> put("redactedData", data) }
                        }))),
                    )
                }
            }
            (JsonAccess.obj(obj, "toolUse"))?.let { tool ->
                val name = (tool["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (usesJsonResponseTool && name == "json") {
                    isJsonResponseFromTool = true
                    content += ContentPart.Text((tool["input"] ?: JsonObject(emptyMap())).toString())
                } else {
                    val call = ContentPart.ToolCall(
                        toolCallId = (tool["toolUseId"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate(),
                        toolName = name,
                        input = tool["input"] ?: JsonObject(emptyMap()),
                    )
                    content += call
                    toolCalls += call
                }
            }
        }
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val stopReason = (response["stopReason"] as? JsonPrimitive)?.contentOrNull
        val metadata = buildJsonObject {
            response["trace"]?.let { put("trace", it) }
            response["performanceConfig"]?.let { put("performanceConfig", it) }
            response["serviceTier"]?.let { put("serviceTier", it) }
            response["usage"]?.let { put("usage", it) }
            if (isJsonResponseFromTool) put("isJsonResponseFromTool", JsonPrimitive(true))
            val responseFields = JsonAccess.obj(response, "additionalModelResponseFields")
            val responseDelta = responseFields?.get("delta") as? JsonObject
            responseDelta?.get("stop_sequence")?.let { put("stopSequence", it) }
        }
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = mapBedrockFinishReason(stopReason, isJsonResponseFromTool),
            usage = bedrockUsage(response["usage"]),
            providerMetadata = if (metadata.isNotEmpty()) {
                ProviderMetadata.Raw(
                    JsonObject(mapOf("bedrock" to metadata))
                )
            } else {
                ProviderMetadata.None
            },
            content = content,
            rawFinishReason = stopReason,
            warnings = warnings,
            request = LanguageModelRequestMetadata(requestBody),
            response = LanguageModelResponseMetadata(
                id = BedrockHttp.headerValue(responseHeaders, "x-amzn-requestid"),
                modelId = (response["modelId"] as? JsonPrimitive)?.contentOrNull,
                headers = responseHeaders,
                body = responseBody,
            ),
        )
    }

    fun bedrockEmbeddingVector(response: JsonObject): List<Float> {
        (JsonAccess.arr(response, "embedding"))?.let { arr ->
            return arr.map { item -> WireDecoder.embeddingFloat(item, "amazon-bedrock.embedding") }
        }
        val embeddings = response["embeddings"]
        if (embeddings is JsonArray) {
            val first = embeddings.firstOrNull() ?: return emptyList()
            if (first is JsonArray) return first.map { WireDecoder.embeddingFloat(it, "amazon-bedrock.embedding") }
            val obj = first as? JsonObject ?: return emptyList()
            return (JsonAccess.arr(obj, "embedding")).orEmpty()
                .map { WireDecoder.embeddingFloat(it, "amazon-bedrock.embedding") }
        }
        val floatEmbeddings = (((embeddings as? JsonObject)?.get("float") as? JsonArray)?.firstOrNull() as? JsonArray)
        return floatEmbeddings.orEmpty().map { WireDecoder.embeddingFloat(it, "amazon-bedrock.embedding") }
    }

    fun bedrockEmbeddingTokens(response: JsonObject): Int =
        (response["inputTextTokenCount"] as? JsonPrimitive)?.intOrNull
            ?: (response["inputTokenCount"] as? JsonPrimitive)?.intOrNull
            ?: 0

    // Shared by the non-streaming decoder above and the streaming [BedrockStreamState] below.
    fun bedrockUsage(element: JsonElement?): Usage {
        val obj = element as? JsonObject ?: return Usage()
        val input = (obj["inputTokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val output = (obj["outputTokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val cacheRead = (obj["cacheReadInputTokens"] as? JsonPrimitive)?.intOrNull
            ?: (obj["cacheReadInputTokenCount"] as? JsonPrimitive)?.intOrNull
            ?: 0
        val cacheWrite = (obj["cacheWriteInputTokens"] as? JsonPrimitive)?.intOrNull
            ?: (obj["cacheWriteInputTokenCount"] as? JsonPrimitive)?.intOrNull
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
}

internal class BedrockStreamState(
    private val generateId: () -> String,
    private val usesJsonResponseTool: Boolean,
) {
    private val blocks = mutableMapOf<Int, BedrockStreamBlock>()
    private var finishReason = FinishReason.Other
    private var rawStopReason: String? = null
    private var usage = Usage()
    private var providerMetadata: JsonObject? = null
    private var isJsonResponseFromTool = false
    private var stopSequence: JsonElement? = null

    fun accept(value: JsonObject): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        val error = value["internalServerException"] ?: value["modelStreamErrorException"] ?: value["throttlingException"] ?: value["validationException"]
        if (error != null) {
            finishReason = FinishReason.Error
            events += StreamEvent.Error(error.toString())
            return events
        }
        (JsonAccess.obj(value, "messageStop"))?.let { stop ->
            rawStopReason = (stop["stopReason"] as? JsonPrimitive)?.contentOrNull
            finishReason = BedrockResponse.mapBedrockFinishReason(rawStopReason, isJsonResponseFromTool)
            val stopResponseFields = JsonAccess.obj(stop, "additionalModelResponseFields")
            val stopDelta = stopResponseFields?.get("delta") as? JsonObject
            stopSequence = stopDelta?.get("stop_sequence")
        }
        (JsonAccess.obj(value, "metadata"))?.let { metadata ->
            metadata["usage"]?.let { usage = BedrockResponse.bedrockUsage(it) }
            providerMetadata = metadata
        }
        (JsonAccess.obj(value, "contentBlockStart"))?.let { start ->
            val index = (start["contentBlockIndex"] as? JsonPrimitive)?.intOrNull ?: return@let
            val toolUse = (JsonAccess.obj(start, "start"))?.get("toolUse") as? JsonObject
            if (toolUse != null) {
                val id = (toolUse["toolUseId"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate()
                val name = (toolUse["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val isJsonTool = usesJsonResponseTool && name == "json"
                blocks[index] = BedrockStreamBlock.Tool(id, name, "", isJsonTool)
                if (!isJsonTool) events += StreamEvent.ToolInputStart(id, name)
            } else {
                blocks[index] = BedrockStreamBlock.Text
                events += StreamEvent.TextStart(index.toString())
            }
        }
        (JsonAccess.obj(value, "contentBlockDelta"))?.let { deltaEvent ->
            val index = (deltaEvent["contentBlockIndex"] as? JsonPrimitive)?.intOrNull ?: 0
            val delta = JsonAccess.obj(deltaEvent, "delta") ?: return@let
            (delta["text"] as? JsonPrimitive)?.contentOrNull?.let { text ->
                if (blocks[index] == null) {
                    blocks[index] = BedrockStreamBlock.Text
                    events += StreamEvent.TextStart(index.toString())
                }
                events += StreamEvent.TextDelta(index.toString(), text)
            }
            (JsonAccess.obj(delta, "reasoningContent"))?.let { reasoning ->
                if (blocks[index] == null) {
                    blocks[index] = BedrockStreamBlock.Reasoning
                    events += StreamEvent.ReasoningStart(index.toString())
                }
                val metadata = buildJsonObject {
                    reasoning["signature"]?.let { put("signature", it) }
                    reasoning["data"]?.let { put("redactedData", it) }
                }.takeIf { it.isNotEmpty() }?.let { ProviderMetadata.Raw(JsonObject(mapOf("bedrock" to it))) } ?: ProviderMetadata.None
                val reasoningText = (reasoning["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                events += StreamEvent.ReasoningDelta(index.toString(), reasoningText, metadata)
            }
            val toolUse = JsonAccess.obj(delta, "toolUse")
            val toolUseInput = (toolUse?.get("input") as? JsonPrimitive)?.contentOrNull
            toolUseInput?.let { inputDelta ->
                val block = blocks[index] as? BedrockStreamBlock.Tool ?: return@let
                block.input += inputDelta
                if (!block.isJsonResponseTool) events += StreamEvent.ToolInputDelta(block.id, inputDelta)
            }
        }
        (JsonAccess.obj(value, "contentBlockStop"))?.let { stop ->
            val index = (stop["contentBlockIndex"] as? JsonPrimitive)?.intOrNull ?: return@let
            when (val block = blocks.remove(index)) {
                BedrockStreamBlock.Text -> events += StreamEvent.TextEnd(index.toString())
                BedrockStreamBlock.Reasoning -> events += StreamEvent.ReasoningEnd(index.toString())
                is BedrockStreamBlock.Tool -> {
                    if (block.isJsonResponseTool) {
                        isJsonResponseFromTool = true
                        events += StreamEvent.TextStart(index.toString())
                        events += StreamEvent.TextDelta(index.toString(), block.input.ifBlank { "{}" })
                        events += StreamEvent.TextEnd(index.toString())
                    } else {
                        events += StreamEvent.ToolInputEnd(block.id)
                        events += StreamEvent.ToolCall(
                            toolCallId = block.id,
                            toolName = block.name,
                            inputJson = runCatching {
                                aiSdkJson.parseToJsonElement(block.input.ifBlank { "{}" })
                            }.getOrElse { JsonPrimitive(block.input) },
                        )
                    }
                }
                null -> Unit
            }
        }
        return events
    }

    fun finish(): List<StreamEvent> {
        val metadata = buildJsonObject {
            providerMetadata?.let { putJsonObjectFields(it) }
            if (isJsonResponseFromTool) put("isJsonResponseFromTool", JsonPrimitive(true))
            stopSequence?.let { put("stopSequence", it) }
        }
        return listOf(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = finishReason,
                usage = usage,
                providerMetadata = if (metadata.isNotEmpty()) {
                    ProviderMetadata.Raw(
                        JsonObject(mapOf("bedrock" to metadata))
                    )
                } else {
                    ProviderMetadata.None
                },
                rawFinishReason = rawStopReason,
            ),
        )
    }

    private fun JsonObjectBuilder.putJsonObjectFields(fields: JsonObject) {
        fields.forEach { (key, value) -> if (value !is JsonNull) put(key, value) }
    }
}

internal sealed class BedrockStreamBlock {
    data object Text : BedrockStreamBlock()
    data object Reasoning : BedrockStreamBlock()
    data class Tool(
        val id: String,
        val name: String,
        var input: String,
        val isJsonResponseTool: Boolean
    ) : BedrockStreamBlock()
}
