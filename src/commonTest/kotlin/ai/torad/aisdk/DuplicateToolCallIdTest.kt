package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class DuplicateToolCallIdTest {
    @Serializable
    private data class EchoInput(val v: String)

    // Step 1 emits TWO tool calls sharing the SAME toolCallId but different inputs (malformed/buggy
    // wire data); step 2 returns text once they've run.
    private class DupIdModel : LanguageModel {
        override val modelId = "m"
        private var calls = 0
        override suspend fun generate(params: LanguageModelCallParams) =
            LanguageModelResult(text = "done", finishReason = FinishReason.Stop, usage = Usage())
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            if (calls++ == 0) {
                emit(StreamEvent.ToolCall("dup", "echo", buildJsonObject { put("v", JsonPrimitive("A")) }))
                emit(StreamEvent.ToolCall("dup", "echo", buildJsonObject { put("v", JsonPrimitive("B")) }))
                emit(StreamEvent.StepFinish(1, FinishReason.ToolCalls, Usage()))
                emit(StreamEvent.Finish(1, FinishReason.ToolCalls, Usage()))
            } else {
                emit(StreamEvent.TextStart("t"))
                emit(StreamEvent.TextDelta("t", "done"))
                emit(StreamEvent.TextEnd("t"))
                emit(StreamEvent.Finish(2, FinishReason.Stop, Usage()))
            }
        }
    }

    /**
     * Regression: resolvedForExecution was keyed by toolCallId, so two calls sharing an id in one
     * step collided — the second's resolved (tool, input) overwrote the first's, and BOTH executed
     * with the second's args. Keyed by position now, each runs with its own input.
     */
    @Test
    fun `two tool calls sharing a toolCallId each run with their own input`() = runTest {
        val recorded = mutableListOf<String>()
        val echo = Tool<EchoInput, String, Unit>(
            name = "echo",
            description = "echo",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { input ->
            recorded += input.v
            input.v
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = DupIdModel(),
            instructions = "x",
            tools = ToolSet(echo),
            maxParallelToolCalls = 1, // serialize so `recorded` isn't a concurrent-write race
        )

        agent.generate(prompt = "go").first()

        assertEquals(listOf("A", "B"), recorded.sorted(), "both inputs ran (pre-fix: the 2nd ran twice)")
    }
}
