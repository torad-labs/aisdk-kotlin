package ai.torad.aisdk

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.JsonElement

// Internal plumbing carriers and adapters that thread state through the
// ToolLoopAgent generate()/stream() bodies. None are part of the public API.

/** Mutable cell used by `streamInternal` to publish the loop's final message
 *  list back to the `generate` aggregator out of the `flow { }` body. */
internal class MessageHolder(var value: List<ModelMessage> = emptyList())

/** Carrier for the caller-provided step accumulator, filled as steps complete. */
internal class StepsHolder(val steps: MutableList<StepResult>)

/**
 * Tiny adapter that captures stream events into a side-effecting collector
 * while leaving the upstream Flow uncollected from the caller's perspective.
 * Used by `generate` to drain its own stream and tally aggregates.
 */
internal class StreamCapture(private val onEach: suspend (StreamEvent) -> Unit) {
    suspend fun consume(event: StreamEvent) = onEach(event)
}

/**
 * [ToolStreamWriter] bound to the agent loop's output [FlowCollector].
 * Supplied to every [ToolExecutionContext] so a tool executor can write
 * custom events into the same stream the loop emits on (gap #21). Safe
 * because tool execution runs sequentially inside the loop's `flow { }`
 * collector — there's no concurrent emission to race with.
 */
internal class FlowToolStreamWriter(
    private val out: FlowCollector<StreamEvent>,
) : ToolStreamWriter {
    override suspend fun write(event: StreamEvent) = out.emit(event)
    override suspend fun writeData(value: JsonElement) = out.emit(StreamEvent.Raw(value))
}

/** Terminal sentinel thrown to unwind the model stream when the provider
 *  surfaces a [StreamEvent.Error] mid-stream (caught inside `streamInternal`). */
internal class TerminalModelStreamError : RuntimeException()
