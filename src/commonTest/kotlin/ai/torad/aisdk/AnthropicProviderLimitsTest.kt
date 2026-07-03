@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.Anthropic
import ai.torad.aisdk.providers.AnthropicProviderSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicProviderLimitsTest {
    @Test
    fun `max_tokens defaults to the per-model limit when caller omits maxOutputTokens`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"m","model":"claude-opus-4-8","stop_reason":"end_turn",
                               "content":[{"type":"text","text":"hi"}],
                               "usage":{"input_tokens":1,"output_tokens":1}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider =
            Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })
        provider.messages(ModelId("claude-opus-4-8")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
            },
        )
        val body = fixture.calls.single().requestBodyJson.jsonObject
        // claude-opus-4-8 → 128000, not the old hardcoded 4096.
        assertEquals(128_000, body["max_tokens"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `thinking max_tokens clamps to model ceiling and warns only for explicit maxOutputTokens`() = runTest {
        val response = Json.parseToJsonElement(
            """{"id":"m","model":"claude-sonnet-4-5","stop_reason":"end_turn",
               "content":[{"type":"text","text":"ok"}],
               "usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler { UrlResponse.JsonValue(response) },
            ),
        )
        fixture.server.start()
        val provider =
            Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })
        val thinkingOptions = ProviderOptions.Raw(
            JsonObject(
                mapOf(
                    "anthropic" to buildJsonObject {
                        put(
                            "thinking",
                            buildJsonObject {
                                put("type", JsonPrimitive("enabled"))
                                put("budgetTokens", JsonPrimitive(10_000))
                            },
                        )
                    },
                )
            )
        )

        val implicit = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                providerOptions(thinkingOptions)
            },
        )
        val explicit = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                maxOutputTokens(60_000)
                providerOptions(thinkingOptions)
            },
        )
        val unknown = provider.messages(ModelId("claude-fictional-9")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                providerOptions(thinkingOptions)
            },
        )

        assertEquals(64_000, fixture.calls[0].requestBodyJson.jsonObject["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertTrue(implicit.warnings.none { it.message == "maxOutputTokens" })

        assertEquals(64_000, fixture.calls[1].requestBodyJson.jsonObject["max_tokens"]?.jsonPrimitive?.intOrNull)
        val warning = explicit.warnings.single { it.message == "maxOutputTokens" }
        assertEquals("unsupported", warning.type)
        assertEquals(
            "70000 (maxOutputTokens + thinkingBudget) is greater than claude-sonnet-4-5 64000 max output tokens. " +
                "The max output tokens have been limited to 64000.",
            warning.details?.jsonPrimitive?.contentOrNull,
        )

        assertEquals(14_096, fixture.calls[2].requestBodyJson.jsonObject["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertTrue(unknown.warnings.none { it.message == "maxOutputTokens" })
    }

    @Test
    fun `models that reject sampling parameters omit them and warn while other models forward them`() = runTest {
        val response = Json.parseToJsonElement(
            """{"id":"m","model":"claude","stop_reason":"end_turn",
               "content":[{"type":"text","text":"ok"}],
               "usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler {
                    UrlResponse.JsonValue(response)
                },
            ),
        )
        fixture.server.start()
        val provider =
            Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })
        val rejectedModels = listOf("claude-opus-4-8", "claude-opus-4-7", "claude-fable-5")
        val samplingFeatures = setOf("temperature", "topK", "topP")

        val rejectedResults = rejectedModels.map { model ->
            provider.messages(ModelId(model)).generate(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                    temperature(0.5f)
                    topP(0.8f)
                    topK(42)
                },
            )
        }
        val sonnetTopPResult = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                topP(0.8f)
                topK(42)
            },
        )
        val sonnetTemperatureResult = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                temperature(0.5f)
                topK(42)
            },
        )

        rejectedModels.forEachIndexed { index, model ->
            val body = fixture.calls[index].requestBodyJson.jsonObject
            assertEquals(model, body["model"]?.jsonPrimitive?.contentOrNull)
            assertTrue("temperature" !in body)
            assertTrue("top_p" !in body)
            assertTrue("top_k" !in body)
            val warnings = rejectedResults[index].warnings.filter { it.message in samplingFeatures }
            assertEquals(listOf("temperature", "topK", "topP"), warnings.map { it.message })
            assertEquals(
                listOf(
                    "temperature is not supported by $model and will be ignored",
                    "topK is not supported by $model and will be ignored",
                    "topP is not supported by $model and will be ignored",
                ),
                warnings.map { it.details?.jsonPrimitive?.contentOrNull },
            )
        }

        val sonnetTopPBody = fixture.calls[rejectedModels.size].requestBodyJson.jsonObject
        assertEquals("claude-sonnet-4-5", sonnetTopPBody["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0.8f, sonnetTopPBody["top_p"]?.jsonPrimitive?.floatOrNull)
        assertEquals(42, sonnetTopPBody["top_k"]?.jsonPrimitive?.intOrNull)
        assertTrue(sonnetTopPResult.warnings.none { it.message in samplingFeatures })

        val sonnetTemperatureBody = fixture.calls[rejectedModels.size + 1].requestBodyJson.jsonObject
        assertEquals("claude-sonnet-4-5", sonnetTemperatureBody["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0.5f, sonnetTemperatureBody["temperature"]?.jsonPrimitive?.floatOrNull)
        assertEquals(42, sonnetTemperatureBody["top_k"]?.jsonPrimitive?.intOrNull)
        assertTrue(sonnetTemperatureResult.warnings.none { it.message in samplingFeatures })
    }
}
