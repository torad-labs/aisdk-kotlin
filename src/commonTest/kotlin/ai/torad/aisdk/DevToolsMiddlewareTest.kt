@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DevToolsMiddlewareTest {

    @Test
    fun `devToolsMiddleware records generate steps`() = runTest {
        val recorder = InMemoryDevToolsRecorder()
        val middleware = DevToolsMiddleware(
            recorder = recorder,
            runId = "run_1",
            idGenerator = { "step_1" },
        )
        val wrapped = WrapLanguageModel(MockLanguageModelTextOnly("ok"), listOf(middleware))

        wrapped.generate(LanguageModelCallParams(messages = listOf(UserMessage("hi"))))

        assertEquals(listOf("run_1"), recorder.runs)
        val step = recorder.steps.single()
        assertEquals("step_1", step.id)
        assertEquals("generate", step.type)
        assertEquals("mock/test", step.modelId)
        assertEquals("mock", step.provider)
        assertEquals(1, step.stepNumber)

        val result = assertNotNull(recorder.results["step_1"])
        assertEquals(null, result.error)
        assertEquals("ok", result.output!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(FinishReason.Stop.name, result.output.jsonObject["finishReason"]!!.jsonPrimitive.content)
        assertEquals(1, result.usage!!.promptTokens)
    }

    @Test
    fun `devToolsMiddleware records stream output and raw chunks`() = runTest {
        val recorder = InMemoryDevToolsRecorder()
        val middleware = DevToolsMiddleware(
            recorder = recorder,
            runId = "run_1",
            idGenerator = { "step_1" },
        )
        val wrapped = WrapLanguageModel(StreamingFixtureModel(), listOf(middleware))

        val events = wrapped.stream(LanguageModelCallParams(messages = listOf(UserMessage("hi")))).toList()

        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "hello" })
        val result = assertNotNull(recorder.results["step_1"])
        val output = result.output!!.jsonObject
        assertEquals("hello", output["textParts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("because", output["reasoningParts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("search", output["toolCalls"]!!.jsonArray.single().jsonObject["toolName"]!!.jsonPrimitive.content)
        assertEquals(FinishReason.ToolCalls.name, output["finishReason"]!!.jsonPrimitive.content)
        assertEquals(2, result.usage!!.completionTokens)
        assertEquals(JsonPrimitive("raw-provider-event"), result.rawChunks.single())
        assertEquals("finish", result.rawResponse!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `devToolsMiddleware rejects production environment`() {
        val error = assertFailsWith<AiSdkException> {
            DevToolsMiddleware(environment = "production")
        }

        assertTrue(error.message!!.contains("should not be used in production"))
    }

    private class StreamingFixtureModel : LanguageModel {
        override val modelId: String = "fixture/model"
        override val provider: String = "fixture"

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
            LanguageModelResult(
                text = "unused",
                finishReason = FinishReason.Stop,
                usage = Usage.of(promptTokens = 1, completionTokens = 1),
            )

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            emit(StreamEvent.TextStart("t1"))
            emit(StreamEvent.TextDelta("t1", "hello"))
            emit(StreamEvent.TextEnd("t1"))
            emit(StreamEvent.ReasoningStart("r1"))
            emit(StreamEvent.ReasoningDelta("r1", "because"))
            emit(StreamEvent.ReasoningEnd("r1"))
            emit(StreamEvent.ToolCall("call_1", "search", JsonObject(emptyMap())))
            emit(StreamEvent.Raw(JsonPrimitive("raw-provider-event")))
            emit(StreamEvent.Finish(1, FinishReason.ToolCalls, Usage.of(promptTokens = 1, completionTokens = 2)))
        }
    }
}
