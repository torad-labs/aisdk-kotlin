package ai.torad.aisdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ModelMessagePokoTest {
    @Test
    fun `D3 ContentPart Poko leaves round trip polymorphically with stable discriminators`() {
        val parts: List<Pair<ContentPart, String>> = listOf(
            ContentPart.Text("hello") to "text",
            ContentPart.ToolCall(
                toolCallId = "call_1",
                toolName = "lookup",
                input = JsonPrimitive("Paris"),
                providerExecuted = true,
                dynamic = true,
            ) to "tool-call",
            ContentPart.ToolResult(
                toolCallId = "call_1",
                toolName = "lookup",
                output = JsonPrimitive("full answer"),
                isError = true,
                modelVisible = JsonPrimitive("summary"),
                dynamic = true,
                providerExecuted = true,
            ) to "tool-result",
        )

        for ((part, discriminator) in parts) {
            val encoded = aiSdkOutputJson.encodeToString(ContentPart.serializer(), part)
            assertEquals(
                discriminator,
                aiSdkJson.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content
            )
            assertEquals(part, aiSdkJson.decodeFromString(ContentPart.serializer(), encoded))
        }
    }

    @Test
    fun `D3 ModelMessage and Usage Poko types round trip through aiSdkJson`() {
        val message = ModelMessage(
            role = MessageRole.Assistant,
            content = listOf(
                ContentPart.Text("answer"),
                ContentPart.ToolCall(
                    toolCallId = "call_1",
                    toolName = "lookup",
                    input = JsonPrimitive("Paris"),
                ),
            ),
        )
        assertRoundTrip(ModelMessage.serializer(), message)

        val usage = Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = 10,
                noCache = 3,
                cacheRead = 5,
                cacheWrite = 2,
            ),
            outputTokens = Usage.OutputTokenBreakdown(
                total = 7,
                text = 4,
                reasoning = 3,
            ),
            raw = JsonPrimitive("provider-usage"),
        )
        assertRoundTrip(Usage.serializer(), usage)
    }

    @Test
    fun `D3 ModelMessage ContentPart and Usage Poko types keep value semantics`() {
        val firstMessage = ModelMessage(MessageRole.User, listOf(ContentPart.Text("hello")))
        val equalMessage = ModelMessage(MessageRole.User, listOf(ContentPart.Text("hello")))
        val differentMessage = ModelMessage(MessageRole.Assistant, listOf(ContentPart.Text("hello")))
        assertValueSemantics(firstMessage, equalMessage, differentMessage)

        val firstPart: ContentPart = ContentPart.Text("hello")
        val equalPart: ContentPart = ContentPart.Text("hello")
        val differentPart: ContentPart = ContentPart.Text("goodbye")
        assertValueSemantics(firstPart, equalPart, differentPart)

        val firstUsage = Usage.of(promptTokens = 1, completionTokens = 2)
        val equalUsage = Usage.of(promptTokens = 1, completionTokens = 2)
        val differentUsage = Usage.of(promptTokens = 2, completionTokens = 1)
        assertValueSemantics(firstUsage, equalUsage, differentUsage)
    }

    private fun <T> assertRoundTrip(serializer: KSerializer<T>, value: T) {
        val encoded = aiSdkOutputJson.encodeToString(serializer, value)
        val decoded = aiSdkJson.decodeFromString(serializer, encoded)
        assertEquals(value, decoded)
    }

    private fun <T> assertValueSemantics(value: T, equal: T, different: T) {
        assertEquals(value, equal)
        assertEquals(value.hashCode(), equal.hashCode())
        assertNotEquals(value, different)
    }
}
