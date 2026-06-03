package ai.torad.aisdk.ui

import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.MessageRole
import ai.torad.aisdk.ModelMessage
import kotlinx.serialization.json.JsonNull

/**
 * Convert a list of UI-shape [UIMessage]s back into the model-shape
 * [ModelMessage] list the LLM expects on the next turn. Mirrors v6's
 * `convertToModelMessages` (per AISDK_PORT_GAPS.md gap #5).
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
fun convertToModelMessages(
    messages: List<UIMessage>,
    ignoreIncompleteToolCalls: Boolean = false,
): List<ModelMessage> {
    val result = mutableListOf<ModelMessage>()
    for (uiMsg in messages) {
        val role = when (uiMsg.role) {
            UIMessageRole.System -> MessageRole.System
            UIMessageRole.User -> MessageRole.User
            UIMessageRole.Assistant -> MessageRole.Assistant
        }
        val parts = mutableListOf<ContentPart>()
        val deferredToolResults = mutableListOf<ContentPart.ToolResult>()
        for (part in uiMsg.parts) {
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
    return result
}

/**
 * Per-part conversion. Extracted as a top-level helper so
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
        is UIMessagePart.Text -> onContentPart(ContentPart.Text(part.text))
        is UIMessagePart.Reasoning -> onContentPart(ContentPart.Reasoning(part.text))
        is UIMessagePart.ToolUI -> convertToolCall(
            toolCallId = part.toolCallId,
            toolName = part.toolName,
            state = part.state,
            input = part.input,
            output = part.output,
            preliminary = part.preliminary,
            ignoreIncompleteToolCalls = ignoreIncompleteToolCalls,
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
            preliminary = part.preliminary,
            ignoreIncompleteToolCalls = ignoreIncompleteToolCalls,
            onContentPart = onContentPart,
            onDeferredToolResult = onDeferredToolResult,
            contextHint = contextHint,
        )
        is UIMessagePart.StepStart,
        is UIMessagePart.SourceUrl,
        is UIMessagePart.SourceDocument,
        is UIMessagePart.File,
        is UIMessagePart.Error,
        -> Unit
    }
}

@Suppress("LongParameterList", "CyclomaticComplexMethod")
private fun convertToolCall(
    toolCallId: String,
    toolName: String,
    state: ToolCallState,
    input: kotlinx.serialization.json.JsonElement?,
    output: kotlinx.serialization.json.JsonElement?,
    preliminary: Boolean,
    ignoreIncompleteToolCalls: Boolean,
    onContentPart: (ContentPart) -> Unit,
    onDeferredToolResult: (ContentPart.ToolResult) -> Unit,
    contextHint: String,
) {
    when (state) {
        ToolCallState.OutputAvailable -> {
            if (preliminary) return
            onContentPart(
                ContentPart.ToolCall(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = input ?: JsonNull,
                ),
            )
            onDeferredToolResult(
                ContentPart.ToolResult(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    output = output ?: JsonNull,
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
                input = input ?: JsonNull,
            ),
        )
        ToolCallState.InputStreaming, ToolCallState.InputAvailable -> if (!ignoreIncompleteToolCalls) {
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
