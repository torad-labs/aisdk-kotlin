package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compiles and executes the README quick-start shape so public docs cannot rot. */
class ReadmeQuickStartTest {
    @Serializable
    data class EmptyInput(val unused: String = "")

    private class HelloAgent(model: LanguageModel, tools: ToolSet<Unit>) :
        ToolLoopAgent<Unit, String>(
            model = model,
            instructions = "Be brief.",
            tools = tools,
            stopWhen = StepCountIs(3),
        )

    @Test
    fun `README quick start compiles and runs`() = runTest {
        val helloTool = Tool<EmptyInput, String, Unit>(
            name = "hello",
            description = "Return a greeting.",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ -> "Hello from a tool." }
        val agent = HelloAgent(
            model = MockLanguageModelTextOnly("Welcome."),
            tools = ToolSet(helloTool),
        )

        val result = agent.generate(prompt = "Say hi").first()

        assertEquals(listOf("hello"), agent.tools.names())
        assertEquals("Welcome.", result.text)
        assertEquals(FinishReason.Stop, result.finishReason)
    }
}
