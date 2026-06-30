@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamingRetryTest {
    @Serializable
    private data class Answer(val value: String)

    private class FlakyStreamModel(
        private val openFailures: MutableList<Throwable> = mutableListOf(),
        private val events: List<StreamEvent> = successfulTextEvents(),
        private val failAfterEvents: Throwable? = null,
    ) : LanguageModel {
        override val modelId: String = "stream-retry-test"
        private var collectionCount: Int = 0
        val streamCollections: Int get() = collectionCount

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
            LanguageModelResult(
                text = "ok",
                finishReason = FinishReason.Stop,
                usage = Usage(),
            )

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            collectionCount += 1
            if (openFailures.isNotEmpty()) throw openFailures.removeAt(0)
            for (event in events) emit(event)
            failAfterEvents?.let { throw it }
        }
    }

    @Test
    fun `streamText retries retryable open errors before first event`() = runTest {
        val model = FlakyStreamModel(
            openFailures = mutableListOf(ApiError(429), ApiError(429)),
        )

        val events = TextGenerator(model).streamResult(GenerationInput.Prompt("hi")).fullStream.toList()

        assertEquals(successfulTextEvents(), events)
        assertEquals(3, model.streamCollections)
    }

    @Test
    fun `streamText does not retry retryable errors after first event`() = runTest {
        val firstEvent = StreamEvent.TextDelta("t1", "partial")
        val model = FlakyStreamModel(
            events = listOf(firstEvent),
            failAfterEvents = ApiError(429),
        )
        val seen = mutableListOf<StreamEvent>()

        val error = assertFailsWith<APICallError> {
            TextGenerator(model).streamResult(GenerationInput.Prompt("hi")).fullStream.collect { seen += it }
        }

        assertEquals(429, error.statusCode)
        assertEquals(listOf<StreamEvent>(firstEvent), seen)
        assertEquals(1, model.streamCollections)
    }

    @Test
    fun `maxRetries zero disables streaming open retry`() = runTest {
        val model = FlakyStreamModel(
            openFailures = mutableListOf(ApiError(429)),
        )

        val error = assertFailsWith<APICallError> {
            TextGenerator(model, CallConfig(maxRetries = 0))
                .streamResult(GenerationInput.Prompt("hi"))
                .fullStream
                .toList()
        }

        assertEquals(429, error.statusCode)
        assertEquals(1, model.streamCollections)
    }

    @Test
    fun `streamObject retries retryable open errors before first event`() = runTest {
        val model = FlakyStreamModel(
            openFailures = mutableListOf(ApiError(503), ApiError(503)),
            events = successfulObjectEvents(),
        )

        val result = StreamObjectResult(
            model = model,
            output = OutputObj<Answer>(serializer()),
            prompt = "json",
        ).finish()

        assertEquals(Answer("ok"), result.value)
        assertEquals(3, model.streamCollections)
    }

    @Test
    fun `agent stream retries retryable open errors before first event`() = runTest {
        val model = FlakyStreamModel(
            openFailures = mutableListOf(ApiError(429), ApiError(429)),
        )
        val agent = TestToolLoopAgent<Unit, String>(
            model = model,
            instructions = "x",
            tools = ToolSet(),
        )

        val text = agent.stream(prompt = "hi")
            .filterIsInstance<StreamEvent.TextDelta>()
            .toList()
            .joinToString("") { it.text }

        assertEquals("ok", text)
        assertEquals(3, model.streamCollections)
    }

    private companion object {
        fun successfulTextEvents(): List<StreamEvent> = listOf(
            StreamEvent.TextStart("t1"),
            StreamEvent.TextDelta("t1", "ok"),
            StreamEvent.TextEnd("t1"),
            StreamEvent.Finish(1, FinishReason.Stop, Usage()),
        )

        fun successfulObjectEvents(): List<StreamEvent> = listOf(
            StreamEvent.TextStart("t1"),
            StreamEvent.TextDelta("t1", """{"value":"ok"}"""),
            StreamEvent.TextEnd("t1"),
            StreamEvent.Finish(1, FinishReason.Stop, Usage()),
        )

        fun ApiError(statusCode: Int): APICallError =
            APICallError(
                message = "HTTP $statusCode",
                url = "https://api.test",
                statusCode = statusCode,
            )
    }
}
