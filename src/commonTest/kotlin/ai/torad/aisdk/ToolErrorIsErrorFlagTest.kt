package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolErrorIsErrorFlagTest {
    @Serializable
    private data class Empty(val unused: String = "")

    // Step 1 calls the (registered, decodable) tool "boom"; once the tool fails at execution, step 2
    // records the messages the provider receives so we can assert the tool-error result is flagged.
    private class ToolThenCapture(
        private val secondRequest: CompletableDeferred<List<ModelMessage>>,
    ) : LanguageModel {
        override val modelId = "m"
        private var calls = 0
        override suspend fun generate(params: LanguageModelCallParams) =
            LanguageModelResult(text = "", finishReason = FinishReason.Stop, usage = Usage())
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            if (calls++ == 0) {
                emit(StreamEvent.ToolCall("c1", "boom", JsonObject(emptyMap())))
                emit(StreamEvent.StepFinish(1, FinishReason.ToolCalls, Usage()))
                emit(StreamEvent.Finish(1, FinishReason.ToolCalls, Usage()))
            } else {
                secondRequest.complete(params.messages)
                emit(StreamEvent.TextStart("t"))
                emit(StreamEvent.TextDelta("t", "ok"))
                emit(StreamEvent.TextEnd("t"))
                emit(StreamEvent.Finish(2, FinishReason.Stop, Usage()))
            }
        }
    }

    @Test
    fun `a failing tool is recorded and re-sent to the provider with isError true`() = runTest {
        val secondRequest = CompletableDeferred<List<ModelMessage>>()
        val boom = Tool<Empty, String, Unit>(
            name = "boom",
            description = "always fails",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ -> throw UnsupportedFunctionalityError("boom") }

        val agent = TestToolLoopAgent<Unit, String>(
            model = ToolThenCapture(secondRequest),
            instructions = "x",
            tools = ToolSet(boom),
        )
        val result = agent.generate(prompt = "go").first()

        // Persisted: the tool-error result in the final log must be flagged as an error, not a
        // success whose body merely contains the error text.
        val persisted = result.messages
            .flatMap { it.content }
            .filterIsInstance<ContentPart.ToolResult>()
            .single { it.toolCallId == "c1" }
        assertTrue(persisted.isError, "persisted tool-error result must carry isError=true")

        // Re-sent: the SAME error flag rides the next-step provider request — providers map
        // isError -> is_error on the wire (AnthropicProvider.kt:806 / GoogleInteractionsModel.kt).
        val inRequest = secondRequest.await()
            .flatMap { it.content }
            .filterIsInstance<ContentPart.ToolResult>()
            .single { it.toolCallId == "c1" }
        assertTrue(inRequest.isError, "next provider request must carry the tool error as isError=true")
    }
}
