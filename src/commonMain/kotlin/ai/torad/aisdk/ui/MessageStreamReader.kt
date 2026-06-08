package ai.torad.aisdk.ui

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.ToolResultOutput
import ai.torad.aisdk.aiSdkJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Convert a raw agent [StreamEvent] flow into a flow of growing
 * [UIMessage] snapshots. Each emission is the *current full state* of
 * the assistant message being built — caller's `StateFlow<List<UIMessage>>`
 * just replaces the in-flight message on each emission.
 *
 * Hosts use this to convert raw stream events into renderable chat
 * snapshots. The v6 equivalent is the `useChat` hook's internal
 * aggregation; here we expose it as a pure transformation.
 *
 * Block-aware: the v6 stream taxonomy uses block IDs to interleave
 * multiple text segments / reasoning blocks / tool inputs in a single
 * step. Each `*Start` opens a part by ID; each `*Delta` grows it; each
 * `*End` closes it. Multiple text or reasoning blocks in one message
 * become separate [UIMessagePart]s in render order.
 *
 * Usage:
 * ```
 * val messages: StateFlow<List<UIMessage>> = chatRepository
 *     .streamReply(turns)
 *     .let { streamToUiMessages(it, assistantMessageId = "asst_${turns.size}") }
 *     .stateIn(scope, SharingStarted.Eagerly, emptyList())
 * ```
 */
