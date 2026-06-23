package ai.torad.aisdk.ui

import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.MessageRole
import ai.torad.aisdk.ModelMessage
import ai.torad.aisdk.ProviderMetadata
import ai.torad.aisdk.StreamEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * UI-to-model message conversion. Groups [convertToModelMessages] and its
 * helpers so none remain loose top-level functions.
 */
public object ModelMessageConversion {

    /**
     * Convert a list of UI-shape [UIMessage]s back into the model-shape
     * [ModelMessage] list the LLM expects on the next turn. Mirrors v6's
     * `convertToModelMessages` (per historical parity gap #5).
     *
     * Required for any flow that PERSISTS the chat in its rendered form
     * and later wants to RESUME the agent: history replay, crash
     * recovery, subagent continuation, manual edit & re-run.
     *
     * Conversion rules:
     *
     * - [UIMessagePart.Text] → `ContentPart.Text`
     * - [UIMessagePart.Reasoning] → `ContentPart.Reasoning`
     * - [UIMessagePart.ToolUI] (static) AND [UIMessagePart.DynamicToolUI]
     *   (runtime) share one rule, dispatched by `state`:
     *     - `OutputAvailable` + `preliminary=false` → assistant carries
     *       `ContentPart.ToolCall`; a SEPARATE follow-up `Tool`-role
     *       [ModelMessage] carries `ContentPart.ToolResult`. (Preliminary
     *       snapshots from `streamingTool` never flow to the model — only
     *       the final emission. Preliminary parts are dropped here.)
     *     - `ApprovalRequired` → `ContentPart.ToolApprovalRequest`
     *     - `InputStreaming` / `InputAvailable` are INCOMPLETE — drop
     *       silently if [ignoreIncompleteToolCalls], otherwise throw.
     *     - `Error` → drop. The tool failed; the model already saw a
     *       `ContentPart.ToolResult` with `isError=true` at the time
     *       (encoded by the agent loop), no useful re-send shape.
     * - [UIMessagePart.StepStart], `Source`, `File`, `Error` → drop.
     *   No useful model-side representation, or out-of-scope for v0.
     *
     * Ordering is preserved: each UIMessage with tool calls becomes:
     *   `Assistant(text, ToolCall...) → Tool(ToolResult) → Tool(ToolResult) → …`
     * — which is what the model expects on the next turn.
     *
     * @param messages The UI-shape history to convert.
     * @param ignoreIncompleteToolCalls If true, drop tool parts in
     *     `InputStreaming` / `InputAvailable` state silently. If false
     *     (default), throw [IllegalStateException] when an incomplete
     *     tool call is seen — these usually indicate corrupted history.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    public fun convertToModelMessages(
        messages: List<UIMessage>,
        ignoreIncompleteToolCalls: Boolean = false,
    ): List<ModelMessage> {
        val result = mutableListOf<ModelMessage>()
        for (uiMsg in messages) {
            val approvalResponse = approvalResponseMessage(uiMsg)
            if (approvalResponse != null) {
                result.add(approvalResponse)
                continue
            }
            val role = when (uiMsg.role) {
                UIMessageRole.System -> MessageRole.System
                UIMessageRole.User -> MessageRole.User
                UIMessageRole.Assistant -> MessageRole.Assistant
            }
            // An assistant turn spanning multiple steps (tool round-trips) replays as
            // an interleaved assistant/tool/assistant sequence — one message group per
            // step-start boundary — rather than a single merged message (upstream parity).
            val groups = if (role == MessageRole.Assistant) splitAtStepBoundaries(uiMsg.parts) else listOf(uiMsg.parts)
            for (group in groups) {
                val parts = mutableListOf<ContentPart>()
                val deferredToolResults = mutableListOf<ContentPart.ToolResult>()
                for (part in group) {
                    convertPart(
                        part = part,
                        ignoreIncompleteToolCalls = ignoreIncompleteToolCalls,
                        onContentPart = parts::add,
                        onDeferredToolResult = deferredToolResults::add,
                        contextHint = "UIMessage(id=${uiMsg.id})",
                    )
                }
                if (parts.isNotEmpty()) {
                    result.add(ModelMessage(role = role, content = parts.toList()))
                }
                for (toolResult in deferredToolResults) {
                    result.add(ModelMessage(role = MessageRole.Tool, content = listOf(toolResult)))
                }
            }
        }
        return result
    }

    /** Partition parts into step groups at each [UIMessagePart.StepStart] boundary. */
    private fun splitAtStepBoundaries(parts: List<UIMessagePart>): List<List<UIMessagePart>> {
        val groups = mutableListOf<List<UIMessagePart>>()
        var current = mutableListOf<UIMessagePart>()
        for (part in parts) {
            if (part is UIMessagePart.StepStart && current.isNotEmpty()) {
                groups.add(current)
                current = mutableListOf()
            }
            current.add(part)
        }
        if (current.isNotEmpty()) groups.add(current)
        return groups.ifEmpty { listOf(emptyList()) }
    }

