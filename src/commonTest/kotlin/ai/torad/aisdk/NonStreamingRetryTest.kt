@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NonStreamingRetryTest {
    @Serializable
    private data class Empty(val unused: String = "")

    @Serializable
    private data class Answer(val value: String)

    private class FlakyTextModel(
        private val failures: MutableList<Throwable>,
        private val successText: String = "ok",
    ) : LanguageModel {
        override val modelId: String = "retry-test"
        private var callCount: Int = 0
        val generateCalls: Int get() = callCount

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            callCount += 1
            if (failures.isNotEmpty()) throw failures.removeAt(0)
            return LanguageModelResult(
                text = successText,
                finishReason = FinishReason.Stop,
                usage = Usage(),
            )
        }

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = emptyFlow()
    }

    private class ToolThenRetryTextModel : LanguageModel {
        override val modelId: String = "tool-retry-test"
        private var callCount: Int = 0
        val generateCalls: Int get() = callCount

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            callCount += 1
            return when (callCount) {
                1 -> LanguageModelResult(
                    text = "",
                    toolCalls = listOf(ContentPart.ToolCall("call_1", "lookup", JsonObject(emptyMap()))),
                    finishReason = FinishReason.ToolCalls,
                    usage = Usage(),
                )
                2 -> throw ApiError(429)
                else -> LanguageModelResult(
                    text = "done",
                    finishReason = FinishReason.Stop,
                    usage = Usage(),
                )
            }
        }

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = emptyFlow()
    }

    @Test
    fun `generateText retries retryable API errors by default`() = runTest {
        val model = FlakyTextModel(mutableListOf(ApiError(429), ApiError(429)))

        val result = TextGenerator(model).generate(GenerationInput.Prompt("hi")).first()

        assertEquals("ok", result.text)
        assertEquals(3, model.generateCalls)
    }

    @Test
    fun `generateObject retries retryable API errors by default`() = runTest {
        val model = FlakyTextModel(mutableListOf(ApiError(503)), successText = """{"value":"ok"}""")
        val output = OutputObj<Answer>(serializer())

        val result = TextGenerator(model).generate(GenerationInput.Prompt("json"), output).first()

        assertEquals(Answer("ok"), result.output)
        assertEquals(2, model.generateCalls)
    }

    @Test
    fun `maxRetries zero disables non-streaming retry`() = runTest {
        val model = FlakyTextModel(mutableListOf(ApiError(429)))

        val error = assertFailsWith<APICallError> {
            TextGenerator(model, CallConfig(maxRetries = 0)).generate(GenerationInput.Prompt("hi")).first()
        }

        assertEquals(429, error.statusCode)
        assertEquals(1, model.generateCalls)
    }

    @Test
    fun `non-retryable API error is not retried`() = runTest {
        val model = FlakyTextModel(mutableListOf(ApiError(400)))

        val error = assertFailsWith<APICallError> {
            TextGenerator(model).generate(GenerationInput.Prompt("hi")).first()
        }

        assertEquals(400, error.statusCode)
        assertEquals(1, model.generateCalls)
    }

    @Test
    fun `agent retries a failed second model call without re-executing prior tools`() = runTest {
        val model = ToolThenRetryTextModel()
        var toolExecutions = 0
        val tool = Tool<Empty, String, Unit>(
            name = "lookup",
            description = "lookup",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            toolExecutions += 1
            "tool-result"
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = model,
            instructions = "x",
            tools = ToolSet(tool),
        )

        val result = agent.generate(prompt = "go").first()

        assertEquals("done", result.text)
        assertEquals(3, model.generateCalls)
        assertEquals(1, toolExecutions)
    }

    private companion object {
        fun ApiError(statusCode: Int): APICallError =
            APICallError(
                message = "HTTP $statusCode",
                url = "https://api.test",
                statusCode = statusCode,
            )
    }
}
