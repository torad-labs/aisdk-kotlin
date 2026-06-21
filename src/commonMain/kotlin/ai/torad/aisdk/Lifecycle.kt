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
 * Fired before each loop step's model call, AFTER `prepareCall` +
 * `prepareStep` have resolved it. Per historical parity gap #35, the
 * payload carries the full prepared [request] plus accumulated
 * [priorSteps] so observers can:
 *
 * - Inspect the EXACT params being sent (system prompt, tool subset,
 *   sampler overrides) — useful for telemetry and reproduction.
 * - Read prior-step output without re-walking the [StepResult] list
 *   from a separate accumulator.
 *
 * [request] and [priorSteps] are REQUIRED (no defaults): the loop fires
 * this event only after the call params exist, so there is no
 * "declared-but-permanently-empty" state — every construction site must
 * supply the real data it has in scope.
 */
public data class OnStepStartEvent(
    val stepNumber: Int,
    val messages: List<ModelMessage>,
    /**
     * The fully-resolved call params for this step: resolved system
     * prompt, tool subset, sampler params, etc., after the
     * `prepareCall`/`prepareStep` overrides have been applied.
     */
    val request: LanguageModelCallParams,
    /**
     * Accumulated prior-step results — `priorSteps[i]` is step i+1's
     * outcome. Empty on step 1.
     */
    val priorSteps: List<StepResult>,
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
     * (per historical parity gap #36). Null when no override
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
 * Fired when generation is aborted via [AbortSignal] before it finishes.
 * Carries the steps completed up to the abort. Mirrors upstream's `onAbort({ steps })`.
 */
public data class OnAbortEvent(
    val steps: List<StepResult>,
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
 * Fired immediately after a tool's executor returns or throws. Carries
 * an owned typed [outcome] instead of paired nullable success/error fields.
 */
public data class OnToolCallFinishEvent(
    val toolCallId: String,
    val toolName: String,
    val outcome: Outcome,
    val stepNumber: Int,
) {
    public sealed class Outcome {
        public data class Success(val outputJson: JsonElement) : Outcome()
        public data class Failure(val errorMessage: String) : Outcome()
    }
}

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
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
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
