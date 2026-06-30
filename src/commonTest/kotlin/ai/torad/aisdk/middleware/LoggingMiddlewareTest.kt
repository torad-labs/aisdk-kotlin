@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.middleware

import ai.torad.aisdk.AgentError
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.Logger
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.UserMessage
import ai.torad.aisdk.WrapLanguageModel
import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.ScriptedResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavior tests for [loggingMiddleware] — the port-side consumer of the
 * [Logger] primitive. Proves tool-call boundary events route to
 * [Logger.debug] and errors route to [Logger.warn] carrying the typed
 * throwable, without the agent loop or providers being touched.
 */
class LoggingMiddlewareTest {

    private class RecordingLogger : Logger {
        val warns = mutableListOf<Pair<String, Throwable?>>()
        val debugs = mutableListOf<String>()
        val infos = mutableListOf<String>()
        override fun warn(message: String, throwable: Throwable?) {
            warns.add(message to throwable)
        }
        override fun info(message: String) {
            infos.add(message)
        }
        override fun debug(message: String) {
            debugs.add(message)
        }
    }

    private val params = LanguageModelCallParams(messages = listOf(UserMessage("hi")))

    @Test
    fun `given a model that emits a tool call when logging-wrapped then the call is logged at debug`() = runTest {
        // GIVEN a model emitting the tool-open + tool-call boundary.
        val model = MockLanguageModel(
            responses = listOf(
                ScriptedResponse(
                    events = listOf(
                        StreamEvent.ToolInputStart("ti1", "weather"),
                        StreamEvent.ToolCall("call_1", "weather", JsonPrimitive("paris")),
                    ),
                ),
            ),
        )
        val logger = RecordingLogger()
        val wrapped = WrapLanguageModel(model, listOf(LoggingMiddleware(logger)))

        // WHEN streamed.
        val sink = mutableListOf<StreamEvent>()
        wrapped.stream(params).collect { sink.add(it) }

        // THEN both boundary events landed at debug; nothing at warn.
        assertTrue(logger.debugs.any { it.contains("tool-open") && it.contains("weather") }, "tool-open logged")
        assertTrue(logger.debugs.any { it.contains("tool-call") && it.contains("call_1") }, "tool-call logged")
        assertEquals(0, logger.warns.size)
    }

    @Test
    fun `given a model that emits a tool error when logging-wrapped then it is logged at warn with the throwable`() =
        runTest {
            // GIVEN a model emitting a typed tool error.
            val boom = AgentError.ToolExecution("weather", "call_1", IllegalStateException("db down"))
            val model = MockLanguageModel(
                responses = listOf(
                    ScriptedResponse(
                        events = listOf(StreamEvent.ToolError("call_1", "weather", "db down", boom)),
                    ),
                ),
            )
            val logger = RecordingLogger()
            val wrapped = WrapLanguageModel(model, listOf(LoggingMiddleware(logger)))

            // WHEN streamed.
            val sink = mutableListOf<StreamEvent>()
            wrapped.stream(params).collect { sink.add(it) }

            // THEN routed to warn, carrying the typed AgentError as the throwable.
            assertEquals(1, logger.warns.size)
            assertTrue(logger.warns[0].first.contains("tool-error"), "warn message shape")
            assertTrue(logger.warns[0].second is AgentError.ToolExecution, "typed error passed as throwable")
        }

    @Test
    fun `tool args and authorization values are absent from logs by default`() = runTest {
        val secretArgs = buildJsonObject {
            put("Authorization", "Bearer sk-live-secret")
            put("api-key", "api-key-secret")
            put("query", "raw user prompt")
        }
        val model = MockLanguageModel(
            responses = listOf(
                ScriptedResponse(
                    events = listOf(StreamEvent.ToolCall("call_1", "weather", secretArgs)),
                ),
            ),
        )
        val logger = RecordingLogger()
        val wrapped = WrapLanguageModel(model, listOf(LoggingMiddleware(logger)))

        wrapped.stream(params).collect { }

        val allLogs = (logger.debugs + logger.infos + logger.warns.map { it.first }).joinToString("\n")
        assertTrue("argsBytes=" in allLogs, "metadata diagnostics should remain")
        assertTrue("raw user prompt" !in allLogs, allLogs)
        assertTrue("sk-live-secret" !in allLogs, allLogs)
        assertTrue("api-key-secret" !in allLogs, allLogs)
        assertTrue("Authorization" !in allLogs, allLogs)
    }

    @Test
    fun `recordInputs logs redacted args and raw logging requires explicit unsafe opt in`() = runTest {
        val secretArgs = buildJsonObject {
            put("Authorization", "Bearer sk-live-secret")
            put("query", "safe city")
        }
        val model = MockLanguageModel(
            responses = listOf(
                ScriptedResponse(events = listOf(StreamEvent.ToolCall("call_1", "weather", secretArgs))),
                ScriptedResponse(events = listOf(StreamEvent.ToolCall("call_2", "weather", secretArgs))),
            ),
        )

        val redactedLogger = RecordingLogger()
        WrapLanguageModel(
            model,
            listOf(LoggingMiddleware(redactedLogger, LoggingOptions(recordInputs = true))),
        ).stream(params).collect { }
        val redactedLogs = redactedLogger.debugs.joinToString("\n")
        assertTrue("safe city" in redactedLogs, redactedLogs)
        assertTrue("sk-live-secret" !in redactedLogs, redactedLogs)
        assertTrue("[REDACTED]" in redactedLogs, redactedLogs)

        val rawLogger = RecordingLogger()
        WrapLanguageModel(
            model,
            listOf(LoggingMiddleware(rawLogger, LoggingOptions(recordInputs = true, allowRawValues = true))),
        ).stream(params).collect { }
        val rawLogs = rawLogger.debugs.joinToString("\n")
        assertTrue("sk-live-secret" in rawLogs, rawLogs)
    }
}
