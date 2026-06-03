package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * Unit tests for the stock [modelRepromptRepair] builder — the
 * host-specific implementation of `experimental_repairToolCall` that
 * re-prompts the model with the tool schema + the parse error and
 * parses the response back as corrected JSON.
 *
 * The full agent-loop wiring is tested in [ToolCallRepairTest]; this
 * file exercises the helper in isolation with a stubbed
 * [LanguageModel] so the assertions can target the repair logic itself
 * (schema lookup, code-fence stripping, null-on-garbage).
 */
class ModelRepromptRepairTest {

    @Serializable data class WeatherIn(val city: String)

    private val weatherTool = tool<WeatherIn, String, Unit>(
        name = "weather",
        description = "Get weather",
        inputSerializer = serializer(),
        outputSerializer = serializer(),
    ) { input -> "sunny in ${input.city}" }

    @Test
    fun `given the model returns valid JSON when repair runs then a corrected ToolCall is produced`() =
        runTest {
            // GIVEN: the mock model replies with the corrected args as
            // plain JSON. The repair builder feeds the failed call +
            // schema + error into the model and expects the response
            // text to be the new arguments.
            val mockModel = mockLanguageModelTextOnly(text = """{"city":"Paris"}""")
            val repair = modelRepromptRepair<Unit>(model = mockModel)
            val failedCall = ContentPart.ToolCall(
                toolCallId = "call_1",
                toolName = "weather",
                input = buildJsonObject { put("location", JsonPrimitive("Paris")) },
            )

            // WHEN
            val corrected = repair(
                failedCall,
                IllegalArgumentException("missing field 'city'"),
                listOf(userMessage("what's the weather in Paris?")),
                toolSetOf(weatherTool),
            )

            // THEN: a corrected call with `city` instead of `location`.
            assertTrue(corrected != null, "repair returned a non-null corrected call")
            assertEquals("weather", corrected.toolName)
            assertEquals("call_1", corrected.toolCallId)
            assertEquals(
                "Paris",
                corrected.input.jsonObject["city"]?.jsonPrimitive?.content,
                "repaired input contains the corrected `city` field",
            )
        }

    @Test
    fun `given the model wraps the JSON in code fences when repair runs then the fences are stripped`() =
        runTest {
            // GIVEN: many on-device models default to wrapping JSON in
            // ```json fences. The repair must strip them before parsing.
            val mockModel = mockLanguageModelTextOnly(
                text = "```json\n{\"city\":\"Berlin\"}\n```",
            )
            val repair = modelRepromptRepair<Unit>(model = mockModel)
            val failedCall = ContentPart.ToolCall(
                toolCallId = "call_2",
                toolName = "weather",
                input = buildJsonObject { put("oops", JsonPrimitive("Berlin")) },
            )

            // WHEN
            val corrected = repair(
                failedCall,
                IllegalArgumentException("bad JSON"),
                emptyList(),
                toolSetOf(weatherTool),
            )

            // THEN
            assertTrue(corrected != null, "fenced JSON parsed cleanly")
            assertEquals(
                "Berlin",
                corrected.input.jsonObject["city"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `given the model returns garbage when repair runs then null is returned`() =
        runTest {
            // GIVEN: the model emits prose, not JSON.
            val mockModel = mockLanguageModelTextOnly(
                text = "I'm not sure what you mean by that.",
            )
            val repair = modelRepromptRepair<Unit>(model = mockModel)
            val failedCall = ContentPart.ToolCall(
                toolCallId = "call_3",
                toolName = "weather",
                input = buildJsonObject { put("location", JsonPrimitive("Lima")) },
            )

            // WHEN
            val corrected = repair(
                failedCall,
                IllegalArgumentException("missing field"),
                emptyList(),
                toolSetOf(weatherTool),
            )

            // THEN: the loop should surface a ToolError.
            assertNull(corrected, "garbage response surfaces null")
        }

    @Test
    fun `given the tool is not in the toolset when repair runs then null is returned without calling the model`() =
        runTest {
            // GIVEN: a tool name that the toolset doesn't know. The
            // builder MUST short-circuit before touching the model so
            // a misrouted call doesn't consume an inference budget.
            val mockModel = mockLanguageModelTextOnly(text = """{"city":"Lima"}""")
            val repair = modelRepromptRepair<Unit>(model = mockModel)
            val failedCall = ContentPart.ToolCall(
                toolCallId = "call_4",
                toolName = "unknown_tool",
                input = buildJsonObject { put("x", JsonPrimitive("y")) },
            )

            // WHEN
            val corrected = repair(
                failedCall,
                IllegalArgumentException("schema mismatch"),
                emptyList(),
                toolSetOf(weatherTool),
            )

            // THEN
            assertNull(corrected, "unknown tool → null, no recovery possible")
        }
}
