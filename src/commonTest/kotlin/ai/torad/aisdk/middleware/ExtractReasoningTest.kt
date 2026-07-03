package ai.torad.aisdk.middleware

import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.Usage
import ai.torad.aisdk.UserMessage
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtractReasoningTest {
    @Test
    fun `stream keeps split reasoning tags buffered`() = runTest {
        val ctx = MiddlewareCallContext(
            params = LanguageModelCallParams {
                messages(listOf(UserMessage("x")))
            },
            model = MockLanguageModelTextOnly("x"),
            doGenerate = { LanguageModelResult("x", emptyList(), FinishReason.Stop, Usage.of(1, 1)) },
            doStream = {
                flowOf(
                    StreamEvent.TextStart("t1"),
                    StreamEvent.TextDelta("t1", "visible <rea"),
                    StreamEvent.TextDelta("t1", "soning>secret</rea"),
                    StreamEvent.TextDelta("t1", "soning> done"),
                    StreamEvent.TextEnd("t1"),
                )
            },
        )

        val events = ExtractReasoningMiddleware(separator = "").wrapStream(ctx).toList()

        assertEquals("visible  done", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("secret", events.filterIsInstance<StreamEvent.ReasoningDelta>().joinToString("") { it.text })
        assertEquals(1, events.filterIsInstance<StreamEvent.ReasoningStart>().size)
        assertEquals(1, events.filterIsInstance<StreamEvent.ReasoningEnd>().size)
    }
}
