@file:Suppress("CyclomaticComplexMethod", "LongMethod")

package ai.torad.aisdk

import ai.torad.aisdk.ui.ChatRequest
import ai.torad.aisdk.ui.ChatTransport
import ai.torad.aisdk.ui.TextUIPartState
import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.convertToModelMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.jvm.JvmName

@Serializable
enum class LangChainMessageRole {
    System,
    Human,
    AI,
    Tool,
}

@Serializable
data class LangChainToolCall(
    val id: String,
    val name: String,
    val input: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class LangChainBaseMessage(
    val role: LangChainMessageRole,
    val content: JsonElement = JsonPrimitive(""),
    val id: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<LangChainToolCall> = emptyList(),
    val additionalKwargs: Map<String, JsonElement> = emptyMap(),
)

sealed interface LangChainStreamItem {
    data class ModelChunk(
        val id: String? = null,
        val text: String = "",
        val reasoning: String = "",
        val toolCalls: List<LangChainToolCall> = emptyList(),
    ) : LangChainStreamItem

    data class StreamEventsEvent(
        val event: String,
        val data: JsonObject? = null,
        val runId: String? = null,
        val name: String? = null,
    ) : LangChainStreamItem

    data class LangGraphEvent(
        val type: String,
        val data: JsonElement = JsonNull,
        val message: ModelChunk? = null,
        val step: Int? = null,
    ) : LangChainStreamItem
}

data class LangSmithDeploymentTransportOptions(
    val url: String,
    val apiKey: String? = null,
    val graphId: String = "agent",
    val headers: Map<String, String> = emptyMap(),
)

typealias LangSmithGraphStream =
    suspend (messages: List<LangChainBaseMessage>, options: LangSmithDeploymentTransportOptions) -> Flow<LangChainStreamItem>

fun toBaseMessages(messages: List<UIMessage>): List<LangChainBaseMessage> =
    convertModelMessages(convertToModelMessages(messages))

fun convertModelMessages(modelMessages: List<ModelMessage>): List<LangChainBaseMessage> =
    buildList {
        for (message in modelMessages) {
            when (message.role) {
                MessageRole.System -> add(
                    LangChainBaseMessage(
                        role = LangChainMessageRole.System,
                        content = message.content.textContent(),
                    ),
                )
                MessageRole.User -> add(
                    LangChainBaseMessage(
                        role = LangChainMessageRole.Human,
                        content = message.content.toLangChainContent(),
                    ),
                )
                MessageRole.Assistant -> add(
                    LangChainBaseMessage(
                        role = LangChainMessageRole.AI,
                        content = message.content.toAssistantContent(),
                        toolCalls = message.content.filterIsInstance<ContentPart.ToolCall>().map {
                            LangChainToolCall(
                                id = it.toolCallId,
                                name = it.toolName,
                                input = it.input,
                            )
                        },
                    ),
                )
                MessageRole.Tool -> message.content.filterIsInstance<ContentPart.ToolResult>().forEach { part ->
                    add(
                        LangChainBaseMessage(
                            role = LangChainMessageRole.Tool,
                            content = part.output,
                            toolCallId = part.toolCallId,
                            additionalKwargs = mapOf(
                                "name" to JsonPrimitive(part.toolName),
                                "isError" to JsonPrimitive(part.isError),
                            ),
                        ),
                    )
                }
            }
        }
    }

@JvmName("langChainToUIMessageStreamExport")
fun toUIMessageStream(
    stream: Flow<LangChainStreamItem>,
    callbacks: StreamCallbacks? = null,
    assistantMessageId: String = "langchain-msg-1",
): Flow<UIMessage> = langChainToUIMessageStream(stream, callbacks, assistantMessageId)

fun langChainToUIMessageStream(
    stream: Flow<LangChainStreamItem>,
    callbacks: StreamCallbacks? = null,
    assistantMessageId: String = "langchain-msg-1",
): Flow<UIMessage> = flow {
    callbacks?.onStart?.invoke()
    val state = LangChainStreamState(assistantMessageId)

    suspend fun emitCurrent(textState: TextUIPartState = TextUIPartState.Streaming) {
        val message = state.currentMessage(textState) ?: return
        emit(message)
    }

    try {
        stream.collect { item ->
            when (item) {
                is LangChainStreamItem.ModelChunk -> {
                    state.acceptModelChunk(item)
                    if (item.text.isNotEmpty()) {
                        callbacks?.onToken?.invoke(item.text)
                        callbacks?.onText?.invoke(item.text)
                    }
                    if (item.text.isNotEmpty() || item.reasoning.isNotEmpty()) emitCurrent()
                    item.toolCalls.forEach { call -> emit(call.toToolMessage(ToolCallState.InputAvailable, output = null)) }
                }
                is LangChainStreamItem.StreamEventsEvent -> when (item.event) {
                    "on_chat_model_start" -> item.runId?.let { state.messageId = it }
                    "on_chat_model_stream" -> {
                        val chunk = item.data?.get("chunk")?.jsonObjectOrNull()
                        val parsed = chunk?.toModelChunk() ?: LangChainStreamItem.ModelChunk()
                        state.acceptModelChunk(parsed)
                        if (parsed.text.isNotEmpty()) {
                            callbacks?.onToken?.invoke(parsed.text)
                            callbacks?.onText?.invoke(parsed.text)
                        }
                        if (parsed.text.isNotEmpty() || parsed.reasoning.isNotEmpty()) emitCurrent()
                    }
                    "on_tool_start" -> {
                        val runId = item.runId ?: item.data?.string("run_id") ?: "tool-call"
                        val toolName = item.name ?: item.data?.string("name") ?: "tool"
                        val input = item.data?.get("input")
                        emit(
                            UIMessage(
                                id = runId,
                                role = UIMessageRole.Assistant,
                                parts = listOf(
                                    UIMessagePart.DynamicToolUI(
                                        toolCallId = runId,
                                        toolName = toolName,
                                        state = ToolCallState.InputAvailable,
                                        input = input,
                                    ),
                                ),
                            ),
                        )
                    }
                    "on_tool_end" -> {
                        val runId = item.runId ?: item.data?.string("run_id") ?: "tool-call"
                        val toolName = item.name ?: item.data?.string("name") ?: "tool"
                        emit(
                            UIMessage(
                                id = runId,
                                role = UIMessageRole.Assistant,
                                parts = listOf(
                                    UIMessagePart.DynamicToolUI(
                                        toolCallId = runId,
                                        toolName = toolName,
                                        state = ToolCallState.OutputAvailable,
                                        output = item.data?.get("output") ?: JsonNull,
                                    ),
                                ),
                            ),
                        )
                    }
                }
                is LangChainStreamItem.LangGraphEvent -> {
                    if (item.type == "values") {
                        state.finalState = item.data
                    }
                    item.step?.let {
                        emit(
                            UIMessage(
                                id = "${state.messageId}-step-$it",
                                role = UIMessageRole.Assistant,
                                parts = listOf(UIMessagePart.StepStart(it)),
                            ),
                        )
                    }
                    val chunk = item.message ?: item.data.jsonObjectOrNull()?.toModelChunk()
                    if (chunk != null) {
                        state.acceptModelChunk(chunk)
                        if (chunk.text.isNotEmpty()) {
                            callbacks?.onToken?.invoke(chunk.text)
                            callbacks?.onText?.invoke(chunk.text)
                        }
                        if (chunk.text.isNotEmpty() || chunk.reasoning.isNotEmpty()) emitCurrent()
                        chunk.toolCalls.forEach { call -> emit(call.toToolMessage(ToolCallState.InputAvailable, output = null)) }
                    }
                }
            }
        }

        callbacks?.onFinal?.invoke(state.text.toString())
        callbacks?.onFinish?.invoke(state.finalState)
        emitCurrent(TextUIPartState.Done)
    } catch (error: Throwable) {
        callbacks?.onFinal?.invoke(state.text.toString())
        if (error is CancellationException) {
            callbacks?.onAbort?.invoke()
            throw error
        }
        callbacks?.onError?.invoke(error)
        emit(
            UIMessage(
                id = "${state.messageId}-error",
                role = UIMessageRole.Assistant,
                parts = listOf(UIMessagePart.Error(error.message ?: "LangChain stream failed")),
            ),
        )
    }
}

class LangSmithDeploymentTransport(
    private val options: LangSmithDeploymentTransportOptions,
    private val graphStream: LangSmithGraphStream = { _, _ ->
        throw UnsupportedOperationException(
            "LangSmithDeploymentTransport requires a graphStream implementation in Kotlin Multiplatform.",
        )
    },
) : ChatTransport {
    override fun sendMessages(request: ChatRequest): Flow<UIMessage> = flow {
        val baseMessages = toBaseMessages(request.messages)
        langChainToUIMessageStream(graphStream(baseMessages, options)).collect { emit(it) }
    }

    override fun reconnectToStream(chatId: String, headers: Map<String, String>): Flow<UIMessage>? =
        throw UnsupportedOperationException(
            "LangSmithDeploymentTransport.reconnectToStream is not implemented by upstream @ai-sdk/langchain.",
        )
}

private class LangChainStreamState(
    initialMessageId: String,
) {
    var messageId: String = initialMessageId
    val text = StringBuilder()
    private val reasoning = StringBuilder()
    var finalState: JsonElement? = null

    fun acceptModelChunk(chunk: LangChainStreamItem.ModelChunk) {
        chunk.id?.let { messageId = it }
        text.append(chunk.text)
        reasoning.append(chunk.reasoning)
    }

    fun currentMessage(textState: TextUIPartState): UIMessage? {
        val parts = buildList {
            if (reasoning.isNotEmpty()) add(UIMessagePart.Reasoning(reasoning.toString(), textState))
            if (text.isNotEmpty()) add(UIMessagePart.Text(text.toString(), textState))
        }
        if (parts.isEmpty()) return null
        return UIMessage(id = messageId, role = UIMessageRole.Assistant, parts = parts)
    }
}

private fun LangChainToolCall.toToolMessage(state: ToolCallState, output: JsonElement?): UIMessage =
    UIMessage(
        id = id,
        role = UIMessageRole.Assistant,
        parts = listOf(
            UIMessagePart.DynamicToolUI(
                toolCallId = id,
                toolName = name,
                state = state,
                input = input,
                output = output,
            ),
        ),
    )

private fun List<ContentPart>.textContent(): JsonElement =
    JsonPrimitive(filterIsInstance<ContentPart.Text>().joinToString(separator = "") { it.text })

private fun List<ContentPart>.toAssistantContent(): JsonElement {
    val text = filterIsInstance<ContentPart.Text>().joinToString(separator = "") { it.text }
    val reasoning = filterIsInstance<ContentPart.Reasoning>().joinToString(separator = "") { it.text }
    return when {
        reasoning.isBlank() -> JsonPrimitive(text)
        text.isBlank() -> JsonArray(listOf(reasoningBlock(reasoning)))
        else -> JsonArray(listOf(reasoningBlock(reasoning), textBlock(text)))
    }
}

private fun List<ContentPart>.toLangChainContent(): JsonElement {
    val blocks = buildJsonArray {
        for (part in this@toLangChainContent) {
            when (part) {
                is ContentPart.Text -> add(textBlock(part.text))
                is ContentPart.Image -> add(fileBlock(part.mediaType, part.base64))
                is ContentPart.File -> add(fileBlock(part.mediaType, part.base64, part.filename))
                is ContentPart.Reasoning -> add(reasoningBlock(part.text))
                is ContentPart.Source,
                is ContentPart.ToolApprovalRequest,
                is ContentPart.ToolApprovalResponse,
                is ContentPart.ToolCall,
                is ContentPart.ToolResult,
                -> Unit
            }
        }
    }
    if (blocks.size == 1 && blocks.single().jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text") {
        return blocks.single().jsonObject.getValue("text")
    }
    return blocks
}

private fun textBlock(text: String): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(text))
    }

