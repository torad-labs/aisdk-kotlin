package ai.torad.aisdk

import ai.torad.aisdk.TypedJsonOps.providerMetadataAs
import ai.torad.aisdk.middleware.DefaultSettingsMiddleware
import ai.torad.aisdk.middleware.LoggingMiddleware
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Compiles and executes the core wiki snippets that describe the public generation surface. */
class CoreDocSnippetTest {
    @Serializable
    data class Label(val category: String, val confidence: Double)

    @Serializable
    data class OpenAiMetadata(val cacheHit: Boolean)

    @Test
    fun `core text generation and streaming snippets compile and run`() = runTest {
        val model = MockLanguageModelTextOnly("Kotlin Multiplatform shares code across targets.")

        val result = TextGenerator(model)
            .generate(GenerationInput.Prompt("Summarize Kotlin Multiplatform in one paragraph."))
            .first()

        val input = GenerationInput.Messages(
            GenerationInput.NonEmptyMessages.of(
                SystemMessage("Answer with short bullets."),
                UserMessage("What changed in the latest release?"),
            ),
        )
        val messagesResult = TextGenerator(model).generate(input).first()

        val streamed = TextGenerator(model)
            .stream(GenerationInput.Prompt("Write a haiku."))
            .filterIsInstance<StreamEvent.TextDelta>()
            .map { it.text }
            .toList()
            .joinToString("")

        val streamResult = TextGenerator(model).streamResult(GenerationInput.Prompt("Tell me a story."))
        val streamResultText = streamResult.textStream.toList().joinToString("")

        assertEquals("Kotlin Multiplatform shares code across targets.", result.text)
        assertEquals("Kotlin Multiplatform shares code across targets.", messagesResult.text)
        assertEquals("Kotlin Multiplatform shares code across targets.", streamed)
        assertEquals("Kotlin Multiplatform shares code across targets.", streamResultText)
    }

    @Test
    fun `core structured output and settings snippets compile and run`() = runTest {
        val model = MockLanguageModelTextOnly("""{"category":"billing","confidence":0.9}""")

        val result = TextGenerator(model)
            .generate(
                GenerationInput.Prompt("Classify this ticket: payment failed after checkout."),
                Output.obj(serializer<Label>()),
            )
            .first()
        val label: Label = result.output

        val config = CallConfig {
            temperature(0.2f)
            maxOutputTokens(600)
            stopSequences(listOf("</answer>"))
            providerOptions(
                ProviderOptions.ofPairs(
                    "openai" to buildJsonObject {
                        put("reasoningEffort", JsonPrimitive("high"))
                    },
                ),
            )
        }
        val configured = TextGenerator(model, config)
            .generate(
                GenerationInput.Prompt(
                    """
                    Answer as a product engineer.
                    How do provider options work?
                    """.trimIndent(),
                ),
                Output.obj(serializer<Label>()),
            )
            .first()
        val settings = CallSettings {
            temperature(0.2f)
            maxOutputTokens(600)
            providerOptions {
                provider("openai") {
                    put("reasoningEffort", JsonPrimitive("high"))
                }
            }
        }

        assertEquals(Label("billing", 0.9), label)
        assertEquals(Label("billing", 0.9), configured.output)
        assertEquals(0.2f, config.temperature)
        assertEquals(600, settings.maxOutputTokens)
        assertEquals(
            "high",
            config.providerOptions.toMap().getValue("openai").jsonObject
                .getValue("reasoningEffort").jsonPrimitive.content,
        )
    }

    @Test
    fun `core media and middleware snippets compile and run`() = runTest {
        val editedImageBytes = byteArrayOf(1, 2, 3)
        val imageInput = ImageGenerationFile(
            FileData.Bytes(
                bytes = editedImageBytes,
                mediaType = "image/png",
                filename = "mask.png",
            ),
        )

        val rawModel = MockLanguageModelTextOnly("ok")
        val wrapped = WrapLanguageModel(
            model = rawModel,
            middlewares = listOf(
                DefaultSettingsMiddleware(temperature = 0.2f),
                LoggingMiddleware(NoopLogger),
            ),
        )
        val result = TextGenerator(wrapped).generate(GenerationInput.Prompt("hello")).first()
        val cacheHit = result.providerMetadataAs<OpenAiMetadata>("openai")?.cacheHit

        assertEquals("image/png", imageInput.mediaType)
        assertEquals("mask.png", imageInput.filename)
        assertEquals("ok", result.text)
        assertNull(cacheHit)
    }
}
