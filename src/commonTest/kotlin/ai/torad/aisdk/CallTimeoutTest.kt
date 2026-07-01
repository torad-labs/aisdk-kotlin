@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CallTimeoutTest {
    @Test
    fun `CallConfig timeout cancels non streaming model call and surfaces typed error`() = runTest {
        val model = HangingLanguageModel()

        val error = assertFailsWith<CallTimeoutError> {
            TextGenerator(
                model,
                CallConfig {
                    timeout(100.milliseconds)
                    maxRetries(0)
                },
            ).generate(GenerationInput.Prompt("hi")).first()
        }

        assertEquals(100.milliseconds, error.timeout)
        assertEquals(100L, testScheduler.currentTime)
        assertTrue(model.generateCancelled.isCompleted)
    }

    @Test
    fun `CallConfig timeout cancels streaming collection and surfaces typed error`() = runTest {
        val model = HangingLanguageModel()

        val error = assertFailsWith<CallTimeoutError> {
            TextGenerator(
                model,
                CallConfig {
                    timeout(50.milliseconds)
                    maxRetries(0)
                },
            ).stream(GenerationInput.Prompt("hi")).toList()
        }

        assertEquals(50.milliseconds, error.timeout)
        assertEquals(50L, testScheduler.currentTime)
        assertTrue(model.streamCancelled.isCompleted)
    }

    private class HangingLanguageModel : LanguageModel {
        override val modelId: String = "timeout-test"
        val generateCancelled = CompletableDeferred<Unit>()
        val streamCancelled = CompletableDeferred<Unit>()

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            try {
                awaitCancellation()
            } finally {
                generateCancelled.complete(Unit)
            }
        }

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            try {
                awaitCancellation()
            } finally {
                streamCancelled.complete(Unit)
            }
        }
    }
}
