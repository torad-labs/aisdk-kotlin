package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

/**
 * The sealed agent-lifecycle event hierarchy — the single source of truth for
 * everything the loop emits. [ToolLoopAgent] surfaces these as a
 * `Flow<AgentEvent>`; observers dispatch with an exhaustive `when` (no `else`).
 * Replaces the former bag of flat `OnXEvent` data classes + nullable callbacks.
 *
 * Telemetry's model-call and span events ([ModelCallStarted], [ModelCallFinished],
 * [SpanEmitted]) live in the same hierarchy, so a telemetry integration is just
 * another collector of the one stream.
 *
 * Payloads are typed — no `Any?` at the event surface: [Started.options] and
 * [Finished.experimentalContext] are `TContext`; [Finished.output] is `TOutput`.
 *
 * Events OBSERVE — they never modify loop behavior. To influence the loop, use
 * [PrepareStepScope] / [PrepareCallScope]. A collector throwing does not crash
 * the loop; the failure surfaces as an [Errored] event.
 */
public sealed class AgentEvent {
    /** Generation started. [options] is the typed agent context (was `Any?`). */
    public data class Started<TContext>(
        val prompt: String?,
        val priorMessages: List<ModelMessage>,
        val options: TContext?,
    ) : AgentEvent()

    /**
     * Fired before each loop step's model call, AFTER `prepareCall` +
     * `prepareStep` have resolved it. [request] carries the EXACT prepared
     * params (system prompt, tool subset, sampler overrides); [priorSteps] are
     * the accumulated step results (`priorSteps[i]` is step i+1's outcome, empty
     * on step 1). Both required — the event fires only once the params exist.
     */
    public data class StepStarted(
        val stepNumber: Int,
        val messages: List<ModelMessage>,
        val request: LanguageModelCallParams,
        val priorSteps: List<StepResult>,
    ) : AgentEvent()

    /** Each individual stream chunk, alongside the step it belongs to. */
    public data class Chunk(
        val event: StreamEvent,
        val stepNumber: Int,
    ) : AgentEvent()

    public data class StepFinished(
        val stepNumber: Int,
        val step: StepResult,
    ) : AgentEvent()

    /**
     * Fired immediately before a tool's executor runs. Carries the parsed tool
     * call envelope and the messages-list snapshot so observers can record context.
     */
    public data class ToolCallStarted(
        val toolCallId: String,
        val toolName: String,
        val input: JsonElement,
        val stepNumber: Int,
        val messages: List<ModelMessage>,
    ) : AgentEvent()

    /**
     * Fired immediately after a tool's executor returns or throws. Carries an
     * owned typed [outcome] instead of paired nullable success/error fields.
     */
    public data class ToolCallFinished(
        val toolCallId: String,
        val toolName: String,
        val outcome: Outcome,
        val stepNumber: Int,
    ) : AgentEvent() {
        public sealed class Outcome {
            public data class Success(val outputJson: JsonElement) : Outcome()
            public data class Failure(val errorMessage: String) : Outcome()
        }
    }

    public data class Errored(
        val error: Throwable,
        val stepNumber: Int,
        val source: ErrorSource,
    ) : AgentEvent() {
        public enum class ErrorSource { Hook, Tool, PrepareStep, PrepareCall, Model, Unknown }
    }

    /**
     * Fired when generation is aborted via [AbortSignal] before it finishes.
     * Carries the steps completed up to the abort.
     */
    public data class Aborted(
        val steps: List<StepResult>,
    ) : AgentEvent()

    /**
     * Generation finished. [output] is the typed agent output (was `Any?`);
     * [experimentalContext] is the typed context after any `prepareStep`
     * override (was `Any?`). [messages] is the final accumulated message list,
     * for resuming after a tool-approval pause without re-walking the events.
     */
    public data class Finished<TContext, TOutput>(
        // Nullable for now: the base loop doesn't compute the typed output here (it flows via
        // `generate(): TOutput`); `null` preserves the prior behavior. Wiring a real value is a
        // step-2 dispatch concern. Typed (no `Any?`) regardless.
        val output: TOutput?,
        val totalSteps: Int,
        val usage: Usage,
        val pendingApprovals: List<PendingApproval> = emptyList(),
        val messages: List<ModelMessage> = emptyList(),
        val experimentalContext: TContext? = null,
    ) : AgentEvent()

    /** Before one step's model call — the EXACT prepared params sent to the provider. */
    public data class ModelCallStarted(
        val stepNumber: Int,
        val modelId: String?,
        val params: LanguageModelCallParams,
    ) : AgentEvent()

    /** After one step's model call streamed to completion. */
    public data class ModelCallFinished(
        val stepNumber: Int,
        val modelId: String?,
        val finishReason: FinishReason,
        val usage: Usage,
        val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
        val rawFinishReason: String? = null,
    ) : AgentEvent()

    /** A span sub-event (name + attributes), emitted via [TelemetryActiveSpan.addEvent]. */
    public data class SpanEmitted(
        val name: String,
        val attributes: Map<String, JsonElement> = emptyMap(),
    ) : AgentEvent()
}

/**
 * Snapshot of one completed loop step — surfaced to [AgentEvent.StepFinished]
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

    /** Tool calls/results split by static (`Tool(...)`) vs dynamic (`DynamicTool(...)`). */
    val staticToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { !it.dynamic }
    val dynamicToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { it.dynamic }
    val staticToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { !it.dynamic }
    val dynamicToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { it.dynamic }
}
