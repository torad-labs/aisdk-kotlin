package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentStepResultParityTest {
    // A single-step model whose stream carries warnings + response metadata + a finish
    // with provider metadata + a raw finish reason, ending without tool calls.
    private fun richModel(finish: FinishReason) = object : LanguageModel {
        override val modelId = "m"
        override suspend fun generate(params: LanguageModelCallParams) =
            LanguageModelResult(text = "hello", finishReason = finish, usage = Usage())
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            emit(StreamEvent.StreamStart(listOf(CallWarning(type = "other", message = "heads up"))))
            emit(StreamEvent.ResponseMetadata(id = "resp_1", modelId = "m"))
            emit(StreamEvent.TextStart("t"))
            emit(StreamEvent.TextDelta("t", "hello"))
            emit(StreamEvent.TextEnd("t"))
            emit(
                StreamEvent.Finish(
                    1,
                    finish,
                    Usage(),
                    providerMetadata = buildJsonObject { put("p", JsonPrimitive(1)) },
                    rawFinishReason = "stop_sequence",
                ),
            )
        }
    }

    @Test
    fun `StepResult is populated with warnings response providerMetadata and rawFinishReason`() = runTest {
        val agent = ToolLoopAgent<Unit, String>(
            model = richModel(FinishReason.Stop),
            instructions = "hi",
            tools = toolSetOf(),
        )
        val result = agent.generate(prompt = "go", options = Unit)
        val step = result.steps.single()
        assertEquals("heads up", step.warnings.single().message)
        assertEquals("resp_1", step.response.id)
        assertEquals("stop_sequence", step.rawFinishReason)
        assertTrue(step.providerMetadata.containsKey("p"), "per-step providerMetadata captured")
    }

    @Test
    fun `structured output is only decoded when the model finished with stop`() = runTest {
        // finishReason = Length → upstream yields no object; we must throw, not decode-error.
        val agent = ToolLoopAgent<Unit, Holder>(
            model = richModel(FinishReason.Length),
            instructions = "hi",
            tools = toolSetOf(),
            output = Output.obj(kotlinx.serialization.serializer<Holder>()),
        )
        assertFailsWith<NoOutputGeneratedError> { agent.generate(prompt = "go", options = Unit) }
    }

    @kotlinx.serialization.Serializable
    private data class Holder(val v: Int = 0)
}
