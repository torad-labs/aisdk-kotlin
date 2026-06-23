package ai.torad.aisdk

import ai.torad.aisdk.AgentSessions.session
import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import ai.torad.aisdk.providers.MockToolInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionTest {

    @Serializable
    data class WeatherInput(val city: String)

    @Serializable
    data class WeatherOutput(val temperature: Int)

    @Serializable
    data class SendInput(val message: String)

    @Serializable
    data class SendResult(val sent: Boolean)

    @Test
    fun `approve re-fires the call hooks on the resumed segment`() = runTest {
        val sendTool = Tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { SendResult(sent = true) }
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(
                toolName = "send",
                toolInput = MockToolInput("message" to "hi"),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = ToolSet(sendTool),
        )
        val session = agent.session(this)

        session.submit(prompt = "trigger").join()
        assertEquals(AgentSessionStatus.AwaitingApproval, session.state.value.status)

        val pending = session.state.value.pendingApprovals.single()
        session.approve(pending).join()

        assertEquals(AgentSessionStatus.Ready, session.state.value.status)
        // The resumed segment runs to completion — it would "go dark" (no final text) if resume
        // dropped the remembered call config. Upstream v6 re-passes settings on every resume.
        assertEquals("sent", session.state.value.text)
    }

    @Test
    fun `streaming session records tool-call and tool-result parts in the message log`() = runTest {
        val tools = ToolSet(Tool<WeatherInput, WeatherOutput, Unit>(
            name = "weather",
            description = "Get weather.",
        ) { input -> WeatherOutput(temperature = input.city.length) })
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(
                toolName = "weather",
                toolInput = MockToolInput("city" to "Paris"),
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
    fun `streaming session records reasoning source file and denied tool outcomes`() = runTest {
        val agent = object : Agent<Unit, String> {
            override val tools: ToolSet<Unit> = ToolSet()

            override fun generate(
                prompt: String?,
                messages: List<ModelMessage>,
                options: Unit?,
                abortSignal: AbortSignal,
            ): Flow<GenerateResult<String>> = flow {}

            override fun stream(
                prompt: String?,
                messages: List<ModelMessage>,
                options: Unit?,
                abortSignal: AbortSignal,
            ): Flow<StreamEvent> = flow {
                emit(StreamEvent.ReasoningStart("r1"))
                emit(StreamEvent.ReasoningDelta("r1", "thinking"))
                emit(StreamEvent.ReasoningEnd("r1"))
                emit(
                    StreamEvent.SourcePart(
                        id = "src1",
                        sourceType = StreamEvent.SourcePart.SourceType.Url,
                        url = "https://example.test",
                    ),
                )
                emit(StreamEvent.FilePart("file1", "text/plain", "aGk="))
                emit(StreamEvent.ToolOutputDenied("call1", "send", approvalId = "approval1", reason = "no"))
                emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
            }
        }
        val session = agent.session(this)

        session.submitStreaming(prompt = "run").join()

        val parts = session.state.value.messages.flatMap { it.content }
        assertTrue(parts.any { it is ContentPart.Reasoning && it.text == "thinking" })
        assertTrue(parts.any { it is ContentPart.Source && it.url == "https://example.test" })
        assertTrue(parts.any { it is ContentPart.File && it.base64 == "aGk=" })
        assertTrue(parts.any { it is ContentPart.ToolResult && it.toolName == "send" && it.isError })
    }

    @Test
    fun `streaming preserves the tool's model-visible summary rather than the full output`() = runTest {
        val tools = ToolSet(Tool<WeatherInput, WeatherOutput, Unit>(
            name = "weather",
            description = "Get weather.",
            toModelOutput = { _, _ -> ToolResultOutput.Text("summary") },
        ) { input -> WeatherOutput(temperature = input.city.length) })
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(
                toolName = "weather",
                toolInput = MockToolInput("city" to "Paris"),
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
                    usage = Usage.of(promptTokens = 1, completionTokens = 1),
                )

            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                val n = ++calls
                if (n == 1) firstCallGate.await()
                emit(StreamEvent.TextStart("t1"))
                emit(StreamEvent.TextDelta("t1", "call$n"))
                emit(StreamEvent.TextEnd("t1"))
                emit(StreamEvent.Finish(1, FinishReason.Stop, Usage.of(promptTokens = 1, completionTokens = 1)))
            }
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = model,
            instructions = "Be brief.",
            tools = ToolSet<Unit>(),
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

    @Test
    fun `non-streaming submit settles Cancelled when aborted via abortSignal`() = runTest {
        val gate = CompletableDeferred<Unit>()
        // A provider that honours the abort by RETURNING a (partial) result rather
        // than throwing CancellationException: generate() parks until released, then
        // emits a normal Ready-shaped result. The session must still settle Cancelled.
        val agent = object : Agent<Unit, String> {
            override val tools = ToolSet<Unit>()

            override fun generate(
                prompt: String?,
                messages: List<ModelMessage>,
                options: Unit?,
                abortSignal: AbortSignal,
            ): Flow<GenerateResult<String>> = flow {
                gate.await()
                emit(
                    GenerateResult(
                        "done",
                        text = "done",
                        steps = emptyList(),
                        finishReason = FinishReason.Stop,
                        usage = Usage.of(promptTokens = 1, completionTokens = 1),
                        messages = messages,
                    ),
                )
            }

            override fun stream(
                prompt: String?,
                messages: List<ModelMessage>,
                options: Unit?,
                abortSignal: AbortSignal,
            ): Flow<StreamEvent> = flow {}
        }
        val session = agent.session(this)

        val controller = AbortController()
        val job = session.submit(prompt = "x", abortSignal = controller.signal)
        runCurrent() // job starts; generate() parks at the gate
        assertEquals(AgentSessionStatus.Running, session.state.value.status)

        controller.abort() // external signal fires; the in-flight turn is aborted
        gate.complete(Unit) // generate() now returns its (partial) result
        job.join()

        // The returned result must NOT be committed as Ready — the abort wins,
        // mirroring submitStreaming's StreamEvent.Abort handling.
        assertEquals(AgentSessionStatus.Cancelled, session.state.value.status)
    }
}
