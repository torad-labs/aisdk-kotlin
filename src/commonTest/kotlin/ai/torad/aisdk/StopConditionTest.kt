package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopConditionTest {

    private fun stateAt(step: Int, calls: List<String> = emptyList()): LoopState = LoopState(
        stepNumber = step,
        totalSteps = step,
        lastFinishReason = FinishReason.Stop,
        toolCallsThisStep = emptyList(),
        toolCallsAllSteps = calls.map { ContentPart.ToolCall("call_$it", it, JsonPrimitive("")) },
    )

    private fun stepWithCall(stepNum: Int, toolName: String, input: JsonElement): StepResult =
        ResultConstruction.stepResult(
            stepNumber = stepNum,
            text = "",
            reasoning = "",
            toolCalls = listOf(ContentPart.ToolCall("call_${stepNum}_$toolName", toolName, input)),
            toolResults = emptyList(),
            toolApprovalRequests = emptyList(),
            finishReason = FinishReason.ToolCalls,
            usage = Usage(),
        )

    private fun emptyStep(stepNum: Int): StepResult = ResultConstruction.stepResult(
        stepNumber = stepNum,
        text = "",
        reasoning = "",
        toolCalls = emptyList(),
        toolResults = emptyList(),
        toolApprovalRequests = emptyList(),
        finishReason = FinishReason.Stop,
        usage = Usage(),
    )

    private fun loopStateWithSteps(steps: List<StepResult>): LoopState = LoopState(
        stepNumber = steps.size,
        totalSteps = steps.size,
        lastFinishReason = steps.lastOrNull()?.finishReason ?: FinishReason.Stop,
        toolCallsThisStep = steps.lastOrNull()?.toolCalls.orEmpty(),
        toolCallsAllSteps = steps.flatMap { it.toolCalls },
        steps = steps,
    )

    @Test
    fun `stepCountIs_returns_true_at_or_above_threshold`() = runTest {
        val cond = StepCountIs(3)
        assertFalse(cond.shouldStop(stateAt(1)))
        assertFalse(cond.shouldStop(stateAt(2)))
        assertTrue(cond.shouldStop(stateAt(3)))
        assertTrue(cond.shouldStop(stateAt(4)))
    }

    @Test
    fun `hasToolCall_returns_true_when_tool_seen`() = runTest {
        val cond = HasToolCall("done")
        assertFalse(cond.shouldStop(stateAt(1, calls = listOf("other"))))
        assertTrue(cond.shouldStop(stateAt(1, calls = listOf("done"))))
        assertTrue(cond.shouldStop(stateAt(2, calls = listOf("other", "done"))))
    }

    @Test
    fun `anyOf_short_circuits_on_first_match`() = runTest {
        val cond = AnyOf(StepCountIs(10), HasToolCall("done"))
        assertTrue(cond.shouldStop(stateAt(1, calls = listOf("done"))))
    }

    @Test
    fun `allOf_requires_every_condition_true`() = runTest {
        val cond = AllOf(StepCountIs(2), HasToolCall("done"))
        assertFalse(cond.shouldStop(stateAt(2, calls = listOf("other"))))
        assertTrue(cond.shouldStop(stateAt(2, calls = listOf("done"))))
    }

    @Test
    fun `given fewer than n steps when repeatedToolCallLoop checks then false`() = runTest {
        val cond = RepeatedToolCallLoop(3)
        val args = JsonPrimitive("hard techno")

        assertFalse(
            cond.shouldStop(loopStateWithSteps(listOf(stepWithCall(1, "findArtists", args)))),
        )
        assertFalse(
            cond.shouldStop(
                loopStateWithSteps(
                    listOf(
                        stepWithCall(1, "findArtists", args),
                        stepWithCall(2, "findArtists", args),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `given n identical tool calls when repeatedToolCallLoop checks then true`() = runTest {
        val cond = RepeatedToolCallLoop(3)
        val args = JsonPrimitive("hard techno")
        val steps = listOf(
            stepWithCall(1, "findArtists", args),
            stepWithCall(2, "findArtists", args),
            stepWithCall(3, "findArtists", args),
        )

        assertTrue(cond.shouldStop(loopStateWithSteps(steps)))
    }

    @Test
    fun `given identical name but different args when repeatedToolCallLoop checks then false`() = runTest {
        val cond = RepeatedToolCallLoop(3)
        val steps = listOf(
            stepWithCall(1, "findArtists", JsonPrimitive("hard techno")),
            stepWithCall(2, "findArtists", JsonPrimitive("melodic")),
            stepWithCall(3, "findArtists", JsonPrimitive("dnb")),
        )

        assertFalse(cond.shouldStop(loopStateWithSteps(steps)))
    }

    @Test
    fun `given same args but different tool names when repeatedToolCallLoop checks then false`() = runTest {
        val cond = RepeatedToolCallLoop(3)
        val args = JsonPrimitive("hard techno")
        val steps = listOf(
            stepWithCall(1, "findArtists", args),
            stepWithCall(2, "getArtistContext", args),
            stepWithCall(3, "findArtists", args),
        )

        assertFalse(cond.shouldStop(loopStateWithSteps(steps)))
    }

    @Test
    fun `given a step with no tool calls in the run when repeatedToolCallLoop checks then false`() = runTest {
        val cond = RepeatedToolCallLoop(3)
        val args = JsonPrimitive("x")
        val steps = listOf(
            stepWithCall(1, "findArtists", args),
            emptyStep(2),
            stepWithCall(3, "findArtists", args),
        )

        assertFalse(cond.shouldStop(loopStateWithSteps(steps)))
    }
}
