package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

    @Test
    @Suppress("LongMethod")
    fun `chunk telemetry redacts approval input raw payloads and streamed tool input by default`() = runTest {
        val capturing = CapturingTelemetry()
        val telemetry = Telemetry.resolveTelemetry(TelemetrySettings(integrations = listOf(capturing)))
            ?: fail("telemetry should resolve")

        telemetry.onEvent(
            call,
            AgentEvent.Chunk(
                StreamEvent.ToolApprovalRequest(
                    toolCallId = "call_1",
                    toolName = "search",
                    inputJson = buildJsonObject { put("query", "raw tool secret") },
                    approvalId = "approval_1",
                    signature = "signed-secret",
                ),
                stepNumber = 1,
            ),
        )
        telemetry.onEvent(
            call,
            AgentEvent.Chunk(StreamEvent.ToolInputDelta(id = "input_1", delta = "token=raw-secret"), stepNumber = 1),
        )
        telemetry.onEvent(
            call,
            AgentEvent.Chunk(
                StreamEvent.Raw(buildJsonObject { put("payload", "raw provider secret") }),
                stepNumber = 1,
            ),
        )
        telemetry.onEvent(
            call,
            AgentEvent.Chunk(
                StreamEvent.ResponseMetadata(
                    headers = mapOf("Authorization" to "Bearer sk-live-secret"),
                    body = buildJsonObject { put("answer", "raw body secret") },
                ),
                stepNumber = 1,
            ),
        )

        val approval = assertIs<StreamEvent.ToolApprovalRequest>(
            assertIs<AgentEvent.Chunk>(capturing.events[0]).event,
        )
        assertEquals(JsonObject(emptyMap()), approval.inputJson)
        assertEquals(null, approval.signature)

        val toolInput = assertIs<StreamEvent.ToolInputDelta>(
            assertIs<AgentEvent.Chunk>(capturing.events[1]).event,
        )
        assertEquals("", toolInput.delta)

        val raw = assertIs<StreamEvent.Raw>(assertIs<AgentEvent.Chunk>(capturing.events[2]).event)
        assertEquals(JsonObject(emptyMap()), raw.rawValue)

        val metadata = assertIs<StreamEvent.ResponseMetadata>(
            assertIs<AgentEvent.Chunk>(capturing.events[3]).event,
        )
        assertEquals("[REDACTED]", metadata.headers["Authorization"])
        assertEquals(null, metadata.body)

        val projected = capturing.events.joinToString("\n")
        assertTrue("raw tool secret" !in projected, projected)
        assertTrue("signed-secret" !in projected, projected)
        assertTrue("raw provider secret" !in projected, projected)
        assertTrue("sk-live-secret" !in projected, projected)
    }

    @Test
    fun `chunk telemetry recordInputs still redacts sensitive approval fields`() = runTest {
        val capturing = CapturingTelemetry()
        val telemetry = Telemetry.resolveTelemetry(
            TelemetrySettings(recordInputs = true, integrations = listOf(capturing)),
        ) ?: fail("telemetry should resolve")

        telemetry.onEvent(
            call,
            AgentEvent.Chunk(
                StreamEvent.ToolApprovalRequest(
                    toolCallId = "call_1",
                    toolName = "search",
                    inputJson = buildJsonObject {
                        put("query", "visible query")
                        put("apiKey", "sk-live-secret")
                    },
                    approvalId = "approval_1",
                    signature = "signed-secret",
                ),
                stepNumber = 1,
            ),
        )

        val approval = assertIs<StreamEvent.ToolApprovalRequest>(
            assertIs<AgentEvent.Chunk>(capturing.events.single()).event,
        )
        assertEquals(null, approval.signature)
        assertEquals(JsonPrimitive("visible query"), approval.inputJson.jsonObject["query"])
        assertEquals(JsonPrimitive("[REDACTED]"), approval.inputJson.jsonObject["apiKey"])
    }

    @Test
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun `chunk telemetry strips provider metadata from stream events`() = runTest {
        val capturing = CapturingTelemetry()
        val telemetry = Telemetry.resolveTelemetry(TelemetrySettings(integrations = listOf(capturing)))
            ?: fail("telemetry should resolve")
        val metadata = ProviderMetadata.Raw(buildJsonObject { put("token", "raw-meta-secret") })
        val rawUsage = Usage(raw = buildJsonObject { put("payload", "raw-usage-secret") })
        val events = listOf(
            StreamEvent.StepStart(stepNumber = 1, providerMetadata = metadata),
            StreamEvent.TextStart(id = "text_1", providerMetadata = metadata),
            StreamEvent.TextDelta(id = "text_1", text = "answer secret", providerMetadata = metadata),
            StreamEvent.TextEnd(id = "text_1", providerMetadata = metadata),
            StreamEvent.ReasoningStart(id = "reasoning_1", providerMetadata = metadata),
            StreamEvent.ReasoningDelta(id = "reasoning_1", text = "reasoning secret", providerMetadata = metadata),
            StreamEvent.ReasoningEnd(id = "reasoning_1", providerMetadata = metadata),
            StreamEvent.ToolInputStart(id = "input_1", toolName = "search", providerMetadata = metadata),
            StreamEvent.ToolInputDelta(id = "input_1", delta = "input secret", providerMetadata = metadata),
            StreamEvent.ToolInputEnd(id = "input_1", providerMetadata = metadata),
            StreamEvent.ToolCall(
                toolCallId = "call_1",
                toolName = "search",
                inputJson = buildJsonObject { put("query", "input secret") },
                providerMetadata = metadata,
            ),
            StreamEvent.ToolError(
                toolCallId = "call_1",
                toolName = "search",
                message = "tool secret",
                providerMetadata = metadata,
            ),
            StreamEvent.ToolOutputDenied(
                toolCallId = "call_1",
                toolName = "search",
                approvalId = "approval_1",
                reason = "denial secret",
                providerMetadata = metadata,
            ),
            StreamEvent.StepFinish(
                stepNumber = 1,
                finishReason = FinishReason.Stop,
                usage = rawUsage,
                providerMetadata = metadata,
            ),
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = FinishReason.Stop,
                usage = rawUsage,
                providerMetadata = metadata,
            ),
        )

        events.forEach { telemetry.onEvent(call, AgentEvent.Chunk(it, stepNumber = 1)) }

        val projectedEvents = capturing.events.map { assertIs<AgentEvent.Chunk>(it).event }
        projectedEvents.forEach { event ->
            when (event) {
                is StreamEvent.StepStart -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.TextStart -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.TextDelta -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.TextEnd -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.ReasoningStart -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.ReasoningDelta -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.ReasoningEnd -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.ToolInputStart -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.ToolInputDelta -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.ToolInputEnd -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.ToolCall -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.ToolError -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.ToolOutputDenied -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.StepFinish -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.Finish -> assertEquals(ProviderMetadata.None, event.providerMetadata)
                is StreamEvent.StreamStart,
                is StreamEvent.ResponseMetadata,
                is StreamEvent.FilePart,
                is StreamEvent.SourcePart,
                is StreamEvent.ToolResult,
                is StreamEvent.ToolApprovalRequest,
                is StreamEvent.Error,
                is StreamEvent.Raw,
                StreamEvent.Abort,
                -> Unit
            }
        }
        assertEquals(null, assertIs<StreamEvent.StepFinish>(projectedEvents[13]).usage.raw)
        assertEquals(null, assertIs<StreamEvent.Finish>(projectedEvents[14]).usage.raw)
        val projected = capturing.events.joinToString("\n")
        assertTrue("raw-meta-secret" !in projected, projected)
        assertTrue("raw-usage-secret" !in projected, projected)
        assertTrue("answer secret" !in projected, projected)
        assertTrue("input secret" !in projected, projected)
    }

    @Test
    fun `chunk telemetry raw payloads require input and output opt in`() = runTest {
        val inputOnly = CapturingTelemetry()
        val inputOnlyTelemetry = Telemetry.resolveTelemetry(
            TelemetrySettings(recordInputs = true, integrations = listOf(inputOnly)),
        ) ?: fail("telemetry should resolve")
        inputOnlyTelemetry.onEvent(
            call,
            AgentEvent.Chunk(
                StreamEvent.Raw(buildJsonObject { put("payload", "raw provider secret") }),
                stepNumber = 1,
            ),
        )
        val redactedRaw = assertIs<StreamEvent.Raw>(assertIs<AgentEvent.Chunk>(inputOnly.events.single()).event)
        assertEquals(JsonObject(emptyMap()), redactedRaw.rawValue)

        val fullOptIn = CapturingTelemetry()
        val fullOptInTelemetry = Telemetry.resolveTelemetry(
            TelemetrySettings(recordInputs = true, recordOutputs = true, integrations = listOf(fullOptIn)),
        ) ?: fail("telemetry should resolve")
        fullOptInTelemetry.onEvent(
            call,
            AgentEvent.Chunk(
                StreamEvent.Raw(
                    buildJsonObject {
                        put("query", "visible")
                        put("apiKey", "sk-live-secret")
                    },
                ),
                stepNumber = 1,
            ),
        )
        val raw = assertIs<StreamEvent.Raw>(assertIs<AgentEvent.Chunk>(fullOptIn.events.single()).event)
        assertEquals(JsonPrimitive("visible"), raw.rawValue.jsonObject["query"])
        assertEquals(JsonPrimitive("[REDACTED]"), raw.rawValue.jsonObject["apiKey"])
    }

    @Test
    fun `chunk telemetry output source keeps title but strips urls`() = runTest {
        val capturing = CapturingTelemetry()
        val telemetry = Telemetry.resolveTelemetry(
            TelemetrySettings(recordOutputs = true, integrations = listOf(capturing)),
        ) ?: fail("telemetry should resolve")

        telemetry.onEvent(
            call,
            AgentEvent.Chunk(
                StreamEvent.SourcePart(
                    id = "source_1",
                    sourceType = StreamEvent.SourcePart.SourceType.Url,
                    url = "https://example.test/doc?token=signed-secret",
                    title = "visible title",
                    mediaType = "text/html",
                    providerMetadata = ProviderMetadata.Raw(buildJsonObject { put("token", "raw-meta-secret") }),
                ),
                stepNumber = 1,
            ),
        )

        val source = assertIs<StreamEvent.SourcePart>(assertIs<AgentEvent.Chunk>(capturing.events.single()).event)
        assertEquals(null, source.url)
        assertEquals("visible title", source.title)
        assertEquals("text/html", source.mediaType)
        assertEquals(ProviderMetadata.None, source.providerMetadata)
        val projected = source.toString()
        assertTrue("signed-secret" !in projected, projected)
        assertTrue("raw-meta-secret" !in projected, projected)
    }
}
