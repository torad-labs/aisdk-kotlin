package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import ai.torad.aisdk.ui.ModelMessageConversion.convertToModelMessages
import ai.torad.aisdk.ui.StreamToUiMessages
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolOccurrenceContractTest {

    @Test
    fun `duplicate provider tool ids survive stream UI model and prompt round trip`() = runTest {
        val firstInput = JsonObject(mapOf("message" to JsonPrimitive("first")))
        val secondInput = JsonObject(mapOf("message" to JsonPrimitive("second")))
        val firstOutput = JsonObject(mapOf("sent" to JsonPrimitive("first")))
        val secondOutput = JsonObject(mapOf("sent" to JsonPrimitive("second")))
        val events = flow {
            emit(StreamEvent.ToolCall("dup", "send", firstInput))
            emit(StreamEvent.ToolCall("dup", "send", secondInput))
            emit(StreamEvent.ToolResult("dup", "send", firstOutput))
            emit(StreamEvent.ToolResult("dup", "send", secondOutput))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }

        val uiMessage = drainAllItems(StreamToUiMessages(events, "msg_occurrence_contract")).last()
        val replayMessages = convertToModelMessages(listOf(uiMessage))
        val promptMessages = PromptConversion.convertToLanguageModelPrompt(replayMessages)

        val toolCalls = promptMessages.first().content.filterIsInstance<ContentPart.ToolCall>()
        val toolResults = promptMessages.last().content.filterIsInstance<ContentPart.ToolResult>()
        assertEquals(listOf(firstInput, secondInput), toolCalls.map { it.input })
        assertEquals(listOf(firstOutput, secondOutput), toolResults.map { it.output })
    }

    @Test
    fun `pruneMessages keeps only the matching duplicate-id tool occurrence from the retained window`() {
        val oldInput = JsonObject(mapOf("message" to JsonPrimitive("old")))
        val newInput = JsonObject(mapOf("message" to JsonPrimitive("new")))
        val messages = listOf(
            ModelMessage(MessageRole.Assistant, listOf(ContentPart.ToolCall("dup", "send", oldInput))),
            ModelMessage(MessageRole.Tool, listOf(ContentPart.ToolResult("dup", "send", JsonPrimitive("old")))),
            ModelMessage(MessageRole.Assistant, listOf(ContentPart.ToolCall("dup", "send", newInput))),
            ModelMessage(MessageRole.Tool, listOf(ContentPart.ToolResult("dup", "send", JsonPrimitive("new")))),
        )

        val pruned = MessagePruning.pruneMessages(messages, toolCalls = PruneToolCalls.BeforeLastMessage)

        assertEquals(2, pruned.size)
        val retainedCall = pruned.first().content.single() as ContentPart.ToolCall
        val retainedResult = pruned.last().content.single() as ContentPart.ToolResult
        assertEquals(newInput, retainedCall.input)
        assertEquals(JsonPrimitive("new"), retainedResult.output)
    }

    @Test
    fun `pruneMessages keeps only the matching fallback approval occurrence from the retained window`() {
        val oldInput = JsonObject(mapOf("message" to JsonPrimitive("old")))
        val newInput = JsonObject(mapOf("message" to JsonPrimitive("new")))
        val messages = listOf(
            ModelMessage(MessageRole.Assistant, listOf(ContentPart.ToolApprovalRequest("dup", "send", oldInput))),
            ModelMessage(MessageRole.Tool, listOf(ContentPart.ToolApprovalResponse("dup", approved = true))),
            ModelMessage(MessageRole.Assistant, listOf(ContentPart.ToolApprovalRequest("dup", "send", newInput))),
            ModelMessage(MessageRole.Tool, listOf(ContentPart.ToolApprovalResponse("dup", approved = false))),
        )

        val pruned = MessagePruning.pruneMessages(messages, toolCalls = PruneToolCalls.BeforeLastMessage)

        assertEquals(2, pruned.size)
        val retainedRequest = pruned.first().content.single() as ContentPart.ToolApprovalRequest
        val retainedResponse = pruned.last().content.single() as ContentPart.ToolApprovalResponse
        assertEquals(newInput, retainedRequest.input)
        assertEquals(false, retainedResponse.approved)
    }
}
