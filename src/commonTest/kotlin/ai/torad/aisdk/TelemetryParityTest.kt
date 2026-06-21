package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelemetryParityTest {
    @AfterTest
    fun clearTelemetry() {
        Telemetry.clearGlobalTelemetry()
    }

    @Test
    fun `disabled telemetry selects no attributes and enabled telemetry honors input output gates`() = runTest {
        val disabled = TelemetryTracing.selectTelemetryAttributes(
            TelemetrySettings(isEnabled = false),
            mapOf("input" to TelemetryTracing.telemetryInput { JsonPrimitive("secret") }),
        )
        val noInputs = TelemetryTracing.selectTelemetryAttributes(
            TelemetrySettings(isEnabled = true, recordInputs = false),
            mapOf(
                "simple" to TelemetryTracing.telemetryAttribute(JsonPrimitive("value")),
                "input" to TelemetryTracing.telemetryInput { JsonPrimitive("in") },
                "output" to TelemetryTracing.telemetryOutput { JsonPrimitive("out") },
            ),
        )

        assertEquals(emptyMap(), disabled)
        assertEquals(JsonPrimitive("value"), noInputs["simple"])
        assertEquals(null, noInputs["input"])
        assertEquals(JsonPrimitive("out"), noInputs["output"])
    }

    @Test
    fun `operation name attributes match v6 telemetry shape`() {
        val attributes = TelemetryTracing.assembleOperationNameAttributes(
            operationId = "ai.generateText",
            telemetry = TelemetrySettings(functionId = "chat"),
        )

        assertEquals(JsonPrimitive("ai.generateText chat"), attributes["operation.name"])
        assertEquals(JsonPrimitive("chat"), attributes["resource.name"])
        assertEquals(JsonPrimitive("ai.generateText"), attributes["ai.operationId"])
        assertEquals(JsonPrimitive("chat"), attributes["ai.telemetry.functionId"])
    }

    @Test
    fun `global telemetry integrations broadcast in registration order`() = runTest {
        val calls = mutableListOf<String>()
        Telemetry.registerTelemetry(OrderedIntegration("first", calls))
        Telemetry.registerTelemetry(OrderedIntegration("second", calls))
        val composite = Telemetry.resolveTelemetry(null)
        checkNotNull(composite)
        val call = TelemetryCall(callId = "c1", agentId = "agent")

        composite.onAgentStart(call, AgentEvent.Started(null, emptyList(), null))
        composite.onAgentFinish(call, AgentEvent.Finished(null, 1, Usage()))

        assertEquals(listOf("first:start", "second:start", "first:finish", "second:finish"), calls)
    }

    @Test
    fun `tracer recordSpan returns result ends spans and records errors`() = runTest {
        val tracer = InMemoryTelemetryTracer()
        val result = TelemetryTracing.recordSpan(
            name = "ai.generateText",
            tracer = tracer,
            attributes = mapOf("ai.model.id" to JsonPrimitive("gpt-test")),
        ) { span ->
            span.setAttribute("custom", JsonPrimitive("ok"))
            "done"
        }

        assertEquals("done", result)
        assertEquals(true, tracer.spans.single().hasEnded)
        assertEquals(JsonPrimitive("gpt-test"), tracer.spans.single().attributes["ai.model.id"])
        assertEquals(JsonPrimitive("ok"), tracer.spans.single().attributes["custom"])

        val error = assertFailsWith<IllegalStateException> {
            TelemetryTracing.recordSpan(name = "ai.fail", tracer = tracer) {
                error("boom")
            }
        }

        assertEquals("boom", error.message)
        val failed = tracer.spans.last()
        assertTrue(failed.hasEnded)
        assertIs<TelemetrySpanStatus.Error>(failed.status)
        assertEquals("exception", failed.events.single().name)
    }

    private class OrderedIntegration(
        override val name: String,
        private val calls: MutableList<String>,
    ) : Telemetry {
        override suspend fun onAgentStart(call: TelemetryCall, event: AgentEvent.Started) {
            calls += "$name:start"
        }

        override suspend fun onAgentFinish(call: TelemetryCall, event: AgentEvent.Finished) {
            calls += "$name:finish"
        }
    }
}
