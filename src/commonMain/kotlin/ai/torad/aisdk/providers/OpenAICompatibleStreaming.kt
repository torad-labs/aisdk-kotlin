package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.OpenAICompatibleWire.isParsableOpenAIJson
import ai.torad.aisdk.providers.OpenAICompatibleWire.openAIFinishReason
import ai.torad.aisdk.providers.OpenAICompatibleWire.openAIUsage
import ai.torad.aisdk.providers.OpenAICompatibleWire.parseOpenAIToolInput
import ai.torad.aisdk.providers.OpenAICompatibleWire.streamResponseMetadata
import ai.torad.aisdk.providers.OpenAICompatibleWire.thoughtSignatureMetadata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class OpenAIChatStreamState(
    private val provider: String,
    private val providerKey: String,
    private val convertUsage: ((JsonElement?) -> Usage)? = null,
) {
    private val toolCalls = linkedMapOf<Int, StreamingToolCall>()
    private var finishReason = FinishReason.Other
    private var rawFinishReason: String? = null
    private var usage = Usage()
    private var activeReasoning = false
    private var activeText = false
    private var emittedResponseMetadata = false

    fun accept(value: JsonElement): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        val obj = WireDecoder.objectValue(value, provider, "chat stream event")
        if (!emittedResponseMetadata) {
            streamResponseMetadata(obj)?.let {
                events += it
                emittedResponseMetadata = true
            }
        }
        if (obj["error"] != null) {
            events += StreamEvent.Error(
                obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: obj["error"]?.jsonPrimitive?.contentOrNull
                    ?: "OpenAI-compatible stream error",
            )
            finishReason = FinishReason.Error
            return events
        }
        obj["usage"]?.let { usage = convertUsage?.invoke(it) ?: openAIUsage(it) }
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return events
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
            finishReason = openAIFinishReason(it)
            rawFinishReason = it
        }
        val delta = choice["delta"]?.jsonObject ?: return events
        val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
            ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull
        if (!reasoning.isNullOrEmpty()) {
            if (!activeReasoning) {
                events += StreamEvent.ReasoningStart("reasoning-0")
                activeReasoning = true
            }
            events += StreamEvent.ReasoningDelta("reasoning-0", reasoning)
        }
        val text = delta["content"]?.jsonPrimitive?.contentOrNull
        if (!text.isNullOrEmpty()) {
            if (activeReasoning) {
                events += StreamEvent.ReasoningEnd("reasoning-0")
                activeReasoning = false
            }
            if (!activeText) {
                events += StreamEvent.TextStart("txt-0")
                activeText = true
            }
            events += StreamEvent.TextDelta("txt-0", text)
        }
        val calls = WireDecoder.optionalArray(delta, "tool_calls", provider, "chat stream event", "$.choices[0].delta").orEmpty()
        if (calls.isNotEmpty() && activeReasoning) {
            events += StreamEvent.ReasoningEnd("reasoning-0")
            activeReasoning = false
        }
        for (call in calls) {
            events += acceptToolCallDelta(call)
        }
        return events
    }

    fun finish(): List<StreamEvent> = buildList {
        if (activeReasoning) add(StreamEvent.ReasoningEnd("reasoning-0"))
        if (activeText) add(StreamEvent.TextEnd("txt-0"))
        for (toolCall in toolCalls.values.filter { !it.finished }) {
            add(StreamEvent.ToolInputEnd(toolCall.id))
            add(
                StreamEvent.ToolCall(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    inputJson = parseOpenAIToolInput(toolCall.arguments),
                    providerMetadata = toolCall.providerMetadata,
                ),
            )
            toolCall.finished = true
        }
        add(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = finishReason,
                usage = usage,
                rawFinishReason = rawFinishReason,
            ),
        )
    }

    private fun acceptToolCallDelta(value: JsonElement): List<StreamEvent> {
        val obj = WireDecoder.objectValue(value, provider, "chat stream tool call")
        val index = WireDecoder.optionalInt(obj, "index", provider, "chat stream tool call") ?: toolCalls.size
        val function = obj["function"]?.let {
            WireDecoder.objectValue(it, provider, "chat stream tool call", "$.function")
        } ?: JsonObject(emptyMap())
        val existing = toolCalls[index]
        if (existing == null) {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate("call")
            val name = WireDecoder.requiredString(function, "name", provider, "chat stream tool call", "$.function")
            val arguments = WireDecoder.optionalString(function, "arguments", provider, "chat stream tool call", "$.function").orEmpty()
            val metadata = thoughtSignatureMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(mapOf(providerKey to JsonObject(it)))) } ?: ProviderMetadata.None
            val toolCall = StreamingToolCall(id, name, arguments, metadata)
            toolCalls[index] = toolCall
            return buildList {
                add(StreamEvent.ToolInputStart(id, name, providerMetadata = metadata))
                if (arguments.isNotEmpty()) add(StreamEvent.ToolInputDelta(id, arguments, providerMetadata = metadata))
                if (isParsableOpenAIJson(arguments)) {
                    add(StreamEvent.ToolInputEnd(id, providerMetadata = metadata))
                    add(StreamEvent.ToolCall(id, name, parseOpenAIToolInput(arguments), providerMetadata = metadata))
                    toolCall.finished = true
                }
            }
        }
        if (existing.finished) return emptyList()
        val delta = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
        existing.arguments += delta
        return buildList {
            if (delta.isNotEmpty()) add(StreamEvent.ToolInputDelta(existing.id, delta, providerMetadata = existing.providerMetadata))
            if (isParsableOpenAIJson(existing.arguments)) {
                add(StreamEvent.ToolInputEnd(existing.id, providerMetadata = existing.providerMetadata))
                add(
                    StreamEvent.ToolCall(
                        existing.id,
                        existing.name,
                        parseOpenAIToolInput(existing.arguments),
                        providerMetadata = existing.providerMetadata,
                    ),
                )
                existing.finished = true
            }
        }
    }
}

private data class StreamingToolCall(
    val id: String,
    val name: String,
    var arguments: String,
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    var finished: Boolean = false,
)
