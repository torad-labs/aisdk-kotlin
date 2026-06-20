package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * Validates `experimental_repairToolCall` (gap #4 from
 * historical parity work). The repair function runs when a tool
 * call's args fail to decode against the tool's input schema —
 * targets Gemma 4 E2B's ~5% rate of malformed-args calls.
 *
 * Three scenarios:
 *
 * 1. Malformed args + repair function rewrites them → tool executes
 *    with corrected input.
 * 2. Malformed args + NO repair function → `StreamEvent.ToolError`.
 * 3. Malformed args + repair returns null → `StreamEvent.ToolError`.
 */
class ToolCallRepairTest {

    @Serializable data class WeatherIn(val city: String)

    @Test
    fun `given malformed args and a repair function when invoked then the tool runs with the repaired input`() =
        runTest {
            // GIVEN: a tool requiring `{city: String}`; the model emits
            // `{location: String}` instead.
            val weatherTool = Tool<WeatherIn, String, Unit>(
                name = "weather",
                description = "Get weather",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
            ) { input -> "sunny in ${input.city}" }
            val malformed: JsonObject = buildJsonObject { put("location", JsonPrimitive("Paris")) }
            val agent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "weather",
                    toolInput = malformed,
                    finalText = "done",
                ),
                instructions = "",
                tools = toolSetOf(weatherTool),
                experimental_repairToolCall = { failedCall, _, _, _ ->
                    // Rewrite `location` → `city`.
                    val original = failedCall.input.jsonObject["location"]?.jsonPrimitive?.content ?: ""
                    failedCall.copy(input = buildJsonObject { put("city", JsonPrimitive(original)) })
                },
            )

            // WHEN
            val events = mutableListOf<StreamEvent>()
            agent.stream(prompt = "weather?").collect { events.add(it) }

            // THEN: a ToolResult emits, not a ToolError; the tool ran with the
            // repaired input ("Paris" survived the rename).
            val toolResults = events.filterIsInstance<StreamEvent.ToolResult>()
            val toolErrors = events.filterIsInstance<StreamEvent.ToolError>()
            assertEquals(1, toolResults.size, "repair fixed the args; the tool emitted exactly one ToolResult")
            assertEquals(0, toolErrors.size, "no ToolError when repair succeeds")
            assertTrue(
                toolResults.single().outputJson.toString().contains("Paris"),
                "repaired input flowed through: '${toolResults.single().outputJson}'",
            )
        }

    @Test
    fun `given malformed args and no repair function when invoked then the loop emits ToolError`() =
        runTest {
            // GIVEN
            val weatherTool = Tool<WeatherIn, String, Unit>(
                name = "weather",
                description = "Get weather",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
            ) { input -> "sunny in ${input.city}" }
            val malformed: JsonObject = buildJsonObject { put("location", JsonPrimitive("Paris")) }
            val agent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "weather",
                    toolInput = malformed,
                    finalText = "done",
                ),
                instructions = "",
                tools = toolSetOf(weatherTool),
                // experimental_repairToolCall = null (default)
            )

            // WHEN
            val events = mutableListOf<StreamEvent>()
            agent.stream(prompt = "weather?").collect { events.add(it) }

            // THEN
            val toolResults = events.filterIsInstance<StreamEvent.ToolResult>()
            val toolErrors = events.filterIsInstance<StreamEvent.ToolError>()
            assertEquals(0, toolResults.size, "no ToolResult when decode fails without repair")
            assertEquals(1, toolErrors.size, "exactly one ToolError when decode fails without repair")
        }

    @Test
    fun `given malformed args and a repair function returning null when invoked then the loop emits ToolError`() =
        runTest {
            // GIVEN
            val weatherTool = Tool<WeatherIn, String, Unit>(
                name = "weather",
                description = "Get weather",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
            ) { input -> "sunny in ${input.city}" }
            val malformed: JsonObject = buildJsonObject { put("location", JsonPrimitive("Paris")) }
            var repairInvoked = false
            val agent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "weather",
                    toolInput = malformed,
                    finalText = "done",
                ),
                instructions = "",
                tools = toolSetOf(weatherTool),
                experimental_repairToolCall = { _, _, _, _ ->
                    repairInvoked = true
                    null // give up
                },
            )

            // WHEN
            val events = mutableListOf<StreamEvent>()
            agent.stream(prompt = "weather?").collect { events.add(it) }

            // THEN
            assertTrue(repairInvoked, "repair function MUST have been invoked")
            assertEquals(
                1,
                events.filterIsInstance<StreamEvent.ToolError>().size,
                "exactly one ToolError when repair gives up",
            )
            assertEquals(
                0,
                events.filterIsInstance<StreamEvent.ToolResult>().size,
                "no ToolResult when repair gives up",
            )
        }
}
