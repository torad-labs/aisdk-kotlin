package ai.torad.aisdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CallParamsToBuilderTest {
    @Test
    fun `LanguageModelCallParams toBuilder round trips and preserves fields on override`() {
        val signal = AbortController().signal
        val providerOptions = ProviderOptions.Raw(JsonObject(mapOf("original" to JsonPrimitive(true))))
        val replacementOptions = ProviderOptions.Raw(JsonObject(mapOf("replacement" to JsonPrimitive("yes"))))
        val params = LanguageModelCallParams {
            messages(listOf(SystemMessage("system"), UserMessage("hi")))
            tools(listOf(LanguageModelTool("lookup", "Lookup", """{"type":"object"}""", strict = true)))
            toolChoice(ToolChoice.Specific("lookup"))
            temperature(0.1f)
            topP(0.2f)
            topK(3)
            maxOutputTokens(128)
            stopSequences(listOf("stop"))
            seed(42)
            providerOptions(providerOptions)
            abortSignal(signal)
            presencePenalty(0.4f)
            frequencyPenalty(0.5f)
            responseFormat(ResponseFormat.Json(schemaName = "Answer", schemaJson = JsonObject(emptyMap())))
            headers(mapOf("x-test" to "1"))
        }

        assertEquals(params, params.toBuilder().build())
        assertEquals(params.hashCode(), params.toBuilder().build().hashCode())

        val updated = params.toBuilder().providerOptions(replacementOptions).build()

        assertEquals(replacementOptions, updated.providerOptions)
        assertEquals(params.messages, updated.messages)
        assertEquals(params.tools, updated.tools)
        assertEquals(params.toolChoice, updated.toolChoice)
        assertEquals(params.temperature, updated.temperature)
        assertEquals(params.topP, updated.topP)
        assertEquals(params.topK, updated.topK)
        assertEquals(params.maxOutputTokens, updated.maxOutputTokens)
        assertEquals(params.stopSequences, updated.stopSequences)
        assertEquals(params.seed, updated.seed)
        assertEquals(params.abortSignal, updated.abortSignal)
        assertEquals(params.presencePenalty, updated.presencePenalty)
        assertEquals(params.frequencyPenalty, updated.frequencyPenalty)
        assertEquals(params.responseFormat, updated.responseFormat)
        assertEquals(params.headers, updated.headers)
    }

    @Test
    fun `EmbeddingModelCallParams toBuilder round trips and preserves fields on override`() {
        val signal = AbortController().signal
        val providerOptions = ProviderOptions.Raw(JsonObject(mapOf("original" to JsonPrimitive(true))))
        val replacementOptions = ProviderOptions.Raw(JsonObject(mapOf("replacement" to JsonPrimitive("yes"))))
        val params = EmbeddingModelCallParams {
            values(listOf("hello", "world"))
            maxEmbeddingsPerCall(16)
            truncate(true)
            providerOptions(providerOptions)
            abortSignal(signal)
            headers(mapOf("x-test" to "1"))
        }

        assertEquals(params, params.toBuilder().build())
        assertEquals(params.hashCode(), params.toBuilder().build().hashCode())

        val updated = params.toBuilder().providerOptions(replacementOptions).build()

        assertEquals(replacementOptions, updated.providerOptions)
        assertEquals(params.values, updated.values)
        assertEquals(params.maxEmbeddingsPerCall, updated.maxEmbeddingsPerCall)
        assertEquals(params.truncate, updated.truncate)
        assertEquals(params.abortSignal, updated.abortSignal)
        assertEquals(params.headers, updated.headers)
    }
}
