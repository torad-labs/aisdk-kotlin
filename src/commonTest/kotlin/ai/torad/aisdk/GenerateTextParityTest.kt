package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

class GenerateTextParityTest {

    @Serializable
    data class Recipe(val name: String, val ingredients: List<String> = emptyList())

    private class CapturingModel(
        private val generateResult: LanguageModelResult = LanguageModelResult(
            text = "ok",
            finishReason = FinishReason.Stop,
            usage = Usage(promptTokens = 1, completionTokens = 1),
        ),
        private val streamEvents: List<StreamEvent> = listOf(
            StreamEvent.TextStart("t1"),
            StreamEvent.TextDelta("t1", "ok"),
            StreamEvent.TextEnd("t1"),
            StreamEvent.StepFinish(1, FinishReason.Stop, Usage(promptTokens = 1, completionTokens = 1)),
            StreamEvent.Finish(1, FinishReason.Stop, Usage(promptTokens = 1, completionTokens = 1)),
        ),
        private val streamRequest: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
        private val streamResponse: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    ) : LanguageModel {
        override val modelId: String = "test/capture"
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
            for (event in streamEvents) emit(event)
        }

        override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
            LanguageModelStreamResult(
                stream = stream(params),
                request = streamRequest,
                response = streamResponse,
            )
    }

    @Test
    fun `generateText forwards v6 call settings and auto derives JSON response format from output`() = runTest {
        // GIVEN
        val providerOptions = mapOf("openai" to buildJsonObject { put("reasoningEffort", JsonPrimitive("high")) })
        val model = CapturingModel(
            generateResult = LanguageModelResult(
                text = """{"name":"cake","ingredients":[]}""",
                finishReason = FinishReason.Stop,
                usage = Usage(promptTokens = 3, completionTokens = 4),
            ),
        )

        // WHEN
        val result = generateText(
            model = model,
            system = "be structured",
            messages = listOf(userMessage("history")),
            prompt = "recipe",
            output = Output.obj(serializer<Recipe>(), name = "Recipe"),
            temperature = 0.1f,
            topP = 0.2f,
            topK = 3,
            maxOutputTokens = 200,
            stopSequences = listOf("</json>"),
            seed = 42,
            providerOptions = providerOptions,
            presencePenalty = 0.4f,
            frequencyPenalty = 0.5f,
        )

        // THEN
        assertEquals("cake", result.output.name)
        val params = assertNotNull(model.generateParams)
        assertEquals(listOf(MessageRole.System, MessageRole.User, MessageRole.User), params.messages.map { it.role })
        assertEquals(0.1f, params.temperature)
        assertEquals(0.2f, params.topP)
        assertEquals(3, params.topK)
        assertEquals(200, params.maxOutputTokens)
        assertEquals(listOf("</json>"), params.stopSequences)
        assertEquals(42, params.seed)
        assertEquals(providerOptions, params.providerOptions)
        assertEquals(0.4f, params.presencePenalty)
        assertEquals(0.5f, params.frequencyPenalty)
        val responseFormat = assertIs<ResponseFormat.Json>(params.responseFormat)
        assertEquals("Recipe", responseFormat.schemaName)
        assertEquals("object", responseFormat.schemaJson?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `generateText keeps explicit response format when output is also supplied`() = runTest {
        // GIVEN
        val model = CapturingModel(
            generateResult = LanguageModelResult(
                text = """{"name":"cake","ingredients":[]}""",
                finishReason = FinishReason.Stop,
                usage = Usage(),
            ),
        )
        val explicit = ResponseFormat.Json(schemaName = "Explicit")

        // WHEN
        generateText(
            model = model,
            prompt = "recipe",
            output = outputObj<Recipe>(serializer()),
            responseFormat = explicit,
        )

        // THEN
        assertEquals(explicit, model.generateParams?.responseFormat)
    }

    @Test
    fun `generateText exposes rich provider result metadata`() = runTest {
        // GIVEN
        val warning = CallWarning(type = "unsupported-setting", message = "topK ignored")
        val toolCall = ContentPart.ToolCall(
            toolCallId = "call_1",
            toolName = "lookup",
            input = JsonObject(mapOf("q" to JsonPrimitive("ai"))),
        )
        val source = ContentPart.Source(StreamEvent.SourcePart.SourceType.Url, url = "https://example.com", title = "Example")
        val file = ContentPart.File(mediaType = "text/plain", base64 = "b2s=", filename = "ok.txt")
        val model = CapturingModel(
            generateResult = LanguageModelResult(
                text = "answer",
                toolCalls = listOf(toolCall),
                finishReason = FinishReason.Other,
                usage = Usage(promptTokens = 10, completionTokens = 11),
                providerMetadata = mapOf("mock" to buildJsonObject { put("trace", JsonPrimitive("abc")) }),
                content = listOf(
                    ContentPart.Text("answer"),
                    ContentPart.Reasoning("because"),
                    source,
                    file,
                    toolCall,
                ),
                rawFinishReason = "provider-stop-code",
                warnings = listOf(warning),
                request = LanguageModelRequestMetadata(body = buildJsonObject { put("prompt", JsonPrimitive("hi")) }),
                response = LanguageModelResponseMetadata(
                    id = "resp_1",
                    timestampMillis = 123,
                    modelId = "mock-large",
                    headers = mapOf("x-request-id" to "req_1"),
                    body = buildJsonObject { put("id", JsonPrimitive("resp_1")) },
                ),
            ),
        )

        // WHEN
        val result = generateText<String>(model = model, prompt = "hi")

        // THEN
        assertEquals("answer", result.output)
        assertEquals("because", result.reasoningText)
        assertEquals(listOf(source), result.sources)
        assertEquals(listOf(file), result.files)
        assertEquals(listOf(warning), result.warnings)
        assertEquals("resp_1", result.response.id)
        assertEquals("provider-stop-code", result.rawFinishReason)
        assertEquals("abc", result.providerMetadata["mock"]?.jsonObject?.get("trace")?.jsonPrimitive?.content)
        assertEquals(21, result.totalUsage.totalTokens)
    }

    @Test
    fun `streamText is cold and forwards output response format plus penalties`() = runTest {
        // GIVEN
        val model = CapturingModel()
        val flow = streamText(
            model = model,
            prompt = "recipe",
            output = outputObj<Recipe>(serializer(), name = "Recipe"),
            presencePenalty = 0.7f,
            frequencyPenalty = 0.8f,
        )

        // WHEN
        assertNull(model.streamParams, "streamText must not call the model before collection")
        val events = drainAllItems(flow)

        // THEN
        assertEquals(1, model.streamCollections)
        assertEquals(5, events.size)
        val params = assertNotNull(model.streamParams)
        assertEquals(0.7f, params.presencePenalty)
        assertEquals(0.8f, params.frequencyPenalty)
        assertEquals("Recipe", assertIs<ResponseFormat.Json>(params.responseFormat).schemaName)
    }

    @Test
    fun `streamTextResult exposes request warnings and response metadata`() = runTest {
        // GIVEN
        val warning = CallWarning("unsupported-setting", "topK ignored")
        val request = LanguageModelRequestMetadata(
            body = buildJsonObject { put("prompt", JsonPrimitive("hi")) },
        )
        val model = CapturingModel(
            streamEvents = listOf(
                StreamEvent.StreamStart(listOf(warning)),
                StreamEvent.ResponseMetadata(
                    id = "resp_1",
                    timestampMillis = 123,
                    modelId = "mock-large",
                    headers = mapOf("x-stream-id" to "stream_1"),
                    body = buildJsonObject { put("id", JsonPrimitive("resp_1")) },
                ),
                StreamEvent.TextStart("t1"),
                StreamEvent.TextDelta("t1", "ok"),
                StreamEvent.TextEnd("t1"),
                StreamEvent.Finish(1, FinishReason.Stop, Usage(promptTokens = 1, completionTokens = 1)),
            ),
            streamRequest = request,
            streamResponse = LanguageModelResponseMetadata(headers = mapOf("x-request-id" to "req_1")),
        )

        // WHEN
        val result = streamTextResult(model = model, prompt = "hi")
        val warnings = drainAllItems(result.warnings)
        val responses = drainAllItems(result.response)

        // THEN
        assertEquals(request, result.request)
        assertEquals(listOf(listOf(warning)), warnings)
        val response = responses.single()
        assertEquals("resp_1", response.id)
        assertEquals(123, response.timestampMillis)
        assertEquals("mock-large", response.modelId)
        assertEquals(mapOf("x-request-id" to "req_1", "x-stream-id" to "stream_1"), response.headers)
        assertEquals("resp_1", response.body?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }

    @Test
    fun `streamTextResult metadata accessors do not recollect provider stream`() = runTest {
        // GIVEN
        val warning = CallWarning("mock-warning")
        val model = CapturingModel(
            streamEvents = listOf(
                StreamEvent.StreamStart(listOf(warning)),
                StreamEvent.ResponseMetadata(id = "resp_1", headers = mapOf("x-stream-id" to "stream_1")),
                StreamEvent.TextStart("t1"),
                StreamEvent.TextDelta("t1", "ok"),
                StreamEvent.TextEnd("t1"),
                StreamEvent.Finish(1, FinishReason.Stop, Usage(promptTokens = 1, completionTokens = 1)),
            ),
            streamResponse = LanguageModelResponseMetadata(headers = mapOf("x-request-id" to "req_1")),
        )

        // WHEN
        val result = streamTextResult(model = model, prompt = "hi")
        assertEquals(listOf("ok"), drainAllItems(result.textStream))
        assertEquals(listOf(listOf(warning)), drainAllItems(result.warnings))
        val response = drainAllItems(result.response).single()

        // THEN
        assertEquals(1, model.streamCollections, "metadata access must not re-run the provider stream")
        assertEquals("resp_1", response.id)
        assertEquals(mapOf("x-request-id" to "req_1", "x-stream-id" to "stream_1"), response.headers)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `generateObject delegates to generateText and returns the typed value`() = runTest {
        // GIVEN
        val model = CapturingModel(
            generateResult = LanguageModelResult(
                text = """{"name":"cake","ingredients":["flour"]}""",
                finishReason = FinishReason.Stop,
                usage = Usage(promptTokens = 2, completionTokens = 3),
                warnings = listOf(CallWarning("mock-warning")),
            ),
        )

        // WHEN
        val result = generateObject(
            model = model,
            output = outputObj<Recipe>(serializer(), name = "Recipe"),
            prompt = "recipe",
        )

        // THEN
        assertEquals("cake", result.value.name)
        assertEquals(result.value, result.output)
        assertEquals(result.value, result.generatedObject)
        assertEquals(listOf(CallWarning("mock-warning")), result.warnings)
        assertEquals("Recipe", assertIs<ResponseFormat.Json>(model.generateParams?.responseFormat).schemaName)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `streamObject delegates to streamText with the supplied output`() = runTest {
        // GIVEN
        val model = CapturingModel()

        // WHEN
        drainAllItems(
            streamObject(
                model = model,
                output = outputChoice("yes", "no"),
                prompt = "choose",
            ),
        )

        // THEN
        val responseFormat = assertIs<ResponseFormat.Json>(model.streamParams?.responseFormat)
        val resultSchema = responseFormat.schemaJson
            ?.jsonObject
            ?.get("properties")
            ?.jsonObject
            ?.get("result")
            ?.jsonObject
        assertEquals("string", resultSchema?.get("type")?.jsonPrimitive?.content)
    }
}
