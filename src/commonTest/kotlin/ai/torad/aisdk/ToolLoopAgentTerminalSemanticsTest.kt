@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolLoopAgentTerminalSemanticsTest {

    @Test
    fun `non-tool terminal finish reasons stop after one model call`() = runTest {
        val terminalReasons = listOf(
            FinishReason.Length,
            FinishReason.ContentFilter,
            FinishReason.Error,
            FinishReason.Other,
        )

        for (reason in terminalReasons) {
            val generateModel = CountingStreamModel(
                responses = listOf(
                    TestStreamResponse(
                        events = listOf(
                            StreamEvent.TextStart("t1"),
                            StreamEvent.TextDelta("t1", "done"),
                            StreamEvent.TextEnd("t1"),
                        ),
                        finishReason = reason,
                    ),
                ),
            )
            val generateAgent = TestToolLoopAgent<Unit, String>(
                model = generateModel,
                instructions = "finish once",
                tools = ToolSet(),
            )
            val result = generateAgent.generate(prompt = "run", options = Unit).first()

            assertEquals(reason, result.finishReason)
            assertEquals(1, result.steps.size, "$reason should produce one step")
            assertEquals(1, generateModel.callCount, "$reason should not trigger extra generate calls")

            val streamModel = CountingStreamModel(
                responses = listOf(
                    TestStreamResponse(
                        events = listOf(
                            StreamEvent.TextStart("t1"),
                            StreamEvent.TextDelta("t1", "done"),
                            StreamEvent.TextEnd("t1"),
                        ),
                        finishReason = reason,
                    ),
                ),
            )
            val streamAgent = TestToolLoopAgent<Unit, String>(
                model = streamModel,
                instructions = "finish once",
                tools = ToolSet(),
            )
            val events = drainAllItems(streamAgent.stream(prompt = "run", options = Unit))

            assertEquals(1, streamModel.callCount, "$reason should not trigger extra stream calls")
            assertEquals(
                1,
                events.filterIsInstance<StreamEvent.StepStart>().size,
                "$reason should not start a second stream step",
            )
        }
    }
}

internal data class TestStreamResponse(
    val events: List<StreamEvent>,
    val finishReason: FinishReason = FinishReason.Stop,
    val usage: Usage = Usage.of(promptTokens = 1, completionTokens = 1),
)

internal class CountingStreamModel(
    private val responses: List<TestStreamResponse>,
) : LanguageModel {
    override val modelId: String = "test/counting"
    override val provider: String = "test"
    private var streamCalls = 0

    val callCount: Int
        get() = streamCalls

    private val generatedResult: LanguageModelResult
        get() {
            val response = responses[streamCalls.coerceAtMost(responses.lastIndex)]
            streamCalls += 1
            val text = response.events
                .filterIsInstance<StreamEvent.TextDelta>()
                .joinToString("") { it.text }
            val toolCalls = response.events
                .filterIsInstance<StreamEvent.ToolCall>()
                .map { ContentPart.ToolCall(it.toolCallId, it.toolName, it.inputJson) }
            return LanguageModelResult(
                text = text,
                toolCalls = toolCalls,
                finishReason = response.finishReason,
                usage = response.usage,
            )
        }

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        generatedResult

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val response = responses[streamCalls.coerceAtMost(responses.lastIndex)]
        streamCalls += 1
        for (event in response.events) emit(event)
        emit(StreamEvent.StepFinish(streamCalls, response.finishReason, response.usage))
    }
}
