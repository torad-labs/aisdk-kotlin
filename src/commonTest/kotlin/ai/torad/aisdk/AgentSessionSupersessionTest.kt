@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionSupersessionTest {

    @Test
    fun `a superseded job's terminal write cannot clobber the newer submit's state`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val agent = object : Agent<Unit, String> {
            override val tools = ToolSet<Unit>()

            override fun generate(
                prompt: String?,
                messages: List<ModelMessage>,
                options: Unit?,
                abortSignal: AbortSignal,
            ): Flow<GenerateResult<String>> = flow {
                gate.await()
                emit(
                    ResultConstruction.generateResult(
                        rawOutput = "first",
                        text = "first",
                        steps = emptyList(),
                        finishReason = FinishReason.Stop,
                        usage = Usage(promptTokens = 1, completionTokens = 1),
                        messages = messages,
                    ),
                )
            }

            override fun stream(
                prompt: String?,
                messages: List<ModelMessage>,
                options: Unit?,
                abortSignal: AbortSignal,
            ): Flow<StreamEvent> = flow {}
        }
        val session = agent.session(this)

        // Drives the first job to its terminal write from *inside* the second submit,
        // after that submit has published its own Running state but before launchSession
        // claims ownership — the exact TOCTOU window a multithreaded dispatcher opens.
        // AbortSignal is a public consumer-implementable interface, and launchSession
        // registers on it inside that window, so this needs no real threads.
        val scheduler = testScheduler
        val interleaveInsideSubmit = object : AbortSignal {
            override val isAborted: Boolean = false
            override fun throwIfAborted() = Unit
            override fun register(onAbort: () -> Unit): AbortSignal.AbortRegistration {
                scheduler.runCurrent()
                return object : AbortSignal.AbortRegistration { override fun cancel() = Unit }
            }
        }

        session.submit(prompt = "first")
        runCurrent() // the first job parks in generate()
        gate.complete(Unit) // its resumption is now queued, past any cancellation check

        session.submit(prompt = "second", abortSignal = interleaveInsideSubmit)

        // The second submit owns the session: its Running state must survive the first
        // job's late terminal write.
        assertEquals(AgentSessionStatus.Running, session.state.value.status)
        assertTrue(session.state.value.messages.any { message -> message.mentions("second") })
    }

    private fun ModelMessage.mentions(text: String): Boolean =
        content.any { part -> part is ContentPart.Text && part.text.contains(text) }
}
