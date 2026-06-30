package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
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
    @Poko
    public class Started<TContext>(
        public val prompt: String?,
        public val priorMessages: List<ModelMessage>,
        public val options: TContext?,
    ) : AgentEvent()

    /**
     * Fired before each loop step's model call, AFTER `prepareCall` +
     * `prepareStep` have resolved it. [request] carries the EXACT prepared
     * params (system prompt, tool subset, sampler overrides); [priorSteps] are
     * the accumulated step results (`priorSteps[i]` is step i+1's outcome, empty
     * on step 1). Both required — the event fires only once the params exist.
     */
    @Poko
    public class StepStarted(
        public val stepNumber: Int,
        public val messages: List<ModelMessage>,
        public val request: LanguageModelCallParams,
        public val priorSteps: List<StepResult>,
    ) : AgentEvent()

    /** Each individual stream chunk, alongside the step it belongs to. */
    @Poko
    public class Chunk(
        public val event: StreamEvent,
        public val stepNumber: Int,
    ) : AgentEvent()

    @Poko
    public class StepFinished(
        public val stepNumber: Int,
        public val step: StepResult,
    ) : AgentEvent()

    /**
     * Fired immediately before a tool's executor runs. Carries the parsed tool
     * call envelope and the messages-list snapshot so observers can record context.
     */
    @Poko
    public class ToolCallStarted(
        public val toolCallId: String,
        public val toolName: String,
        public val input: JsonElement,
        public val stepNumber: Int,
        public val messages: List<ModelMessage>,
    ) : AgentEvent()

    /**
     * Fired immediately after a tool's executor returns or throws. Carries an
     * owned typed [outcome] instead of paired nullable success/error fields.
     */
    @Poko
    public class ToolCallFinished(
        public val toolCallId: String,
        public val toolName: String,
        public val outcome: Outcome,
        public val stepNumber: Int,
    ) : AgentEvent() {
        public sealed class Outcome {
            @Poko
            public class Success(public val outputJson: JsonElement) : Outcome()

            @Poko
            public class Failure(public val errorMessage: String) : Outcome()
        }
    }

    @Poko
    public class Errored(
        public val error: Throwable,
        public val stepNumber: Int,
        public val source: ErrorSource,
    ) : AgentEvent() {
        public enum class ErrorSource { Hook, Tool, PrepareStep, PrepareCall, Model, Unknown }
    }

    /**
     * Fired when generation is aborted via [AbortSignal] before it finishes.
     * Carries the steps completed up to the abort.
     */
    @Poko
    public class Aborted(
        public val steps: List<StepResult>,
    ) : AgentEvent()

    /**
     * Generation finished. [output] is the typed agent output (was `Any?`);
     * [experimentalContext] is the typed context after any `prepareStep`
     * override (was `Any?`). [messages] is the final accumulated message list,
     * for resuming after a tool-approval pause without re-walking the events.
     */
    @Poko
    public class Finished<TContext, TOutput>(
        // Nullable for now: the base loop doesn't compute the typed output here (it flows via
        // `generate(): TOutput`); `null` preserves the prior behavior. Wiring a real value is a
        // step-2 dispatch concern. Typed (no `Any?`) regardless.
        public val output: TOutput?,
        public val totalSteps: Int,
        public val usage: Usage,
        public val pendingApprovals: List<PendingApproval> = emptyList(),
        public val messages: List<ModelMessage> = emptyList(),
        public val experimentalContext: TContext? = null,
    ) : AgentEvent()

    /** Before one step's model call — the EXACT prepared params sent to the provider. */
    @Poko
    public class ModelCallStarted(
        public val stepNumber: Int,
        public val modelId: String?,
        public val params: LanguageModelCallParams,
    ) : AgentEvent()

    /** After one step's model call streamed to completion. */
    @Poko
    public class ModelCallFinished(
        public val stepNumber: Int,
        public val modelId: String?,
        public val finishReason: FinishReason,
        public val usage: Usage,
        public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
        public val rawFinishReason: String? = null,
    ) : AgentEvent()

    /** A span sub-event (name + attributes), emitted via [TelemetryActiveSpan.addEvent]. */
    @Poko
    public class SpanEmitted(
        public val name: String,
        public val attributes: Map<String, JsonElement> = emptyMap(),
    ) : AgentEvent()
}

/**
 * Snapshot of one completed loop step — surfaced to [AgentEvent.StepFinished]
 * and accumulated in the loop state for [PrepareStepScope.steps].
 */
@Poko
public class StepResult(
    public val stepNumber: Int,
    public val text: String,
    public val reasoning: String,
    public val toolCalls: List<ContentPart.ToolCall>,
    public val toolResults: List<ContentPart.ToolResult>,
    public val toolApprovalRequests: List<ContentPart.ToolApprovalRequest>,
    public val finishReason: FinishReason,
    public val usage: Usage,
    public val warnings: List<CallWarning> = emptyList(),
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    public val rawFinishReason: String? = null,
    /** The model id that produced this step (upstream's `model.modelId`). */
    public val model: String? = null,
    /** The evolving agent context live for this step (upstream's `experimental_context`). */
    public val experimentalContext: Any? = null,
) {
    /** The non-empty reasoning text for this step, or null — mirrors upstream's `reasoningText`. */
    public val reasoningText: String? get() = reasoning.takeIf { it.isNotEmpty() }

    /** Tool calls/results split by static (`Tool(...)`) vs dynamic (`DynamicTool(...)`). */
    public val staticToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { !it.dynamic }
    public val dynamicToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { it.dynamic }
    public val staticToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { !it.dynamic }
    public val dynamicToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { it.dynamic }
}
