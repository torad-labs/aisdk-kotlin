package ai.torad.aisdk
import ai.torad.aisdk.providers.XAI_VERSION
import ai.torad.aisdk.providers.XaiProviderSettings
import ai.torad.aisdk.providers.createXai
import ai.torad.aisdk.providers.xai

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XaiProviderTest {
    @Test
    fun `chat and responses route through xAI endpoints with headers options and citations`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.x.ai/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"chat-1",
                              "created":1780000000,
                              "model":"grok-3",
                              "choices":[{"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}],
                              "usage":{
                                "prompt_tokens":12,
                                "completion_tokens":1,
                                "prompt_tokens_details":{"cached_tokens":2},
                                "completion_tokens_details":{"reasoning_tokens":3}
                              },
                              "citations":["https://example.com/a","https://example.com/b"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://api.x.ai/v1/responses" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"resp-1",
                              "created_at":1780000001,
                              "model":"grok-4",
                              "output":[
                                {"type":"message","id":"msg-1","role":"assistant","content":[{"type":"output_text","text":"response text"}]}
                              ],
                              "usage":{"input_tokens":3,"output_tokens":4}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createXai(
            fixture.httpClient(),
            XaiProviderSettings(apiKey = "key", headers = mapOf("X-Provider" to "provider")),
        )

        val chat = provider.chat("grok-3").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("Hello")),
                maxOutputTokens = 128,
                providerOptions = mapOf(
                    "xai" to buildJsonObject {
                        put("topLogprobs", JsonPrimitive(3))
                        put(
                            "searchParameters",
                            buildJsonObject {
                                put("mode", JsonPrimitive("on"))
                                put("returnCitations", JsonPrimitive(true))
                                put("maxSearchResults", JsonPrimitive(10))
                                put(
                                    "sources",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", JsonPrimitive("web"))
                                                put("allowedWebsites", buildJsonArray { add(JsonPrimitive("example.com")) })
                                                put("safeSearch", JsonPrimitive(false))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                ),
                headers = mapOf("X-Request" to "request"),
            ),
        )
        val responses = provider.responses("grok-4").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("Hi")),
                providerOptions = mapOf("xai" to buildJsonObject { put("reasoningEffort", JsonPrimitive("low")) }),
            ),
        )

        assertEquals("answer", chat.text)
        assertEquals("response text", responses.text)
        assertEquals("xai.chat", provider("grok-3").provider)
        assertEquals("xai.responses", provider.responses("grok-4").provider)
        assertEquals(mapOf("image/*" to listOf("^https?://.*$")), provider.chat("grok-3").supportedUrls)
        assertEquals(2, chat.content.filterIsInstance<ContentPart.Source>().size)
        assertEquals(3, chat.usage.outputTokens.reasoning)

        val chatCall = fixture.calls[0]
        assertEquals("Bearer key", chatCall.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("provider", chatCall.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", chatCall.requestHeaders.headerValue("X-Request"))
        assertTrue(chatCall.requestUserAgent.orEmpty().contains("ai-sdk/xai/$XAI_VERSION"))
        val chatBody = chatCall.requestBodyJson.jsonObject
        assertEquals("grok-3", chatBody["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(128, chatBody["max_completion_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, chatBody["logprobs"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(3, chatBody["top_logprobs"]?.jsonPrimitive?.intOrNull)
        val search = chatBody["search_parameters"]?.jsonObject
        assertEquals(true, search?.get("return_citations")?.jsonPrimitive?.booleanOrNull)
        assertEquals(10, search?.get("max_search_results")?.jsonPrimitive?.intOrNull)
        assertEquals("example.com", search?.get("sources")?.jsonArray?.single()?.jsonObject?.get("allowed_websites")?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `chat body drops stop and strips additionalProperties and maps xHandles alias`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.x.ai/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            "{\"id\":\"c\",\"choices\":[{\"message\":{\"role\":\"assistant\"," +
                                "\"content\":\"ok\"},\"finish_reason\":\"stop\"}]," +
                                "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createXai(fixture.httpClient(), XaiProviderSettings(apiKey = "key"))
        provider.chat("grok-3").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("go")),
                stopSequences = listOf("END"),
                tools = listOf(
                    LanguageModelTool(
                        "lookup",
                        "d",
                        """
                        {
                          "${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
                          "title":"LookupInput",
                          "type":"object",
                          "additionalProperties":false,
                          "properties":{
                            "q":{
                              "title":"Query",
                              "type":"string",
                              "additionalProperties":false
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
                ),
                providerOptions = mapOf(
                    "xai" to buildJsonObject {
                        put(
                            "searchParameters",
                            buildJsonObject {
                                put(
                                    "sources",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", JsonPrimitive("x"))
                                                put("xHandles", buildJsonArray { add(JsonPrimitive("grok")) })
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                ),
            ),
        )
        val body = fixture.calls.single().requestBodyJson.jsonObject
        assertEquals(null, body["stop"], "stop dropped (xAI unsupported)")
        val toolFn = body["tools"]?.jsonArray?.single()?.jsonObject?.get("function")?.jsonObject
        val toolParams = toolFn?.get("parameters")?.jsonObject
        assertEquals(null, toolParams?.get("additionalProperties"), "additionalProperties stripped from tool schema")
        assertEquals(null, toolParams?.get("\$schema"), "\$schema stripped from tool schema")
        assertEquals(null, toolParams?.get("title"), "title stripped from tool schema")
        val nested = toolParams?.get("properties")?.jsonObject?.get("q")?.jsonObject
        assertEquals(null, nested?.get("additionalProperties"), "nested additionalProperties stripped from tool schema")
        assertEquals(null, nested?.get("title"), "nested title stripped from tool schema")
        val src = body["search_parameters"]?.jsonObject?.get("sources")?.jsonArray?.single()?.jsonObject
        assertEquals("grok", src?.get("included_x_handles")?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
        assertEquals(null, src?.get("x_handles"), "xHandles not naively snake-cased")
    }

    @Test
    fun `image model supports generation edits options metadata and warnings`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://xai.test/v1/images/generations" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"data":[{"b64_json":"base64-image","revised_prompt":"revised"}],"usage":{"cost_in_usd_ticks":12}}""",
                        ),
                    ),
                ),
                "https://xai.test/v1/images/edits" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"data":[{"b64_json":"edited-image"}]}""")),
                ),
            ),
        )
        fixture.server.start()
        val provider = createXai(fixture.httpClient(), XaiProviderSettings(baseURL = "https://xai.test/v1", apiKey = "key"))
        val model = provider.image("grok-imagine-image")

        val generated = model.generate(
            ImageGenerationParams(
                prompt = "A cute baby sea otter",
                n = 1,
                size = "1024x1024",
                seed = 42,
                aspectRatio = "16:9",
                providerOptions = mapOf(
                    "xai" to buildJsonObject {
                        put("output_format", JsonPrimitive("jpeg"))
                        put("sync_mode", JsonPrimitive(true))
                        put("resolution", JsonPrimitive("2k"))
                        put("quality", JsonPrimitive("high"))
                        put("user", JsonPrimitive("user-1"))
                    },
                ),
            ),
        )
        val edited = model.generate(
            ImageGenerationParams(
                prompt = "combine",
                files = listOf(
                    ImageGenerationFile(url = "https://example.com/input.png"),
                    ImageGenerationFile(mediaType = "image/png", base64 = "iVBORw=="),
                ),
                mask = ImageGenerationFile(mediaType = "image/png", base64 = "mask"),
            ),
        )

        assertEquals("xai.image", model.provider)
        assertEquals("base64-image", generated.images.single().base64)
        assertEquals("edited-image", edited.images.single().base64)
        assertTrue(generated.warnings.any { it.message.orEmpty().contains("size") })
        assertTrue(generated.warnings.any { it.message.orEmpty().contains("seed") })
        assertTrue(edited.warnings.any { it.message.orEmpty().contains("mask") })
        val metadata = generated.providerMetadata["xai"]?.jsonObject
        assertEquals("revised", metadata?.get("images")?.jsonArray?.single()?.jsonObject?.get("revisedPrompt")?.jsonPrimitive?.contentOrNull)
        assertEquals(12, metadata?.get("costInUsdTicks")?.jsonPrimitive?.intOrNull)

        val generateBody = fixture.calls[0].requestBodyJson.jsonObject
        assertEquals("16:9", generateBody["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
        assertEquals("jpeg", generateBody["output_format"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, generateBody["sync_mode"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("2k", generateBody["resolution"]?.jsonPrimitive?.contentOrNull)
        assertEquals("high", generateBody["quality"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user-1", generateBody["user"]?.jsonPrimitive?.contentOrNull)

        val editBody = fixture.calls[1].requestBodyJson.jsonObject
        val images = editBody["images"]?.jsonArray.orEmpty()
        assertEquals("https://example.com/input.png", images[0].jsonObject["url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/png;base64,iVBORw==", images[1].jsonObject["url"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `video model submits polls maps modes warnings and metadata`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://xai.test/v1/videos/generations" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"request_id":"req-1"}""")),
                ),
                "https://xai.test/v1/videos/edits" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"request_id":"req-2"}""")),
                ),
                "https://xai.test/v1/videos/req-1" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"status":"done","video":{"url":"https://cdn.example/video.mp4","duration":5,"respect_moderation":true},"progress":100,"usage":{"cost_in_usd_ticks":9}}""",
                        ),
                    ),
                ),
                "https://xai.test/v1/videos/req-2" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"status":"done","video":{"url":"https://cdn.example/edit.mp4","respect_moderation":true}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createXai(fixture.httpClient(), XaiProviderSettings(baseURL = "https://xai.test/v1", apiKey = "key"))
        val model = provider.video("grok-imagine-video")

        val generated = model.generate(
            VideoGenerationParams(
                prompt = "A chicken flying into the sunset",
                n = 2,
                image = GeneratedFile(mediaType = "image/png", base64 = "", url = "https://example.com/ref.png"),
                durationSeconds = 5f,
                aspectRatio = "16:9",
                resolution = "1280x720",
                fps = 24,
                seed = 7,
                providerOptions = mapOf("xai" to buildJsonObject {
                    put("pollIntervalMs", JsonPrimitive(0))
                    put("pollTimeoutMs", JsonPrimitive(1))
                    put("custom_option", JsonPrimitive("kept"))
                }),
            ),
        )
        val edited = model.generate(
            VideoGenerationParams(
                prompt = "edit",
                durationSeconds = 3f,
                aspectRatio = "1:1",
                resolution = "1280x720",
                providerOptions = mapOf("xai" to buildJsonObject {
                    put("mode", JsonPrimitive("edit-video"))
                    put("videoUrl", JsonPrimitive("https://example.com/source.mp4"))
                    put("pollIntervalMs", JsonPrimitive(0))
                    put("pollTimeoutMs", JsonPrimitive(1))
                }),
            ),
        )

        assertEquals("xai.video", model.provider)
        assertEquals("https://cdn.example/video.mp4", generated.videos.single().url)
        assertEquals("https://cdn.example/edit.mp4", edited.videos.single().url)
        assertTrue(generated.warnings.any { it.message.orEmpty().contains("FPS") })
        assertTrue(generated.warnings.any { it.message.orEmpty().contains("multiple videos") })
        assertTrue(edited.warnings.any { it.message.orEmpty().contains("duration") })
        assertTrue(edited.warnings.any { it.message.orEmpty().contains("aspect ratio") })

        val generateBody = fixture.calls[0].requestBodyJson.jsonObject
        assertEquals("720p", generateBody["resolution"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/ref.png", generateBody["image"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull)
        assertEquals("kept", generateBody["custom_option"]?.jsonPrimitive?.contentOrNull)
        val editBody = fixture.calls[2].requestBodyJson.jsonObject
        assertEquals("https://example.com/source.mp4", editBody["video"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull)
        assertEquals(null, editBody["duration"])
        assertEquals(null, editBody["aspect_ratio"])
        assertEquals("req-1", generated.providerMetadata["xai"]?.jsonObject?.get("requestId")?.jsonPrimitive?.contentOrNull)
        assertEquals(9, generated.providerMetadata["xai"]?.jsonObject?.get("costInUsdTicks")?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `tools unsupported embeddings and default singleton match provider surface`() {
        val provider = createXai(
            createTestServer(mutableMapOf()).httpClient(),
            XaiProviderSettings(apiKey = "key"),
        )

        assertProviderTool(provider.tools.codeExecution, "code_execution", "xai.code_execution")
        assertProviderTool(provider.tools.fileSearch, "file_search", "xai.file_search")
        assertProviderTool(provider.tools.mcpServer, "mcp", "xai.mcp")
        assertProviderTool(provider.tools.viewImage, "view_image", "xai.view_image")
        assertProviderTool(provider.tools.viewXVideo, "view_x_video", "xai.view_x_video")
        assertProviderTool(provider.tools.webSearch, "web_search", "xai.web_search")
        assertProviderTool(provider.tools.xSearch, "x_search", "xai.x_search")
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.textEmbeddingModel("embed") }
        val error = assertFailsWith<AiSdkException> { xai.chat("grok-3") }
        assertNotNull(error.message)
        assertTrue(error.message.orEmpty().contains("createXai"))
    }

    private fun assertProviderTool(tool: Tool<JsonElement, JsonElement, Any?>, name: String, providerToolId: String) {
        assertEquals(name, tool.name)
        assertEquals(true, tool.providerExecuted)
        assertEquals(JsonPrimitive(providerToolId), tool.metadata["providerToolId"])
        assertIs<JsonElement>(tool.metadata["providerOptions"])
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
