package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.ui.SafeValidateUIMessagesResult
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UiMessageStreams
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Compiles and executes the error-handling wiki snippets (docs/wiki/error-handling.md). */
class ErrorHandlingDocSnippetTest {

    private class SnippetLogger {
        val warnings = mutableListOf<String>()
        fun warn(message: String) {
            warnings += message
        }
    }

    @Test
    fun `catch narrow errors snippet compiles and runs`() = runTest {
        val model = MockLanguageModelTextOnly("Welcome.")
        val prompt = "Say hi"
        val logger = SnippetLogger()
        var rendered: String? = null
        var retryQueued = false
        fun render(text: String) {
            rendered = text
        }
        fun queueRetry() {
            retryQueued = true
        }

        try {
            val result = TextGenerator(model).generate(GenerationInput.Prompt(prompt)).first()
            render(result.text)
        } catch (error: APICallError) {
            logger.warn("Provider call failed: ${error.statusCode} ${error.message}")
            if (error.isRetryable) queueRetry()
        } catch (error: AiSdkException) {
            logger.warn("SDK error: ${error.message}")
        }

        assertEquals("Welcome.", rendered)
        assertTrue(logger.warnings.isEmpty())
        assertTrue(!retryQueued)
    }

    @Test
    fun `retry policy snippet compiles and runs`() = runTest {
        val query = "greeting"
        val remoteIndex = object {
            suspend fun query(query: String, attempt: Int): String = "$query#$attempt"
        }

        val value = RetryPolicy { maxRetries(3) }.execute(
            shouldRetry = { error -> (error as? APICallError)?.isRetryable == true },
        ) { attempt ->
            remoteIndex.query(query, attempt)
        }

        assertTrue(value.startsWith("greeting#"))
    }

    @Test
    fun `streaming errors snippet compiles and runs`() = runTest {
        class SnippetAgent(model: LanguageModel) : ToolLoopAgent<Unit, String>(
            model = model,
            instructions = "Be brief.",
            tools = ToolSet(emptyMap()),
        )

        val agent = SnippetAgent(MockLanguageModelTextOnly("Streamed."))
        val prompt = "Say hi"
        val context: Unit? = null
        val renderedEvents = mutableListOf<StreamEvent>()
        val failures = mutableListOf<String>()
        var finished: FinishReason? = null
        fun renderError(message: String) {
            failures += message
        }
        fun renderToolError(toolName: String, toolError: AgentError?) {
            failures += "$toolName: $toolError"
        }
        fun finish(reason: FinishReason) {
            finished = reason
        }
        fun renderEvent(event: StreamEvent) {
            renderedEvents += event
        }

        agent.stream(prompt = prompt, options = context).collect { event ->
            when (event) {
                is StreamEvent.Error -> renderError(event.message)
                is StreamEvent.ToolError -> renderToolError(event.toolName, event.error)
                is StreamEvent.Finish -> finish(event.finishReason)
                else -> renderEvent(event)
            }
        }

        assertEquals(FinishReason.Stop, finished)
        assertTrue(failures.isEmpty())
        assertTrue(renderedEvents.isNotEmpty())
    }

    @Test
    fun `ui validation snippet compiles and runs`() {
        val messages: List<UIMessage>? = null
        var saved: List<UIMessage>? = null
        var reported: Throwable? = null
        fun save(value: List<UIMessage>) {
            saved = value
        }
        fun report(error: Throwable) {
            reported = error
        }

        when (val checked = UiMessageStreams.safeValidateUIMessages(messages)) {
            is SafeValidateUIMessagesResult.Success -> save(checked.messages)
            is SafeValidateUIMessagesResult.Failure -> report(checked.error)
        }

        assertNotNull(reported)
        assertEquals(null, saved)
    }
}
