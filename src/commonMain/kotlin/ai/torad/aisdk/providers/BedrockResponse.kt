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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val parts = response["output"]?.jsonObject?.get("message")?.jsonObject?.get("content") as? JsonArray ?: JsonArray(emptyList())
        for (part in parts) {
            val obj = part.jsonObject
            obj["text"]?.jsonPrimitive?.contentOrNull?.let { content += ContentPart.Text(it) }
            (obj["reasoningContent"] as? JsonObject)?.let { reasoning ->
                reasoning["reasoningText"]?.jsonObject?.let {
                    content += ContentPart.Reasoning(
                        text = it["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("bedrock" to buildJsonObject { it["signature"]?.let { signature -> put("signature", signature) } }))),
                    )
                }
                reasoning["redactedReasoning"]?.jsonObject?.let {
                    content += ContentPart.Reasoning(
                        text = "",
                        providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("bedrock" to buildJsonObject { it["data"]?.let { data -> put("redactedData", data) } }))),
                    )
                }
            }
            (obj["toolUse"] as? JsonObject)?.let { tool ->
                val name = tool["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (usesJsonResponseTool && name == "json") {
                    isJsonResponseFromTool = true
                    content += ContentPart.Text((tool["input"] ?: JsonObject(emptyMap())).toString())
                } else {
                    val call = ContentPart.ToolCall(
                        toolCallId = tool["toolUseId"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate(),
                        toolName = name,
                        input = tool["input"] ?: JsonObject(emptyMap()),
                    )
                    content += call
                    toolCalls += call
                }
            }
        }
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val stopReason = response["stopReason"]?.jsonPrimitive?.contentOrNull
        val metadata = buildJsonObject {
            response["trace"]?.let { put("trace", it) }
            response["performanceConfig"]?.let { put("performanceConfig", it) }
            response["serviceTier"]?.let { put("serviceTier", it) }
            response["usage"]?.let { put("usage", it) }
            if (isJsonResponseFromTool) put("isJsonResponseFromTool", JsonPrimitive(true))
            response["additionalModelResponseFields"]?.jsonObject?.get("delta")?.jsonObject?.get("stop_sequence")?.let { put("stopSequence", it) }
        }
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = BedrockMapping.mapBedrockFinishReason(stopReason, isJsonResponseFromTool),
            usage = BedrockMapping.bedrockUsage(response["usage"]),
            providerMetadata = if (metadata.isNotEmpty()) ProviderMetadata.Raw(JsonObject(mapOf("bedrock" to metadata))) else ProviderMetadata.None,
            content = content,
            rawFinishReason = stopReason,
            warnings = warnings,
            request = LanguageModelRequestMetadata(requestBody),
            response = LanguageModelResponseMetadata(
                id = BedrockHttp.headerValue(responseHeaders, "x-amzn-requestid"),
                modelId = response["modelId"]?.jsonPrimitive?.contentOrNull,
                headers = responseHeaders,
                body = responseBody,
            ),
        )
    }

    fun bedrockEmbeddingVector(response: JsonObject): List<Float> {
        response["embedding"]?.let { return it.jsonArray.map { item -> WireDecoder.embeddingFloat(item, "amazon-bedrock.embedding") } }
        val embeddings = response["embeddings"]
        if (embeddings is JsonArray) {
            val first = embeddings.firstOrNull() ?: return emptyList()
            if (first is JsonArray) return first.map { WireDecoder.embeddingFloat(it, "amazon-bedrock.embedding") }
            val obj = first.jsonObject
            return obj["embedding"]?.jsonArray.orEmpty().map { WireDecoder.embeddingFloat(it, "amazon-bedrock.embedding") }
        }
        val floatEmbeddings = embeddings?.jsonObject?.get("float")?.jsonArray?.firstOrNull()?.jsonArray
        return floatEmbeddings.orEmpty().map { WireDecoder.embeddingFloat(it, "amazon-bedrock.embedding") }
    }

    fun bedrockEmbeddingTokens(response: JsonObject): Int =
        response["inputTextTokenCount"]?.jsonPrimitive?.intOrNull
            ?: response["inputTokenCount"]?.jsonPrimitive?.intOrNull
            ?: 0
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
        (value["messageStop"] as? JsonObject)?.let { stop ->
            rawStopReason = stop["stopReason"]?.jsonPrimitive?.contentOrNull
            finishReason = BedrockMapping.mapBedrockFinishReason(rawStopReason, isJsonResponseFromTool)
            stopSequence = stop["additionalModelResponseFields"]?.jsonObject?.get("delta")?.jsonObject?.get("stop_sequence")
        }
        (value["metadata"] as? JsonObject)?.let { metadata ->
            metadata["usage"]?.let { usage = BedrockMapping.bedrockUsage(it) }
            providerMetadata = metadata
        }
        (value["contentBlockStart"] as? JsonObject)?.let { start ->
            val index = start["contentBlockIndex"]?.jsonPrimitive?.intOrNull ?: return@let
            val toolUse = start["start"]?.jsonObject?.get("toolUse") as? JsonObject
            if (toolUse != null) {
                val id = toolUse["toolUseId"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate()
                val name = toolUse["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val isJsonTool = usesJsonResponseTool && name == "json"
                blocks[index] = BedrockStreamBlock.Tool(id, name, "", isJsonTool)
                if (!isJsonTool) events += StreamEvent.ToolInputStart(id, name)
            } else {
                blocks[index] = BedrockStreamBlock.Text
                events += StreamEvent.TextStart(index.toString())
            }
        }
        (value["contentBlockDelta"] as? JsonObject)?.let { deltaEvent ->
            val index = deltaEvent["contentBlockIndex"]?.jsonPrimitive?.intOrNull ?: 0
            val delta = deltaEvent["delta"] as? JsonObject ?: return@let
            delta["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                if (blocks[index] == null) {
                    blocks[index] = BedrockStreamBlock.Text
                    events += StreamEvent.TextStart(index.toString())
                }
                events += StreamEvent.TextDelta(index.toString(), text)
            }
            (delta["reasoningContent"] as? JsonObject)?.let { reasoning ->
                if (blocks[index] == null) {
                    blocks[index] = BedrockStreamBlock.Reasoning
                    events += StreamEvent.ReasoningStart(index.toString())
                }
                val metadata = buildJsonObject {
                    reasoning["signature"]?.let { put("signature", it) }
                    reasoning["data"]?.let { put("redactedData", it) }
                }.takeIf { it.isNotEmpty() }?.let { ProviderMetadata.Raw(JsonObject(mapOf("bedrock" to it))) } ?: ProviderMetadata.None
                events += StreamEvent.ReasoningDelta(index.toString(), reasoning["text"]?.jsonPrimitive?.contentOrNull.orEmpty(), metadata)
            }
            (delta["toolUse"] as? JsonObject)?.get("input")?.jsonPrimitive?.contentOrNull?.let { inputDelta ->
                val block = blocks[index] as? BedrockStreamBlock.Tool ?: return@let
                block.input += inputDelta
                if (!block.isJsonResponseTool) events += StreamEvent.ToolInputDelta(block.id, inputDelta)
            }
        }
        (value["contentBlockStop"] as? JsonObject)?.let { stop ->
            val index = stop["contentBlockIndex"]?.jsonPrimitive?.intOrNull ?: return@let
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
                            inputJson = runCatching { aiSdkJson.parseToJsonElement(block.input.ifBlank { "{}" }) }.getOrElse { JsonPrimitive(block.input) },
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
                providerMetadata = if (metadata.isNotEmpty()) ProviderMetadata.Raw(JsonObject(mapOf("bedrock" to metadata))) else ProviderMetadata.None,
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
    data class Tool(val id: String, val name: String, var input: String, val isJsonResponseTool: Boolean) : BedrockStreamBlock()
}
