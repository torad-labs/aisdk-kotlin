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
        clearGlobalTelemetryIntegrations()
    }

    @Test
    fun `disabled telemetry selects no attributes and enabled telemetry honors input output gates`() = runTest {
        val disabled = selectTelemetryAttributes(
            TelemetrySettings(isEnabled = false),
            mapOf("input" to telemetryInput { JsonPrimitive("secret") }),
        )
        val noInputs = selectTelemetryAttributes(
            TelemetrySettings(isEnabled = true, recordInputs = false),
            mapOf(
                "simple" to telemetryAttribute(JsonPrimitive("value")),
                "input" to telemetryInput { JsonPrimitive("in") },
                "output" to telemetryOutput { JsonPrimitive("out") },
            ),
        )

        assertEquals(emptyMap(), disabled)
        assertEquals(JsonPrimitive("value"), noInputs["simple"])
        assertEquals(null, noInputs["input"])
        assertEquals(JsonPrimitive("out"), noInputs["output"])
    }

    @Test
    fun `operation name attributes match v6 telemetry shape`() {
        val attributes = assembleOperationNameAttributes(
            operationId = "ai.generateText",
            telemetry = TelemetrySettings(functionId = "chat"),
        )

        assertEquals(JsonPrimitive("ai.generateText chat"), attributes["operation.name"])
        assertEquals(JsonPrimitive("chat"), attributes["resource.name"])
        assertEquals(JsonPrimitive("ai.generateText"), attributes["ai.operationId"])
        assertEquals(JsonPrimitive("chat"), attributes["ai.telemetry.functionId"])
    }

    @Test
    fun `global telemetry integration composes lifecycle listeners in registration order`() = runTest {
        val calls = mutableListOf<String>()
        registerTelemetryIntegration(lifecycleIntegration("global") { calls += it })
        val composite = getGlobalTelemetryIntegration(lifecycleIntegration("local") { calls += it })

        composite.onStart(TelemetryEvent("start"))
        composite.onFinish(TelemetryEvent("finish"))

        assertEquals(listOf("global:start", "local:start", "global:finish", "local:finish"), calls)
    }

    @Test
    fun `tracer recordSpan returns result ends spans and records errors`() = runTest {
        val tracer = InMemoryTelemetryTracer()
        val result = recordSpan(
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
            recordSpan(name = "ai.fail", tracer = tracer) {
                error("boom")
            }
        }

        assertEquals("boom", error.message)
        val failed = tracer.spans.last()
        assertTrue(failed.hasEnded)
        assertIs<TelemetrySpanStatus.Error>(failed.status)
        assertEquals("exception", failed.events.single().name)
    }

    private fun lifecycleIntegration(name: String, record: (String) -> Unit): TelemetryIntegration =
        object : TelemetryIntegration {
            override val name: String = name

            override suspend fun record(span: TelemetrySpan, block: suspend () -> Unit) {
                block()
            }

            override suspend fun onStart(event: TelemetryEvent) {
                record("$name:${event.name}")
            }

            override suspend fun onFinish(event: TelemetryEvent) {
                record("$name:${event.name}")
            }
        }
}
