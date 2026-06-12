package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import ai.torad.aisdk.testing.drainAllItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v7 telemetry revamp wiring — the loop feeds a registered [Telemetry]
 * integration EVERY lifecycle event automatically (agent/step/model-call/
 * tool-call/error/abort), correlated by one [TelemetryCall.callId] per
 * invocation. Integrations observe; they never alter loop behavior.
 */
class TelemetryWiringTest {

    @Serializable
    private data class EchoInput(val q: String)

    private fun echoTool() = tool<EchoInput, String, Unit>(
        name = "echo",
        description = "echoes",
        inputSerializer = serializer(),
        outputSerializer = serializer(),
    ) { input -> "echo:${input.q}" }

    private class RecordingTelemetry(override val name: String = "recording") : Telemetry {
        val events = mutableListOf<String>()
        val callIds = mutableSetOf<String>()
        var agentId: String? = null
        var modelId: String? = null

        private fun record(call: TelemetryCall, label: String) {
            events += label
            callIds += call.callId
            agentId = call.agentId
            modelId = call.modelId
        }

        override suspend fun onAgentStart(call: TelemetryCall, event: OnStartEvent) = record(call, "agentStart")
        override suspend fun onStepStart(call: TelemetryCall, event: OnStepStartEvent) =
            record(call, "stepStart:${event.stepNumber}")
        override suspend fun onModelCallStart(call: TelemetryCall, event: TelemetryModelCallEvent) =
            record(call, "modelCallStart:${event.stepNumber}")
        override suspend fun onModelCallFinish(call: TelemetryCall, event: TelemetryModelCallResultEvent) =
            record(call, "modelCallFinish:${event.stepNumber}:${event.finishReason}")
        override suspend fun onToolCallStart(call: TelemetryCall, event: OnToolCallStartEvent) =
            record(call, "toolCallStart:${event.toolName}")
        override suspend fun onToolCallFinish(call: TelemetryCall, event: OnToolCallFinishEvent) =
            record(call, "toolCallFinish:${event.toolName}:${if (event.errorMessage == null) "ok" else "err"}")
        override suspend fun onStepFinish(call: TelemetryCall, event: OnStepFinishEvent) =
            record(call, "stepFinish:${event.stepNumber}")
        override suspend fun onError(call: TelemetryCall, event: OnErrorEvent) =
            record(call, "error:${event.source}")
        override suspend fun onAbort(call: TelemetryCall, event: OnAbortEvent) = record(call, "abort")
        override suspend fun onAgentFinish(call: TelemetryCall, event: OnFinishEvent) =
            record(call, "agentFinish:${event.totalSteps}")
    }

    /** Throws from every method — the loop must be unaffected. */
    private class ExplodingTelemetry : Telemetry {
        override val name: String = "exploding"
        override suspend fun onAgentStart(call: TelemetryCall, event: OnStartEvent) = boom()
        override suspend fun onStepStart(call: TelemetryCall, event: OnStepStartEvent) = boom()
        override suspend fun onModelCallStart(call: TelemetryCall, event: TelemetryModelCallEvent) = boom()
        override suspend fun onModelCallFinish(call: TelemetryCall, event: TelemetryModelCallResultEvent) = boom()
        override suspend fun onToolCallStart(call: TelemetryCall, event: OnToolCallStartEvent) = boom()
        override suspend fun onToolCallFinish(call: TelemetryCall, event: OnToolCallFinishEvent) = boom()
        override suspend fun onStepFinish(call: TelemetryCall, event: OnStepFinishEvent) = boom()
        override suspend fun onError(call: TelemetryCall, event: OnErrorEvent) = boom()
        override suspend fun onAbort(call: TelemetryCall, event: OnAbortEvent) = boom()
        override suspend fun onAgentFinish(call: TelemetryCall, event: OnFinishEvent) = boom()
        private fun boom(): Nothing = error("telemetry exploded")
    }

    private fun toolThenTextAgent(rec: Telemetry?) = TestToolLoopAgent<Unit, String>(
        model = mockLanguageModelToolThenText(
            toolName = "echo",
            toolInput = buildJsonObject { put("q", "hi") },
            finalText = "done",
        ),
        instructions = "x",
        tools = toolSetOf(echoTool()),
        telemetry = rec?.let { TelemetrySettings(integrations = listOf(it)) },
    )

