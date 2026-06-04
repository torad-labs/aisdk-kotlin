package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import ai.torad.aisdk.providers.mockToolInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentSessionTest {

    @Serializable
    data class WeatherInput(val city: String)

    @Serializable
    data class WeatherOutput(val temperature: Int)

    @Test
    fun `streaming session records tool-call and tool-result parts in the message log`() = runTest {
        val tools = toolSet<Unit> {
            tool<WeatherInput, WeatherOutput>(
                name = "weather",
                description = "Get weather.",
            ) { input -> WeatherOutput(temperature = input.city.length) }
        }
        val agent = ToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "weather",
                toolInput = mockToolInput("city" to "Paris"),
                finalText = "It is mild.",
            ),
            instructions = "Be brief.",
            tools = tools,
        )
        val session = agent.session(this)

        session.submitStreaming(prompt = "weather?").join()

        val state = session.state.value
        assertEquals(AgentSessionStatus.Ready, state.status)
        assertEquals("It is mild.", state.text)

        val parts = state.messages.flatMap { it.content }
        assertTrue(
            parts.any { it is ContentPart.ToolCall && it.toolName == "weather" },
            "streamed tool-call part must be in the message log",
        )
        assertTrue(
            parts.any { it is ContentPart.ToolResult && it.toolName == "weather" },
            "streamed tool-result part must be in the message log",
        )
    }

    @Test
    fun `streaming preserves the tool's model-visible summary, not the full output`() = runTest {
        val tools = toolSet<Unit> {
            tool<WeatherInput, WeatherOutput>(
                name = "weather",
                description = "Get weather.",
                toModelOutput = { _, _ -> ToolResultOutput.Text("summary") },
            ) { input -> WeatherOutput(temperature = input.city.length) }
        }
        val agent = ToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "weather",
                toolInput = mockToolInput("city" to "Paris"),
                finalText = "done",
            ),
            instructions = "Be brief.",
            tools = tools,
        )
        val session = agent.session(this)

        session.submitStreaming(prompt = "weather?").join()

        val toolResult = session.state.value.messages
            .flatMap { it.content }
            .filterIsInstance<ContentPart.ToolResult>()
            .single()
        // modelVisible must be the toModelOutput summary, NOT the full payload —
        // otherwise a resumed turn re-feeds the full output to the model.
        assertEquals("summary", (toolResult.modelVisible as JsonPrimitive).content)
        assertNotEquals(toolResult.modelVisible, toolResult.output)
    }

    @Test
    fun `rapid resubmission converges to the latest submission's result`() = runTest {
        val firstCallGate = CompletableDeferred<Unit>()
        // The agent drives the model through stream() even for generate(), so
        // the gate lives in stream(). The first (soon-superseded) call parks
        // until released; the second returns immediately.
        val model = object : LanguageModel {
            override val modelId: String = "test/gated"
            var calls = 0

            override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
                LanguageModelResult(
                    text = "",
                    finishReason = FinishReason.Stop,
                    usage = Usage(promptTokens = 1, completionTokens = 1),
                )

            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                val n = ++calls
                if (n == 1) firstCallGate.await()
                emit(StreamEvent.TextStart("t1"))
                emit(StreamEvent.TextDelta("t1", "call$n"))
                emit(StreamEvent.TextEnd("t1"))
                emit(StreamEvent.Finish(1, FinishReason.Stop, Usage(promptTokens = 1, completionTokens = 1)))
            }
        }
        val agent = ToolLoopAgent<Unit, String>(
            model = model,
            instructions = "Be brief.",
            tools = toolSet {},
        )
        val session = agent.session(this)

        session.submit(prompt = "first")
        runCurrent() // let the first job reach firstCallGate.await()
        val second = session.submit(prompt = "second") // cancels the first, becomes the active job
        firstCallGate.complete(Unit) // first job resumes into a CancellationException
        second.join()
        advanceUntilIdle()

        // Submitting again while a generation is in flight cancels it and
        // settles on the newer result — never stuck Running, never the stale
        // "call1". (The @Volatile `currentJob` active-job guard additionally
        // protects this on real multithreaded dispatchers, where a cancelled
        // job's terminal write can land after the new job settles; that
        // interleaving is not reproducible on the single-threaded test
        // scheduler, so this asserts the deterministic convergence behavior.)
        assertEquals(AgentSessionStatus.Ready, session.state.value.status)
        assertEquals("call2", session.state.value.text)
    }
}
