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
import kotlin.test.assertTrue

class ExtractReasoningTest {
    @Test
    fun `stream keeps split reasoning tags buffered`() = runTest {
        val ctx = MiddlewareCallContext(
            params = LanguageModelCallParams {
                messages(listOf(UserMessage("x")))
            },
            model = MockLanguageModelTextOnly("x"),
            doGenerate = { LanguageModelResult("x", emptyList(), FinishReason.Stop, Usage(1, 1)) },
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

    @Test
    fun `stream closes an unterminated reasoning section at stream end`() = runTest {
        val ctx = streamContext(
            StreamEvent.TextStart("t1"),
            StreamEvent.TextDelta("t1", "hello <reasoning>thinking hard"),
            StreamEvent.TextEnd("t1"),
            StreamEvent.Finish(1, FinishReason.Length, Usage(1, 1)),
        )

        val events = ExtractReasoningMiddleware(separator = "").wrapStream(ctx).toList()

        assertEquals(1, events.filterIsInstance<StreamEvent.ReasoningStart>().size)
        assertEquals(1, events.filterIsInstance<StreamEvent.ReasoningEnd>().size)
        assertEquals(
            "thinking hard",
            events.filterIsInstance<StreamEvent.ReasoningDelta>().joinToString("") { it.text },
        )
        // The section must close before the text block it lived in is terminated.
        assertTrue(
            events.indexOfFirst { it is StreamEvent.ReasoningEnd } <
                events.indexOfFirst { it is StreamEvent.TextEnd }
        )
    }

    @Test
    fun `separator is not emitted before the first visible text`() = runTest {
        val ctx = streamContext(
            StreamEvent.TextStart("t1"),
            StreamEvent.TextDelta("t1", "<think>"),
            StreamEvent.TextDelta("t1", "ana"),
            StreamEvent.TextDelta("t1", "lyzing the request"),
            StreamEvent.TextDelta("t1", "</think>"),
            StreamEvent.TextDelta("t1", "Here"),
            StreamEvent.TextDelta("t1", " is the response"),
            StreamEvent.TextEnd("t1"),
        )

        val events = ExtractReasoningMiddleware(tagName = "think").wrapStream(ctx).toList()

        assertEquals(
            "Here is the response",
            events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text },
        )
    }

    @Test
    fun `separator is not emitted when no visible text follows the reasoning`() = runTest {
        val ctx = streamContext(
            StreamEvent.TextStart("t1"),
            StreamEvent.TextDelta("t1", "<think>"),
            StreamEvent.TextDelta("t1", "ana"),
            StreamEvent.TextDelta("t1", "lyzing the request\n"),
            StreamEvent.TextDelta("t1", "</think>"),
            StreamEvent.TextEnd("t1"),
        )

        val events = ExtractReasoningMiddleware(tagName = "think").wrapStream(ctx).toList()

        assertEquals(emptyList(), events.filterIsInstance<StreamEvent.TextDelta>())
    }

    @Test
    fun `separator prefixes text and reasoning that resume after a switch`() = runTest {
        val ctx = streamContext(
            StreamEvent.TextStart("t1"),
            StreamEvent.TextDelta("t1", "before<think>one</think>middle<think>two</think>after"),
            StreamEvent.TextEnd("t1"),
        )

        val events = ExtractReasoningMiddleware(tagName = "think").wrapStream(ctx).toList()

        assertEquals(
            "before\nmiddle\nafter",
            events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text },
        )
        assertEquals(
            "one\ntwo",
            events.filterIsInstance<StreamEvent.ReasoningDelta>().joinToString("") { it.text },
        )
    }

    private fun streamContext(vararg events: StreamEvent): MiddlewareCallContext =
        MiddlewareCallContext(
            params = LanguageModelCallParams {
                messages(listOf(UserMessage("x")))
            },
            model = MockLanguageModelTextOnly("x"),
            doGenerate = { LanguageModelResult("x", emptyList(), FinishReason.Stop, Usage(1, 1)) },
            doStream = { flowOf(*events) },
        )
}
