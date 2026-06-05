package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** L-2: a model failure's typed cause survives through StreamEvent.Error to the boundary. */
class ErrorCauseTest {

    @Test
    fun `generate surfaces the typed cause of a model failure`() = runTest {
        val boom = IllegalStateException("provider exploded")
        val model = object : LanguageModel {
            override val modelId: String = "test/boom"
            override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
                throw boom
            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow { throw boom }
        }
        val agent = ToolLoopAgent<Unit, String>(
            model = model,
            instructions = "x",
            tools = toolSet {},
        )

        val thrown = assertFailsWith<AiSdkException> { agent.generate(prompt = "hi") }
        assertEquals(boom, thrown.cause)
    }
}
