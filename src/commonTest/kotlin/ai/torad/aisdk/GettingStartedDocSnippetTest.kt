package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compiles and executes the getting-started snippets so the entrypoint docs cannot rot. */
class GettingStartedDocSnippetTest {
    @Serializable
    data class WeatherInput(val city: String)

    @Serializable
    data class WeatherOutput(val summary: String)

    private class WeatherAgent(model: LanguageModel, tools: ToolSet<Unit>) :
        ToolLoopAgent<Unit, String>(
            model = model,
            instructions = "Use tools when they help. Be brief.",
            tools = tools,
            stopWhen = StepCountIs(6),
        )

    @Test
    fun `getting started direct text snippets compile and run`() = runTest {
        val model = MockLanguageModelTextOnly("Hello from AI SDK Kotlin.")

        val generated = TextGenerator(model)
            .generate(GenerationInput.Prompt("What does this SDK provide?"))
            .first()
        val configured = TextGenerator(
            model,
            CallConfig {
                temperature(0.2f)
                maxOutputTokens(400)
            },
        ).generate(GenerationInput.Prompt("How do I stream output?")).first()
        val streamed = TextGenerator(model)
            .stream(GenerationInput.Prompt("Write a short intro."))
            .filterIsInstance<StreamEvent.TextDelta>()
            .map { it.text }
            .toList()
            .joinToString("")
        val streamResult = TextGenerator(model).streamResult(GenerationInput.Prompt("Stream this."))
        val streamResultText = streamResult.textStream.toList().joinToString("")

        assertEquals("Hello from AI SDK Kotlin.", generated.text)
        assertEquals("Hello from AI SDK Kotlin.", configured.text)
        assertEquals("Hello from AI SDK Kotlin.", streamed)
        assertEquals("Hello from AI SDK Kotlin.", streamResultText)
    }

    @Test
    fun `getting started agent snippet compiles and reads flow result`() = runTest {
        val weatherTool = Tool<WeatherInput, WeatherOutput, Unit>(
            name = "weather",
            description = "Get the weather for a city.",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { input ->
            WeatherOutput(summary = "Mild in ${input.city}.")
        }
        val agent = WeatherAgent(MockLanguageModelTextOnly("Mild in Paris."), ToolSet(weatherTool))

        val answer = agent.generate(prompt = "What is the weather in Paris?").first()

        assertEquals("Mild in Paris.", answer.text)
    }
}
