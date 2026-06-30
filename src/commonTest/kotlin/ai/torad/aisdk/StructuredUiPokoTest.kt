package ai.torad.aisdk

import ai.torad.aisdk.ui.SafeValidateUIMessagesResult
import ai.torad.aisdk.ui.TextStreamResponse
import ai.torad.aisdk.ui.UIToolInvocationMetadata
import ai.torad.aisdk.ui.UIToolInvocationPayload
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StructuredUiPokoTest {
    @Test
    fun `D12 structured object Poko result types keep value semantics`() {
        val finish = StreamObjectFinish(
            value = mapOf("city" to "Paris"),
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
            finishReason = FinishReason.Stop,
            warnings = listOf(CallWarning(type = "other", message = "heads up")),
            response = LanguageModelResponseMetadata(id = "resp_1", modelId = "model"),
        )
        val equalFinish = StreamObjectFinish(
            value = mapOf("city" to "Paris"),
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
            finishReason = FinishReason.Stop,
            warnings = listOf(CallWarning(type = "other", message = "heads up")),
            response = LanguageModelResponseMetadata(id = "resp_1", modelId = "model"),
        )
        val differentFinish = StreamObjectFinish(
            value = mapOf("city" to "Rome"),
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
            finishReason = FinishReason.Stop,
            warnings = listOf(CallWarning(type = "other", message = "heads up")),
            response = LanguageModelResponseMetadata(id = "resp_1", modelId = "model"),
        )
        assertEquals(finish, equalFinish)
        assertEquals(finish.hashCode(), equalFinish.hashCode())
        assertNotEquals(finish, differentFinish)

        val phase: StructuredObjectPhase<String> = StructuredObjectPhase.Done(
            value = "done",
            raw = JsonPrimitive("done"),
            error = null,
        )
        val equalPhase: StructuredObjectPhase<String> = StructuredObjectPhase.Done(
            value = "done",
            raw = JsonPrimitive("done"),
            error = null,
        )
        val differentPhase: StructuredObjectPhase<String> = StructuredObjectPhase.Streaming(
            partial = "done",
            raw = JsonPrimitive("done"),
            error = null,
        )
        assertEquals(phase, equalPhase)
        assertEquals(phase.hashCode(), equalPhase.hashCode())
        assertNotEquals(phase, differentPhase)
    }

    @Test
    fun `D12 UI stream Poko result types keep value semantics`() {
        val payload: UIToolInvocationPayload<String, String> = UIToolInvocationPayload(
            input = "query",
            output = "answer",
            error = null,
        )
        val equalPayload: UIToolInvocationPayload<String, String> = UIToolInvocationPayload(
            input = "query",
            output = "answer",
            error = null,
        )
        val differentPayload: UIToolInvocationPayload<String, String> = UIToolInvocationPayload(
            input = "query",
            output = null,
            error = "failed",
        )
        assertEquals(payload, equalPayload)
        assertEquals(payload.hashCode(), equalPayload.hashCode())
        assertNotEquals(payload, differentPayload)

        val metadata = UIToolInvocationMetadata(
            preliminary = true,
            approvalId = "approval_1",
            signature = "sig",
        )
        val equalMetadata = UIToolInvocationMetadata(
            preliminary = true,
            approvalId = "approval_1",
            signature = "sig",
        )
        val differentMetadata = UIToolInvocationMetadata(
            preliminary = false,
            approvalId = "approval_1",
            signature = "sig",
        )
        assertEquals(metadata, equalMetadata)
        assertEquals(metadata.hashCode(), equalMetadata.hashCode())
        assertNotEquals(metadata, differentMetadata)

        val textStream = flowOf("hi")
        val response = TextStreamResponse(textStream = textStream, status = 202)
        val equalResponse = TextStreamResponse(textStream = textStream, status = 202)
        val differentResponse = TextStreamResponse(textStream = textStream, status = 500)
        assertEquals(response, equalResponse)
        assertEquals(response.hashCode(), equalResponse.hashCode())
        assertNotEquals(response, differentResponse)

        val message = UIMessage(
            id = "m1",
            role = UIMessageRole.Assistant,
            parts = listOf(UIMessagePart.Text("hello")),
        )
        val result: SafeValidateUIMessagesResult = SafeValidateUIMessagesResult.Success(messages = listOf(message))
        val equalResult: SafeValidateUIMessagesResult = SafeValidateUIMessagesResult.Success(messages = listOf(message))
        val errorResult: SafeValidateUIMessagesResult = SafeValidateUIMessagesResult.Failure(
            error = IllegalArgumentException("bad"),
        )
        assertEquals(result, equalResult)
        assertEquals(result.hashCode(), equalResult.hashCode())
        assertNotEquals(result, errorResult)
    }
}
