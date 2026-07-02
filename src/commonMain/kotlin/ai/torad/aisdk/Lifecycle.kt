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
/** @since 0.3.0-beta01 */
public sealed class AgentEvent {
    /**
     * Generation started. [options] is the typed agent context (was `Any?`).
     * @since 0.3.0-beta01
     */
    @Poko
    public class Started<TContext>(
        /** @since 0.3.0-beta01 */
        public val prompt: String?,
        /** @since 0.3.0-beta01 */
        public val priorMessages: List<ModelMessage>,
        /** @since 0.3.0-beta01 */
        public val options: TContext?,
    ) : AgentEvent()

    /**
     * Fired before each loop step's model call, AFTER `prepareCall` +
     * `prepareStep` have resolved it. [request] carries the EXACT prepared
     * params (system prompt, tool subset, sampler overrides); [priorSteps] are
     * the accumulated step results (`priorSteps[i]` is step i+1's outcome, empty
     * on step 1). Both required — the event fires only once the params exist.
     * @since 0.3.0-beta01
     */
    @Poko
    public class StepStarted(
        /** @since 0.3.0-beta01 */
        public val stepNumber: Int,
        /** @since 0.3.0-beta01 */
        public val messages: List<ModelMessage>,
        /** @since 0.3.0-beta01 */
        public val request: LanguageModelCallParams,
        /** @since 0.3.0-beta01 */
        public val priorSteps: List<StepResult>,
    ) : AgentEvent()

    /**
     * Each individual stream chunk, alongside the step it belongs to.
     * @since 0.3.0-beta01
     */
    @Poko
    public class Chunk(
        /** @since 0.3.0-beta01 */
        public val event: StreamEvent,
        /** @since 0.3.0-beta01 */
        public val stepNumber: Int,
    ) : AgentEvent()

    @Poko
    /** @since 0.3.0-beta01 */
    public class StepFinished(
        /** @since 0.3.0-beta01 */
        public val stepNumber: Int,
        /** @since 0.3.0-beta01 */
        public val step: StepResult,
    ) : AgentEvent()

    /**
     * Fired immediately before a tool's executor runs. Carries the parsed tool
     * call envelope and the messages-list snapshot so observers can record context.
     * @since 0.3.0-beta01
     */
    @Poko
    public class ToolCallStarted(
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val input: JsonElement,
        /** @since 0.3.0-beta01 */
        public val stepNumber: Int,
        /** @since 0.3.0-beta01 */
        public val messages: List<ModelMessage>,
    ) : AgentEvent()

    /**
     * Fired immediately after a tool's executor returns or throws. Carries an
     * owned typed [outcome] instead of paired nullable success/error fields.
     * @since 0.3.0-beta01
     */
    @Poko
    public class ToolCallFinished(
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val outcome: Outcome,
        /** @since 0.3.0-beta01 */
        public val stepNumber: Int,
    ) : AgentEvent() {
        /** @since 0.3.0-beta01 */
        public sealed class Outcome {
            @Poko
            /** @since 0.3.0-beta01 */
            public class Success(public val outputJson: JsonElement) : Outcome()

            @Poko
            /** @since 0.3.0-beta01 */
            public class Failure(public val errorMessage: String) : Outcome()
        }
    }

    @Poko
    /** @since 0.3.0-beta01 */
    public class Errored(
        /** @since 0.3.0-beta01 */
        public val error: Throwable,
        /** @since 0.3.0-beta01 */
        public val stepNumber: Int,
        /** @since 0.3.0-beta01 */
        public val source: ErrorSource,
    ) : AgentEvent() {
        /** @since 0.3.0-beta01 */
        public enum class ErrorSource { Hook, Tool, PrepareStep, PrepareCall, Model, Unknown }
    }

    /**
     * Fired when generation is aborted via [AbortSignal] before it finishes.
     * Carries the steps completed up to the abort.
     * @since 0.3.0-beta01
     */
    @Poko
    public class Aborted(
        /** @since 0.3.0-beta01 */
        public val steps: List<StepResult>,
    ) : AgentEvent()

