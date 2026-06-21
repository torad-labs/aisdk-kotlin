package ai.torad.aisdk.middleware

import ai.torad.aisdk.AgentError
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.Logger
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.ScriptedResponse
import ai.torad.aisdk.UserMessage
import ai.torad.aisdk.WrapLanguageModel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
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
}
