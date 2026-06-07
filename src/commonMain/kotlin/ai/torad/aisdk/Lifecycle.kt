package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

/**
 * Lifecycle hook event types (best practice #10). Hooks are observation
 * points — they do NOT modify behavior. If a hook needs to influence the
 * loop, the right place is [PrepareStepScope] / [PrepareCallScope].
 *
 * Hook failures do not crash the loop — they're logged via
 * [OnErrorEvent]. Tool and prepareStep failures DO crash the loop (those
 * are real errors).
 */
public data class OnStartEvent(
    val prompt: String?,
    val priorMessages: List<ModelMessage>,
    val options: Any?,
)

/**
 * Fired before each loop step's model call. Per AISDK_PORT_GAPS.md
 * gap #35, the payload was extended from `(stepNumber, messages)` to
 * carry the full prepared [request] (post `prepareCall` + `prepareStep`
 * overrides) plus accumulated [priorSteps] so observers can:
 *
 * - Inspect the EXACT params being sent (system prompt, tool subset,
 *   sampler overrides) — useful for telemetry and reproduction.
 * - Read prior-step output without re-walking the [StepResult] list
 *   from a separate accumulator.
 *
 * Both new fields default to null / empty so existing observers keep
 * working unchanged. Loop-side population is staged in as a follow-up;
 * the type surface is in place now.
 */
public data class OnStepStartEvent(
    val stepNumber: Int,
    val messages: List<ModelMessage>,
    /**
     * The prepared call params for this step. Null when the loop
     * hasn't yet wired the field (back-compat). When set, includes
     * the resolved system prompt, tool subset, sampler params, etc.
     */
    val request: LanguageModelCallParams? = null,
    /**
     * Accumulated prior-step results — `priorSteps[i]` is step i+1's
     * outcome. Empty on step 1.
     */
    val priorSteps: List<StepResult> = emptyList(),
)

public data class OnStepFinishEvent(
    val stepNumber: Int,
    val step: StepResult,
)

public data class OnFinishEvent(
    val finalOutput: Any?,
    val totalSteps: Int,
    val usage: Usage,
    val pendingApprovals: List<PendingApproval> = emptyList(),
    /**
     * Final accumulated message list at the end of this generation
     * (system + user + assistant + tool messages). Hosts use this to
     * resume after a tool-approval pause without re-walking the events
     * themselves: `agent.generate(messages = onFinish.messages + approvalResponse)`.
     */
    val messages: List<ModelMessage> = emptyList(),
    /**
     * Final typed context after any `prepareStep.experimental_context`
     * overrides — the value `ToolExecutionContext.context` carried on
     * the last step. Mirrors v6's `OnFinishEvent.experimental_context`
     * (per AISDK_PORT_GAPS.md gap #36). Null when no override
     * happened (the call started with `options = null`). Typed as
     * `Any?` at the erasure-friendly hook surface; consumers cast to
     * their `TContext` when they need it.
     */
    val experimental_context: Any? = null,
)

public data class OnErrorEvent(
    val error: Throwable,
    val stepNumber: Int,
    val source: ErrorSource,
) {
    public enum class ErrorSource { Hook, Tool, PrepareStep, PrepareCall, Model, Unknown }
}

/** Each individual stream chunk, alongside the step it belongs to. */
public data class OnChunkEvent(
    val event: StreamEvent,
    val stepNumber: Int,
)

/**
 * Fired immediately before a tool's executor runs. Carries the parsed
 * tool call envelope and the messages-list snapshot so observers can
 * record context (e.g. for tracing).
 */
public data class OnToolCallStartEvent(
    val toolCallId: String,
    val toolName: String,
    val input: JsonElement,
    val stepNumber: Int,
    val messages: List<ModelMessage>,
)

/**
 * Fired immediately after a tool's executor returns or throws. Exactly
 * one of [outputJson] or [errorMessage] is non-null.
 */
public data class OnToolCallFinishEvent(
    val toolCallId: String,
    val toolName: String,
    val outputJson: JsonElement?,
    val errorMessage: String?,
    val stepNumber: Int,
)

/**
 * Snapshot of one completed loop step — surfaced to [OnStepFinishEvent]
 * and accumulated in the loop state for [PrepareStepScope.steps].
 */
public data class StepResult(
    val stepNumber: Int,
    val text: String,
    val reasoning: String,
    val toolCalls: List<ContentPart.ToolCall>,
    val toolResults: List<ContentPart.ToolResult>,
    val toolApprovalRequests: List<ContentPart.ToolApprovalRequest>,
    val finishReason: FinishReason,
    val usage: Usage,
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
    val rawFinishReason: String? = null,
    /** The model id that produced this step (upstream's `model.modelId`). */
    val model: String? = null,
    /** The evolving agent context live for this step (upstream's `experimental_context`). */
    val experimentalContext: Any? = null,
) {
    /** The non-empty reasoning text for this step, or null — mirrors upstream's `reasoningText`. */
    val reasoningText: String? get() = reasoning.takeIf { it.isNotEmpty() }

    /** Tool calls/results split by static (`tool(...)`) vs dynamic (`dynamicTool(...)`), per upstream. */
    val staticToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { !it.dynamic }
    val dynamicToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { it.dynamic }
    val staticToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { !it.dynamic }
    val dynamicToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { it.dynamic }
}
