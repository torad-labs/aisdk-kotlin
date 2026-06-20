package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

class KotlinApiTest {

    @Serializable
    data class Recipe(val name: String)

    private class CapturingModel(
        private val generateResult: LanguageModelResult = LanguageModelResult(
            text = "ok",
            finishReason = FinishReason.Stop,
            usage = Usage(promptTokens = 1, completionTokens = 1),
        ),
    ) : LanguageModel {
        override val modelId: String = "test/native"
        var generateParams: LanguageModelCallParams? = null
        var streamParams: LanguageModelCallParams? = null
        var streamCollections: Int = 0

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            generateParams = params
            return generateResult
        }

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            streamCollections += 1
            streamParams = params
            emit(StreamEvent.TextStart("t1"))
            emit(StreamEvent.TextDelta("t1", "ok"))
            emit(StreamEvent.TextEnd("t1"))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage(promptTokens = 1, completionTokens = 1)))
        }
    }

    @Test
    fun `text generation request forwards grouped settings`() = runTest {
        val providerOptions = mapOf("openai" to buildJsonObject { put("reasoningEffort", JsonPrimitive("high")) })
        val model = CapturingModel()
        val request = TextGenerationRequest(
            input = TextGenerationRequest.Input.messagesWithPrompt(
                history = TextGenerationRequest.NonEmptyMessages.of(userMessage("history")),
                prompt = "answer",
            ),
            system = "be concise",
            settings = CallSettings(
                temperature = 0.1f,
                topP = 0.2f,
                topK = 3,
                maxOutputTokens = 200,
                stopSequences = listOf("</done>"),
                seed = 42,
                providerOptions = providerOptions,
                presencePenalty = 0.4f,
                frequencyPenalty = 0.5f,
            ),
        )

        val result = generateText(model = model, request = request)

        assertEquals("ok", result.text)
        val params = assertNotNull(model.generateParams)
        assertEquals(listOf(MessageRole.System, MessageRole.User, MessageRole.User), params.messages.map { it.role })
        assertEquals(0.1f, params.temperature)
        assertEquals(0.2f, params.topP)
        assertEquals(3, params.topK)
        assertEquals(200, params.maxOutputTokens)
        assertEquals(listOf("</done>"), params.stopSequences)
        assertEquals(42, params.seed)
        assertEquals(providerOptions, params.providerOptions)
        assertEquals(0.4f, params.presencePenalty)
        assertEquals(0.5f, params.frequencyPenalty)
    }

    @Test
    fun `compat request constructor derives owned typed input and still executes`() = runTest {
        val model = CapturingModel()
        val request = TextGenerationRequest(
            messages = listOf(userMessage("history")),
            prompt = "answer",
        )

        val input = assertIs<TextGenerationRequest.Input.MessageHistoryWithPrompt>(request.input)

        assertEquals(listOf(userMessage("history")), input.messages)
        assertEquals("answer", input.prompt)

        generateText(model = model, request = request)

        val params = assertNotNull(model.generateParams)
        assertEquals(listOf(MessageRole.User, MessageRole.User), params.messages.map { it.role })
    }

    @Test
    fun `empty message history is rejected by owned request input`() {
        assertFailsWith<IllegalArgumentException> {
            TextGenerationRequest.NonEmptyMessages.from(emptyList())
        }
    }

    @Test
    fun `builder creates request with owned typed input`() {
        val request = textGenerationRequest {
            prompt("answer")
        }

        val input = assertIs<TextGenerationRequest.Input.PromptText>(request.input)

        assertEquals("answer", input.text)
    }

    @Test
    fun `empty builder preserves construction compatibility and fails at execution boundary`() = runTest {
        val model = CapturingModel()
        val request = textGenerationRequest { }

        assertEquals(null, request.prompt)
        assertEquals(emptyList(), request.messages)
        assertFailsWith<IllegalArgumentException> {
            generateText(model = model, request = request)
        }
    }

    @Test
    fun `empty compat request fails at execution boundary`() = runTest {
        val model = CapturingModel()
        val request = TextGenerationRequest()

        assertFailsWith<IllegalArgumentException> {
            generateText(model = model, request = request)
        }
    }

    @Test
    fun `builder settings override base settings and merge provider options`() = runTest {
        val model = CapturingModel()
        val base = CallSettings(
            temperature = 0.2f,
            topP = 0.3f,
            providerOptions = mapOf("base" to JsonPrimitive("yes")),
        )

        generateText(model = model, settings = base) {
            prompt("answer")
            settings {
                temperature = 0.9f
                providerOption("call", JsonPrimitive("yes"))
            }
        }

        val params = assertNotNull(model.generateParams)
        assertEquals(0.9f, params.temperature)
        assertEquals(0.3f, params.topP)
        assertEquals("yes", params.providerOptions["base"]?.jsonPrimitive?.content)
        assertEquals("yes", params.providerOptions["call"]?.jsonPrimitive?.content)
    }

    @Test
    fun `structured builder derives response format from output`() = runTest {
        val model = CapturingModel(
            generateResult = LanguageModelResult(
                text = """{"name":"cake"}""",
                finishReason = FinishReason.Stop,
                usage = Usage(promptTokens = 1, completionTokens = 1),
            ),
        )

        val result = generateText(model = model, output = outputObj<Recipe>(serializer(), name = "Recipe")) {
            prompt("recipe")
        }

        assertEquals("cake", result.output.name)
        val responseFormat = assertIs<ResponseFormat.Json>(model.generateParams?.responseFormat)
        assertEquals("Recipe", responseFormat.schemaName)
        assertEquals("object", responseFormat.schemaJson?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `stream builder remains cold and forwards settings`() = runTest {
        val model = CapturingModel()
        val events = streamText(model = model) {
            prompt("answer")
            settings {
                presencePenalty = 0.7f
                frequencyPenalty = 0.8f
            }
        }

        assertNull(model.streamParams)
        assertEquals(4, drainAllItems(events).size)
        assertEquals(1, model.streamCollections)
        val params = assertNotNull(model.streamParams)
        assertEquals(0.7f, params.presencePenalty)
        assertEquals(0.8f, params.frequencyPenalty)
    }
}
