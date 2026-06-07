package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConvertToLanguageModelPromptTest {
    private fun imageMessage(url: String, mediaType: String = "image/png") = ModelMessage(
        MessageRole.User,
        listOf(ContentPart.Image(mediaType = mediaType, url = url)),
    )

    @Test
    fun `a data URL image is decoded inline regardless of supportedUrls`() = runTest {
        val converted = convertToLanguageModelPrompt(
            listOf(imageMessage("data:image/png;base64,aW1n")),
        )
        val img = converted.single().content.single() as ContentPart.Image
        assertEquals("aW1n", img.base64)
        assertNull(img.url, "url cleared once inlined")
    }

    @Test
    fun `a supported remote URL passes through untouched`() = runTest {
        var downloaded = false
        val converted = convertToLanguageModelPrompt(
            listOf(imageMessage("https://cdn.test/a.png")),
            supportedUrls = mapOf("image/*" to listOf("^https://cdn\\.test/")),
            download = {
                downloaded = true
                DownloadedAsset("X", "image/png")
            },
        )
        val img = converted.single().content.single() as ContentPart.Image
        assertEquals("https://cdn.test/a.png", img.url, "supported URL not rewritten")
        assertEquals(false, downloaded, "supported URL not downloaded")
    }

    @Test
    fun `an unsupported remote URL is downloaded and inlined`() = runTest {
        val converted = convertToLanguageModelPrompt(
            listOf(imageMessage("https://other.test/a", mediaType = "")),
            supportedUrls = mapOf("image/*" to listOf("^https://cdn\\.test/")),
            download = { DownloadedAsset("ZGF0YQ==", "image/jpeg") },
        )
        val img = converted.single().content.single() as ContentPart.Image
        assertEquals("ZGF0YQ==", img.base64)
        assertEquals("image/jpeg", img.mediaType, "media type filled from the download")
        assertNull(img.url)
    }

    @Test
    fun `a dangling tool call before a new user turn throws MissingToolResultsError`() = runTest {
        val messages = listOf(
            ModelMessage(MessageRole.User, listOf(ContentPart.Text("go"))),
            ModelMessage(
                MessageRole.Assistant,
                listOf(ContentPart.ToolCall("call_1", "t", JsonObject(emptyMap()))),
            ),
            // no Tool result before the next user turn
            ModelMessage(MessageRole.User, listOf(ContentPart.Text("again"))),
        )
        val e = assertFailsWith<MissingToolResultsError> { convertToLanguageModelPrompt(messages) }
        assertEquals(listOf("call_1"), e.toolCallIds)
    }

    @Test
    fun `a tool call answered by a tool result is not dangling`() = runTest {
        val messages = listOf(
            ModelMessage(
                MessageRole.Assistant,
                listOf(ContentPart.ToolCall("call_1", "t", JsonObject(emptyMap()))),
            ),
            ModelMessage(
                MessageRole.Tool,
                listOf(ContentPart.ToolResult("call_1", "t", JsonPrimitive("ok"))),
            ),
        )
        // does not throw
        assertEquals(2, convertToLanguageModelPrompt(messages).size)
    }

    @Test
    fun `an approved tool call awaiting execution is not dangling`() = runTest {
        // The approval-resume path: a tool call, then a tool-approval-response (no
        // tool result yet), then the conversation continues — must NOT throw.
        val messages = listOf(
            ModelMessage(
                MessageRole.Assistant,
                listOf(ContentPart.ToolApprovalRequest("call_1", "t", JsonObject(emptyMap()), approvalId = "ap_1")),
            ),
            ModelMessage(
                MessageRole.Tool,
                listOf(ContentPart.ToolApprovalResponse("call_1", approved = true, approvalId = "ap_1")),
            ),
            ModelMessage(MessageRole.User, listOf(ContentPart.Text("ok go"))),
        )
        assertEquals(3, convertToLanguageModelPrompt(messages).size)
    }

    @Test
    fun `a non-base64 data URL is left untouched rather than crashing`() = runTest {
        val messages = listOf(imageMessage("data:text/plain,Hello", mediaType = "text/plain"))
        // splitDataUrl would throw on this; the resolver must leave it for the provider.
        val img = convertToLanguageModelPrompt(messages).single().content.single() as ContentPart.Image
        assertEquals("data:text/plain,Hello", img.url, "non-base64 data URL preserved, not crashed")
    }
}
