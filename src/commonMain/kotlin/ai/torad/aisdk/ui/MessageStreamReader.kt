package ai.torad.aisdk.ui

import ai.torad.aisdk.ProviderMetadata
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.ToolResultOutput
import ai.torad.aisdk.aiSdkJson
import ai.torad.aisdk.protocol.ProtocolAdapters
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
/** @since 0.3.0-beta01 */
public fun StreamToUiMessages(
    events: Flow<StreamEvent>,
    assistantMessageId: String,
): Flow<UIMessage> = flow {
    val parts = mutableListOf<UIMessagePart>()
    val textPartIndexById = mutableMapOf<String, Int>()
    val reasoningPartIndexById = mutableMapOf<String, Int>()
    val toolIndexesByCallId = mutableMapOf<String, MutableList<Int>>()
    val toolInputBufById = mutableMapOf<String, StringBuilder>()
    val toolNameByInputId = mutableMapOf<String, String>()
    val dataPartIndexById = mutableMapOf<String, Int>()

    fun snapshot() = UIMessage(
        id = assistantMessageId,
        role = UIMessageRole.Assistant,
        parts = parts.toList(),
    )

    fun shiftIndexesAfterRemoval(removedIndex: Int) {
        for ((key, indexes) in toolIndexesByCallId.toMap()) {
            val shifted = indexes.mapNotNull { index ->
                when {
                    index == removedIndex -> null
                    index > removedIndex -> index - 1
                    else -> index
                }
            }
            if (shifted.isEmpty()) {
                toolIndexesByCallId.remove(key)
            } else {
                toolIndexesByCallId[key] = shifted.toMutableList()
            }
        }
        for ((key, value) in textPartIndexById.toMap()) {
            if (value > removedIndex) textPartIndexById[key] = value - 1
        }
        for ((key, value) in reasoningPartIndexById.toMap()) {
            if (value > removedIndex) reasoningPartIndexById[key] = value - 1
        }
        // dataPartIndexById also stores ABSOLUTE part indices — it must shift too, or a keyed Raw
        // data part recorded after the removed placeholder gets a stale index and a later upsert
        // overwrites the wrong part (or appends a duplicate).
        for ((key, value) in dataPartIndexById.toMap()) {
            if (value > removedIndex) dataPartIndexById[key] = value - 1
        }
    }

    fun registerToolIndex(toolCallId: String, index: Int) {
        toolIndexesByCallId.getOrPut(toolCallId) { mutableListOf() } += index
    }

    fun toolAt(index: Int): UIMessagePart.ToolUI? =
        index.takeIf { it in parts.indices }?.let { parts[it] as? UIMessagePart.ToolUI }

    fun firstToolIndex(
        toolCallId: String,
        predicate: (UIMessagePart.ToolUI) -> Boolean = { true },
    ): Int? =
        toolIndexesByCallId[toolCallId]
            ?.firstOrNull { index -> toolAt(index)?.let(predicate) == true }

    fun lastToolIndex(
        toolCallId: String,
        predicate: (UIMessagePart.ToolUI) -> Boolean = { true },
    ): Int? =
        toolIndexesByCallId[toolCallId]
            ?.lastOrNull { index -> toolAt(index)?.let(predicate) == true }

    fun openTextPart(id: String, providerMetadata: ProviderMetadata) {
        if (textPartIndexById.containsKey(id)) return
        parts.add(
            UIMessagePart.Text(
                text = "",
                state = TextUIPartState.Streaming,
                providerMetadata = providerMetadata,
            ),
        )
        textPartIndexById[id] = parts.size - 1
    }

    fun appendTextPart(id: String, delta: String, providerMetadata: ProviderMetadata) {
        val idx = textPartIndexById[id]
        val existing = idx?.let { parts[it] as? UIMessagePart.Text }
        if (idx == null || existing == null) {
            // No part yet, OR the id was first opened as a different kind (a text/reasoning id
            // collision) — start a fresh Text part rather than crashing on an unchecked cast.
            parts.add(
                UIMessagePart.Text(
                    text = delta,
                    state = TextUIPartState.Streaming,
                    providerMetadata = providerMetadata,
                ),
            )
            textPartIndexById[id] = parts.size - 1
            return
        }
        parts[idx] = UIMessagePart.Text(
            text = existing.text + delta,
            state = TextUIPartState.Streaming,
            providerMetadata = existing.providerMetadata + providerMetadata,
        )
    }

    fun closeTextPart(id: String, providerMetadata: ProviderMetadata) {
        val idx = textPartIndexById[id] ?: return
        val existing = parts[idx] as? UIMessagePart.Text ?: return
        parts[idx] = UIMessagePart.Text(
            text = existing.text,
            state = TextUIPartState.Done,
            providerMetadata = existing.providerMetadata + providerMetadata,
        )
    }

    fun openReasoningPart(id: String, providerMetadata: ProviderMetadata) {
        if (reasoningPartIndexById.containsKey(id)) return
        parts.add(
            UIMessagePart.Reasoning(
                text = "",
                state = TextUIPartState.Streaming,
                providerMetadata = providerMetadata,
            ),
        )
        reasoningPartIndexById[id] = parts.size - 1
    }

    fun appendReasoningPart(id: String, delta: String, providerMetadata: ProviderMetadata) {
        val idx = reasoningPartIndexById[id]
        val existing = idx?.let { parts[it] as? UIMessagePart.Reasoning }
        if (idx == null || existing == null) {
            // Mirror appendTextPart: a fresh Reasoning part on an absent index or a kind mismatch.
            parts.add(
                UIMessagePart.Reasoning(
                    text = delta,
                    state = TextUIPartState.Streaming,
                    providerMetadata = providerMetadata,
                ),
            )
            reasoningPartIndexById[id] = parts.size - 1
            return
        }
        parts[idx] = UIMessagePart.Reasoning(
            text = existing.text + delta,
            state = TextUIPartState.Streaming,
            providerMetadata = existing.providerMetadata + providerMetadata,
        )
    }

    fun closeReasoningPart(id: String, providerMetadata: ProviderMetadata) {
        val idx = reasoningPartIndexById[id] ?: return
        val existing = parts[idx] as? UIMessagePart.Reasoning ?: return
        parts[idx] = UIMessagePart.Reasoning(
            text = existing.text,
            state = TextUIPartState.Done,
            providerMetadata = existing.providerMetadata + providerMetadata,
        )
    }

    data class ToolUpsertOptions(val preliminary: Boolean = false, val appendNew: Boolean = false)

    fun upsertTool(
        toolCallId: String,
        toolName: String,
        state: ToolCallState,
        input: JsonElement? = null,
        output: JsonElement? = null,
        error: String? = null,
        approvalId: String? = null,
        signature: String? = null,
        providerMetadata: ProviderMetadata = ProviderMetadata.None,
        existingIndex: Int? = null,
        options: ToolUpsertOptions = ToolUpsertOptions(),
        matchExisting: ((UIMessagePart.ToolUI) -> Boolean)? = null,
    ) {
        val targetIndex = when {
            existingIndex != null -> existingIndex
            options.appendNew -> null
            matchExisting != null -> firstToolIndex(toolCallId, matchExisting)
            else -> lastToolIndex(toolCallId)
        }
        val existing = targetIndex?.let { toolAt(it) }
        val nextPart = UIMessagePart.ToolUI(
            toolCallId = toolCallId,
            toolName = toolName,
            state = state,
            input = input,
            output = output,
            error = error,
            preliminary = options.preliminary,
            approvalId = approvalId ?: existing?.approvalId,
            signature = signature ?: existing?.signature,
            providerMetadata = existing?.providerMetadata?.plus(providerMetadata) ?: providerMetadata,
        )
        if (targetIndex != null) {
            parts[targetIndex] = nextPart
        } else {
            parts.add(nextPart)
            registerToolIndex(toolCallId, parts.size - 1)
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
                openTextPart(event.id, event.providerMetadata)
                emit(snapshot())
            }
            is StreamEvent.TextDelta -> {
                appendTextPart(event.id, event.text, event.providerMetadata)
                emit(snapshot())
            }
            is StreamEvent.TextEnd -> {
                closeTextPart(event.id, event.providerMetadata)
                emit(snapshot())
            }
            is StreamEvent.ReasoningStart -> {
                openReasoningPart(event.id, event.providerMetadata)
                emit(snapshot())
            }
            is StreamEvent.ReasoningDelta -> {
                appendReasoningPart(event.id, event.text, event.providerMetadata)
                emit(snapshot())
            }
            is StreamEvent.ReasoningEnd -> {
                closeReasoningPart(event.id, event.providerMetadata)
                emit(snapshot())
            }
            is StreamEvent.SourcePart -> {
                val partForEvent = when (event.sourceType) {
                    StreamEvent.SourcePart.SourceType.Url -> UIMessagePart.SourceUrl(
                        sourceId = event.id,
                        url = event.url.orEmpty(),
                        title = event.title,
                        providerMetadata = event.providerMetadata,
                    )
                    StreamEvent.SourcePart.SourceType.Document -> UIMessagePart.SourceDocument(
                        sourceId = event.id,
                        mediaType = event.mediaType.orEmpty(),
                        title = event.title.orEmpty(),
                        filename = ProtocolAdapters.metadataString(event.providerMetadata, "filename"),
                        providerMetadata = event.providerMetadata,
                    )
                }
                parts.add(partForEvent)
                emit(snapshot())
            }
            is StreamEvent.FilePart -> {
                parts.add(
                    UIMessagePart.File(
                        mediaType = event.mediaType,
                        base64 = event.base64,
                        filename = ProtocolAdapters.metadataString(event.providerMetadata, "filename"),
                        providerMetadata = event.providerMetadata,
                    ),
                )
                emit(snapshot())
            }
            is StreamEvent.ToolInputStart -> {
                toolInputBufById[event.id] = StringBuilder()
                toolNameByInputId[event.id] = event.toolName
                upsertTool(
                    toolCallId = event.id,
                    toolName = event.toolName,
                    state = ToolCallState.InputStreaming,
                    providerMetadata = event.providerMetadata,
                )
                emit(snapshot())
            }
            is StreamEvent.ToolInputDelta -> {
                val buf = toolInputBufById.getOrPut(event.id) { StringBuilder() }
                buf.append(event.delta)
                val partial = runCatching { aiSdkJson.parseToJsonElement(buf.toString()) }.getOrNull()
                val toolName = toolNameByInputId[event.id] ?: return@collect
                val targetIndex = lastToolIndex(event.id)
                if (
                    targetIndex?.let {
                        val state = toolAt(it)?.state
                        state == ToolCallState.OutputAvailable ||
                            state == ToolCallState.OutputError ||
                            state == ToolCallState.OutputDenied
                    } == true
                ) return@collect
                upsertTool(
                    toolCallId = event.id,
                    toolName = toolName,
                    state = ToolCallState.InputStreaming,
                    input = partial,
                    providerMetadata = event.providerMetadata,
                    existingIndex = targetIndex,
                )
                emit(snapshot())
            }
            is StreamEvent.ToolInputEnd -> {
                val placeholderIdx = lastToolIndex(event.id)
                if (placeholderIdx != null && placeholderIdx in parts.indices) {
                    parts.removeAt(placeholderIdx)
                    shiftIndexesAfterRemoval(placeholderIdx)
                }
                toolInputBufById.remove(event.id)
                toolNameByInputId.remove(event.id)
            }
            is StreamEvent.ToolCall -> {
                // The streaming partial id (ToolInputStart.id) is typically distinct
                // from the final ToolCall.toolCallId. If we see a placeholder in
                // toolByCallId for the same toolName, drop it before adding the
                // final entry so we don't render two cards.
                val placeholderId = when {
                    event.toolCallId in toolNameByInputId -> event.toolCallId
                    else -> toolNameByInputId.entries
                        .firstOrNull {
                            if (it.value != event.toolName || it.key == event.toolCallId) return@firstOrNull false
                            val raw = toolInputBufById[it.key]?.toString()?.trim().orEmpty()
                            if (raw.isEmpty()) return@firstOrNull false
                            runCatching { aiSdkJson.parseToJsonElement(raw) }
                                .getOrNull()
                                ?.let { buffered -> return@firstOrNull buffered == event.inputJson }
                            event.inputJson.toString().startsWith(raw)
                        }
                        ?.key
                }
                if (placeholderId != null) {
                    val placeholderIdx = lastToolIndex(placeholderId)
                    if (placeholderIdx != null && placeholderIdx in parts.indices) {
                        parts.removeAt(placeholderIdx)
                        shiftIndexesAfterRemoval(placeholderIdx)
                    }
                    toolNameByInputId.remove(placeholderId)
                    toolInputBufById.remove(placeholderId)
                }
                upsertTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    state = ToolCallState.InputAvailable,
                    input = event.inputJson,
                    providerMetadata = event.providerMetadata,
                    options = ToolUpsertOptions(appendNew = true),
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
                    providerMetadata = event.providerMetadata,
                    matchExisting = {
                        it.toolName == event.toolName &&
                            it.input == event.inputJson &&
                            it.approvalId == null &&
                            it.state != ToolCallState.OutputAvailable &&
                            it.state != ToolCallState.OutputError &&
                            it.state != ToolCallState.OutputDenied
                    },
                )
                emit(snapshot())
            }
            is StreamEvent.ToolResult -> {
                val resultIndex = firstToolIndex(event.toolCallId) {
                    it.state == ToolCallState.OutputDenied && it.output == null
                } ?: firstToolIndex(event.toolCallId) {
                    it.preliminary
                } ?: firstToolIndex(event.toolCallId) {
                    it.state == ToolCallState.InputStreaming ||
                        it.state == ToolCallState.InputAvailable ||
                        it.state == ToolCallState.ApprovalRequested
                }
                val existingInput = resultIndex?.let { toolAt(it) }?.input
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
                    providerMetadata = event.providerMetadata,
                    existingIndex = resultIndex,
                    options = ToolUpsertOptions(preliminary = event.preliminary, appendNew = resultIndex == null),
                )
                emit(snapshot())
            }
            is StreamEvent.ToolError -> {
                val errorIndex = firstToolIndex(event.toolCallId) {
                    it.state == ToolCallState.InputStreaming ||
                        it.state == ToolCallState.InputAvailable ||
                        it.state == ToolCallState.ApprovalRequested ||
                        it.preliminary
                }
                val existingInput = errorIndex?.let { toolAt(it) }?.input
                upsertTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    state = ToolCallState.OutputError,
                    input = existingInput,
                    error = event.message,
                    providerMetadata = event.providerMetadata,
                    existingIndex = errorIndex,
                    options = ToolUpsertOptions(appendNew = errorIndex == null),
                )
                emit(snapshot())
            }
            is StreamEvent.ToolOutputDenied -> {
                val deniedIndex = firstToolIndex(event.toolCallId) {
                    it.approvalId == event.approvalId
                } ?: firstToolIndex(event.toolCallId) {
                    it.state == ToolCallState.ApprovalRequested ||
                        it.state == ToolCallState.InputAvailable ||
                        it.state == ToolCallState.InputStreaming
                }
                val existingInput = deniedIndex?.let { toolAt(it) }?.input
                upsertTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    state = ToolCallState.OutputDenied,
                    input = existingInput,
                    // Carry the approval-identity key so a denial whose approvalId diverges
                    // from toolCallId can be correlated to its originating request.
                    approvalId = event.approvalId,
                    error = event.reason,
                    providerMetadata = event.providerMetadata,
                    existingIndex = deniedIndex,
                    options = ToolUpsertOptions(appendNew = deniedIndex == null),
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
            is StreamEvent.Data -> {
                val part = UIMessagePart.Data(
                    type = "data-${event.name}",
                    data = event.data,
                    id = event.id,
                    transient = event.transient,
                )
                // Keyed data parts upsert in place; unkeyed ones append.
                val existingIdx = event.id?.let { dataPartIndexById[it] }
                if (existingIdx != null && existingIdx in parts.indices) {
                    parts[existingIdx] = part
                } else {
                    parts.add(part)
                    if (event.id != null) dataPartIndexById[event.id] = parts.size - 1
                }
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
