package ai.torad.aisdk

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector

// Channel-based, order-preserving fan-in for the tool loop's bounded-parallel
// tool execution. A step's tool calls run concurrently; their progress and
// terminal outcomes are multiplexed over one parent-owned channel so only the
// single collector coroutine performs real Flow emits, while final results are
// still applied to the message log in deterministic call order.

/**
 * A message on the parallel-tool channel: either a [Progress] stream event to
 * forward to the loop's collector, or a [Completed] terminal outcome tagged
 * with its original call order. Sent by child tool coroutines, drained by the
 * single collector in the loop's `coroutineScope`.
 */
internal sealed class ParallelToolSignal {
    class Progress(val event: StreamEvent) : ParallelToolSignal()
    class Completed(val completion: OrderedToolCompletion) : ParallelToolSignal()
}

/**
 * A tool execution's terminal outcome carrying its original call [index], so
 * results are applied to the conversation in deterministic call order
 * regardless of which tool finished first.
 */
internal sealed class OrderedToolCompletion {
    abstract val index: Int

    class Executed(
        override val index: Int,
        val call: ContentPart.ToolCall,
        val result: ToolExecutionResult,
    ) : OrderedToolCompletion()

    class Skipped(override val index: Int) : OrderedToolCompletion()

    /**
     * A child whose tool execution unwound with [AbortError] (the cooperative stop signal).
     * Because that abort does NOT structurally cancel the parent scope (DECISION 3 decouples
     * AbortSignal from coroutine cancellation), the child sends this terminal marker on its way
     * out so the collector's count still completes instead of waiting forever on a dead child;
     * the loop then surfaces a single [StreamEvent.Abort] for the step.
     */
    class Aborted(override val index: Int) : OrderedToolCompletion()
}

/**
 * A [FlowCollector] that funnels a child tool coroutine's preliminary
 * emissions into the parent [Channel] as [ParallelToolSignal.Progress] —
 * so the children never emit on the loop's Flow directly (only the collector
 * coroutine does), keeping concurrent tool progress race-free.
 */
internal class ChannelToolEventCollector(
    private val signals: Channel<ParallelToolSignal>,
) : FlowCollector<StreamEvent> {
    override suspend fun emit(value: StreamEvent) {
        signals.send(ParallelToolSignal.Progress(value))
    }
}
