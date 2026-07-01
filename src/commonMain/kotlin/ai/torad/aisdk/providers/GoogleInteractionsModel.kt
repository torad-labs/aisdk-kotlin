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

internal class GoogleInteractionsLanguageModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    private val modelInput: GoogleInteractionsModelInput,
) : LanguageModel {
    override val modelId: String = modelInput.name
    override val provider: String = "${settings.name}.interactions"
    override val supportedUrls: Map<String, List<String>> = mapOf(
        "image/*" to listOf("^https?://.+"),
        "application/pdf" to listOf("^https?://.+"),
        "audio/*" to listOf("^https?://.+"),
        "video/*" to listOf(
            "^https?://(www\\.)?youtube\\.com/watch\\?v=.+",
            "^https?://youtu\\.be/.+",
            "^gs://.+",
        ),
    )

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = googleInteractionsRequestBody(modelInput, params, stream = false)
        val response = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/interactions",
            body = prepared.body,
            headers = settings.googleInteractionsHeaders(params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        var body = response.value.jsonObject
        if (modelInput !is GoogleInteractionsModelInput.Model && !googleInteractionsTerminal((body["status"] as? JsonPrimitive)?.contentOrNull)) {
            body = googlePollInteraction(
                client = client,
                settings = settings,
                interactionId = (body["id"] as? JsonPrimitive)?.contentOrNull
                    ?: throw InvalidResponseDataError(body, "google.interactions: background response did not include an interaction id."),
                headers = settings.googleInteractionsHeaders(params.headers),
                abortSignal = params.abortSignal,
                timeoutMillis = prepared.pollingTimeoutMillis,
            ).value.jsonObject
        }
        return googleInteractionsResult(body, prepared.body, response.headers, response.value, prepared.warnings, settings)
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = googleInteractionsRequestBody(modelInput, params, stream = true)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = GoogleInteractionsStreamState(settings.generateId)
        if (prepared.isBackground) {
            val post = googlePostJson(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/interactions",
                body = prepared.body,
                headers = settings.googleInteractionsHeaders(params.headers),
                abortSignal = params.abortSignal,
                parseJson = true,
            )
            val postBody = post.value.jsonObject
            if (googleInteractionsTerminal((postBody["status"] as? JsonPrimitive)?.contentOrNull)) {
                state.synthesize(postBody).forEach { emit(it) }
            } else {
                val interactionId = (postBody["id"] as? JsonPrimitive)?.contentOrNull
                    ?: throw InvalidResponseDataError(postBody, "google.interactions: background response did not include an interaction id.")
                val rawLines = googleStreamSseGet(
                    client = client,
                    url = "${settings.baseURL.trimEnd('/')}/interactions/$interactionId?stream=true",
                    headers = settings.googleInteractionsHeaders(params.headers) + (HttpHeaders.Accept to "text/event-stream"),
                    abortSignal = params.abortSignal,
                )
                with(GoogleInteractions) { collectGoogleInteractions(rawLines, state) }
            }
        } else {
            val rawLines = googleStreamSse(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/interactions",
                body = prepared.body,
                headers = settings.googleInteractionsHeaders(params.headers) + (HttpHeaders.Accept to "text/event-stream"),
                abortSignal = params.abortSignal,
            )
            with(GoogleInteractions) { collectGoogleInteractions(rawLines, state) }
        }
        state.finishIfNeeded().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = googleInteractionsRequestBody(modelInput, params, stream = true)
        return LanguageModelStreamResult(stream = stream(params), request = LanguageModelRequestMetadata(prepared.body))
    }

    /** Streaming counterpart of the background-interaction GET poll: reads the SSE body incrementally. */
    private fun googleStreamSseGet(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
    ): Flow<String> = flow {
        abortSignal.throwIfAborted()
        emitAll(
            HttpTransport.streamSse(
                client = client,
                url = url,
                method = HttpMethod.Get,
                headers = headers,
                body = null,
                json = aiSdkJson,
                errorMessage = GoogleHttp.googleErrorExtractor,
            ),
        )
    }
}

internal data class GoogleInteractionsPreparedRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
    val pollingTimeoutMillis: Long?,
    val isBackground: Boolean,
)

internal data class GoogleInteractionsConvertedInput(
    val input: JsonArray,
    val systemInstruction: String?,
    val warnings: List<CallWarning>,
)

