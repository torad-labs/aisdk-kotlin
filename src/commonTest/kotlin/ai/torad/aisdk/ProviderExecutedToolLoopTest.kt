package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

class ProviderExecutedToolLoopTest {

    @Test
    fun `provider-executed tool visibility calls are not executed locally`() = runTest {
        val model = CountingStreamModel(
            responses = listOf(
                TestStreamResponse(
                    events = listOf(
                        StreamEvent.ToolCall(
                            toolCallId = "call_provider",
                            toolName = "web_search",
                            inputJson = JsonPrimitive("query"),
                            providerMetadata = mapOf("test" to JsonPrimitive("provider-executed")),
                        ),
                    ),
                    finishReason = FinishReason.Stop,
                ),
            ),
        )
        val hostedTool = providerExecutedTool<JsonElement, JsonElement, Unit>(
            name = "web_search",
            description = "Provider-hosted web search.",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        )
        val agent = ToolLoopAgent<Unit, String>(
            model = model,
            instructions = "use hosted tools",
            tools = toolSetOf(hostedTool),
        )

        val events = drainAllItems(agent.stream(prompt = "search", options = Unit))

        assertEquals(1, model.callCount)
        assertTrue(events.any { it is StreamEvent.ToolCall && it.toolName == "web_search" })
        assertFalse(events.any { it is StreamEvent.ToolError }, "hosted provider tools must not run local executor")
        assertTrue(events.any { it is StreamEvent.Finish && it.finishReason == FinishReason.Stop })
    }
}
