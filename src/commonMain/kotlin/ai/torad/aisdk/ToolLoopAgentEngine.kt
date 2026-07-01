package ai.torad.aisdk

import dev.drewhamilton.poko.Poko

/**
 * Engine-shape state for [ToolLoopAgent].
 *
 * The Vercel AI SDK's `Agent` interface is stateless per-call — the host
 * passes `messages`, gets back `GenerateResult`, manages history itself.
 * For an Android app using a long-lived `@AppScope` agent (no HTTP
 * boundary, no per-request lifecycle), it's cleaner to treat the agent
 * as a state-holder engine: the agent owns the message log, exposes
 * [ToolLoopAgent.engineState] as a StateFlow, and the host drives it via
 * [ToolLoopAgent.dispatchEngineAction] — the same pattern a Compose ViewModel uses
 * for UI state.
 *
 * The per-call `Agent.generate` / `Agent.stream` surface stays available
 * (used by tests and any host that does want stateless calls). The
 * engine surface is layered on top, not a replacement.
 */
/** @since 0.3.0-beta01 */
public data class ToolLoopAgentState(
    /** Persistent log of all messages — system, user, assistant, tool. */
    val messages: List<ModelMessage> = emptyList(),
    /** Text accumulating on the in-flight assistant message, before the step closes. */
    val streamingAssistantText: String = "",
    /** Tool calls the model has emitted in the current in-flight step. */
    val currentToolCalls: List<ContentPart.ToolCall> = emptyList(),
    /** Tool calls awaiting user approval — non-empty pauses the loop. */
    val pendingApprovals: List<PendingApproval> = emptyList(),
    /** Current lifecycle phase of the engine. */
    val phase: Phase = Phase.Idle,
    /** Total step count across all actions for this agent's lifetime. */
    val totalSteps: Int = 0,
    /** Finish reason of the most recent completed loop. */
    val lastFinishReason: FinishReason? = null,
) {
    /** @since 0.3.0-beta01 */
    public sealed class Phase {
        /** @since 0.3.0-beta01 */
        public data object Idle : Phase()
        /** @since 0.3.0-beta01 */
        public data object Streaming : Phase()
        /** @since 0.3.0-beta01 */
        public data object ModelLoading : Phase()
        @Poko
        /** @since 0.3.0-beta01 */
        public class Error(public val message: String) : Phase()
    }
}

/**
 * Actions the host dispatches to drive the agent. Mirrors the
 * Action sealed type a Compose ViewModel exposes — the agent is the
 * "model" of an MVI loop.
  * @since 0.3.0-beta01
 */
public sealed class ToolLoopAgentAction<out TContext> {

    /**
     * New user turn. Cancels any in-flight stream and starts a fresh one.
     * @since 0.3.0-beta01
     */
    public data class UserSubmitPrompt<TContext>(
        val text: String,
        val context: TContext? = null,
    ) : ToolLoopAgentAction<TContext>()

    /**
     * Approve a tool call that paused the loop. Resumes the agent.
     * @since 0.3.0-beta01
     */
    public data class ApproveToolCall(val toolCallId: String) : ToolLoopAgentAction<Nothing>()

    /**
     * Deny a tool call. Resumes the agent with the denial fed back to the model.
     * @since 0.3.0-beta01
     */
    public data class DenyToolCall(val toolCallId: String, val reason: String? = null) : ToolLoopAgentAction<Nothing>()

    /**
     * Cancel the in-flight stream without erasing history.
     * @since 0.3.0-beta01
     */
    public data object Cancel : ToolLoopAgentAction<Nothing>()

    /**
     * Drop history + abort any in-flight stream.
     * @since 0.3.0-beta01
     */
    public data object Reset : ToolLoopAgentAction<Nothing>()
}
