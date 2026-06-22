package ai.torad.aisdk

import ai.torad.aisdk.providers.GoogleInteractions
import ai.torad.aisdk.providers.GoogleInteractionsModelInput
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleInteractionsToolChoiceTest {
    private val googleSearchTool = LanguageModelTool(
        name = "google_search",
        description = "",
        parametersSchemaJson = "{}",
        providerExecuted = true,
        metadata = mapOf("providerToolId" to JsonPrimitive("google.google_search")),
    )

    private val functionTool = LanguageModelTool(
        name = "lookup",
        description = "Lookup city details.",
        parametersSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}}}""",
    )

    /**
     * Regression: with only provider-executed tools present, the Interactions API rejects
     * tool_choice. The old gate keyed off the assembled tools array (which still contains the
     * provider-executed entries), so tool_choice leaked into the body. It must be omitted.
     */
    @Test
    fun `omits tool_choice when only provider-executed tools are present`() {
        val prepared = GoogleInteractions.googleInteractionsRequestBody(
            input = GoogleInteractionsModelInput.Model("gemini-2.0"),
            params = LanguageModelCallParams(
                messages = listOf(UserMessage("Hello")),
                tools = listOf(googleSearchTool),
                toolChoice = ToolChoice.Required,
            ),
            stream = false,
        )

        val generationConfig = prepared.body["generation_config"]?.jsonObject
        assertNull(generationConfig?.get("tool_choice"), "tool_choice must be omitted for provider-executed-only tools")
    }

    /**
     * Guard the positive path: a real (non-provider-executed) function tool still emits tool_choice.
     */
    @Test
    fun `emits tool_choice when a real function tool is present`() {
        val prepared = GoogleInteractions.googleInteractionsRequestBody(
            input = GoogleInteractionsModelInput.Model("gemini-2.0"),
            params = LanguageModelCallParams(
                messages = listOf(UserMessage("Hello")),
                tools = listOf(googleSearchTool, functionTool),
                toolChoice = ToolChoice.Required,
            ),
            stream = false,
        )

        val toolChoice = prepared.body["generation_config"]?.jsonObject?.get("tool_choice")
        assertTrue(toolChoice != null, "tool_choice must be present when a function tool is requested")
        assertEquals(JsonPrimitive("any"), toolChoice, "ToolChoice.Required maps to \"any\"")
    }
}
