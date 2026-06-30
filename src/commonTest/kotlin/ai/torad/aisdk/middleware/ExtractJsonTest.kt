package ai.torad.aisdk.middleware

import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.Usage
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.UserMessage
import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Behavior tests for [extractJsonMiddleware]. The load-bearing case is
 * truncation: an on-device generation cut off by context exhaustion or
 * max tokens leaves the JSON open (`{"a":1`), and the middleware must
 * repair it to a parseable object rather than leaking prose or emitting
 * a fragment that fails downstream `Output.decode`.
 */
class ExtractJsonTest {

    private fun genContext(rawText: String) = MiddlewareCallContext(
        params = LanguageModelCallParams {
    messages(listOf(UserMessage("x")))
},
        model = MockLanguageModelTextOnly("x"),
        doGenerate = { LanguageModelResult(rawText, emptyList(), FinishReason.Stop, Usage.of(1, 1)) },
        doStream = { flowOf() },
    )

    @Test
    fun `given fenced complete json when generate-wrapped then it passes through unchanged`() = runTest {
        // GIVEN a model that fenced its complete object.
        val raw = "```json\n{\"a\":1}\n```"

        // WHEN
        val result = ExtractJsonMiddleware().wrapGenerate(genContext(raw))

        // THEN the fence is stripped; a clean parse is returned verbatim.
        assertEquals("{\"a\":1}", result.text)
    }

    @Test
    fun `given prose around a complete object when generate-wrapped then only the object remains`() = runTest {
        // WHEN
        val result = ExtractJsonMiddleware().wrapGenerate(genContext("Here you go: {\"a\":1} thanks"))

        // THEN both the leading and trailing prose are dropped.
        assertEquals("{\"a\":1}", result.text)
    }

    @Test
    fun `given prose around json when generate-wrapped then content text is rebuilt`() = runTest {
        val result = ExtractJsonMiddleware().wrapGenerate(genContext("Here you go: {\"a\":1} thanks"))

        assertEquals("{\"a\":1}", result.text)
        assertEquals(listOf(ContentPart.Text("{\"a\":1}")), result.content)
    }

    @Test
    fun `given a truncated object when generate-wrapped then the json is repaired and closed`() = runTest {
        // GIVEN a generation cut off before the closing brace.
        // WHEN
        val result = ExtractJsonMiddleware().wrapGenerate(genContext("prefix {\"a\":1"))

        // THEN the repair layer closes it.
        assertEquals("{\"a\":1}", result.text)
    }

    @Test
    fun `given a truncated object with a prose prefix when generate-wrapped then the prefix is stripped`() = runTest {
        // Regression: the old no-close path returned the whole text
        // including "Sure! ". The region scan must drop the prefix.
        // WHEN
        val result = ExtractJsonMiddleware().wrapGenerate(genContext("Sure! {\"name\":\"a\",\"v\":1"))

        // THEN
        assertEquals("{\"name\":\"a\",\"v\":1}", result.text)
    }

    @Test
    fun `given a closing brace inside a string value when generate-wrapped then trailing fields survive`() = runTest {
        // Regression: a string-blind scanner closes the object at the `}`
        // inside "}" and silently drops "b". scanBalanced must track string
        // state (mirrors fixJson) so the real closing brace is found.
        // WHEN
        val result = ExtractJsonMiddleware().wrapGenerate(genContext("here: {\"a\":\"}\",\"b\":2} done"))

        // THEN the whole object — including the trailing "b" — is preserved.
        assertEquals("{\"a\":\"}\",\"b\":2}", result.text)
    }

    @Test
    fun `given a closing bracket inside a string value when generate-wrapped then the array is not truncated`() = runTest {
        // WHEN a `]` lives inside a string element of an array.
        val result = ExtractJsonMiddleware().wrapGenerate(genContext("[\"a]b\",2]"))

        // THEN the array closes at the real bracket, not the in-string one.
        assertEquals("[\"a]b\",2]", result.text)
    }

