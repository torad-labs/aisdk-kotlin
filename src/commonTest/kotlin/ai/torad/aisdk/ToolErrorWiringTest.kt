package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Wiring tests for the [AgentError] taxonomy. [AgentErrorTest] proves
 * the types CONSTRUCT correctly; these prove the tool loop actually
 * EMITS them — every [StreamEvent.ToolError] now carries the typed
 * `error` slot so in-process consumers can `when (event.error)` instead
 * of substring-matching the message. Before this wiring the taxonomy
 * existed but nothing in the loop ever threw it.
 */
class ToolErrorWiringTest {

    @Serializable
    data class WeatherIn(val city: String)

    private fun weatherTool(body: (WeatherIn) -> String) = Tool<WeatherIn, String, Unit>(
        name = "weather",
        description = "Get weather",
        inputSerializer = serializer(),
        outputSerializer = serializer(),
    ) { input -> body(input) }

    private val validInput: JsonObject = buildJsonObject { put("city", JsonPrimitive("Paris")) }
    private val malformedInput: JsonObject = buildJsonObject { put("location", JsonPrimitive("Paris")) }

    private suspend fun firstToolError(agent: ToolLoopAgent<Unit, String>): StreamEvent.ToolError {
        val events = mutableListOf<StreamEvent>()
        agent.stream(prompt = "?").collect { events.add(it) }
        return events.filterIsInstance<StreamEvent.ToolError>().single()
    }

    @Test
    fun `given the model calls a tool not in the set when run then ToolError carries a typed NoSuchTool`() = runTest {
        // GIVEN — the set has only "weather"; the model calls "ghost".
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(toolName = "ghost", toolInput = validInput, finalText = "done"),
            instructions = "",
            tools = ToolSet(weatherTool { "sunny in ${it.city}" }),
        )

        // WHEN
        val toolError = firstToolError(agent)

        // THEN
        val typed = toolError.error
        assertTrue(typed is AgentError.NoSuchTool, "expected NoSuchTool, was $typed")
        assertEquals(listOf("weather"), typed.availableTools)
    }

    @Test
    fun `given a tool executor that throws when run then ToolError carries a typed ToolExecution`() = runTest {
        // GIVEN — the tool exists, but its executor blows up.
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(toolName = "weather", toolInput = validInput, finalText = "done"),
            instructions = "",
            tools = ToolSet(weatherTool { error("db down") }),
        )

        // WHEN
        val toolError = firstToolError(agent)

        // THEN
        val typed = toolError.error
        assertTrue(typed is AgentError.ToolExecution, "expected ToolExecution, was $typed")
        assertEquals("weather", typed.toolName)
        assertTrue(typed.executorError.message?.contains("db down") == true, "executor cause preserved")
    }

    @Test
    fun `given a tool executor cancellation when run then cancellation propagates instead of ToolError`() = runTest {
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(toolName = "weather", toolInput = validInput, finalText = "done"),
            instructions = "",
            tools = ToolSet(weatherTool { throw CancellationException("tool cancelled") }),
        )

        assertFailsWith<CancellationException> {
            agent.stream(prompt = "?").collect {}
        }
    }

    @Test
    fun `given malformed args and no repair when run then ToolError carries a typed InvalidToolInput`() = runTest {
        // GIVEN — model emits {location} for a tool wanting {city}; no repair fn.
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(toolName = "weather", toolInput = malformedInput, finalText = "done"),
            instructions = "",
            tools = ToolSet(weatherTool { "sunny in ${it.city}" }),
        )

        // WHEN
        val toolError = firstToolError(agent)

        // THEN
        assertTrue(
            toolError.error is AgentError.InvalidToolInput,
            "expected InvalidToolInput, was ${toolError.error}",
        )
    }

    @Test
    fun `given malformed args and a repair that gives up when run then ToolError carries a typed ToolCallRepairFailed`() =
        runTest {
            // GIVEN — decode fails, repair runs but returns null.
            val agent = TestToolLoopAgent<Unit, String>(
                model = MockLanguageModelToolThenText(
                    toolName = "weather",
                    toolInput = malformedInput,
                    finalText = "done",
                ),
                instructions = "",
                tools = ToolSet(weatherTool { "sunny in ${it.city}" }),
                experimental_repairToolCall = { _, _, _, _ -> null },
            )

            // WHEN
            val toolError = firstToolError(agent)

            // THEN
            assertTrue(
                toolError.error is AgentError.ToolCallRepairFailed,
                "expected ToolCallRepairFailed, was ${toolError.error}",
            )
        }

    @Test
    fun `given a repair fn that throws when run then ToolError carries ToolCallRepairFailed with repairError set`() =
        runTest {
            // GIVEN — decode fails, repair runs but the repair fn ITSELF throws
            // (e.g. the model re-prompt failed). The stock modelRepromptRepair
            // calls model.generate(), which can throw on a constrained device.
            val agent = TestToolLoopAgent<Unit, String>(
                model = MockLanguageModelToolThenText(
                    toolName = "weather",
                    toolInput = malformedInput,
                    finalText = "done",
                ),
                instructions = "",
                tools = ToolSet(weatherTool { "sunny in ${it.city}" }),
                experimental_repairToolCall = { _, _, _, _ -> error("repair model unreachable") },
            )

            // WHEN
            val toolError = firstToolError(agent)

            // THEN — routed to ToolCallRepairFailed (NOT the generic
            // ToolExecution), with the repair throwable captured in repairError.
            val typed = toolError.error
            assertTrue(typed is AgentError.ToolCallRepairFailed, "expected ToolCallRepairFailed, was $typed")
            assertTrue(
                typed.repairError?.message?.contains("repair model unreachable") == true,
                "repairError should carry the repair throwable, was ${typed.repairError}",
            )
        }
}