private fun reasoningBlock(text: String): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("reasoning"))
        put("reasoning", JsonPrimitive(text))
    }

private fun fileBlock(mediaType: String, base64: String, filename: String? = null): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("file"))
        put("mediaType", JsonPrimitive(mediaType))
        put("data", JsonPrimitive(base64))
        filename?.let { put("filename", JsonPrimitive(it)) }
    }

private fun JsonObject.toModelChunk(): LangChainStreamItem.ModelChunk =
    LangChainStreamItem.ModelChunk(
        id = string("id"),
        text = contentText(),
        reasoning = reasoningText(),
        toolCalls = toolCalls(),
    )

private fun JsonObject.contentText(): String {
    val content = get("content") ?: return ""
    return when {
        content is JsonPrimitive -> content.contentOrNull.orEmpty()
        content is JsonArray -> content.joinToString(separator = "") { item ->
            item.jsonObjectOrNull()?.let { obj ->
                if (obj.string("type") == "text") obj.string("text").orEmpty() else ""
            }.orEmpty()
        }
        else -> ""
    }
}

private fun JsonObject.reasoningText(): String =
    string("reasoning")
        ?: string("reasoning_content")
        ?: get("additional_kwargs")?.jsonObjectOrNull()?.string("reasoning_content")
        ?: get("content")?.jsonArrayOrNull()?.joinToString(separator = "") { item ->
            val obj = item.jsonObjectOrNull()
            when (obj?.string("type")) {
                "reasoning" -> obj.string("reasoning").orEmpty()
                "thinking" -> obj.string("thinking").orEmpty()
                else -> ""
            }
        }.orEmpty()

private fun JsonObject.toolCalls(): List<LangChainToolCall> =
    (get("tool_calls") ?: get("toolCalls"))?.jsonArrayOrNull()?.mapIndexedNotNull { index, item ->
        val obj = item.jsonObjectOrNull() ?: return@mapIndexedNotNull null
        val id = obj.string("id") ?: "tool-call-$index"
        val name = obj.string("name")
            ?: obj["function"]?.jsonObjectOrNull()?.string("name")
            ?: "tool"
        val input = obj["args"]
            ?: obj["input"]
            ?: obj["function"]?.jsonObjectOrNull()?.get("arguments")
            ?: JsonObject(emptyMap())
        LangChainToolCall(id, name, input)
    }.orEmpty()

private fun JsonObject.string(key: String): String? =
    get(key)?.jsonPrimitiveOrNull()?.contentOrNull

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
