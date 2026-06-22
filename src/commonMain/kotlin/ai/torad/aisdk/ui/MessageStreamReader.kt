package ai.torad.aisdk.ui

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.ToolResultOutput
import ai.torad.aisdk.aiSdkJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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
 *     .let { StreamToUiMessages(it, assistantMessageId = "asst_${turns.size}") }
 *     .stateIn(scope, SharingStarted.Eagerly, emptyList())
 * ```
 */
public fun StreamToUiMessages(
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
        // dataPartIndexById also stores ABSOLUTE part indices — it must shift too, or a keyed Raw
        // data part recorded after the removed placeholder gets a stale index and a later upsert
        // overwrites the wrong part (or appends a duplicate).
        for ((key, value) in dataPartIndexById.toMap()) {
            if (value > removedIndex) dataPartIndexById[key] = value - 1
        }
    }

    fun openTextPart(id: String) {
        if (partIndexById.containsKey(id)) return
        parts.add(UIMessagePart.Text(text = "", state = TextUIPartState.Streaming))
        partIndexById[id] = parts.size - 1
    }

    fun appendTextPart(id: String, delta: String) {
        val idx = partIndexById[id]
        val existing = idx?.let { parts[it] as? UIMessagePart.Text }
        if (idx == null || existing == null) {
            // No part yet, OR the id was first opened as a different kind (a text/reasoning id
            // collision) — start a fresh Text part rather than crashing on an unchecked cast.
            parts.add(UIMessagePart.Text(text = delta, state = TextUIPartState.Streaming))
            partIndexById[id] = parts.size - 1
            return
        }
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
        val existing = idx?.let { parts[it] as? UIMessagePart.Reasoning }
        if (idx == null || existing == null) {
            // Mirror appendTextPart: a fresh Reasoning part on an absent index or a kind mismatch.
            parts.add(UIMessagePart.Reasoning(text = delta, state = TextUIPartState.Streaming))
            partIndexById[id] = parts.size - 1
            return
        }
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
        approvalId: String? = null,
        signature: String? = null,
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
            approvalId = approvalId,
            signature = signature,
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
                    approvalId = event.approvalId,
                    signature = event.signature,
                )
                emit(snapshot())
            }
            is StreamEvent.ToolResult -> {
                val existingIndex = toolByCallId[event.toolCallId]
                val existingInput = existingIndex?.takeIf { it in parts.indices }
                    ?.let { parts[it] as? UIMessagePart.ToolUI }
                    ?.input
                val deniedOutput = event.output as? ToolResultOutput.ExecutionDenied
                // A result that returned can still signal failure — output type Error/ErrorJson, or
                // an MCP Content(isError=true). event.isError captures all of these; render it as
                // OutputError (not a green OutputAvailable card) and surface the error text.
                val resultState = when {
                    deniedOutput != null -> ToolCallState.OutputDenied
                    event.isError -> ToolCallState.OutputError
                    else -> ToolCallState.OutputAvailable
                }
                val resultError = when {
                    deniedOutput != null -> deniedOutput.reason
                    event.isError -> when (val out = event.output) {
                        is ToolResultOutput.Error -> out.message
                        is ToolResultOutput.ErrorJson -> out.json.toString()
                        is ToolResultOutput.Text,
                        is ToolResultOutput.Json,
                        is ToolResultOutput.ExecutionDenied,
                        is ToolResultOutput.Content,
                        -> event.outputJson.toString()
                    }
                    else -> null
                }
                upsertTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    state = resultState,
                    input = existingInput,
                    output = event.outputJson,
                    error = resultError,
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
                    // Carry the approval-identity key so a denial whose approvalId diverges
                    // from toolCallId can be correlated to its originating request.
                    approvalId = event.approvalId,
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
                val type = (rawObject?.get("type") as? JsonPrimitive)?.contentOrNull
                val data = rawObject?.get("data")
                if (type != null && data != null) {
                    val dataId = (rawObject["id"] as? JsonPrimitive)?.contentOrNull
                    val transient = (rawObject["transient"] as? JsonPrimitive)?.contentOrNull == "true"
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
