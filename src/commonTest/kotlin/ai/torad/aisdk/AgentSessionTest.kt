@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import ai.torad.aisdk.providers.MockToolInput
import ai.torad.aisdk.providers.ScriptedResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    fun `approving one of two pending approvals keeps the other answerable`() = runTest {
        val executed = mutableListOf<String>()
        val sendTool = Tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { input ->
            executed += input.message
            SendResult(sent = true)
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModel(
                responses = listOf(
                    ScriptedResponse {
                        events(
                            listOf(
                                StreamEvent.ToolCall("call_A", "send", buildJsonObject { put("message", "first") }),
                                StreamEvent.ToolCall("call_B", "send", buildJsonObject { put("message", "second") }),
                            ),
                        )
                        finishReason(FinishReason.ToolCalls)
                    },
                    ScriptedResponse {
                        events(
                            listOf(
                                StreamEvent.TextStart("t1"),
                                StreamEvent.TextDelta("t1", "done"),
                                StreamEvent.TextEnd("t1"),
                            ),
                        )
                    },
                ),
            ),
            instructions = "use send",
            tools = ToolSet(sendTool),
        )
        val session = agent.session(this)

        session.submit(prompt = "trigger").join()
        val pendings = session.state.value.pendingApprovals
        assertEquals(2, pendings.size, "a step gating two tools surfaces two approvals")

        session.approve(pendings[0]).join()

        // Answering one of two must NOT relaunch the loop: the unanswered call would be
        // replayed to the provider as a tool_use with no tool_result, and the host would
        // have lost the handle needed to answer it.
        assertEquals(listOf(pendings[1]), session.state.value.pendingApprovals)
        assertEquals(AgentSessionStatus.AwaitingApproval, session.state.value.status)

        session.approve(pendings[1]).join()

        assertEquals(listOf("first", "second"), executed)
        assertEquals(AgentSessionStatus.Ready, session.state.value.status)
    }

    @Test
    fun `streaming session records tool-call and tool-result parts in the message log`() = runTest {
        val tools = ToolSet(
            Tool<WeatherInput, WeatherOutput, Unit>(
                name = "weather",
                description = "Get weather.",
            ) { input -> WeatherOutput(temperature = input.city.length) }
        )
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
    fun `streaming session keeps each step's messages separate and in order`() = runTest {
        val tools = ToolSet(
            Tool<WeatherInput, WeatherOutput, Unit>(
                name = "weather",
                description = "Get weather.",
            ) { input -> WeatherOutput(temperature = input.city.length) }
        )
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModel(
                responses = listOf(
                    ScriptedResponse {
                        events(
                            listOf(
                                StreamEvent.TextStart("t1"),
                                StreamEvent.TextDelta("t1", "Let me check the weather"),
                                StreamEvent.TextEnd("t1"),
                                StreamEvent.ToolCall(
                                    "call_1",
                                    "weather",
                                    buildJsonObject { put("city", "Paris") },
                                ),
                            ),
                        )
                        finishReason(FinishReason.ToolCalls)
                    },
                    ScriptedResponse {
                        events(
                            listOf(
                                StreamEvent.TextStart("t2"),
                                StreamEvent.TextDelta("t2", "It is 20C in Paris."),
                                StreamEvent.TextEnd("t2"),
                            ),
                        )
                    },
                ),
            ),
            instructions = "Be brief.",
            tools = tools,
        )
        val session = agent.session(this)

        session.submitStreaming(prompt = "weather?").join()

        // The projected log is fed straight back to the model on the next submit()/approve(),
        // so it must mirror the real turn: step 1's text + tool call, the tool result, THEN
        // step 2's answer — not one merged assistant message with both steps' text.
        val messages = session.state.value.messages
        assertEquals(
            listOf(MessageRole.User, MessageRole.Assistant, MessageRole.Tool, MessageRole.Assistant),
            messages.map { it.role },
        )
        assertEquals(
            listOf("Let me check the weather"),
            messages[1].content.filterIsInstance<ContentPart.Text>().map { it.text },
        )
        assertTrue(messages[1].content.any { it is ContentPart.ToolCall && it.toolCallId == "call_1" })
        assertEquals(
            listOf("It is 20C in Paris."),
            messages[3].content.filterIsInstance<ContentPart.Text>().map { it.text },
        )
        // state.text stays the whole turn's text (parity with the non-streaming submit path).
        assertEquals("Let me check the weatherIt is 20C in Paris.", session.state.value.text)
    }

    @Test
    fun `streaming session preserves duplicate tool occurrences and approvals`() = runTest {
        fun input(message: String): JsonObject = buildJsonObject { put("message", message) }
        fun metadata(source: String): ProviderMetadata = ProviderMetadata.Raw(
            buildJsonObject {
                put("source", source)
            },
        )
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
                emit(StreamEvent.ToolCall("dup", "send", input("first"), metadata("call-1")))
                emit(StreamEvent.ToolCall("dup", "send", input("second"), metadata("call-2")))
                emit(
                    StreamEvent.ToolApprovalRequest(
                        toolCallId = "dup",
                        toolName = "send",
                        inputJson = input("first"),
                        approvalId = "approval-1",
                        signature = "sig-1",
                        providerMetadata = metadata("approval-1"),
                    ),
                )
                emit(
                    StreamEvent.ToolApprovalRequest(
                        toolCallId = "dup",
                        toolName = "send",
                        inputJson = input("second"),
                        approvalId = "approval-2",
                        signature = "sig-2",
                        providerMetadata = metadata("approval-2"),
                    ),
                )
                emit(StreamEvent.Finish(1, FinishReason.ToolApprovalRequested, Usage()))
            }
        }
        val session = agent.session(this)

        session.submitStreaming(prompt = "send").join()

        val assistantParts = session.state.value.messages
            .filter { it.role == MessageRole.Assistant }
            .flatMap { it.content }
        val calls = assistantParts.filterIsInstance<ContentPart.ToolCall>()
        assertEquals(2, calls.size)
        assertEquals(listOf(input("first"), input("second")), calls.map { it.input })

        val approvals = assistantParts.filterIsInstance<ContentPart.ToolApprovalRequest>()
        assertEquals(listOf("approval-1", "approval-2"), approvals.map { it.approvalId })
        assertEquals(listOf("sig-1", "sig-2"), approvals.map { it.signature })
        assertEquals(
            listOf(JsonPrimitive("approval-1"), JsonPrimitive("approval-2")),
            approvals.map { it.providerMetadata.toMap()["source"] },
        )
        assertEquals(approvals.map { it.approvalId }, session.state.value.pendingApprovals.map { it.approvalId })
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
                val denied = ToolResultOutput.ExecutionDenied("no")
                val deniedJson = denied.toJsonElement()
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
                emit(
                    StreamEvent.ToolResult(
                        toolCallId = "call1",
                        toolName = "send",
                        outputJson = deniedJson,
                        output = denied,
                        modelOutput = denied,
                        isError = true,
                    ),
                )
                emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
            }
        }
        val session = agent.session(this)

        session.submitStreaming(prompt = "run").join()

        val parts = session.state.value.messages.flatMap { it.content }
        assertTrue(parts.any { it is ContentPart.Reasoning && it.text == "thinking" })
        assertTrue(parts.any { it is ContentPart.Source && it.url == "https://example.test" })
        assertTrue(parts.any { it is ContentPart.File && it.base64 == "aGk=" })
        val toolResults = parts.filterIsInstance<ContentPart.ToolResult>()
        assertEquals(1, toolResults.size)
        assertTrue(toolResults.single().toolName == "send" && toolResults.single().isError)
    }

    @Test
    fun `streaming preserves the tool's model-visible summary rather than the full output`() = runTest {
        val tools = ToolSet(
            Tool<WeatherInput, WeatherOutput, Unit>(
                name = "weather",
                description = "Get weather.",
                toModelOutput = { _, _ -> ToolResultOutput.Text("summary") },
            ) { input -> WeatherOutput(temperature = input.city.length) }
        )
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
        // The first non-streaming generate call parks until released; the
        // second returns immediately after superseding it.
        val model = object : LanguageModel {
            override val modelId: String = "test/gated"
            var calls = 0

            override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
                (++calls).let { n ->
                    if (n == 1) firstCallGate.await()
                    LanguageModelResult(
                        text = "call$n",
                        finishReason = FinishReason.Stop,
                        usage = Usage(promptTokens = 1, completionTokens = 1),
                    )
                }

            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                val n = ++calls
                if (n == 1) firstCallGate.await()
                emit(StreamEvent.TextStart("t1"))
                emit(StreamEvent.TextDelta("t1", "call$n"))
                emit(StreamEvent.TextEnd("t1"))
                emit(StreamEvent.Finish(1, FinishReason.Stop, Usage(promptTokens = 1, completionTokens = 1)))
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
                    ResultConstruction.generateResult(
                        rawOutput = "done",
                        text = "done",
                        steps = emptyList(),
                        finishReason = FinishReason.Stop,
                        usage = Usage(promptTokens = 1, completionTokens = 1),
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

    @Test
    fun `structured-output submit settles Cancelled when aborted mid-run`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val weatherTool = Tool<WeatherInput, WeatherOutput, Unit>(
            name = "weather",
            description = "Get weather.",
        ) { input ->
            gate.await()
            WeatherOutput(temperature = input.city.length)
        }
        val agent = TestToolLoopAgent<Unit, WeatherOutput>(
            model = MockLanguageModelToolThenText(
                toolName = "weather",
                toolInput = MockToolInput("city" to "Paris"),
                finalText = """{"temperature":20}""",
            ),
            instructions = "Be brief.",
            tools = ToolSet(weatherTool),
            output = OutputObj(serializer<WeatherOutput>()),
        )
        val session = agent.session(this)

        val controller = AbortController()
        val job = session.submit(prompt = "weather?", abortSignal = controller.signal)
        runCurrent() // the tool parks at the gate inside step 1
        controller.abort()
        gate.complete(Unit) // step 1 finishes; the loop aborts at the next step boundary
        job.join()

        // The user's own cancel must settle Cancelled — not Error("the model finished with
        // `tool-calls`, not `stop`"), which is a decode diagnosis for a length/step-cap ending.
        assertEquals(AgentSessionStatus.Cancelled, session.state.value.status)
    }

    @Test
    fun `submit settles Cancelled when agent generation throws cancellation`() = runTest {
        val agent = object : Agent<Unit, String> {
            override val tools = ToolSet<Unit>()

            override fun generate(
                prompt: String?,
                messages: List<ModelMessage>,
                options: Unit?,
                abortSignal: AbortSignal,
            ): Flow<GenerateResult<String>> = flow {
                throw CancellationException("generation cancelled")
            }

            override fun stream(
                prompt: String?,
                messages: List<ModelMessage>,
                options: Unit?,
                abortSignal: AbortSignal,
            ): Flow<StreamEvent> = flow {}
        }
        val session = agent.session(this)

        val job = session.submit(prompt = "x")
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(AgentSessionStatus.Cancelled, session.state.value.status)
    }
}
