@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentGenerateRoutingTest {

    @Serializable
    private data class PingInput(val value: String = "")

    private class GenerateOnlyToolModel : LanguageModel {
        override val modelId: String = "test/generate-only"
        private var generateCallCount: Int = 0
        private var streamCallCount: Int = 0
        private var streamResultCallCount: Int = 0
        private val capturedGenerateParams: MutableList<LanguageModelCallParams> = mutableListOf()

        val generateCalls: Int get() = generateCallCount
        val streamCalls: Int get() = streamCallCount
        val streamResultCalls: Int get() = streamResultCallCount
        val generateParams: List<LanguageModelCallParams> get() = capturedGenerateParams

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            generateCallCount += 1
            capturedGenerateParams += params
            return when (generateCallCount) {
                1 -> {
                    val call = ContentPart.ToolCall(
                        toolCallId = "call_1",
                        toolName = "ping",
                        input = JsonObject(mapOf("value" to JsonPrimitive("one"))),
                    )
                    LanguageModelResult(
                        text = "",
                        toolCalls = listOf(call),
                        finishReason = FinishReason.ToolCalls,
                        usage = Usage.of(promptTokens = 3, completionTokens = 2),
                    )
                }
                else -> LanguageModelResult(
                    text = "done",
                    finishReason = FinishReason.Stop,
                    usage = Usage.of(promptTokens = 4, completionTokens = 1),
                )
            }
        }

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> {
            streamCallCount += 1
            return flow {
                throw AssertionError("Agent.generate must not call LanguageModel.stream")
            }
        }

        override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
            streamResultCallCount += 1
            throw AssertionError("Agent.generate must not call LanguageModel.streamResult")
        }
    }

    @Test
    fun `agent generate calls model generate not stream while preserving tool loop`() = runTest {
        val model = GenerateOnlyToolModel()
        val agent = TestToolLoopAgent<Unit, String>(
            model = model,
            instructions = "Use tools when needed.",
            tools = ToolSet(
                Tool<PingInput, String, Unit>(
                    name = "ping",
                    description = "Ping",
                ) { input -> "pong:${input.value}" },
            ),
        )

        val result = agent.generate(prompt = "go").first()

        assertEquals("done", result.output)
        assertEquals(2, model.generateCalls)
        assertEquals(0, model.streamCalls)
        assertEquals(0, model.streamResultCalls)
        assertTrue(
            model.generateParams[1].messages
                .filter { it.role == MessageRole.Tool }
                .flatMap { it.content }
                .filterIsInstance<ContentPart.ToolResult>()
                .any { it.toolCallId == "call_1" && it.toolName == "ping" },
        )
    }
}
