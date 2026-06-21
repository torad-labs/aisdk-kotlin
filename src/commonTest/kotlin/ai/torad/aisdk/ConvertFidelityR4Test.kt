package ai.torad.aisdk

import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.ModelMessageConversion.convertToModelMessages
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ConvertFidelityR4Test {
    @Test
    fun `multi-step assistant turn splits into separate messages at step boundaries`() {
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(
                    UIMessagePart.Text("step one"),
                    UIMessagePart.StepStart(stepNumber = 2),
                    UIMessagePart.Text("step two"),
                ),
            ),
        )
        val result = convertToModelMessages(ui)
        // Two assistant messages, not one merged.
        assertEquals(2, result.size)
        assertEquals("step one", (result[0].content.single() as ContentPart.Text).text)
        assertEquals("step two", (result[1].content.single() as ContentPart.Text).text)
    }

    @Test
    fun `Text and Reasoning carry providerMetadata to the model`() {
        val sig: kotlinx.serialization.json.JsonElement = buildJsonObject { put("signature", JsonPrimitive("sig")) }
        val meta = mapOf("anthropic" to sig)
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(UIMessagePart.Text("hi", providerMetadata = ProviderMetadata.Raw(JsonObject(meta)))),
            ),
        )
        val text = convertToModelMessages(ui).single().content.single() as ContentPart.Text
        assertEquals(ProviderMetadata.Raw(JsonObject(meta)), text.providerMetadata, "providerMetadata preserved on round-trip")
    }

    @Test
    fun `a user File part carries its filename`() {
        val ui = listOf(
            UIMessage(
                id = "u1",
                role = UIMessageRole.User,
                parts = listOf(
                    UIMessagePart.File(mediaType = "application/pdf", base64 = "AA==", filename = "report.pdf"),
                ),
            ),
        )
        val file = convertToModelMessages(ui).single().content.single() as ContentPart.File
        assertEquals("report.pdf", file.filename)
    }

    @Test
    fun `a SourceDocument carries its mediaType and filename to the model`() {
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(
                    UIMessagePart.SourceDocument(
                        sourceId = "s1",
                        mediaType = "application/pdf",
                        title = "Spec",
                        filename = "spec.pdf",
                    ),
                ),
            ),
        )
        val source = convertToModelMessages(ui).single().content.single() as ContentPart.Source
        assertEquals("application/pdf", source.mediaType)
        assertEquals("spec.pdf", source.filename)
    }
}