    /**
     * Generation finished. [output] is the typed agent output (was `Any?`);
     * [experimentalContext] is the typed context after any `prepareStep`
     * override (was `Any?`). [messages] is the final accumulated message list,
     * for resuming after a tool-approval pause without re-walking the events.
     * @since 0.3.0-beta01
     */
    @Poko
    public class Finished<TContext, TOutput>(
        // Nullable for now: the base loop doesn't compute the typed output here (it flows via
        // `generate(): TOutput`); `null` preserves the prior behavior. Wiring a real value is a
        // step-2 dispatch concern. Typed (no `Any?`) regardless.
        /** @since 0.3.0-beta01 */
        public val output: TOutput?,
        /** @since 0.3.0-beta01 */
        public val totalSteps: Int,
        /** @since 0.3.0-beta01 */
        public val usage: Usage,
        /** @since 0.3.0-beta01 */
        public val pendingApprovals: List<PendingApproval> = emptyList(),
        /** @since 0.3.0-beta01 */
        public val messages: List<ModelMessage> = emptyList(),
        /** @since 0.3.0-beta01 */
        public val experimentalContext: TContext? = null,
    ) : AgentEvent()

    /**
     * Before one step's model call — the EXACT prepared params sent to the provider.
     * @since 0.3.0-beta01
     */
    @Poko
    public class ModelCallStarted(
        /** @since 0.3.0-beta01 */
        public val stepNumber: Int,
        /** @since 0.3.0-beta01 */
        public val modelId: String?,
        /** @since 0.3.0-beta01 */
        public val params: LanguageModelCallParams,
    ) : AgentEvent()

    /**
     * After one step's model call streamed to completion.
     * @since 0.3.0-beta01
     */
    @Poko
    public class ModelCallFinished(
        /** @since 0.3.0-beta01 */
        public val stepNumber: Int,
        /** @since 0.3.0-beta01 */
        public val modelId: String?,
        /** @since 0.3.0-beta01 */
        public val finishReason: FinishReason,
        /** @since 0.3.0-beta01 */
        public val usage: Usage,
        /** @since 0.3.0-beta01 */
        public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
        /** @since 0.3.0-beta01 */
        public val rawFinishReason: String? = null,
    ) : AgentEvent()

    /**
     * A span sub-event (name + attributes), emitted via [TelemetryActiveSpan.addEvent].
     * @since 0.3.0-beta01
     */
    @Poko
    public class SpanEmitted(
        /** @since 0.3.0-beta01 */
        public val name: String,
        /** @since 0.3.0-beta01 */
        public val attributes: Map<String, JsonElement> = emptyMap(),
    ) : AgentEvent()
}

/**
 * Snapshot of one completed loop step — surfaced to [AgentEvent.StepFinished]
 * and accumulated in the loop state for [PrepareStepScope.steps].
 * @since 0.3.0-beta01
 */
@Poko
public class StepResult(
    /** @since 0.3.0-beta01 */
    public val stepNumber: Int,
    /** @since 0.3.0-beta01 */
    public val text: String,
    /** @since 0.3.0-beta01 */
    public val reasoning: String,
    /** @since 0.3.0-beta01 */
    public val toolCalls: List<ContentPart.ToolCall>,
    /** @since 0.3.0-beta01 */
    public val toolResults: List<ContentPart.ToolResult>,
    /** @since 0.3.0-beta01 */
    public val toolApprovalRequests: List<ContentPart.ToolApprovalRequest>,
    /** @since 0.3.0-beta01 */
    public val finishReason: FinishReason,
    /** @since 0.3.0-beta01 */
    public val usage: Usage,
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /** @since 0.3.0-beta01 */
    public val rawFinishReason: String? = null,
    /**
     * The model id that produced this step (upstream's `model.modelId`).
     * @since 0.3.0-beta01
     */
    public val model: String? = null,
    /**
     * The evolving agent context live for this step (upstream's `experimental_context`).
     * @since 0.3.0-beta01
     */
    public val experimentalContext: Any? = null,
) {
    /**
     * The non-empty reasoning text for this step, or null — mirrors upstream's `reasoningText`.
     * @since 0.3.0-beta01
     */
    public val reasoningText: String? get() = reasoning.takeIf { it.isNotEmpty() }

    /**
     * Tool calls/results split by static (`Tool(...)`) vs dynamic (`DynamicTool(...)`).
     * @since 0.3.0-beta01
     */
    public val staticToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { !it.dynamic }

    /** @since 0.3.0-beta01 */
    public val dynamicToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { it.dynamic }

    /** @since 0.3.0-beta01 */
    public val staticToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { !it.dynamic }

    /** @since 0.3.0-beta01 */
    public val dynamicToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { it.dynamic }
}
