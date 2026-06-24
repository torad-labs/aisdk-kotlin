package ai.torad.aisdk

import kotlin.time.Duration

/**
 * Resource limits for executing tool calls emitted by one agent step.
 *
 * Defaults are deliberately bounded: a model cannot fan out into unbounded
 * coroutine creation or unbounded in-step tool execution unless the host opts
 * into larger limits explicitly.
 */
public data class ToolExecutionPolicy(
    /** Maximum tool executors that may run concurrently within one model step. */
    public val maxParallelToolCalls: Int = DEFAULT_MAX_PARALLEL_TOOL_CALLS,
    /** Maximum tool calls accepted from one model step before the loop fails closed. */
    public val maxToolCallsPerStep: Int = DEFAULT_MAX_TOOL_CALLS_PER_STEP,
    /** Buffer capacity for preliminary tool progress events before the parent collector applies them. */
    public val progressBufferCapacity: Int = DEFAULT_PROGRESS_BUFFER_CAPACITY,
    /** Optional per-tool execution timeout. Null leaves individual tool duration uncapped. */
    public val toolExecutionTimeout: Duration? = null,
) {
    init {
        require(maxParallelToolCalls > 0) { "maxParallelToolCalls must be greater than zero" }
        require(maxToolCallsPerStep > 0) { "maxToolCallsPerStep must be greater than zero" }
        require(progressBufferCapacity >= 0) { "progressBufferCapacity must be zero or greater" }
        require(toolExecutionTimeout == null || toolExecutionTimeout.isPositive()) {
            "toolExecutionTimeout must be positive when set"
        }
    }

    internal fun workerCountFor(callCount: Int): Int = minOf(callCount, maxParallelToolCalls)

    public companion object {
        /** Default cap for concurrently executing tool calls in a single step. */
        public const val DEFAULT_MAX_PARALLEL_TOOL_CALLS: Int = 8

        /** Default cap for total tool calls accepted from a single model step. */
        public const val DEFAULT_MAX_TOOL_CALLS_PER_STEP: Int = 128

        /** Default buffer capacity for streaming/preliminary tool progress events. */
        public const val DEFAULT_PROGRESS_BUFFER_CAPACITY: Int = 64
    }
}
