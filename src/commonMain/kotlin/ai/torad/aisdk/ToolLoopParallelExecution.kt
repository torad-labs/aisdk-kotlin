package ai.torad.aisdk

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
     * out so the loop can surface a single [StreamEvent.Abort] for the step.
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

private class IndexedToolCall(
    val index: Int,
    val call: ContentPart.ToolCall,
)

/**
 * Execute `toolCalls` with at most [ToolExecutionPolicy.maxParallelToolCalls]
 * worker coroutines. This bounds child-coroutine count before work is acquired:
 * queued calls are data in `work`, not suspended coroutines waiting on permits.
 *
 * The parent drains progress and terminal signals, applying terminal completions
 * in original call order even when workers finish out of order. Returns true if
 * any worker reported [OrderedToolCompletion.Aborted], or if the worker pool
 * closed before every queued call produced an ordered terminal completion.
 */
internal object ToolLoopParallelExecution {
    suspend fun runBoundedParallelToolCalls(
        toolCalls: List<ContentPart.ToolCall>,
        policy: ToolExecutionPolicy,
        parentOut: FlowCollector<StreamEvent>,
        executeCall: suspend (Int, ContentPart.ToolCall, FlowCollector<StreamEvent>) -> OrderedToolCompletion,
        applyCompletion: suspend (OrderedToolCompletion) -> Unit,
    ): Boolean {
        if (toolCalls.isEmpty()) return false

        var sawAbort = false
        coroutineScope {
            val signals = Channel<ParallelToolSignal>(policy.progressBufferCapacity)
            val workerCount = policy.workerCountFor(toolCalls.size)
            val work = Channel<IndexedToolCall>(workerCount)

            val producer = launch {
                try {
                    toolCalls.forEachIndexed { index, call ->
                        work.send(IndexedToolCall(index, call))
                    }
                } finally {
                    work.close()
                }
            }

            val workers = List(workerCount) {
                launch {
                    for (item in work) {
                        try {
                            val progressOut = ChannelToolEventCollector(signals)
                            signals.send(ParallelToolSignal.Completed(executeCall(item.index, item.call, progressOut)))
                        } catch (abort: AbortError) {
                            // AbortError is a cooperative user stop, not a structural failure of the
                            // scheduler. Send the terminal marker while unwinding so the parent count
                            // completes and the loop can emit exactly one StreamEvent.Abort.
                            withContext(NonCancellable) {
                                signals.send(
                                    ParallelToolSignal.Completed(OrderedToolCompletion.Aborted(item.index)),
                                )
                            }
                            throw abort
                        }
                    }
                }
            }
            // The collector is driven by worker-pool shutdown, not by total queued call count:
            // abort can end every worker while unstarted calls remain in `work`.
            launch {
                workers.forEach { worker -> worker.join() }
                producer.cancel()
                signals.close()
            }

            val completions = mutableMapOf<Int, OrderedToolCompletion>()
            var nextToApply = 0
            for (signal in signals) {
                when (signal) {
                    is ParallelToolSignal.Progress -> parentOut.emit(signal.event)
                    is ParallelToolSignal.Completed -> {
                        completions[signal.completion.index] = signal.completion
                        while (true) {
                            val completion = completions.remove(nextToApply) ?: break
                            if (completion is OrderedToolCompletion.Aborted) sawAbort = true
                            applyCompletion(completion)
                            nextToApply += 1
                        }
                    }
                }
            }
            if (nextToApply < toolCalls.size) sawAbort = true
        }
        return sawAbort
    }
}