    @Test
    fun `given a truncated object streamed when stream-wrapped then a repaired json delta is emitted`() = runTest {
        // GIVEN a text block whose content is an open object.
        val ctx = MiddlewareCallContext(
            params = LanguageModelCallParams {
    messages(listOf(UserMessage("x")))
},
            model = MockLanguageModelTextOnly("x"),
            doGenerate = { LanguageModelResult("x", emptyList(), FinishReason.Stop, Usage.of(1, 1)) },
            doStream = {
                flowOf(
                    StreamEvent.TextStart("t1"),
                    StreamEvent.TextDelta("t1", "result: {\"a\":1"),
                    StreamEvent.TextEnd("t1"),
                    StreamEvent.StepFinish(0, FinishReason.Stop, Usage.of(1, 1)),
                )
            },
        )

        // WHEN / THEN — at TextEnd the buffered text is repaired and
        // emitted as one delta, then the TextEnd passes through.
        ExtractJsonMiddleware().wrapStream(ctx).test {
            assertIs<StreamEvent.TextStart>(awaitItem())
            val delta = awaitItem()
            assertIs<StreamEvent.TextDelta>(delta)
            assertEquals("{\"a\":1}", delta.text)
            assertIs<StreamEvent.TextEnd>(awaitItem())
            assertIs<StreamEvent.StepFinish>(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `given fenced json streamed in split chunks when stream-wrapped then fences are stripped`() = runTest {
        val ctx = MiddlewareCallContext(
            params = LanguageModelCallParams {
    messages(listOf(UserMessage("x")))
},
            model = MockLanguageModelTextOnly("x"),
            doGenerate = { LanguageModelResult("x", emptyList(), FinishReason.Stop, Usage.of(1, 1)) },
            doStream = {
                flowOf(
                    StreamEvent.TextStart("t1"),
                    StreamEvent.TextDelta("t1", "`"),
                    StreamEvent.TextDelta("t1", "``"),
                    StreamEvent.TextDelta("t1", "json\n"),
                    StreamEvent.TextDelta("t1", "{\"value\":\"test\"}"),
                    StreamEvent.TextDelta("t1", "\n`"),
                    StreamEvent.TextDelta("t1", "``"),
                    StreamEvent.TextEnd("t1"),
                )
            },
        )

        val events = ExtractJsonMiddleware().wrapStream(ctx).toList()
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

        assertEquals("{\"value\":\"test\"}", text)
        assertTrue(events.first() is StreamEvent.TextStart)
        assertTrue(events.last() is StreamEvent.TextEnd)
    }

    @Test
    fun `given large fenced json when stream-wrapped then text streams before text end`() = runTest {
        val largeJson = """{"data":"${"x".repeat(100)}","nested":[0,1,2,3]}"""
        val ctx = MiddlewareCallContext(
            params = LanguageModelCallParams {
    messages(listOf(UserMessage("x")))
},
            model = MockLanguageModelTextOnly("x"),
            doGenerate = { LanguageModelResult("x", emptyList(), FinishReason.Stop, Usage.of(1, 1)) },
            doStream = {
                flowOf(
                    StreamEvent.TextStart("t1"),
                    StreamEvent.TextDelta("t1", "```json\n"),
                    StreamEvent.TextDelta("t1", largeJson),
                    StreamEvent.TextDelta("t1", "\n```"),
                    StreamEvent.TextEnd("t1"),
                )
            },
        )

        val events = ExtractJsonMiddleware().wrapStream(ctx).toList()
        val firstDeltaIndex = events.indexOfFirst { it is StreamEvent.TextDelta }
        val textEndIndex = events.indexOfFirst { it is StreamEvent.TextEnd }
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

        assertEquals(largeJson, text)
        assertTrue(firstDeltaIndex in 1 until textEndIndex, "large JSON should stream before TextEnd")
    }

    @Test
    fun `given custom transform when stream-wrapped then text is buffered and transformed at text end`() = runTest {
        val ctx = MiddlewareCallContext(
            params = LanguageModelCallParams {
    messages(listOf(UserMessage("x")))
},
            model = MockLanguageModelTextOnly("x"),
            doGenerate = { LanguageModelResult("x", emptyList(), FinishReason.Stop, Usage.of(1, 1)) },
            doStream = {
                flowOf(
                    StreamEvent.TextStart("t1"),
                    StreamEvent.TextDelta("t1", "PREFIX"),
                    StreamEvent.TextDelta("t1", "{\"value\":\"test\"}"),
                    StreamEvent.TextDelta("t1", "SUFFIX"),
                    StreamEvent.TextEnd("t1"),
                )
            },
        )

        val events = ExtractJsonMiddleware { it.removePrefix("PREFIX").removeSuffix("SUFFIX") }
            .wrapStream(ctx)
            .toList()

        assertEquals(
            "{\"value\":\"test\"}",
            events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text },
        )
    }
}
