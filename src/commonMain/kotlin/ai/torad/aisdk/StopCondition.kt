package ai.torad.aisdk

/**
 * First-class loop stop conditions (invariant I-7). The SDK manages the
 * loop; consumers express completion as a [StopCondition] passed to the
 * agent constructor or `generateText`.
 *
 * Built-ins:
 *   - [StepCountIs] — stop after N completed steps. Default `StepCountIs(20)`
 *     so any agent without explicit `stopWhen` still terminates.
 *   - [HasToolCall] — stop the moment a specific tool is called.
 *   - [AnyOf] — short-circuit OR over multiple conditions (matches v6's
 *     array form).
 *
 * Custom: implement [StopCondition] directly. Receives a snapshot of the
 * loop state on every check.
 */
public fun interface StopCondition {
    /** True if the loop should stop after the just-completed step. */
    public suspend fun shouldStop(state: LoopState): Boolean
}

public data class LoopState(
    val stepNumber: Int,
    val totalSteps: Int,
    val lastFinishReason: FinishReason,
    val toolCallsThisStep: List<ContentPart.ToolCall>,
    val toolCallsAllSteps: List<ContentPart.ToolCall>,
    /**
     * All completed steps so far, in order. Mirrors v6's
     * `{steps: StepResult[]}` parameter shape (per
     * historical parity gap #17). Lets custom stop conditions
     * inspect per-step text / reasoning / usage instead of only
     * the synthesized aggregate fields above. Empty during the
     * pre-first-step check.
     */
    val steps: List<StepResult> = emptyList(),
)

/** Stop after [n] completed steps. v6's default is 20 if `stopWhen` omitted. */
public fun StepCountIs(n: Int): StopCondition = StopCondition { state -> state.totalSteps >= n }

/** Stop the moment the named tool is called in any step. */
public fun HasToolCall(toolName: String): StopCondition = StopCondition { state ->
    state.toolCallsAllSteps.any { it.toolName == toolName }
}

/** Stop when any of [conditions] reports done. v6's array-of-conditions shape. */
public fun AnyOf(vararg conditions: StopCondition): StopCondition = StopCondition { state ->
    conditions.any { it.shouldStop(state) }
}

/** Stop only when all [conditions] report done. */
public fun AllOf(vararg conditions: StopCondition): StopCondition = StopCondition { state ->
    conditions.all { it.shouldStop(state) }
}

/**
 * Stop when the model has emitted the same (toolName, input) tuple [n]
 * consecutive steps in a row — i.e. the loop is spinning on a tool whose
 * result didn't change the model's behavior. Documented anti-pattern for
 * small / on-device models that can't reason their way out of an
 * unhelpful tool result; surfaced as a hard stop so the host renders
 * the trapped state instead of looping until the step cap.
 *
 * Compares only the FIRST tool call of each step (small models call one
 * tool per step in practice); per-step JSON identity uses the raw
 * `input` JsonElement's stringified form so identical args match.
 */
public fun RepeatedToolCallLoop(n: Int): StopCondition = StopCondition { state ->
    if (n < 2 || state.steps.size < n) return@StopCondition false
    val recent = state.steps.takeLast(n).map { it.toolCalls.firstOrNull() }
    val first = recent.first() ?: return@StopCondition false
    recent.all { call -> call != null && call.toolName == first.toolName && call.input == first.input }
}