internal data class GoogleInteractionsParsedContent(
    val content: List<ContentPart>,
    val hasFunctionCall: Boolean,
)

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

internal object GoogleInteractions {
    fun googleInteractionsRequestBody(
    input: GoogleInteractionsModelInput,
    params: LanguageModelCallParams,
    stream: Boolean,
): GoogleInteractionsPreparedRequest {
    val warnings = mutableListOf<CallWarning>()
    val options = JsonAccess.obj(params.providerOptions.toMap(), "google") ?: JsonObject(emptyMap())
    val isAgent = input !is GoogleInteractionsModelInput.Model
    val isBackground = (options["background"] as? JsonPrimitive)?.booleanOrNull == true
    val converted = googleInteractionsInput(
        messages = params.messages,
        mediaResolution = (options["mediaResolution"] as? JsonPrimitive)?.contentOrNull,
    )
    warnings += converted.warnings

    val responseFormat = googleInteractionsResponseFormat(params.responseFormat, options, isAgent, warnings)
    val tools = if (isAgent) {
        if (params.tools.isNotEmpty()) {
            warnings += CallWarning("other", "google.interactions: tools are not supported when an agent is set; tools will be omitted from the request body.")
        }
        JsonArray(emptyList())
    } else {
        googleInteractionsTools(params.tools, warnings)
    }
    val hasFunctionTool = params.tools.any { !it.providerExecuted }
    val toolChoice = if (isAgent || !hasFunctionTool) null else googleInteractionsToolChoice(params.toolChoice)
    val generationConfig = if (isAgent) {
        val dropped = buildList {
            if (params.temperature != null) add("temperature")
            if (params.topP != null) add("topP")
            if (params.seed != null) add("seed")
            if (params.stopSequences.isNotEmpty()) add("stopSequences")
            if (params.maxOutputTokens != null) add("maxOutputTokens")
            if (options["thinkingLevel"] != null) add("thinkingLevel")
            if (options["thinkingSummaries"] != null) add("thinkingSummaries")
            if (options["imageConfig"] != null) add("imageConfig")
        }
        if (dropped.isNotEmpty()) {
            warnings += CallWarning("other", "google.interactions: ${dropped.joinToString()} are not supported when an agent is set; use providerOptions.google.agentConfig instead. Dropped from the request body.")
        }
        null
    } else {
        buildJsonObject {
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            if (params.stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(params.stopSequences.map(::JsonPrimitive)))
            params.maxOutputTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
            options["thinkingLevel"]?.let { put("thinking_level", it) }
            options["thinkingSummaries"]?.let { put("thinking_summaries", it) }
            toolChoice?.let { put("tool_choice", it) }
        }.takeIf { it.isNotEmpty() }
    }

    val systemInstruction = converted.systemInstruction
        ?: (options["systemInstruction"] as? JsonPrimitive)?.contentOrNull

    return GoogleInteractionsPreparedRequest(
        body = buildJsonObject {
            when (input) {
                is GoogleInteractionsModelInput.Model -> put("model", JsonPrimitive(input.name))
                is GoogleInteractionsModelInput.Agent -> put("agent", JsonPrimitive(input.name))
                is GoogleInteractionsModelInput.ManagedAgent -> put("agent", JsonPrimitive(input.name))
            }
            put("input", converted.input)
            systemInstruction?.let { put("system_instruction", JsonPrimitive(it)) }
            if (tools.isNotEmpty()) put("tools", tools)
            if (responseFormat.isNotEmpty()) put("response_format", responseFormat)
            options["responseModalities"]?.let { put("response_modalities", it) }
            options["previousInteractionId"]?.let { put("previous_interaction_id", it) }
            options["serviceTier"]?.let { put("service_tier", it) }
            options["store"]?.let { put("store", it) }
            generationConfig?.let { put("generation_config", it) }
            googleInteractionsAgentConfig(options)?.let { put("agent_config", it) }
            googleInteractionsEnvironment(options["environment"], isAgent, warnings)?.let { put("environment", it) }
            options["background"]?.let { put("background", it) }
            if (stream && !isBackground) put("stream", JsonPrimitive(true))
        },
        warnings = warnings,
        pollingTimeoutMillis = (options["pollingTimeoutMs"] as? JsonPrimitive)?.intOrNull?.toLong(),
        isBackground = isBackground,
    )
}
    fun googleInteractionsInput(
    messages: List<ModelMessage>,
    mediaResolution: String?,
): GoogleInteractionsConvertedInput {
    val warnings = mutableListOf<CallWarning>()
    val steps = mutableListOf<JsonElement>()
    val systemTexts = mutableListOf<String>()
    for (message in messages) {
        when (message.role) {
            MessageRole.System -> systemTexts += message.content.filterIsInstance<ContentPart.Text>().map { it.text }
            MessageRole.User -> {
                val content = message.content.mapNotNull { part ->
                    when (part) {
                        is ContentPart.Text -> buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(part.text))
                        }
                        is ContentPart.File -> googleInteractionsFileContent(part.mediaType, part.base64, part.url, mediaResolution)
                        is ContentPart.Image -> googleInteractionsFileContent(part.mediaType, part.base64, part.url, mediaResolution)
                        is ContentPart.Reasoning,
                        is ContentPart.ToolCall,
                        is ContentPart.ToolResult,
                        is ContentPart.ToolApprovalRequest,
                        is ContentPart.ToolApprovalResponse,
                        is ContentPart.Source,
                        is ContentPart.Raw,
                        -> null
                    }
                }
                if (content.isNotEmpty()) steps += buildJsonObject {
                    put("type", JsonPrimitive("user_input"))
                    put("content", JsonArray(content))
                }
            }
            MessageRole.Assistant -> {
                val pending = mutableListOf<JsonElement>()
                fun flush() {
                    if (pending.isNotEmpty()) {
                        steps += buildJsonObject {
                            put("type", JsonPrimitive("model_output"))
                            put("content", JsonArray(pending.toList()))
                        }
                        pending.clear()
                    }
                }
                for (part in message.content) {
                    when (part) {
                        is ContentPart.Text -> pending += buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(part.text))
                        }
                        is ContentPart.File -> pending += googleInteractionsFileContent(part.mediaType, part.base64, part.url, mediaResolution)
                        is ContentPart.Image -> pending += googleInteractionsFileContent(part.mediaType, part.base64, part.url, mediaResolution)
                        is ContentPart.Reasoning -> {
                            flush()
                            steps += buildJsonObject {
                                put("type", JsonPrimitive("thought"))
                                googleInteractionsSignature(part.providerMetadata.toMap())?.let { put("signature", JsonPrimitive(it)) }
                                if (part.text.isNotBlank()) {
                                    put(
                                        "summary",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("text"))
                                                    put("text", JsonPrimitive(part.text))
                                                },
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                        is ContentPart.ToolCall -> {
                            flush()
                            steps += buildJsonObject {
                                put("type", JsonPrimitive("function_call"))
                                put("id", JsonPrimitive(part.toolCallId))
                                put("name", JsonPrimitive(part.toolName))
                                put("arguments", part.input)
                                googleInteractionsSignature(part.providerMetadata.toMap())?.let { put("signature", JsonPrimitive(it)) }
                            }
                        }
                        is ContentPart.ToolResult,
                        is ContentPart.ToolApprovalRequest,
                        is ContentPart.ToolApprovalResponse,
                        is ContentPart.Source,
                        is ContentPart.Raw,
                        -> warnings += CallWarning("other", "google.interactions: unsupported assistant content part; part dropped.")
                    }
                }
                flush()
            }
            MessageRole.Tool -> {
                val content = message.content.filterIsInstance<ContentPart.ToolResult>().map { part ->
                    buildJsonObject {
                        put("type", JsonPrimitive("function_result"))
                        put("call_id", JsonPrimitive(part.toolCallId))
                        put("name", JsonPrimitive(part.toolName))
                        put("result", part.modelVisible)
                        put("is_error", JsonPrimitive(part.isError))
                        googleInteractionsSignature(part.providerMetadata.toMap())?.let { put("signature", JsonPrimitive(it)) }
                    }
                }
                if (content.isNotEmpty()) steps += buildJsonObject {
                    put("type", JsonPrimitive("user_input"))
                    put("content", JsonArray(content))
                }
            }
        }
    }
    return GoogleInteractionsConvertedInput(
        input = JsonArray(steps),
        systemInstruction = systemTexts.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        warnings = warnings,
    )
}

    fun googleInteractionsFileContent(mediaType: String, base64: String, url: String?, mediaResolution: String?): JsonObject {
    val type = when (mediaType.substringBefore('/')) {
        "image" -> "image"
        "audio" -> "audio"
        "video" -> "video"
        else -> "document"
    }
    return buildJsonObject {
        put("type", JsonPrimitive(type))
        if (url != null) {
            put("uri", JsonPrimitive(url))
        } else {
            put("data", JsonPrimitive(base64))
        }
        put("mime_type", JsonPrimitive(mediaType))
        if ((type == "image" || type == "video") && mediaResolution != null) {
            put("resolution", JsonPrimitive(mediaResolution))
        }
    }
}

    // Map one caller responseFormat entry, skipping a non-object element (Wave 7b). Extracted so
    // googleInteractionsResponseFormat stays under the cyclomatic-complexity threshold.
    private fun googleInteractionsFormatEntry(entry: JsonElement): JsonObject? {
    val obj = entry as? JsonObject ?: return null
    return buildJsonObject {
        obj["type"]?.let { put("type", it) }
        obj["mimeType"]?.let { put("mime_type", it) }
        obj["schema"]?.let { put("schema", it) }
        obj["aspectRatio"]?.let { put("aspect_ratio", it) }
        obj["imageSize"]?.let { put("image_size", it) }
    }
    }

    fun googleInteractionsResponseFormat(
    responseFormat: ResponseFormat,
    options: JsonObject,
    isAgent: Boolean,
    warnings: MutableList<CallWarning>,
): JsonArray {
    val entries = mutableListOf<JsonElement>()
    if (responseFormat is ResponseFormat.Json) {
        if (isAgent) {
            warnings += CallWarning("other", "google.interactions: structured output is not supported when an agent is set; responseFormat will be ignored.")
        } else {
            entries += buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("mime_type", JsonPrimitive("application/json"))
                responseFormat.schemaJson?.let { put("schema", SchemaSanitizer.stripUnsupportedSchemaKeys(it, dropAdditionalProperties = true, googleOpenApi = true)) }
            }
        }
    }
    entries += (JsonAccess.arr(options, "responseFormat")).orEmpty().mapNotNull(::googleInteractionsFormatEntry)
    (JsonAccess.obj(options, "imageConfig"))?.let { image ->
        warnings += CallWarning("other", "google.interactions: providerOptions.google.imageConfig is deprecated. Use providerOptions.google.responseFormat with an image entry instead.")
        if (entries.none { (it.jsonObject["type"] as? JsonPrimitive)?.contentOrNull == "image" }) {
            entries += buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("mime_type", JsonPrimitive("image/png"))
                image["aspectRatio"]?.let { put("aspect_ratio", it) }
                image["imageSize"]?.let { put("image_size", it) }
            }
        }
    }
    return JsonArray(entries)
}

    fun googleInteractionsTools(
    tools: List<LanguageModelTool>,
    warnings: MutableList<CallWarning>,
): JsonArray = JsonArray(
    tools.mapNotNull { tool ->
        if (!tool.providerExecuted) {
            buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(tool.name))
                if (tool.description.isNotBlank()) put("description", JsonPrimitive(tool.description))
                put(
                    "parameters",
                    SchemaSanitizer.stripUnsupportedSchemaKeys(
                        aiSdkJson.parseToJsonElement(tool.parametersSchemaJson),
                        dropAdditionalProperties = true,
                        googleOpenApi = true,
                    ),
                )
            }
        } else {
            when ((tool.metadata["providerToolId"] as? JsonPrimitive)?.contentOrNull) {
                "google.google_search" -> buildJsonObject { put("type", JsonPrimitive("google_search")) }
                "google.code_execution" -> buildJsonObject { put("type", JsonPrimitive("code_execution")) }
                "google.url_context" -> buildJsonObject { put("type", JsonPrimitive("url_context")) }
                "google.file_search" -> buildJsonObject { put("type", JsonPrimitive("file_search")) }
                "google.google_maps" -> buildJsonObject { put("type", JsonPrimitive("google_maps")) }
                "google.mcp_server" -> buildJsonObject { put("type", JsonPrimitive("mcp_server")) }
                "google.retrieval" -> buildJsonObject {
                    put("type", JsonPrimitive("retrieval"))
                    put("retrieval_types", JsonArray(listOf(JsonPrimitive("vertex_ai_search"))))
                }
                else -> null.also {
                    warnings += CallWarning("unsupported", "provider-defined tool ${tool.name} is not supported by google.interactions; tool dropped.")
                }
            }
        }
    },
)

    fun googleInteractionsToolChoice(choice: ToolChoice): JsonElement? = when (choice) {
    ToolChoice.Auto -> JsonPrimitive("auto")
    ToolChoice.Required -> JsonPrimitive("any")
    ToolChoice.None -> JsonPrimitive("none")
    is ToolChoice.Specific -> buildJsonObject {
        put(
            "allowed_tools",
            buildJsonObject {
                put("mode", JsonPrimitive("validated"))
                put("tools", JsonArray(listOf(JsonPrimitive(choice.toolName))))
            },
        )
    }
}

    fun googleInteractionsAgentConfig(options: JsonObject): JsonObject? {
    val config = (JsonAccess.obj(options, "agentConfig")) ?: return null
    val type = (config["type"] as? JsonPrimitive)?.contentOrNull ?: return null
    return buildJsonObject {
        put("type", JsonPrimitive(type))
        if (type == "deep-research") {
            config["thinkingSummaries"]?.let { put("thinking_summaries", it) }
            config["visualization"]?.let { put("visualization", it) }
            config["collaborativePlanning"]?.let { put("collaborative_planning", it) }
        }
    }
}

    fun googleInteractionsEnvironment(
    element: JsonElement?,
    isAgent: Boolean,
    warnings: MutableList<CallWarning>,
): JsonElement? {
    if (element == null) return null
    if (!isAgent) {
        warnings += CallWarning("other", "google.interactions: environment is only supported when an agent is set; environment will be omitted from the request body.")
        return null
    }
    return when (element) {
        !is JsonObject -> element
        else -> buildJsonObject {
            put("type", element["type"] ?: JsonPrimitive("remote"))
            element["sources"]?.let { put("sources", it) }
            element["network"]?.let { put("network", googleInteractionsNetwork(it)) }
        }
    }
}

    fun googleInteractionsNetwork(element: JsonElement): JsonElement =
    if (element is JsonPrimitive) {
        element
    } else {
        (element as? JsonObject)?.let { obj ->
            buildJsonObject { obj["allowlist"]?.let { put("allowlist", it) } }
        } ?: element
    }

    fun googleInteractionsResult(
    response: JsonObject,
    requestBody: JsonObject,
    headers: Map<String, String>,
    rawBody: JsonElement,
    warnings: List<CallWarning>,
    settings: GoogleGenerativeAIProviderSettings,
): LanguageModelResult {
    val interactionId = (response["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val parsed = googleInteractionsContent(JsonAccess.arr(response, "steps"), settings.generateId, interactionId)
    val text = parsed.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    val status = (response["status"] as? JsonPrimitive)?.contentOrNull
    val serviceTier = (response["service_tier"] as? JsonPrimitive)?.contentOrNull ?: headers["x-gemini-service-tier"]
    val providerMetadata = buildJsonObject {
        interactionId?.let { put("interactionId", JsonPrimitive(it)) }
        serviceTier?.let { put("serviceTier", JsonPrimitive(it)) }
    }
    return LanguageModelResult(
        text = text,
        toolCalls = parsed.content.filterIsInstance<ContentPart.ToolCall>(),
        finishReason = googleInteractionsFinishReason(status, parsed.hasFunctionCall),
        usage = googleInteractionsUsage(response["usage"]),
        providerMetadata = if (providerMetadata.isEmpty()) ProviderMetadata.None else ProviderMetadata.Raw(JsonObject(mapOf("google" to providerMetadata))),
        content = parsed.content,
        rawFinishReason = status,
        warnings = warnings,
        request = LanguageModelRequestMetadata(requestBody),
        response = LanguageModelResponseMetadata(
            id = interactionId,
            modelId = (response["model"] as? JsonPrimitive)?.contentOrNull,
            headers = headers,
            body = rawBody,
        ),
    )
}
    fun googleInteractionsContent(
    steps: JsonArray?,
    generateId: () -> String,
    interactionId: String?,
): GoogleInteractionsParsedContent {
    val content = mutableListOf<ContentPart>()
    var hasFunctionCall = false
    steps.orEmpty().forEach { stepElement ->
        val step = stepElement as? JsonObject ?: return@forEach
        when (val type = (step["type"] as? JsonPrimitive)?.contentOrNull) {
            "model_output" -> {
                (JsonAccess.arr(step, "content")).orEmpty().forEach { blockElement ->
                    val block = blockElement as? JsonObject ?: return@forEach
                    when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
                        "text" -> {
                            val metadata = googleInteractionsMetadata(interactionId = interactionId)
                            val blockText = (block["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            content += ContentPart.Text(blockText, metadata)
                            content += googleInteractionsAnnotationSources(
                                JsonAccess.arr(block, "annotations"), generateId, metadata.toMap().ifEmpty { null },
                            )
                        }
                        "image" -> {
                            val metadata = googleInteractionsMetadata(
                                interactionId = interactionId,
                                extra = (block["uri"] as? JsonPrimitive)?.contentOrNull
                                    ?.let { mapOf("imageUri" to JsonPrimitive(it)) }.orEmpty(),
                            )
                            content += ContentPart.File(
                                mediaType = (block["mime_type"] as? JsonPrimitive)?.contentOrNull ?: "image/png",
                                base64 = (block["data"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                                providerMetadata = metadata,
                            )
                        }
                    }
                }
            }
            "thought" -> {
                content += ContentPart.Reasoning(
                    text = (JsonAccess.arr(step, "summary")).orEmpty()
                        .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                        .joinToString("\n"),
                    providerMetadata = googleInteractionsMetadata(
                        signature = (step["signature"] as? JsonPrimitive)?.contentOrNull,
                        interactionId = interactionId,
                    ),
                )
            }
            "function_call" -> {
                hasFunctionCall = true
                content += ContentPart.ToolCall(
                    toolCallId = (step["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate(),
                    // Fail loudly on a missing/blank function_call name instead of fabricating "".
                    toolName = WireDecoder.requiredString(step, "name", "google", "interactions response", "$.function_call"),
                    input = step["arguments"] ?: JsonObject(emptyMap()),
                    providerMetadata = googleInteractionsMetadata(
                        signature = (step["signature"] as? JsonPrimitive)?.contentOrNull,
                        interactionId = interactionId,
                    ),
                )
            }
            else -> {
                if (type != null && type.endsWith("_call")) {
                    hasFunctionCall = true
                    content += ContentPart.ToolCall(
                        toolCallId = (step["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate(),
                        toolName = if (type == "mcp_server_tool_call") {
                            (step["name"] as? JsonPrimitive)?.contentOrNull ?: "mcp_server_tool"
                        } else {
                            type.removeSuffix("_call")
                        },
                        input = step["arguments"] ?: JsonObject(emptyMap()),
                        providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }))),
                    )
                } else if (type != null && type.endsWith("_result")) {
                    content += ContentPart.ToolResult(
                        toolCallId = (step["call_id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate(),
                        toolName = if (type == "mcp_server_tool_result") {
                            (step["name"] as? JsonPrimitive)?.contentOrNull ?: "mcp_server_tool"
                        } else {
                            type.removeSuffix("_result")
                        },
                        output = step.getOrElse("result") { JsonNull },
                        isError = (step["is_error"] as? JsonPrimitive)?.booleanOrNull == true,
                        providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }))),
                    )
                }
            }
        }
    }
    return GoogleInteractionsParsedContent(content, hasFunctionCall)
}

    fun googleInteractionsAnnotationSources(
    annotations: JsonArray?,
    generateId: () -> String,
    metadata: Map<String, JsonElement>?,
): List<ContentPart.Source> = annotations.orEmpty().mapNotNull { annotationElement ->
    val annotation = annotationElement as? JsonObject ?: return@mapNotNull null
    val type = (annotation["type"] as? JsonPrimitive)?.contentOrNull
    val url = (annotation["url"] as? JsonPrimitive)?.contentOrNull
        ?: (annotation["document_uri"] as? JsonPrimitive)?.contentOrNull
        ?: return@mapNotNull null
    when (type) {
        "url_citation", "place_citation" -> ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Url,
            url = url,
            title = (annotation["title"] as? JsonPrimitive)?.contentOrNull
                ?: (annotation["name"] as? JsonPrimitive)?.contentOrNull,
            providerMetadata = metadata?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject { put("id", JsonPrimitive(IdGenerator.generate())) }))),
        )
        "file_citation" -> ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Document,
            url = url,
            title = (annotation["file_name"] as? JsonPrimitive)?.contentOrNull,
            providerMetadata = metadata?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject { put("id", JsonPrimitive(IdGenerator.generate())) }))),
        )
        else -> null
    }
}
    fun googleInteractionsUsage(element: JsonElement?): Usage {
    val obj = element as? JsonObject ?: return Usage()
    val input = (obj["total_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
    val output = (obj["total_output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
    val thought = (obj["total_thought_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
    val cached = ((obj["total_cached_tokens"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, input)
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = input,
            noCache = (input - cached).coerceAtLeast(0),
            cacheRead = cached,
        ),
        outputTokens = Usage.OutputTokenBreakdown(
            total = output + thought,
            text = output,
            reasoning = thought,
        ),
        raw = element,
    )
}

    fun googleInteractionsFinishReason(status: String?, hasFunctionCall: Boolean): FinishReason = when (status) {
    "completed" -> if (hasFunctionCall) FinishReason.ToolCalls else FinishReason.Stop
    "requires_action" -> FinishReason.ToolCalls
    "failed" -> FinishReason.Error
    "incomplete" -> FinishReason.Length
    "cancelled" -> FinishReason.Other
    else -> FinishReason.Other
}

    fun googleInteractionsTerminal(status: String?): Boolean =
    status in setOf("completed", "failed", "cancelled", "incomplete")

    private suspend fun googleGetJson(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
        parseJson: Boolean = true,
    ): HttpJsonResponse {
        abortSignal.throwIfAborted()
        val response = client.request(url) {
            method = HttpMethod.Get
            headers.forEach { (name, value) -> header(name, value) }
        }
        return with(GoogleHttp) { response.parseGoogleResponse(url, parseJson = parseJson) }
    }

    suspend fun googlePollInteraction(
    client: HttpClient,
    settings: GoogleGenerativeAIProviderSettings,
    interactionId: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    timeoutMillis: Long?,
): HttpJsonResponse {
    val maxAttempts = ((timeoutMillis ?: 30 * 60 * 1_000L) / settings.videoPollIntervalMillis.coerceAtLeast(1L)).coerceAtLeast(1L).toInt()
    var current = googleGetJson(client, "${settings.baseURL.trimEnd('/')}/interactions/$interactionId", headers, abortSignal)
    repeat(maxAttempts) {
        val status = (current.value.jsonObject["status"] as? JsonPrimitive)?.contentOrNull
        if (googleInteractionsTerminal(status)) return current
        if (settings.videoPollIntervalMillis > 0) delay(settings.videoPollIntervalMillis)
        current = googleGetJson(client, "${settings.baseURL.trimEnd('/')}/interactions/$interactionId", headers, abortSignal)
    }
    // The loop checks terminal status at the TOP of each iteration, so the final fetch is never
    // inspected — check it here (mirrors the video-polling post-loop guard) before timing out.
    val finalStatus = (current.value.jsonObject["status"] as? JsonPrimitive)?.contentOrNull
    if (googleInteractionsTerminal(finalStatus)) return current
    throw InvalidResponseDataError(null, "google.interactions: polling timed out for interaction $interactionId.")
}

    fun googleInteractionsMetadata(
    signature: String? = null,
    interactionId: String? = null,
    extra: Map<String, JsonElement> = emptyMap(),
): ProviderMetadata {
    val google = buildJsonObject {
        signature?.let { put("signature", JsonPrimitive(it)) }
        interactionId?.let { put("interactionId", JsonPrimitive(it)) }
        extra.forEach { (key, value) -> put(key, value) }
    }
    return if (google.isEmpty()) ProviderMetadata.None else ProviderMetadata.Raw(JsonObject(mapOf("google" to google)))
}

    fun googleInteractionsSignature(metadata: Map<String, JsonElement>?): String? =
    ((metadata?.get("google") as? JsonObject)?.get("signature") as? JsonPrimitive)?.contentOrNull
/** Parse the interactions SSE [rawLines] and feed each event to [state],
 *  emitting its produced [StreamEvent]s (shared by both stream branches). */
    suspend fun FlowCollector<StreamEvent>.collectGoogleInteractions(
    rawLines: Flow<String>,
    state: GoogleInteractionsStreamState,
) {
    EventStreamParser.parse(rawLines, Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())), aiSdkJson).collect { event ->
        when (event) {
            is ParseResult.Success -> state.accept(event.value.jsonObject).forEach { emit(it) }
            is ParseResult.Failure -> emit(
                StreamEvent.Error("Failed to parse Google Interactions stream event: ${event.error.message}"),
            )
        }
    }
}
}
