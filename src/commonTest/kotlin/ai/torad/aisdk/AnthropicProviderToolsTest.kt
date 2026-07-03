@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.Anthropic
import ai.torad.aisdk.providers.AnthropicMessagesLanguageModel.Companion.forwardAnthropicContainerIdFromLastStep
import ai.torad.aisdk.providers.AnthropicProviderSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnthropicProviderToolsTest {
    @Test
    fun `auth conflict hosted tools and container forwarding are exposed`() {
        val fixture = TestServer.createTestServer(mutableMapOf())
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings {
                authToken("token")
            },
        )

        val tool = provider.tools.webSearch_20260209
        assertEquals("web_search", tool.name)
        assertEquals(true, tool.providerExecuted)
        assertEquals("anthropic.web_search_20260209", tool.metadata["providerToolId"]?.jsonPrimitive?.contentOrNull)
        assertFailsWith<InvalidArgumentError> {
            Anthropic(
                fixture.httpClient(),
                AnthropicProviderSettings {
                    apiKey("key")
                    authToken("token")
                },
            )
        }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.textEmbeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.imageModel("image") }

        val forwarded = forwardAnthropicContainerIdFromLastStep(
            listOf(
                mapOf("anthropic" to buildJsonObject {}),
                mapOf(
                    "anthropic" to buildJsonObject {
                        put("container", buildJsonObject { put("id", JsonPrimitive("container-next")) })
                    },
                ),
            ),
        )
        assertEquals(
            "container-next",
            forwarded?.get(
                "anthropic"
            )?.jsonObject?.get("container")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        )
    }

    @Test
    fun `provider-executed tools map by version id and forward args`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_tools",
                              "type":"message",
                              "role":"assistant",
                              "model":"claude-sonnet-4-5",
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":1,"output_tokens":1},
                              "content":[{"type":"text","text":"ok"}]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings {
                baseURL("https://anthropic.test/v1")
                apiKey("key")
            },
        )

        provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                tools(
                    listOf(
                        LanguageModelTool(
                            name = "computer",
                            description = "old computer tool",
                            parametersSchemaJson = """{"type":"object"}""",
                            providerExecuted = true,
                            metadata = mapOf(
                                "providerToolId" to JsonPrimitive("anthropic.computer_20241022"),
                                "args" to buildJsonObject {
                                    put("displayWidthPx", JsonPrimitive(1024))
                                    put("displayHeightPx", JsonPrimitive(768))
                                    put("displayNumber", JsonPrimitive(1))
                                },
                            ),
                        ),
                        LanguageModelTool(
                            name = "computer",
                            description = "current computer tool",
                            parametersSchemaJson = """{"type":"object"}""",
                            providerExecuted = true,
                            metadata = mapOf(
                                "providerToolId" to JsonPrimitive("anthropic.computer_20251124"),
                                "args" to buildJsonObject {
                                    put("displayWidthPx", JsonPrimitive(1440))
                                    put("displayHeightPx", JsonPrimitive(900))
                                    put("displayNumber", JsonPrimitive(2))
                                    put("enableZoom", JsonPrimitive(true))
                                },
                            ),
                        ),
                        LanguageModelTool(
                            name = "str_replace_based_edit_tool",
                            description = "text editor",
                            parametersSchemaJson = """{"type":"object"}""",
                            providerExecuted = true,
                            metadata = mapOf("providerToolId" to JsonPrimitive("anthropic.text_editor_20250429")),
                        ),
                        LanguageModelTool(
                            name = "web_search",
                            description = "web search",
                            parametersSchemaJson = """{"type":"object"}""",
                            providerExecuted = true,
                            metadata = mapOf(
                                "providerToolId" to JsonPrimitive("anthropic.web_search_20250305"),
                                "args" to buildJsonObject {
                                    put("maxUses", JsonPrimitive(3))
                                    put("allowedDomains", JsonArray(listOf(JsonPrimitive("example.com"))))
                                    put("blockedDomains", JsonArray(listOf(JsonPrimitive("blocked.test"))))
                                    put("userLocation", buildJsonObject { put("city", JsonPrimitive("Austin")) })
                                },
                            ),
                        ),
                    )
                )
            }
        )

        val request = fixture.calls.single()
        val toolsByType = request.requestBodyJson.jsonObject
            .getValue("tools")
            .jsonArray
            .associateBy { it.jsonObject.getValue("type").jsonPrimitive.content }

        val oldComputer = toolsByType.getValue("computer_20241022").jsonObject
        assertEquals("computer", oldComputer.getValue("name").jsonPrimitive.content)
        assertEquals(1024, oldComputer.getValue("display_width_px").jsonPrimitive.intOrNull)
        assertEquals(768, oldComputer.getValue("display_height_px").jsonPrimitive.intOrNull)
        assertEquals(1, oldComputer.getValue("display_number").jsonPrimitive.intOrNull)

        val currentComputer = toolsByType.getValue("computer_20251124").jsonObject
        assertEquals(1440, currentComputer.getValue("display_width_px").jsonPrimitive.intOrNull)
        assertEquals(900, currentComputer.getValue("display_height_px").jsonPrimitive.intOrNull)
        assertEquals(2, currentComputer.getValue("display_number").jsonPrimitive.intOrNull)
        assertEquals(true, currentComputer.getValue("enable_zoom").jsonPrimitive.booleanOrNull)

        val editor = toolsByType.getValue("text_editor_20250429").jsonObject
        assertEquals("str_replace_based_edit_tool", editor.getValue("name").jsonPrimitive.content)

        val webSearch = toolsByType.getValue("web_search_20250305").jsonObject
        assertEquals(3, webSearch.getValue("max_uses").jsonPrimitive.intOrNull)
        assertEquals("example.com", webSearch.getValue("allowed_domains").jsonArray.single().jsonPrimitive.content)
        assertEquals("blocked.test", webSearch.getValue("blocked_domains").jsonArray.single().jsonPrimitive.content)
        assertEquals("Austin", webSearch.getValue("user_location").jsonObject.getValue("city").jsonPrimitive.content)

        val betaHeader = request.requestHeaders.headerValue("anthropic-beta").orEmpty()
        assertTrue(betaHeader.contains("computer-use-2024-10-22"), betaHeader)
        assertTrue(betaHeader.contains("computer-use-2025-11-24"), betaHeader)
        assertTrue(betaHeader.contains("computer-use-2025-01-24"), betaHeader)
    }

    @Test
    fun `anthropic-beta headers from caller and computed betas are unioned`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_beta",
                              "type":"message",
                              "role":"assistant",
                              "model":"claude-sonnet-4-5",
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":1,"output_tokens":1},
                              "content":[{"type":"text","text":"ok"}]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings {
                baseURL("https://anthropic.test/v1")
                apiKey("key")
                headers(mapOf("anthropic-beta" to "config-beta-1, config-beta-2"))
            },
        )

        provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                headers(mapOf("anthropic-beta" to "request-beta-1"))
                tools(
                    listOf(
                        LanguageModelTool(
                            name = "lookup",
                            description = "Lookup.",
                            parametersSchemaJson = """{"type":"object"}""",
                        )
                    )
                )
            }
        )

        val betaHeader = fixture.calls.single().requestHeaders.headerValue("anthropic-beta").orEmpty()
        assertTrue(betaHeader.contains("config-beta-1"), betaHeader)
        assertTrue(betaHeader.contains("config-beta-2"), betaHeader)
        assertTrue(betaHeader.contains("request-beta-1"), betaHeader)
        assertTrue(betaHeader.contains("structured-outputs-2025-11-13"), betaHeader)
    }

    @Test
    fun `speed and task budget set required betas without structured output beta for effort only`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_options",
                              "type":"message",
                              "role":"assistant",
                              "model":"claude-opus-4-8",
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":1,"output_tokens":1},
                              "content":[{"type":"text","text":"ok"}]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings {
                baseURL("https://anthropic.test/v1")
                apiKey("key")
            },
        )

        provider.messages(ModelId("claude-opus-4-8")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "anthropic" to buildJsonObject { put("speed", JsonPrimitive("fast")) },
                            )
                        )
                    )
                )
            }
        )
        provider.messages(ModelId("claude-opus-4-8")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "anthropic" to buildJsonObject {
                                    put(
                                        "taskBudget",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("tokens"))
                                            put("total", JsonPrimitive(400_000))
                                        }
                                    )
                                },
                            )
                        )
                    )
                )
            }
        )
        provider.messages(ModelId("claude-opus-4-8")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "anthropic" to buildJsonObject { put("effort", JsonPrimitive("medium")) },
                            )
                        )
                    )
                )
            }
        )

        val speedRequest = fixture.calls[0]
        assertEquals("fast", speedRequest.requestBodyJson.jsonObject["speed"]?.jsonPrimitive?.contentOrNull)
        assertTrue(speedRequest.requestHeaders.headerValue("anthropic-beta").orEmpty().contains("fast-mode-2026-02-01"))

        val taskBudgetRequest = fixture.calls[1]
        assertEquals(
            400_000,
            taskBudgetRequest.requestBodyJson.jsonObject["output_config"]?.jsonObject
                ?.get("task_budget")?.jsonObject
                ?.get("total")?.jsonPrimitive?.intOrNull,
        )
        val taskBudgetBeta = taskBudgetRequest.requestHeaders.headerValue("anthropic-beta").orEmpty()
        assertTrue(taskBudgetBeta.contains("task-budgets-2026-03-13"), taskBudgetBeta)
        assertTrue(!taskBudgetBeta.contains("structured-outputs-2025-11-13"), taskBudgetBeta)

        val effortRequest = fixture.calls[2]
        assertEquals(
            "medium",
            effortRequest.requestBodyJson.jsonObject["output_config"]?.jsonObject?.get(
                "effort"
            )?.jsonPrimitive?.contentOrNull
        )
        assertEquals(null, effortRequest.requestHeaders.headerValue("anthropic-beta"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
