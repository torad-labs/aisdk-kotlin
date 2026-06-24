package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class TelemetryRedactionTest {
    private class CapturingTelemetry : Telemetry {
        override val name: String = "capturing"
        val events = mutableListOf<AgentEvent>()

        override suspend fun onEvent(call: TelemetryCall, event: AgentEvent) {
            events += event
        }
    }

    private val call = TelemetryCall(callId = "call_1", agentId = "agent")

    @Test
    fun `telemetry defaults to metadata-only event projection`() = runTest {
        val capturing = CapturingTelemetry()
        val telemetry = Telemetry.resolveTelemetry(TelemetrySettings(integrations = listOf(capturing)))
            ?: fail("telemetry should resolve")
        val params = LanguageModelCallParams(
            messages = listOf(UserMessage("prompt secret")),
            headers = mapOf("Authorization" to "Bearer sk-live-secret"),
        )

        telemetry.onEvent(call, AgentEvent.Started("prompt secret", listOf(UserMessage("prior secret")), null))
        telemetry.onEvent(call, AgentEvent.ModelCallStarted(stepNumber = 1, modelId = "model", params = params))
        telemetry.onEvent(
            call,
            AgentEvent.ToolCallStarted(
                toolCallId = "tool_1",
                toolName = "search",
                input = buildJsonObject { put("query", "raw tool arg") },
                stepNumber = 1,
                messages = listOf(UserMessage("message secret")),
            ),
        )
        telemetry.onEvent(
            call,
            AgentEvent.Finished<Unit, String>(
                output = "final secret",
                totalSteps = 1,
                usage = Usage(),
                messages = listOf(AssistantMessage("answer secret")),
            ),
        )

        val started = assertIs<AgentEvent.Started<*>>(capturing.events[0])
        assertEquals(null, started.prompt)
        assertTrue(started.priorMessages.isEmpty())

        val modelStart = assertIs<AgentEvent.ModelCallStarted>(capturing.events[1])
        assertTrue(modelStart.params.messages.isEmpty())
        assertEquals("[REDACTED]", modelStart.params.headers["Authorization"])

        val toolStart = assertIs<AgentEvent.ToolCallStarted>(capturing.events[2])
        assertEquals(JsonObject(emptyMap()), toolStart.input)
        assertTrue(toolStart.messages.isEmpty())

        val finish = assertIs<AgentEvent.Finished<*, *>>(capturing.events[3])
        assertEquals(null, finish.output)
        assertTrue(finish.messages.isEmpty())

        val projected = capturing.events.joinToString("\n")
        assertTrue("prompt secret" !in projected, projected)
        assertTrue("raw tool arg" !in projected, projected)
        assertTrue("final secret" !in projected, projected)
        assertTrue("sk-live-secret" !in projected, projected)
    }

    @Test
    fun `recordInputs opt-in still redacts authorization and sensitive keys`() = runTest {
        val capturing = CapturingTelemetry()
        val telemetry = Telemetry.resolveTelemetry(
            TelemetrySettings(recordInputs = true, integrations = listOf(capturing)),
        ) ?: fail("telemetry should resolve")

        telemetry.onEvent(
            call,
            AgentEvent.ToolCallStarted(
                toolCallId = "tool_1",
                toolName = "search",
                input = buildJsonObject {
                    put("Authorization", "Bearer sk-live-secret")
                    put("query", "visible query")
                },
                stepNumber = 1,
                messages = listOf(UserMessage("visible prompt")),
            ),
        )

        val toolStart = assertIs<AgentEvent.ToolCallStarted>(capturing.events.single())
        val projected = toolStart.toString()
        assertTrue("visible query" in projected, projected)
        assertTrue("visible prompt" in projected, projected)
        assertTrue("sk-live-secret" !in projected, projected)
        assertTrue("[REDACTED]" in projected, projected)
    }
}