    private fun approvalResponseMessage(uiMsg: UIMessage): ModelMessage? {
        if (uiMsg.role != UIMessageRole.User || uiMsg.parts.size != 1) return null
        val part = uiMsg.parts.single() as? UIMessagePart.ToolUI ?: return null
        if (part.toolName != "approval") return null
        val approved = when (part.state) {
            ToolCallState.OutputAvailable -> true
            ToolCallState.OutputDenied -> false
            else -> return null
        }
        val approvalId = part.approvalId ?: when (val output = part.output) {
            null -> part.toolCallId
            is JsonPrimitive -> output.contentOrNull ?: part.toolCallId
            else -> throw IllegalArgumentException("Approval response output must be a string approval id.")
        }
        return ModelMessage(
            role = MessageRole.Tool,
            content = listOf(
                ContentPart.ToolApprovalResponse(
                    toolCallId = part.toolCallId,
                    approved = approved,
                    reason = part.error,
                    approvalId = approvalId,
                ),
            ),
        )
    }

    /**
     * Per-part conversion. Extracted as a helper so
     * [convertToModelMessages] stays readable + so the static / dynamic
     * tool branches share one body. Returns nothing — emits via the
     * supplied callbacks so the caller controls assistant-vs-tool
     * placement.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun convertPart(
        part: UIMessagePart,
        ignoreIncompleteToolCalls: Boolean,
        onContentPart: (ContentPart) -> Unit,
        onDeferredToolResult: (ContentPart.ToolResult) -> Unit,
        contextHint: String,
    ) {
        when (part) {
            is UIMessagePart.Text -> onContentPart(ContentPart.Text(part.text, providerMetadata = part.providerMetadata))
            is UIMessagePart.Reasoning ->
                onContentPart(ContentPart.Reasoning(part.text, providerMetadata = part.providerMetadata))
            is UIMessagePart.ToolUI -> convertToolCall(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                state = part.state,
                input = part.input,
                output = part.output,
                flags = ToolConversionFlags(part.preliminary, ignoreIncompleteToolCalls),
                approvalId = part.approvalId,
                signature = part.signature,
                providerMetadata = part.providerMetadata,
                onContentPart = onContentPart,
                onDeferredToolResult = onDeferredToolResult,
                contextHint = contextHint,
            )
            is UIMessagePart.DynamicToolUI -> convertToolCall(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                state = part.state,
                input = part.input,
                output = part.output,
                flags = ToolConversionFlags(part.preliminary, ignoreIncompleteToolCalls),
                // Dynamic tool parts carry no approval identity (server-defined tools only).
                approvalId = null,
                signature = null,
                providerMetadata = part.providerMetadata,
                onContentPart = onContentPart,
                onDeferredToolResult = onDeferredToolResult,
                contextHint = contextHint,
            )
            // User-attached files and model sources DO carry to the model (they were
            // previously dropped, losing attachments and source provenance).
            is UIMessagePart.File,
            is UIMessagePart.SourceUrl,
            is UIMessagePart.SourceDocument,
            -> mediaOrSourcePart(part)?.let(onContentPart)
            // StepStart is a UI boundary marker; Data/Error are UI-only — not model content.
            is UIMessagePart.StepStart,
            is UIMessagePart.Data,
            is UIMessagePart.Error,
            -> Unit
        }
    }

    /** Converts a File / SourceUrl / SourceDocument UI part to its model content part. */
    private fun mediaOrSourcePart(part: UIMessagePart): ContentPart? = when (part) {
        is UIMessagePart.File -> ContentPart.File(
            mediaType = part.mediaType,
            base64 = part.base64,
            filename = part.filename,
            providerMetadata = part.providerMetadata,
        )
        is UIMessagePart.SourceUrl -> ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Url,
            sourceId = part.sourceId,
            url = part.url,
            title = part.title,
            providerMetadata = part.providerMetadata,
        )
        is UIMessagePart.SourceDocument -> ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Document,
            sourceId = part.sourceId,
            title = part.title,
            providerMetadata = part.providerMetadata,
            mediaType = part.mediaType,
            filename = part.filename,
        )
        is UIMessagePart.Text,
        is UIMessagePart.ToolUI,
        is UIMessagePart.Reasoning,
        is UIMessagePart.Error,
        is UIMessagePart.Data,
        is UIMessagePart.StepStart,
        is UIMessagePart.DynamicToolUI,
        -> null
    }

    private data class ToolConversionFlags(
        val preliminary: Boolean,
        val ignoreIncompleteToolCalls: Boolean,
    )

    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    private fun convertToolCall(
        toolCallId: String,
        toolName: String,
        state: ToolCallState,
        input: kotlinx.serialization.json.JsonElement?,
        output: kotlinx.serialization.json.JsonElement?,
        flags: ToolConversionFlags,
        approvalId: String?,
        signature: String?,
        providerMetadata: ProviderMetadata,
        onContentPart: (ContentPart) -> Unit,
        onDeferredToolResult: (ContentPart.ToolResult) -> Unit,
        contextHint: String,
    ) {
        when (state) {
            ToolCallState.OutputAvailable -> {
                if (flags.preliminary) return
                onContentPart(
                    ContentPart.ToolCall(
                        toolCallId = toolCallId,
                        toolName = toolName,
                        input = requireNotNull(input) { "ToolCall.input absent at OutputAvailable in $contextHint" },
                        providerMetadata = providerMetadata,
                    ),
                )
                onDeferredToolResult(
                    ContentPart.ToolResult(
                        toolCallId = toolCallId,
                        toolName = toolName,
                        output = requireNotNull(output) { "ToolResult.output absent at OutputAvailable in $contextHint" },
                        providerMetadata = providerMetadata,
                    ),
                )
            }
            // The model is mid-approval flow. We send ToolApprovalRequest to
            // resume — same shape whether the user has answered (Responded)
            // or hasn't (Requested) — the agent layer will re-emit the
            // resume signal once the matching ToolApprovalResponse appears.
            ToolCallState.ApprovalRequested, ToolCallState.ApprovalResponded -> onContentPart(
                ContentPart.ToolApprovalRequest(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = requireNotNull(input) { "ToolApprovalRequest.input absent at approval state in $contextHint" },
                    approvalId = approvalId,
                    // The v6.0.202 HMAC signature must survive the UI round-trip: with a
                    // configured approval secret, a replay without it is denied fail-closed.
                    signature = signature,
                    providerMetadata = providerMetadata,
                ),
            )
            ToolCallState.InputStreaming, ToolCallState.InputAvailable -> if (!flags.ignoreIncompleteToolCalls) {
                error(
                    "incomplete tool call '$toolName' (id=$toolCallId, state=$state) in $contextHint; " +
                        "set ignoreIncompleteToolCalls=true to drop these silently",
                )
            }
            // OutputError / OutputDenied don't re-flow to the model on
            // resume — the original ToolResult / approval-denial outcome
            // already landed in the persisted message log.
            ToolCallState.OutputError, ToolCallState.OutputDenied -> Unit
        }
    }
}
