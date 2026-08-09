@file:OptIn(LowLevelLanguageModelApi::class, ExperimentalAiSdkApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

        wrapped.generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
            }
        )

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

        val events = wrapped.stream(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
            }
        ).toList()

        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "hello" })
        val result = assertNotNull(recorder.results["step_1"])
        val output = result.output!!.jsonObject
        assertEquals("hello", output["textParts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(
            "because",
            output["reasoningParts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        )
        assertEquals("search", output["toolCalls"]!!.jsonArray.single().jsonObject["toolName"]!!.jsonPrimitive.content)
        assertEquals(FinishReason.ToolCalls.name, output["finishReason"]!!.jsonPrimitive.content)
        assertEquals(2, result.usage!!.completionTokens)
        assertEquals(JsonPrimitive("raw-provider-event"), result.rawChunks.single())
        assertEquals("finish", result.rawResponse!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `inMemoryDevToolsRecorder hands out snapshots a later step cannot mutate`() = runTest {
        val recorder = InMemoryDevToolsRecorder()
        recorder.createRun("run_1")
        recorder.createStep(recorderStep("step_1"))
        val runs = recorder.runs
        val steps = recorder.steps
        val results = recorder.results

        recorder.createRun("run_2")
        recorder.createStep(recorderStep("step_2"))
        recorder.updateStepResult("step_1", DevToolsStepResult(1, null, null, null))

        assertEquals(listOf("run_1"), runs)
        assertEquals(listOf("step_1"), steps.map { it.id })
        assertEquals(emptySet(), results.keys)
    }

    @Test
    fun `inMemoryDevToolsRecorder records every step written by concurrent callers`() = runTest {
        val recorder = InMemoryDevToolsRecorder()

        withContext(Dispatchers.Default) {
            (0 until 8).map { writer ->
                launch {
                    repeat(200) { index -> recorder.createStep(recorderStep("step_${writer}_$index")) }
                }
            }.joinAll()
        }

        assertEquals(1600, recorder.steps.size)
    }

    private fun recorderStep(id: String): DevToolsStep = DevToolsStep(
        id = id,
        runId = "run_1",
        stepNumber = 1,
        type = "generate",
        modelId = "mock/test",
        provider = "mock",
        input = JsonObject(emptyMap()),
        providerOptions = ProviderOptions.None,
    )

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
                usage = Usage(promptTokens = 1, completionTokens = 1),
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
            emit(StreamEvent.Finish(1, FinishReason.ToolCalls, Usage(promptTokens = 1, completionTokens = 2)))
        }
    }
}