    @Test
    fun `the loop feeds an integration every lifecycle event under one callId`() = runTest {
        val rec = RecordingTelemetry()
        val result = toolThenTextAgent(rec).generate(prompt = "go")

        assertEquals("done", result.text)
        assertEquals(
            listOf(
                "agentStart",
                "stepStart:1",
                "modelCallStart:1",
                "modelCallFinish:1:ToolCalls",
                "toolCallStart:echo",
                "toolCallFinish:echo:ok",
                "stepFinish:1",
                "stepStart:2",
                "modelCallStart:2",
                "modelCallFinish:2:Stop",
                "stepFinish:2",
                "agentFinish:2",
            ),
            rec.events,
        )
        assertEquals(1, rec.callIds.size, "every event of one invocation carries the same callId")
        assertEquals("agent", rec.agentId)
        assertEquals("mock/test", rec.modelId)
    }

    @Test
    fun `two invocations get distinct callIds`() = runTest {
        val rec = RecordingTelemetry()
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelTextOnly("hi"),
            instructions = "x",
            tools = toolSetOf(),
            telemetry = TelemetrySettings(integrations = listOf(rec)),
        )
        agent.generate(prompt = "one")
        agent.generate(prompt = "two")
        assertEquals(2, rec.callIds.size)
    }

    @Test
    fun `a throwing integration never alters the loop`() = runTest {
        val result = toolThenTextAgent(ExplodingTelemetry()).generate(prompt = "go")
        assertEquals("done", result.text)
        assertEquals(2, result.steps.size)
    }

    @Test
    fun `a globally registered integration observes with no constructor wiring`() = runTest {
        val rec = RecordingTelemetry()
        registerTelemetry(rec)
        try {
            val agent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelTextOnly("hi"),
                instructions = "x",
                tools = toolSetOf(),
            )
            agent.generate(prompt = "go")
        } finally {
            clearGlobalTelemetry()
        }
        assertTrue(rec.events.isNotEmpty(), "global registration alone makes calls emit")
        assertEquals("agentStart", rec.events.first())
        assertTrue(rec.events.last().startsWith("agentFinish"))
    }

    @Test
    fun `per-call integrations replace the global registration`() = runTest {
        val global = RecordingTelemetry("global")
        val local = RecordingTelemetry("local")
        registerTelemetry(global)
        try {
            val agent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelTextOnly("hi"),
                instructions = "x",
                tools = toolSetOf(),
                telemetry = TelemetrySettings(integrations = listOf(local)),
            )
            agent.generate(prompt = "go")
        } finally {
            clearGlobalTelemetry()
        }
        assertTrue(global.events.isEmpty(), "per-call integrations REPLACE the global set")
        assertTrue(local.events.isNotEmpty())
    }

    @Test
    fun `a model failure reaches onError with source Model`() = runTest {
        val rec = RecordingTelemetry()
        val brokenModel = object : LanguageModel {
            override val modelId = "broken"
            override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
                error("no service")
            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                error("no service")
            }
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = brokenModel,
            instructions = "x",
            tools = toolSetOf(),
            telemetry = TelemetrySettings(integrations = listOf(rec)),
        )
        assertFailsWith<AiSdkException> { agent.generate(prompt = "go") }
        assertTrue(rec.events.contains("error:Model"), "got: ${rec.events}")
    }

    @Test
    fun `a pre-aborted call fires onAbort`() = runTest {
        val rec = RecordingTelemetry()
        val controller = AbortController()
        controller.abort()
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelTextOnly("hi"),
            instructions = "x",
            tools = toolSetOf(),
            telemetry = TelemetrySettings(integrations = listOf(rec)),
        )
        val events = drainAllItems(agent.stream(prompt = "go", abortSignal = controller.signal))
        assertTrue(events.any { it is StreamEvent.Abort })
        assertTrue(rec.events.contains("abort"), "got: ${rec.events}")
        assertNotNull(rec.callIds.singleOrNull())
    }

    @Test
    fun `an explicit isEnabled false opts the call out even with a global registration`() = runTest {
        val global = RecordingTelemetry("global")
        registerTelemetry(global)
        try {
            val agent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelTextOnly("hi"),
                instructions = "x",
                tools = toolSetOf(),
                telemetry = TelemetrySettings(isEnabled = false),
            )
            agent.generate(prompt = "go")
        } finally {
            clearGlobalTelemetry()
        }
        assertTrue(global.events.isEmpty(), "isEnabled=false is the per-call opt-out")
    }

    @Test
    fun `a CancellationException from an integration propagates — the one throw that must alter the loop`() = runTest {
        val cancelling = object : Telemetry {
            override val name: String = "cancelling"
            override suspend fun onAgentStart(call: TelemetryCall, event: OnStartEvent) {
                throw CancellationException("stop from telemetry")
            }
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelTextOnly("hi"),
            instructions = "x",
            tools = toolSetOf(),
            telemetry = TelemetrySettings(integrations = listOf(cancelling)),
        )
        assertFailsWith<CancellationException> { agent.generate(prompt = "go") }
    }

    @Test
    fun `a CancellationException propagates through the COMPOSITE fan-out too`() = runTest {
        val survivor = RecordingTelemetry("survivor")
        val cancelling = object : Telemetry {
            override val name: String = "cancelling"
            override suspend fun onAgentStart(call: TelemetryCall, event: OnStartEvent) {
                throw CancellationException("stop from composite member")
            }
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelTextOnly("hi"),
            instructions = "x",
            tools = toolSetOf(),
            // Two integrations force the CompositeTelemetry path — its broadcast guard must
            // still let cancellation through (and stop the remaining integrations).
            telemetry = TelemetrySettings(integrations = listOf(cancelling, survivor)),
        )
        assertFailsWith<CancellationException> { agent.generate(prompt = "go") }
        assertTrue(survivor.events.isEmpty(), "cancellation stops the broadcast, not just the member")
    }

    @Test
    fun `a provider stream error still closes the model-call bracket`() = runTest {
        val rec = RecordingTelemetry()
        val erroringStreamModel = object : LanguageModel {
            override val modelId = "erroring"
            override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
                error("unused — stream path only")
            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                emit(StreamEvent.TextStart("t"))
                emit(StreamEvent.TextDelta("t", "partial"))
                emit(StreamEvent.Error("provider 500", cause = null))
            }
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = erroringStreamModel,
            instructions = "x",
            tools = toolSetOf(),
            telemetry = TelemetrySettings(integrations = listOf(rec)),
        )
        drainAllItems(agent.stream(prompt = "go"))
        assertTrue(
            rec.events.contains("modelCallFinish:1:Error"),
            "a span-pairing integration must never leak an open model-call span; got: ${rec.events}",
        )
    }

    @Test
    fun `a failing tool reaches onToolCallFinish with an error and onError with source Tool`() = runTest {
        val rec = RecordingTelemetry()
        val failingTool = tool<EchoInput, String, Unit>(
            name = "echo",
            description = "always fails",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ -> error("tool blew up") }
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "echo",
                toolInput = buildJsonObject { put("q", "hi") },
                finalText = "done",
            ),
            instructions = "x",
            tools = toolSetOf(failingTool),
            telemetry = TelemetrySettings(integrations = listOf(rec)),
        )
        agent.generate(prompt = "go")
        assertTrue(rec.events.contains("toolCallFinish:echo:err"), "got: ${rec.events}")
        assertTrue(rec.events.contains("error:Tool"), "got: ${rec.events}")
    }

    @Test
    fun `an approval-resumed tool execution emits tool telemetry under the resumed call`() = runTest {
        val rec = RecordingTelemetry()
        val gated = tool<EchoInput, String, Unit>(
            name = "echo",
            description = "gated echo",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { input -> "echo:${input.q}" }
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "echo",
                toolInput = buildJsonObject { put("q", "hi") },
                finalText = "done",
            ),
            instructions = "x",
            tools = toolSetOf(gated),
            telemetry = TelemetrySettings(integrations = listOf(rec)),
        )
        val first = agent.generate(prompt = "go")
        val pending = first.pendingApprovals.single()
        assertTrue(rec.events.none { it.startsWith("toolCallStart") }, "gated tool must not run before approval")

        rec.events.clear()
        val approval = toolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)
        agent.generate(messages = first.messages + approval)

        // The approval-resume path runs the tool through executeTool — the telemetry bracket
        // is the stated reason it lives THERE, not at the step-loop dispatch site.
        assertTrue(rec.events.contains("toolCallStart:echo"), "got: ${rec.events}")
        assertTrue(rec.events.contains("toolCallFinish:echo:ok"), "got: ${rec.events}")
    }
}