public fun streamToUiMessages(
    events: Flow<StreamEvent>,
    assistantMessageId: String,
): Flow<UIMessage> = flow {
    val parts = mutableListOf<UIMessagePart>()
    val partIndexById = mutableMapOf<String, Int>()
    val toolByCallId = mutableMapOf<String, Int>()
    val toolInputBufById = mutableMapOf<String, StringBuilder>()
    val toolNameByInputId = mutableMapOf<String, String>()
    val dataPartIndexById = mutableMapOf<String, Int>()

    fun snapshot() = UIMessage(
        id = assistantMessageId,
        role = UIMessageRole.Assistant,
        parts = parts.toList(),
    )

    fun shiftIndexesAfterRemoval(removedIndex: Int) {
        for ((key, value) in toolByCallId.toMap()) {
            if (value > removedIndex) toolByCallId[key] = value - 1
        }
        for ((key, value) in partIndexById.toMap()) {
            if (value > removedIndex) partIndexById[key] = value - 1
        }
    }

    fun openTextPart(id: String) {
        if (partIndexById.containsKey(id)) return
        parts.add(UIMessagePart.Text(text = "", state = TextUIPartState.Streaming))
        partIndexById[id] = parts.size - 1
    }

    fun appendTextPart(id: String, delta: String) {
        val idx = partIndexById[id]
        if (idx == null) {
            parts.add(UIMessagePart.Text(text = delta, state = TextUIPartState.Streaming))
            partIndexById[id] = parts.size - 1
            return
        }
        val existing = parts[idx] as UIMessagePart.Text
        parts[idx] = UIMessagePart.Text(
            text = existing.text + delta,
            state = TextUIPartState.Streaming,
        )
    }

    fun closeTextPart(id: String) {
        val idx = partIndexById[id] ?: return
        val existing = parts[idx] as? UIMessagePart.Text ?: return
        parts[idx] = existing.copy(state = TextUIPartState.Done)
    }

    fun openReasoningPart(id: String) {
        if (partIndexById.containsKey(id)) return
        parts.add(UIMessagePart.Reasoning(text = "", state = TextUIPartState.Streaming))
        partIndexById[id] = parts.size - 1
    }

    fun appendReasoningPart(id: String, delta: String) {
        val idx = partIndexById[id]
        if (idx == null) {
            parts.add(UIMessagePart.Reasoning(text = delta, state = TextUIPartState.Streaming))
            partIndexById[id] = parts.size - 1
            return
        }
        val existing = parts[idx] as UIMessagePart.Reasoning
        parts[idx] = UIMessagePart.Reasoning(
            text = existing.text + delta,
            state = TextUIPartState.Streaming,
        )
    }

    fun closeReasoningPart(id: String) {
        val idx = partIndexById[id] ?: return
        val existing = parts[idx] as? UIMessagePart.Reasoning ?: return
        parts[idx] = existing.copy(state = TextUIPartState.Done)
    }

    fun upsertTool(
        toolCallId: String,
        toolName: String,
        state: ToolCallState,
        input: JsonElement? = null,
        output: JsonElement? = null,
        error: String? = null,
        preliminary: Boolean = false,
    ) {
        val existingIndex = toolByCallId[toolCallId]
        val nextPart = UIMessagePart.ToolUI(
            toolCallId = toolCallId,
            toolName = toolName,
            state = state,
            input = input,
            output = output,
            error = error,
            preliminary = preliminary,
        )
        if (existingIndex != null) {
            parts[existingIndex] = nextPart
        } else {
            parts.add(nextPart)
            toolByCallId[toolCallId] = parts.size - 1
        }
    }

    events.collect { event ->
        when (event) {
            is StreamEvent.StreamStart -> Unit
            is StreamEvent.ResponseMetadata -> Unit
            is StreamEvent.StepStart -> {
                // Multi-step flows (tool round-trips) cross step boundaries;
                // emitting a StepStart part lets the renderer chrome the
                // handoff. Step 1 is the initial call — no part emitted
                // because that's the implicit start of the message; later
                // steps are visible boundaries.
                if (event.stepNumber > 1) {
                    parts.add(UIMessagePart.StepStart(stepNumber = event.stepNumber))
                    emit(snapshot())
                }
            }
            is StreamEvent.TextStart -> {
                openTextPart(event.id)
                emit(snapshot())
            }
            is StreamEvent.TextDelta -> {
                appendTextPart(event.id, event.text)
                emit(snapshot())
            }
            is StreamEvent.TextEnd -> {
                closeTextPart(event.id)
                emit(snapshot())
            }
            is StreamEvent.ReasoningStart -> {
                openReasoningPart(event.id)
                emit(snapshot())
            }
            is StreamEvent.ReasoningDelta -> {
                appendReasoningPart(event.id, event.text)
                emit(snapshot())
            }
            is StreamEvent.ReasoningEnd -> {
                closeReasoningPart(event.id)
                emit(snapshot())
            }
            is StreamEvent.SourcePart -> {
                val partForEvent = when (event.sourceType) {
                    StreamEvent.SourcePart.SourceType.Url -> UIMessagePart.SourceUrl(
                        sourceId = event.id,
                        url = event.url.orEmpty(),
                        title = event.title,
                    )
                    StreamEvent.SourcePart.SourceType.Document -> UIMessagePart.SourceDocument(
                        sourceId = event.id,
                        mediaType = event.mediaType.orEmpty(),
                        title = event.title.orEmpty(),
                    )
                }
                parts.add(partForEvent)
                emit(snapshot())
            }
            is StreamEvent.FilePart -> {
                parts.add(UIMessagePart.File(mediaType = event.mediaType, base64 = event.base64))
                emit(snapshot())
            }
            is StreamEvent.ToolInputStart -> {
                toolInputBufById[event.id] = StringBuilder()
                toolNameByInputId[event.id] = event.toolName
                upsertTool(event.id, event.toolName, ToolCallState.InputStreaming)
                emit(snapshot())
            }
            is StreamEvent.ToolInputDelta -> {
                val buf = toolInputBufById.getOrPut(event.id) { StringBuilder() }
                buf.append(event.delta)
                val partial = runCatching { aiSdkJson.parseToJsonElement(buf.toString()) }.getOrNull()
                val toolName = toolNameByInputId[event.id] ?: return@collect
                upsertTool(
                    toolCallId = event.id,
                    toolName = toolName,
                    state = ToolCallState.InputStreaming,
                    input = partial,
                )
                emit(snapshot())
            }
            is StreamEvent.ToolInputEnd -> Unit
            is StreamEvent.ToolCall -> {
                // The streaming partial id (ToolInputStart.id) is typically distinct
                // from the final ToolCall.toolCallId. If we see a placeholder in
                // toolByCallId for the same toolName, drop it before adding the
                // final entry so we don't render two cards.
                val placeholderId = when {
                    event.toolCallId in toolNameByInputId -> event.toolCallId
                    else -> toolNameByInputId.entries
                        .firstOrNull { it.value == event.toolName && it.key != event.toolCallId }
                        ?.key
                }
                if (placeholderId != null) {
                    val placeholderIdx = toolByCallId[placeholderId]
                    if (placeholderIdx != null && placeholderIdx in parts.indices) {
                        parts.removeAt(placeholderIdx)
                        shiftIndexesAfterRemoval(placeholderIdx)
                    }
                    toolByCallId.remove(placeholderId)
                    toolNameByInputId.remove(placeholderId)
                    toolInputBufById.remove(placeholderId)
                }
                upsertTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    state = ToolCallState.InputAvailable,
                    input = event.inputJson,
                )
                emit(snapshot())
            }
            is StreamEvent.ToolApprovalRequest -> {
                upsertTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    state = ToolCallState.ApprovalRequested,
                    input = event.inputJson,
                )
                emit(snapshot())
            }
            is StreamEvent.ToolResult -> {
                val existingIndex = toolByCallId[event.toolCallId]
                val existingInput = existingIndex?.takeIf { it in parts.indices }
                    ?.let { parts[it] as? UIMessagePart.ToolUI }
                    ?.input
                val deniedOutput = event.output as? ToolResultOutput.ExecutionDenied
                upsertTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    state = if (deniedOutput != null) {
                        ToolCallState.OutputDenied
                    } else {
                        ToolCallState.OutputAvailable
                    },
                    input = existingInput,
                    output = event.outputJson,
                    error = deniedOutput?.reason,
                    preliminary = event.preliminary,
                )
                emit(snapshot())
            }
            is StreamEvent.ToolError -> {
                val existingIndex = toolByCallId[event.toolCallId]
                val existingInput = existingIndex?.takeIf { it in parts.indices }
                    ?.let { parts[it] as? UIMessagePart.ToolUI }
                    ?.input
                upsertTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    state = ToolCallState.OutputError,
                    input = existingInput,
                    error = event.message,
                )
                emit(snapshot())
            }
            is StreamEvent.ToolOutputDenied -> {
                val existingIndex = toolByCallId[event.toolCallId]
                val existingInput = existingIndex?.takeIf { it in parts.indices }
                    ?.let { parts[it] as? UIMessagePart.ToolUI }
                    ?.input
                upsertTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    state = ToolCallState.OutputDenied,
                    input = existingInput,
                    error = event.reason,
                )
                emit(snapshot())
            }
            is StreamEvent.StepFinish -> Unit
            is StreamEvent.Finish -> emit(snapshot())
            is StreamEvent.Abort -> emit(snapshot())
            is StreamEvent.Error -> {
                parts.add(UIMessagePart.Error(event.message))
                emit(snapshot())
            }
            is StreamEvent.Raw -> {
                val rawObject = runCatching { event.rawValue.jsonObject }.getOrNull()
                val type = rawObject?.get("type")?.jsonPrimitive?.contentOrNull
                val data = rawObject?.get("data")
                if (type != null && data != null) {
                    val dataId = rawObject["id"]?.jsonPrimitive?.contentOrNull
                    val transient = rawObject["transient"]?.jsonPrimitive?.contentOrNull == "true"
                    val part = UIMessagePart.Data(type = type, data = data, id = dataId, transient = transient)
                    // Keyed data parts upsert in place; unkeyed ones append.
                    val existingIdx = dataId?.let { dataPartIndexById[it] }
                    if (existingIdx != null && existingIdx in parts.indices) {
                        parts[existingIdx] = part
                    } else {
                        parts.add(part)
                        if (dataId != null) dataPartIndexById[dataId] = parts.size - 1
                    }
                    emit(snapshot())
                }
            }
        }
    }
}
