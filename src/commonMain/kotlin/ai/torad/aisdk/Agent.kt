package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow

/**
 * The agent contract — what application code depends on (best practice #7).
 * [ToolLoopAgent] is the default implementation, but other implementations
 * exist (e.g. a `DurableAgent` that journals to disk for offline-resilient
 * mode, a `SubagentTestAgent` that records calls for unit tests).
 *
 * Application code should depend on this interface, not on `ToolLoopAgent`.
 *
 * ## Approval flow
 *
 * Per v6 RPC semantics, when a tool needs approval the loop **ends**:
 *   - [GenerateResult.pendingApprovals] is populated with [PendingApproval]s
 *   - [GenerateResult.messages] contains the assistant message with
 *     [ContentPart.ToolApprovalRequest] parts appended
 *
 * The host resumes by calling [generate] again with:
 * ```
 * agent.generate(
 *   messages = result.messages + toolApprovalResponseMessage(toolCallId, approved = true),
 *   options = ...
 * )
 * ```
 *
 * No `submitApproval` method exists — state lives entirely in the
 * message list so it can be serialized, persisted across process
 * restarts, and replayed.
 *
 * @param TContext typed application context (RAG state, user IDs, request
 *                 metadata) propagated through the loop. Set via the agent
 *                 constructor's `callOptionsSchema` and passed at call time
 *                 via [generate] / [stream].
 * @param TOutput  agent's terminal output type. For free-form chat agents
 *                 it's `String`; for structured-output agents (with
 *                 [Output] set) it's the typed shape.
 */
public interface Agent<TContext, TOutput> {

    /**
     * Stable identifier for the agent, useful for telemetry, log
     * routing, and the `Agent.version / id / tools` accessors v6
     * exposes (per historical parity gap #33). Implementations
     * default to `"agent"`; override in the constructor or via class
     * member to set a more specific tag (`"chat-agent"`,
     * `"lineup-subagent"`, etc.).
     */
    public val id: String
        get() = "agent"

    /**
     * Optional version string — `"1.0.0"`, a git sha, anything stable
     * across an app build. Null = unversioned. Lets telemetry pin
     * issues to a specific agent revision.
     */
    public val version: String?
        get() = null

    /**
     * The tool surface this agent carries. Exposed at the interface
     * so consumers can inspect / dispatch without casting to
     * [ToolLoopAgent].
     */
    public val tools: ToolSet<TContext>

    /**
     * One-shot generation. Either [prompt] or [messages] (or both) must
     * be supplied. When both are present, [prompt] is appended as a
     * trailing user message.
     *
     * @param hooks per-call lifecycle observation; see [AgentCallHooks].
     */
    public fun generate(
        prompt: String? = null,
        messages: List<ModelMessage> = emptyList(),
        options: TContext? = null,
        abortSignal: AbortSignal = AbortSignalNever,
        hooks: AgentCallHooks? = null,
    ): Flow<GenerateResult<TOutput>>

    /** Streaming generation. Cold flow — starts when collected. */
    public fun stream(
        prompt: String? = null,
        messages: List<ModelMessage> = emptyList(),
        options: TContext? = null,
        abortSignal: AbortSignal = AbortSignalNever,
        hooks: AgentCallHooks? = null,
    ): Flow<StreamEvent>
}

/**
 * Final output of [Agent.generate]. When [pendingApprovals] is non-empty,
 * the loop paused on tool approval — call [Agent.generate] again with
 * [messages] plus tool-approval-response messages to resume.
 */
public data class GenerateResult<TOutput>(
    val output: TOutput,
    val text: String,
    val steps: List<StepResult>,
    val finishReason: FinishReason,
    /** Token usage of the FINAL step (matching upstream's `usage`); for the sum see [totalUsage]. */
    val usage: Usage,
    /** Combined token usage across every step of this call (matching upstream's `totalUsage`). */
    val totalUsage: Usage = usage,
    /** Tool calls awaiting host decision. Empty when generation finished naturally. */
    val pendingApprovals: List<PendingApproval> = emptyList(),
    /** Full message log including all assistant + tool messages from this call. */
    val messages: List<ModelMessage> = emptyList(),
)

/**
 * Per-call lifecycle hooks. These mirror the ones on the agent
 * constructor but apply to a single [Agent.generate] / [Agent.stream]
 * invocation — useful for request-scoped logging, tracing, or
 * progress UI.
 *
 * Both call-site hooks and constructor-site hooks fire (constructor
 * first, then call-site) — neither replaces the other.
 */
public data class AgentCallHooks(
    val onStart: (suspend (AgentEvent.Started<*>) -> Unit)? = null,
    val onStepStart: (suspend (AgentEvent.StepStarted) -> Unit)? = null,
    val onStepFinish: (suspend (AgentEvent.StepFinished) -> Unit)? = null,
    val onFinish: (suspend (AgentEvent.Finished<*, *>) -> Unit)? = null,
    val onError: (suspend (AgentEvent.Errored) -> Unit)? = null,
    val onChunk: (suspend (AgentEvent.Chunk) -> Unit)? = null,
    val onAbort: (suspend (AgentEvent.Aborted) -> Unit)? = null,
    /** Per-call mirror of the constructor hook — fired before a (non-gated) tool executes. */
    val experimental_onToolCallStart: (suspend (AgentEvent.ToolCallStarted) -> Unit)? = null,
    /** Per-call mirror of the constructor hook — fired after a tool returns or throws. */
    val experimental_onToolCallFinish: (suspend (AgentEvent.ToolCallFinished) -> Unit)? = null,
)
