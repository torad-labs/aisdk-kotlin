package ai.torad.aisdk

import ai.torad.aisdk.providers.MockEmbeddingModel
import ai.torad.aisdk.providers.MockImageModel
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.ui.CreateUiMessageStream
import ai.torad.aisdk.ui.StreamToUiMessages
import ai.torad.aisdk.ui.TextUIPartState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compiles and executes representative snippets from docs/wiki/cookbook.md. */
class CookbookDocSnippetTest {
    @Serializable
    private data class RouteDecision(val route: String, val confidence: Double)

    @Test
    fun `text generator config and structured output snippets compile and run`() = runTest {
        val model = MockLanguageModelTextOnly("""{"route":"billing","confidence":0.9}""")
        val config = CallConfig {
            temperature(0.2f)
            maxOutputTokens(800)
            providerOptions(
                ProviderOptions.ofPairs(
                    "openai" to buildJsonObject {
                        put("reasoningEffort", JsonPrimitive("medium"))
                    },
                ),
            )
        }

        val result = TextGenerator(model, config).generate(
            GenerationInput.Messages(
                GenerationInput.NonEmptyMessages.of(
                    SystemMessage("Answer as an SDK maintainer."),
                    UserMessage("Route this request: user needs invoice copy."),
                ),
            ),
            output = Output.obj(serializer<RouteDecision>(), name = "RouteDecision"),
        ).first()

        assertEquals(RouteDecision("billing", 0.9), result.output)
    }

    @Test
    fun `stream to UI and custom UI stream snippets compile and run`() = runTest {
        val result = TextGenerator(MockLanguageModelTextOnly("Walk through it."))
            .streamResult(GenerationInput.Prompt("Walk through a tool-calling loop."))

        val messages = StreamToUiMessages(
            events = result.fullStream,
            assistantMessageId = "assistant-1",
        ).toList()

        val status = UIMessage(
            id = "status-1",
            role = UIMessageRole.Assistant,
            parts = listOf(
                UIMessagePart.Data(
                    type = "status",
                    data = JsonPrimitive("running"),
                    transient = true,
                ),
            ),
        )
        val stream = CreateUiMessageStream {
            write(status)
            merge(messages.asFlow())
        }

        val rendered = stream.toList()
        assertEquals(status, rendered.first())
        assertTrue(
            rendered.any { message ->
                message.parts.any { part ->
                    part is UIMessagePart.Text && part.state == TextUIPartState.Done
                }
            },
        )
    }

    @Test
    fun `agent generate and lifecycle snippets compile and run`() = runTest {
        class DocsAgent(model: LanguageModel) :
            ToolLoopAgent<Unit, String>(
                model = model,
                instructions = "Search docs before answering SDK questions.",
                tools = ToolSet<Unit>(),
                stopWhen = StepCountIs(1),
            )

        val agent = DocsAgent(MockLanguageModelTextOnly("Use UiMessageStreams.safeValidateUIMessages."))
        val result = agent.generate(
            prompt = "How do I validate UI messages?",
            options = Unit,
        ).first()

        val tokenCounts = mutableListOf<Int>()
        val savedMessages = mutableListOf<List<ModelMessage>>()
        agent.events(prompt = "Observe lifecycle.", options = Unit).collect { event ->
            when (event) {
                is AgentEvent.StepFinished -> tokenCounts += event.step.usage.totalTokens
                is AgentEvent.Finished<*, *> -> savedMessages += event.messages
                else -> Unit
            }
        }

        assertTrue(result.text.contains("safeValidateUIMessages"))
        assertTrue(tokenCounts.isNotEmpty())
        assertTrue(savedMessages.isNotEmpty())
    }

    @Test
    fun `provider embedding image devtools and completion snippets compile and run`() = runTest {
        val provider = CustomProvider {
            providerId("test")
            languageModel("small", MockLanguageModelTextOnly("ok"))
        }

        val providerResult = TextGenerator(provider.languageModel("small"))
            .generate(GenerationInput.Prompt("anything"))
            .first()

        val embeddings = Embedding.embedMany(
            model = MockEmbeddingModel(dimensions = 2),
            values = listOf("alpha", "beta"),
            maxParallelCalls = 4,
        )
        val image = ImageGeneration.generateImage(
            model = MockImageModel(),
            prompt = "A clean architecture diagram for an SDK docs page.",
            aspectRatio = "16:9",
        ).image

        val recorder = InMemoryDevToolsRecorder()
        val inspected = WrapLanguageModel(
            model = MockLanguageModelTextOnly("retry notes"),
            middlewares = listOf(DevToolsMiddleware(recorder)),
        )
        TextGenerator(inspected).generate(GenerationInput.Prompt("Explain retries.")).first()

        val completion = Completion(
            UseCompletionOptions(block = {
                transport(
                    object : CompletionTransport {
                        override fun complete(request: CompletionRequest) =
                            flowOf("Draft for: ${request.prompt}")
                    },
                )
            }),
        )
        val text = completion.complete("Write a release title.")

        assertEquals("ok", providerResult.text)
        assertEquals(2, embeddings.embeddings.size)
        assertEquals("image/png", image.mediaType)
        assertTrue(recorder.results.isNotEmpty())
        assertEquals("Draft for: Write a release title.", text)
    }
}
