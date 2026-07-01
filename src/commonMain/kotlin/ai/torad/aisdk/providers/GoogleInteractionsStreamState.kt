@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.GoogleHttp.googlePostJson
import ai.torad.aisdk.providers.GoogleHttp.googleStreamSse
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsFinishReason
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsMetadata
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsRequestBody
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsResult
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsTerminal
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsUsage
import ai.torad.aisdk.providers.GoogleInteractions.googlePollInteraction
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class GoogleInteractionsStreamState(
    private val generateId: () -> String,
) {
    private sealed class OpenBlockState {
        abstract val id: String

        data class PendingModelOutput(override val id: String) : OpenBlockState()
        data class Text(override val id: String) : OpenBlockState()
        data class Image(
            override val id: String,
            private var dataValue: String? = null,
            private var mediaTypeValue: String? = null,
            private var uriValue: String? = null,
        ) : OpenBlockState() {
            fun data(): String? = dataValue
            fun mediaType(): String? = mediaTypeValue
            fun uri(): String? = uriValue
            fun update(data: String?, mediaType: String?, uri: String?) {
                data?.let { dataValue = it }
                mediaType?.let { mediaTypeValue = it }
                uri?.let { uriValue = it }
            }
            fun clearPayload() {
                dataValue = null
                uriValue = null
            }
        }

        data class Reasoning(
            override val id: String,
            private var signatureValue: String? = null,
        ) : OpenBlockState() {
            fun signature(): String? = signatureValue
            fun updateSignature(value: String?) {
                value?.let { signatureValue = it }
            }
        }

        data class FunctionCall(
            override val id: String,
            private var toolCallIdValue: String,
            val toolName: String,
            val arguments: StringBuilder = StringBuilder(),
            private var signatureValue: String? = null,
        ) : OpenBlockState() {
            fun toolCallId(): String = toolCallIdValue
            fun signature(): String? = signatureValue
            fun updateToolCallId(value: String?) {
                value?.let { toolCallIdValue = it }
            }
            fun updateSignature(value: String?) {
                value?.let { signatureValue = it }
            }
        }

        data class BuiltinToolCall(
            override val id: String,
            val blockType: String,
            private var toolCallIdValue: String,
            private var toolNameValue: String,
            private var argumentsValue: JsonElement = JsonObject(emptyMap()),
        ) : OpenBlockState() {
            fun toolCallId(): String = toolCallIdValue
            fun toolName(): String = toolNameValue
            fun arguments(): JsonElement = argumentsValue
            fun updateToolCallId(value: String?) {
                value?.let { toolCallIdValue = it }
            }
            fun updateToolName(value: String?) {
                value?.let { toolNameValue = it }
            }
            fun updateArguments(value: JsonElement?) {
                value?.let { argumentsValue = it }
            }
        }

        data class BuiltinToolResult(
            override val id: String,
            val blockType: String,
            private var callIdValue: String,
            private var toolNameValue: String,
            private var resultValue: JsonElement = JsonNull,
            private var isErrorValue: Boolean = false,
        ) : OpenBlockState() {
            fun callId(): String = callIdValue
            fun toolName(): String = toolNameValue
            fun result(): JsonElement = resultValue
            fun isError(): Boolean = isErrorValue
            fun updateCallId(value: String?) {
                value?.let { callIdValue = it }
            }
            fun updateToolName(value: String?) {
                value?.let { toolNameValue = it }
            }
            fun updateResult(value: JsonElement?) {
                value?.let { resultValue = it }
            }
            fun updateIsError(value: Boolean?) {
                value?.let { isErrorValue = it }
            }
        }

        data class Unknown(override val id: String) : OpenBlockState()
    }

    private val liveInteractionId = arrayOfNulls<String>(1)
    private val liveServiceTier = arrayOfNulls<String>(1)
    private var textId: String? = null
    private var textCounter = 0
    private var usage = Usage()
    private var finishReason = FinishReason.Other
    private var rawFinishReason: String? = null
    private var hasFunctionCall = false
    private var finished = false
    private val openBlocks = mutableMapOf<Int, OpenBlockState>()
    private val emittedSourceKeys = mutableSetOf<String>()

    fun accept(event: JsonObject): List<StreamEvent> = when ((event["event_type"] as? JsonPrimitive)?.contentOrNull) {
        "interaction.created" -> acceptInteractionCreated(event)
        "step.start" -> acceptStepStart(event)
        "step.delta" -> acceptStepDelta(event)
        "step.stop" -> acceptStepStop(event)
        "interaction.status_update",
        "interaction.in_progress",
        "interaction.requires_action",
        -> acceptStatus(event)
        "interaction.completed" -> acceptInteractionCompleted(event)
        "error" -> acceptError(event)
        else -> emptyList()
    }

    fun synthesize(response: JsonObject): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        val interactionId = (response["id"] as? JsonPrimitive)?.contentOrNull
        events += StreamEvent.ResponseMetadata(
            id = interactionId,
            modelId = (response["model"] as? JsonPrimitive)?.contentOrNull,
            body = response,
        )
        (JsonAccess.arr(response, "steps")).orEmpty().forEach { step ->
            (step as? JsonObject)?.let { events += acceptStep(it, interactionId) }
        }
        usage = googleInteractionsUsage(response["usage"])
        rawFinishReason = (response["status"] as? JsonPrimitive)?.contentOrNull
        finishReason = googleInteractionsFinishReason(rawFinishReason, hasFunctionCall)
        events += closeText()
        events += StreamEvent.Finish(
            1,
            finishReason,
            usage,
            googleInteractionsMetadata(interactionId = interactionId),
            rawFinishReason = rawFinishReason,
        )
        finished = true
        return events
    }

    private fun acceptInteractionCreated(event: JsonObject): List<StreamEvent> {
        val interaction = JsonAccess.obj(event, "interaction") ?: return emptyList()
        liveInteractionId[0] = normalizeInteractionId((interaction["id"] as? JsonPrimitive)?.contentOrNull)
        rawFinishReason = (interaction["status"] as? JsonPrimitive)?.contentOrNull ?: rawFinishReason
        finishReason = googleInteractionsFinishReason(rawFinishReason, hasFunctionCall)
        return listOf(
            StreamEvent.ResponseMetadata(
                id = liveInteractionId[0],
                modelId = (interaction["model"] as? JsonPrimitive)?.contentOrNull,
                body = interaction,
            ),
        )
    }

    private fun acceptStepStart(event: JsonObject): List<StreamEvent> {
        val index = eventIndex(event) ?: return emptyList()
        val step = JsonAccess.obj(event, "step") ?: return emptyList()
        val blockId = blockId(index)
        val stepType = (step["type"] as? JsonPrimitive)?.contentOrNull
        val events = mutableListOf<StreamEvent>()
        when {
            stepType == "model_output" -> {
                val initial = JsonAccess.arr(step, "content").orEmpty().firstOrNull() as? JsonObject
                when ((initial?.get("type") as? JsonPrimitive)?.contentOrNull) {
                    "text" -> {
                        openBlocks[index] = OpenBlockState.Text(blockId)
                        events += StreamEvent.TextStart(blockId, currentMetadata())
                        events += annotationSourceEvents(JsonAccess.arr(initial, "annotations"))
                    }
                    "image" -> openBlocks[index] = OpenBlockState.Image(
                        id = blockId,
                        dataValue = (initial["data"] as? JsonPrimitive)?.contentOrNull,
                        mediaTypeValue = (initial["mime_type"] as? JsonPrimitive)?.contentOrNull,
                        uriValue = (initial["uri"] as? JsonPrimitive)?.contentOrNull,
                    )
                    else -> openBlocks[index] = OpenBlockState.PendingModelOutput(blockId)
                }
            }
            stepType == "thought" -> {
                val signature = (step["signature"] as? JsonPrimitive)?.contentOrNull
                openBlocks[index] = OpenBlockState.Reasoning(blockId, signature)
                val metadata = currentMetadata(signature)
                events += StreamEvent.ReasoningStart(blockId, metadata)
                JsonAccess.arr(step, "summary").orEmpty().forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                        (obj["text"] as? JsonPrimitive)?.contentOrNull?.let {
                            events += StreamEvent.ReasoningDelta(blockId, it, metadata)
                        }
                    }
                }
            }
            stepType == "function_call" -> {
                hasFunctionCall = true
                val toolCallId = (step["id"] as? JsonPrimitive)?.contentOrNull ?: blockId
                val toolName = (step["name"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
                val signature = (step["signature"] as? JsonPrimitive)?.contentOrNull
                openBlocks[index] = OpenBlockState.FunctionCall(blockId, toolCallId, toolName, signatureValue = signature)
                events += StreamEvent.ToolInputStart(toolCallId, toolName, currentMetadata(signature))
            }
            stepType != null && stepType.endsWith("_call") -> {
                hasFunctionCall = true
                val toolName = if (stepType == "mcp_server_tool_call") {
                    (step["name"] as? JsonPrimitive)?.contentOrNull ?: "mcp_server_tool"
                } else {
                    stepType.removeSuffix("_call")
                }
                openBlocks[index] = OpenBlockState.BuiltinToolCall(
                    id = blockId,
                    blockType = stepType,
                    toolCallIdValue = (step["id"] as? JsonPrimitive)?.contentOrNull ?: blockId,
                    toolNameValue = toolName,
                    argumentsValue = step["arguments"] ?: JsonObject(emptyMap()),
                )
            }
            stepType != null && stepType.endsWith("_result") -> {
                val toolName = if (stepType == "mcp_server_tool_result") {
                    (step["name"] as? JsonPrimitive)?.contentOrNull ?: "mcp_server_tool"
                } else {
                    stepType.removeSuffix("_result")
                }
                openBlocks[index] = OpenBlockState.BuiltinToolResult(
                    id = blockId,
                    blockType = stepType,
                    callIdValue = (step["call_id"] as? JsonPrimitive)?.contentOrNull ?: blockId,
                    toolNameValue = toolName,
                    resultValue = step.getOrElse("result") { JsonNull },
                    isErrorValue = (step["is_error"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
            else -> openBlocks[index] = OpenBlockState.Unknown(blockId)
        }
        return events
    }

    private fun acceptStepDelta(event: JsonObject): List<StreamEvent> {
        val index = eventIndex(event) ?: return emptyList()
        var open = openBlocks[index] ?: return emptyList()
        val delta = JsonAccess.obj(event, "delta") ?: return emptyList()
        val deltaType = (delta["type"] as? JsonPrimitive)?.contentOrNull
        val events = mutableListOf<StreamEvent>()
        if (open is OpenBlockState.PendingModelOutput &&
            deltaType in setOf("text", "text_annotation", "text_annotation_delta")
        ) {
            val promoted = OpenBlockState.Text(open.id)
            openBlocks[index] = promoted
            open = promoted
            events += StreamEvent.TextStart(promoted.id, currentMetadata())
        }
        if (deltaType == "image" &&
            (open is OpenBlockState.PendingModelOutput || open is OpenBlockState.Text || open is OpenBlockState.Image)
        ) {
            events += imageEvent(
                data = (delta["data"] as? JsonPrimitive)?.contentOrNull,
                mediaType = (delta["mime_type"] as? JsonPrimitive)?.contentOrNull,
                uri = (delta["uri"] as? JsonPrimitive)?.contentOrNull,
            )
            if (open is OpenBlockState.Image) {
                open.clearPayload()
            }
            return events
        }
        when (open) {
            is OpenBlockState.Text -> when (deltaType) {
                "text" -> (delta["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    events += StreamEvent.TextDelta(open.id, it, currentMetadata())
                }
                "text_annotation", "text_annotation_delta" ->
                    events += annotationSourceEvents(JsonAccess.arr(delta, "annotations"))
            }
            is OpenBlockState.Image -> if (deltaType == "image") {
                open.update(
                    data = (delta["data"] as? JsonPrimitive)?.contentOrNull,
                    mediaType = (delta["mime_type"] as? JsonPrimitive)?.contentOrNull,
                    uri = (delta["uri"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            is OpenBlockState.Reasoning -> when (deltaType) {
                "thought_summary" -> {
                    val content = JsonAccess.obj(delta, "content")
                    if ((content?.get("type") as? JsonPrimitive)?.contentOrNull == "text") {
                        (content["text"] as? JsonPrimitive)?.contentOrNull?.let {
                            events += StreamEvent.ReasoningDelta(open.id, it, currentMetadata(open.signature()))
                        }
                    }
                }
                "thought_signature" -> open.updateSignature((delta["signature"] as? JsonPrimitive)?.contentOrNull)
            }
            is OpenBlockState.FunctionCall -> if (deltaType == "arguments_delta") {
                val slice = (delta["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (slice.isNotEmpty()) {
                    open.arguments.append(slice)
                    events += StreamEvent.ToolInputDelta(open.toolCallId(), slice, currentMetadata(open.signature()))
                }
                open.updateToolCallId((delta["id"] as? JsonPrimitive)?.contentOrNull)
                open.updateSignature((delta["signature"] as? JsonPrimitive)?.contentOrNull)
                hasFunctionCall = true
            }
            is OpenBlockState.BuiltinToolCall -> if (deltaType == open.blockType) {
                open.updateToolCallId((delta["id"] as? JsonPrimitive)?.contentOrNull)
                open.updateArguments(delta["arguments"])
                if (open.blockType == "mcp_server_tool_call") {
                    open.updateToolName((delta["name"] as? JsonPrimitive)?.contentOrNull)
                }
            }
            is OpenBlockState.BuiltinToolResult -> if (deltaType == open.blockType) {
                open.updateCallId((delta["call_id"] as? JsonPrimitive)?.contentOrNull)
                open.updateResult(delta["result"])
                open.updateIsError((delta["is_error"] as? JsonPrimitive)?.booleanOrNull)
                if (open.blockType == "mcp_server_tool_result") {
                    open.updateToolName((delta["name"] as? JsonPrimitive)?.contentOrNull)
                }
            }
            is OpenBlockState.PendingModelOutput,
            is OpenBlockState.Unknown,
            -> Unit
        }
        return events
    }

    private fun acceptStepStop(event: JsonObject): List<StreamEvent> {
        val index = eventIndex(event) ?: return emptyList()
        val open = openBlocks.remove(index) ?: return emptyList()
        return closeOpenBlock(open)
    }

    private fun acceptStatus(event: JsonObject): List<StreamEvent> {
        normalizeInteractionId((event["interaction_id"] as? JsonPrimitive)?.contentOrNull)?.let { liveInteractionId[0] = it }
        rawFinishReason = (event["status"] as? JsonPrimitive)?.contentOrNull ?: when ((event["event_type"] as? JsonPrimitive)?.contentOrNull) {
            "interaction.requires_action" -> "requires_action"
            else -> rawFinishReason ?: "in_progress"
        }
        finishReason = googleInteractionsFinishReason(rawFinishReason, hasFunctionCall)
        return emptyList()
    }

    private fun acceptInteractionCompleted(event: JsonObject): List<StreamEvent> {
        val interaction = JsonAccess.obj(event, "interaction") ?: JsonObject(emptyMap())
        normalizeInteractionId((interaction["id"] as? JsonPrimitive)?.contentOrNull)?.let { liveInteractionId[0] = it }
        liveServiceTier[0] = (interaction["service_tier"] as? JsonPrimitive)?.contentOrNull ?: liveServiceTier[0]
        usage = googleInteractionsUsage(interaction["usage"])
        rawFinishReason = (interaction["status"] as? JsonPrimitive)?.contentOrNull ?: rawFinishReason
        finishReason = googleInteractionsFinishReason(rawFinishReason, hasFunctionCall)
        val events = mutableListOf<StreamEvent>()
        events += closeOpenBlocks()
        events += closeText()
        events += StreamEvent.Finish(
            1,
            finishReason,
            usage,
            finishMetadata(),
            rawFinishReason = rawFinishReason,
        )
        finished = true
        return events
    }

    private fun acceptError(event: JsonObject): List<StreamEvent> {
        rawFinishReason = "failed"
        finishReason = FinishReason.Error
        val error = JsonAccess.obj(event, "error")
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (error?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: "Google Interactions stream error"
        return listOf(StreamEvent.Error(message))
    }

    private fun acceptStep(step: JsonObject, interactionId: String?): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        when (val type = (step["type"] as? JsonPrimitive)?.contentOrNull) {
            "model_output" -> {
                (JsonAccess.arr(step, "content")).orEmpty().forEachIndexed { index, blockElement ->
                    val block = try {
                        WireDecoder.objectValue(blockElement, "google", "interactions stream step", "$.content[$index]")
                    } catch (error: WireDecodeException) {
                        return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                    }
                    when (val blockType = WireDecoder.optionalString(block, "type", "google", "interactions stream step", "$.content[$index]")) {
                        "text" -> {
                            val id = textId ?: (textCounter++).toString().also {
                                textId = it
                                events += StreamEvent.TextStart(it, googleInteractionsMetadata(interactionId = interactionId))
                            }
                            val text = try {
                                WireDecoder.requiredString(block, "text", "google", "interactions stream step", "$.content[$index]")
                            } catch (error: WireDecodeException) {
                                return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                            }
                            events += StreamEvent.TextDelta(id, text, googleInteractionsMetadata(interactionId = interactionId))
                        }
                        "image" -> events += StreamEvent.FilePart(
                            id = IdGenerator.generate(),
                            mediaType = (block["mime_type"] as? JsonPrimitive)?.contentOrNull ?: "image/png",
                            base64 = try {
                                WireDecoder.requiredString(block, "data", "google", "interactions stream step", "$.content[$index]")
                            } catch (error: WireDecodeException) {
                                return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                            },
                            providerMetadata = googleInteractionsMetadata(interactionId = interactionId),
                        )
                        null -> return listOf(StreamEvent.Error("Google stream protocol error: model_output content block missing type."))
                        else -> return listOf(StreamEvent.Error("Google stream protocol error: unsupported model_output content block type `$blockType`."))
                    }
                }
            }
            "thought" -> {
                val id = IdGenerator.generate()
                val metadata = googleInteractionsMetadata(
                    signature = (step["signature"] as? JsonPrimitive)?.contentOrNull,
                    interactionId = interactionId,
                )
                events += StreamEvent.ReasoningStart(id, metadata)
                events += StreamEvent.ReasoningDelta(
                    id,
                    (JsonAccess.arr(step, "summary")).orEmpty()
                        .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                        .joinToString("\n"),
                    metadata,
                )
                events += StreamEvent.ReasoningEnd(id, metadata)
            }
            "function_call" -> {
                hasFunctionCall = true
                val id = (step["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate()
                val name = try {
                    WireDecoder.requiredString(step, "name", "google", "interactions stream step")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                val input = step["arguments"] ?: JsonObject(emptyMap())
                val metadata = googleInteractionsMetadata(
                    signature = (step["signature"] as? JsonPrimitive)?.contentOrNull,
                    interactionId = interactionId,
                )
                events += StreamEvent.ToolInputStart(id, name, metadata)
                events += StreamEvent.ToolInputDelta(id, input.toString(), metadata)
                events += StreamEvent.ToolInputEnd(id, metadata)
                events += StreamEvent.ToolCall(id, name, input, metadata)
            }
            else -> if (type != null && type.endsWith("_call")) {
                hasFunctionCall = true
                val id = (step["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate()
                val name = if (type == "mcp_server_tool_call") {
                    WireDecoder.optionalString(step, "name", "google", "interactions stream step") ?: "mcp_server_tool"
                } else {
                    type.removeSuffix("_call")
                }
                val input = step["arguments"] ?: JsonObject(emptyMap())
                val metadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) })))
                events += StreamEvent.ToolInputStart(id, name, metadata)
                events += StreamEvent.ToolInputDelta(id, input.toString(), metadata)
                events += StreamEvent.ToolInputEnd(id, metadata)
                events += StreamEvent.ToolCall(id, name, input, metadata)
            }
        }
        return events
    }

    fun finishIfNeeded(): List<StreamEvent> =
        if (finished) {
            emptyList()
        } else {
            closeOpenBlocks() + closeText() + StreamEvent.Finish(1, finishReason, usage, finishMetadata(), rawFinishReason = rawFinishReason)
        }

    private fun closeOpenBlocks(): List<StreamEvent> {
        val events = openBlocks.entries.sortedBy { it.key }.flatMap { closeOpenBlock(it.value) }
        openBlocks.clear()
        return events
    }

    private fun closeOpenBlock(open: OpenBlockState): List<StreamEvent> = when (open) {
        is OpenBlockState.Text -> listOf(StreamEvent.TextEnd(open.id, currentMetadata()))
        is OpenBlockState.Reasoning -> listOf(StreamEvent.ReasoningEnd(open.id, currentMetadata(open.signature())))
        is OpenBlockState.Image -> imageEvent(open.data(), open.mediaType(), open.uri())
        is OpenBlockState.FunctionCall -> closeFunctionCall(open)
        is OpenBlockState.BuiltinToolCall -> listOf(
            StreamEvent.ToolCall(
                toolCallId = open.toolCallId(),
                toolName = open.toolName(),
                inputJson = open.arguments(),
                providerMetadata = providerExecutedMetadata(),
            ),
        )
        is OpenBlockState.BuiltinToolResult -> listOf(
            StreamEvent.ToolResult(
                toolCallId = open.callId(),
                toolName = open.toolName(),
                outputJson = open.result(),
                isError = open.isError(),
                providerMetadata = providerExecutedMetadata(),
            ),
        )
        is OpenBlockState.PendingModelOutput,
        is OpenBlockState.Unknown,
        -> emptyList()
    }

    private fun closeFunctionCall(open: OpenBlockState.FunctionCall): List<StreamEvent> {
        val inputText = open.arguments.toString().ifBlank { "{}" }
        val metadata = currentMetadata(open.signature())
        val input = try {
            aiSdkJson.parseToJsonElement(inputText)
        } catch (error: Throwable) {
            return listOf(StreamEvent.Error("Google stream protocol error: function_call arguments were not valid JSON: ${error.message}"))
        }
        return listOf(
            StreamEvent.ToolInputEnd(open.toolCallId(), metadata),
            StreamEvent.ToolCall(open.toolCallId(), open.toolName, input, metadata),
        )
    }

    private fun imageEvent(data: String?, mediaType: String?, uri: String?): List<StreamEvent> {
        val extra = uri?.takeIf { it.isNotBlank() }?.let { mapOf("imageUri" to JsonPrimitive(it)) }.orEmpty()
        return if (!data.isNullOrEmpty() || !uri.isNullOrEmpty()) {
            listOf(
                StreamEvent.FilePart(
                    id = IdGenerator.generate(),
                    mediaType = mediaType ?: "image/png",
                    base64 = data.orEmpty(),
                    providerMetadata = currentMetadata(extra = extra),
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun annotationSourceEvents(annotations: JsonArray?): List<StreamEvent.SourcePart> {
        val metadata = currentMetadata().toMap().ifEmpty { null }
        return GoogleInteractions.googleInteractionsAnnotationSources(annotations, generateId, metadata).mapNotNull { source ->
            val key = when (source.sourceType) {
                StreamEvent.SourcePart.SourceType.Url -> "url:${source.url}"
                StreamEvent.SourcePart.SourceType.Document -> "doc:${source.url ?: source.title}"
            }
            if (!emittedSourceKeys.add(key)) return@mapNotNull null
            StreamEvent.SourcePart(
                id = source.sourceId ?: IdGenerator.generate(),
                sourceType = source.sourceType,
                url = source.url,
                title = source.title,
                mediaType = source.mediaType,
                providerMetadata = source.providerMetadata,
            )
        }
    }

    private fun eventIndex(event: JsonObject): Int? = (event["index"] as? JsonPrimitive)?.intOrNull

    private fun blockId(index: Int): String = "${liveInteractionId[0] ?: "interaction"}:$index"

    private fun currentMetadata(
        signature: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): ProviderMetadata = googleInteractionsMetadata(signature = signature, interactionId = liveInteractionId[0], extra = extra)

    private fun finishMetadata(): ProviderMetadata = googleInteractionsMetadata(
        interactionId = liveInteractionId[0],
        extra = liveServiceTier[0]?.let { mapOf("serviceTier" to JsonPrimitive(it)) }.orEmpty(),
    )

    private fun providerExecutedMetadata(): ProviderMetadata =
        ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) })))

    private fun normalizeInteractionId(value: String?): String? = value?.takeIf { it.isNotBlank() }

    private fun closeText(): List<StreamEvent> =
        textId?.let {
            textId = null
            listOf(StreamEvent.TextEnd(it))
        }.orEmpty()
}

