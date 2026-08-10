@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.XAI_VERSION
import ai.torad.aisdk.providers.XSearch
import ai.torad.aisdk.providers.Xai
import ai.torad.aisdk.providers.XaiProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class XaiProviderTest {
    @Test
    fun `chat and responses route through xAI endpoints with headers options and citations`() = runTest {
        val fixture = TestServer.createTestServer(
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
        val provider = Xai(
            fixture.httpClient(),
            XaiProviderSettings {
                apiKey("key")
                headers(mapOf("X-Provider" to "provider"))
            },
        )

        val chat = provider.chat(ModelId("grok-3")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("Hello")))
                maxOutputTokens(128)
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
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
                            )
                        )
                    )
                )
                headers(mapOf("X-Request" to "request"))
            },
        )
        val responses = provider.responses(ModelId("grok-4")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("Hi")))
                providerOptions(ProviderOptions.Raw(JsonObject(mapOf("xai" to buildJsonObject {
                    put("reasoningEffort", JsonPrimitive("low"))
                }))))
            },
        )

        assertEquals("answer", chat.text)
        assertEquals("response text", responses.text)
        assertEquals("xai.chat", provider(ModelId("grok-3")).provider)
        assertEquals("xai.responses", provider.responses(ModelId("grok-4")).provider)
        assertEquals(mapOf("image/*" to listOf("^https?://.*$")), provider.chat(ModelId("grok-3")).supportedUrls)
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
        assertEquals(
            "example.com",
            search?.get(
                "sources"
            )?.jsonArray?.single()?.jsonObject?.get(
                "allowed_websites"
            )?.jsonArray?.single()?.jsonPrimitive?.contentOrNull
        )
    }

    @Test
    fun `chat usage treats xAI reasoning as additive and handles non inclusive cached tokens`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.x.ai/v1/chat/completions" to UrlHandler(
                    listOf(
                        UrlResponse.JsonValue(
                            Json.parseToJsonElement(
                                """
                                {
                                  "id":"chat-usage-1",
                                  "created":1780000000,
                                  "model":"grok-3-mini",
                                  "choices":[{"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}],
                                  "usage":{
                                    "prompt_tokens":4142,
                                    "completion_tokens":254,
                                    "total_tokens":8724,
                                    "prompt_tokens_details":{"cached_tokens":4328},
                                    "completion_tokens_details":{"reasoning_tokens":10}
                                  }
                                }
                                """.trimIndent(),
                            ),
                        ),
                        UrlResponse.StreamChunks(
                            listOf(
                                """
                                data: {"id":"chat-usage-2","choices":[{"delta":{"content":"answer"}}]}

                                data: {"id":"chat-usage-2","choices":[{"finish_reason":"stop"}],"usage":{"prompt_tokens":4142,"completion_tokens":254,"total_tokens":8724,"prompt_tokens_details":{"cached_tokens":4328},"completion_tokens_details":{"reasoning_tokens":10}}}

                                data: [DONE]

                                """.trimIndent(),
                            ),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Xai(fixture.httpClient(), XaiProviderSettings { apiKey("key") })

        val generated = provider.chat(ModelId("grok-3-mini")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
            },
        )
        val streamedFinish = drainAllItems(
            provider.chat(ModelId("grok-3-mini")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        ).filterIsInstance<StreamEvent.Finish>().single()

        listOf(generated.usage, streamedFinish.usage).forEach { usage ->
            assertEquals(8470, usage.inputTokens.total)
            assertEquals(4142, usage.inputTokens.noCache)
            assertEquals(4328, usage.inputTokens.cacheRead)
            assertEquals(264, usage.outputTokens.total)
            assertEquals(254, usage.outputTokens.text)
            assertEquals(10, usage.outputTokens.reasoning)
        }
    }

    @Test
    fun `chat stream surfaces citations as source events`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.x.ai/v1/chat/completions" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"id":"chat-cite","choices":[{"delta":{"content":"hello"}}]}

                            data: {"id":"chat-cite","choices":[{"finish_reason":"stop"}],"citations":["https://example.com/source1","https://example.com/source2"]}

                            data: [DONE]

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Xai(fixture.httpClient(), XaiProviderSettings { apiKey("key") })

        val events = drainAllItems(
            provider.chat(ModelId("grok-3")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        val sources = events.filterIsInstance<StreamEvent.SourcePart>()
        assertEquals(2, sources.size)
        assertEquals(StreamEvent.SourcePart.SourceType.Url, sources[0].sourceType)
        assertEquals("https://example.com/source1", sources[0].url)
        assertEquals("https://example.com/source2", sources[1].url)
    }

    @Test
    fun `chat streamResult surfaces citations as source events`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.x.ai/v1/chat/completions" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"id":"chat-cite","choices":[{"delta":{"content":"hello"}}]}

                            data: {"id":"chat-cite","choices":[{"finish_reason":"stop"}],"citations":["https://example.com/source1"]}

                            data: [DONE]

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Xai(fixture.httpClient(), XaiProviderSettings { apiKey("key") })

        val events = drainAllItems(
            provider.chat(ModelId("grok-3")).streamResult(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            ).stream
        )

        val source = events.filterIsInstance<StreamEvent.SourcePart>().single()
        assertEquals("https://example.com/source1", source.url)
    }

    @Test
    fun `chat stream requests include usage stream option`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.x.ai/v1/chat/completions" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"id":"chat-1","choices":[{"delta":{"content":"hello"}}]}

                            data: {"id":"chat-1","choices":[{"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}

                            data: [DONE]

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Xai(fixture.httpClient(), XaiProviderSettings { apiKey("key") })

        drainAllItems(
            provider.chat(ModelId("grok-3")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        val bodyText = fixture.calls.single().requestBodyText
        assertTrue(bodyText.contains(""""stream_options":{"include_usage":true}"""))
        val body = fixture.calls.single().requestBodyJson.jsonObject
        assertEquals(true, body["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    @Suppress("LongMethod")
    fun `chat body keeps stop strips additionalProperties and maps xHandles alias`() = runTest {
        val fixture = TestServer.createTestServer(
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
        val provider = Xai(fixture.httpClient(), XaiProviderSettings { apiKey("key") })
        provider.chat(ModelId("grok-3")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("go")))
                stopSequences(listOf("END"))
                tools(
                    listOf(
                        LanguageModelTool(
                            "lookup",
                            "d",
                            xaiUnsupportedToolSchema(),
                        ),
                    )
                )
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
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
                            )
                        )
                    )
                )
            },
        )
        val body = fixture.calls.single().requestBodyJson.jsonObject
        // stop is documented as supported on non-reasoning models — forward it.
        assertEquals("END", body["stop"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
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
    fun `chat throws APICallError when xAI returns 200 error body`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.x.ai/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "code":"The service is currently unavailable",
                              "error":"Timed out waiting for first token"
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Xai(fixture.httpClient(), XaiProviderSettings { apiKey("key") })

        val error = assertFailsWith<APICallError> {
            provider.chat(ModelId("grok-3")).generate(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        }

        assertEquals("Timed out waiting for first token", error.message)
        assertEquals(200, error.statusCode)
        assertEquals(true, error.isRetryable)
        assertTrue(error.responseBody.orEmpty().contains("Timed out waiting for first token"))
    }

    @Test
    fun `image model supports generation edits options metadata and warnings`() = runTest {
        val fixture = TestServer.createTestServer(
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
        val provider = Xai(
            fixture.httpClient(),
            XaiProviderSettings {
                baseURL("https://xai.test/v1")
                apiKey("key")
            },
        )
        val model = provider.image(ModelId("grok-imagine-image"))

        val generated = model.generate(
            ImageGenerationParams {
                prompt("A cute baby sea otter")
                n(1)
                size("1024x1024")
                seed(42)
                aspectRatio("16:9")
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "xai" to buildJsonObject {
                                    put("output_format", JsonPrimitive("jpeg"))
                                    put("sync_mode", JsonPrimitive(true))
                                    put("resolution", JsonPrimitive("2k"))
                                    put("quality", JsonPrimitive("high"))
                                    put("user", JsonPrimitive("user-1"))
                                },
                            )
                        )
                    )
                )
            },
        )
        val edited = model.generate(
            ImageGenerationParams {
                prompt("combine")
                files(
                    listOf(
                        ImageGenerationFile(url = "https://example.com/input.png"),
                        ImageGenerationFile(mediaType = "image/png", base64 = "iVBORw=="),
                    )
                )
                mask(ImageGenerationFile(mediaType = "image/png", base64 = "mask"))
            },
        )

        assertEquals("xai.image", model.provider)
        assertEquals("base64-image", generated.images.single().base64)
        assertEquals("edited-image", edited.images.single().base64)
        assertTrue(generated.warnings.any { it.message.orEmpty().contains("size") })
        assertTrue(generated.warnings.any { it.message.orEmpty().contains("seed") })
        assertTrue(edited.warnings.any { it.message.orEmpty().contains("mask") })
        val metadata = generated.providerMetadata.toMap()["xai"]?.jsonObject
        assertEquals(
            "revised",
            metadata?.get("images")?.jsonArray?.single()?.jsonObject?.get("revisedPrompt")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(12, metadata?.get("costInUsdTicks")?.jsonPrimitive?.intOrNull)

        val generateBody = fixture.calls[0].requestBodyJson.jsonObject
        assertEquals("16:9", generateBody["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
        // Documented field is response_format (set to b64_json); output_format is off-schema.
        assertEquals("b64_json", generateBody["response_format"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, generateBody["output_format"])
        // sync_mode / quality are not in /v1/images/generations schema — dropped on the wire.
        assertEquals(null, generateBody["sync_mode"])
        assertEquals("2k", generateBody["resolution"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, generateBody["quality"])
        assertEquals("user-1", generateBody["user"]?.jsonPrimitive?.contentOrNull)

        val editBody = fixture.calls[1].requestBodyJson.jsonObject
        val images = editBody["images"]?.jsonArray.orEmpty()
        assertEquals("https://example.com/input.png", images[0].jsonObject["url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/png;base64,iVBORw==", images[1].jsonObject["url"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `inline base64 images use response mime type and preserve request filtering`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://xai.test/v1/images/generations" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "data":[
                                {"b64_json":"jpeg-image","mime_type":"image/jpeg"},
                                {"b64_json":"webp-image","mime_type":"image/webp"},
                                {"b64_json":"legacy-image"},
                                {"b64_json":"null-mime-image","mime_type":null},
                                {"b64_json":"blank-mime-image","mime_type":"   "},
                                {"b64_json":"spaced-image","mime_type":" image/jpeg "}
                              ]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Xai(
            fixture.httpClient(),
            XaiProviderSettings {
                baseURL("https://xai.test/v1")
                apiKey("key")
            },
        )

        val result = provider.image(ModelId("grok-imagine-image")).generate(
            ImageGenerationParams {
                prompt("Generate images")
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "xai" to buildJsonObject {
                                    put("output_format", JsonPrimitive("jpeg"))
                                    put("outputFormat", JsonPrimitive("webp"))
                                    put("sync_mode", JsonPrimitive(true))
                                    put("syncMode", JsonPrimitive(true))
                                    put("quality", JsonPrimitive("high"))
                                },
                            )
                        )
                    )
                )
            },
        )

        assertEquals(
            listOf("image/jpeg", "image/webp", "image/png", "image/png", "image/png", " image/jpeg "),
            result.images.map { it.mediaType },
        )
        assertEquals(
            listOf(
                "jpeg-image",
                "webp-image",
                "legacy-image",
                "null-mime-image",
                "blank-mime-image",
                "spaced-image",
            ),
            result.images.map { it.base64 },
        )
        val requestBody = fixture.calls.single().requestBodyJson.jsonObject
        assertEquals("b64_json", requestBody["response_format"]?.jsonPrimitive?.contentOrNull)
        listOf("output_format", "outputFormat", "sync_mode", "syncMode", "quality").forEach { field ->
            assertEquals(null, requestBody[field], "$field must remain off the xAI image request wire")
        }
    }

    @Test
    fun `video model submits polls maps modes warnings and metadata`() = runTest {
        val fixture = TestServer.createTestServer(
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
        val provider = Xai(
            fixture.httpClient(),
            XaiProviderSettings {
                baseURL("https://xai.test/v1")
                apiKey("key")
            },
        )
        val model = provider.video(ModelId("grok-imagine-video"))

        val generated = model.generate(
            VideoGenerationParams {
                prompt("A chicken flying into the sunset")
                n(2)
                image(GeneratedFile(mediaType = "image/png", base64 = "", url = "https://example.com/ref.png"))
                durationSeconds(5f)
                aspectRatio("16:9")
                resolution("1280x720")
                fps(24)
                seed(7)
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "xai" to buildJsonObject {
                                    put("pollIntervalMs", JsonPrimitive(0))
                                    put("pollTimeoutMs", JsonPrimitive(1))
                                    put("custom_option", JsonPrimitive("kept"))
                                }
                            )
                        )
                    )
                )
            },
        )
        val edited = model.generate(
            VideoGenerationParams {
                prompt("edit")
                durationSeconds(3f)
                aspectRatio("1:1")
                resolution("1280x720")
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "xai" to buildJsonObject {
                                    put("mode", JsonPrimitive("edit-video"))
                                    put("videoUrl", JsonPrimitive("https://example.com/source.mp4"))
                                    put("pollIntervalMs", JsonPrimitive(0))
                                    put("pollTimeoutMs", JsonPrimitive(1))
                                }
                            )
                        )
                    )
                )
            },
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
        assertEquals(
            "https://example.com/ref.png",
            generateBody["image"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        )
        assertEquals("kept", generateBody["custom_option"]?.jsonPrimitive?.contentOrNull)
        val editBody = fixture.calls[2].requestBodyJson.jsonObject
        assertEquals(
            "https://example.com/source.mp4",
            editBody["video"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(null, editBody["duration"])
        assertEquals(null, editBody["aspect_ratio"])
        assertEquals(
            "req-1",
            generated.providerMetadata.toMap()["xai"]?.jsonObject?.get("requestId")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(
            9,
            generated.providerMetadata.toMap()["xai"]?.jsonObject?.get("costInUsdTicks")?.jsonPrimitive?.intOrNull
        )
    }

    @Test
    fun `video poll treats expired as terminal and stops polling`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://xai.test/v1/videos/generations" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"request_id":"req-1"}""")),
                ),
                "https://xai.test/v1/videos/req-1" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"status":"expired"}""")),
                ),
            ),
        )
        fixture.server.start()
        val provider = Xai(
            fixture.httpClient(),
            XaiProviderSettings {
                baseURL("https://xai.test/v1")
                apiKey("key")
            },
        )

        val error = assertFailsWith<NoVideoGeneratedError> {
            provider.video(ModelId("grok-imagine-video")).generate(
                VideoGenerationParams {
                    prompt("A chicken flying into the sunset")
                    providerOptions(
                        ProviderOptions.Raw(
                            JsonObject(
                                mapOf(
                                    "xai" to buildJsonObject {
                                        put("pollIntervalMs", JsonPrimitive(0))
                                        // Room for 1000 attempts: if `expired` were not terminal
                                        // the loop would keep polling instead of failing fast.
                                        put("pollTimeoutMs", JsonPrimitive(1000))
                                    }
                                )
                            )
                        )
                    )
                },
            )
        }

        assertTrue(error.message.orEmpty().contains("expired"), "error must name the terminal status")
        assertEquals(
            listOf("https://xai.test/v1/videos/generations", "https://xai.test/v1/videos/req-1"),
            fixture.calls.map { it.requestUrl },
            "polling must stop on the first expired response",
        )
    }

    @Test
    fun `video request maps 1920x1080 to the provider 1080p spelling`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://xai.test/v1/videos/generations" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"request_id":"req-1"}""")),
                ),
                "https://xai.test/v1/videos/req-1" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"status":"done","video":{"url":"https://cdn.example/video.mp4"}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Xai(
            fixture.httpClient(),
            XaiProviderSettings {
                baseURL("https://xai.test/v1")
                apiKey("key")
            },
        )

        provider.video(ModelId("grok-imagine-video")).generate(
            VideoGenerationParams {
                prompt("A chicken flying into the sunset")
                resolution("1920x1080")
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "xai" to buildJsonObject {
                                    put("pollIntervalMs", JsonPrimitive(0))
                                    put("pollTimeoutMs", JsonPrimitive(1))
                                }
                            )
                        )
                    )
                )
            },
        )

        assertEquals(
            "1080p",
            fixture.calls.first().requestBodyJson.jsonObject["resolution"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `tools unsupported embeddings and default singleton match provider surface`() {
        val provider = Xai(
            TestServer.createTestServer(mutableMapOf()).httpClient(),
            XaiProviderSettings { apiKey("key") },
        )

        assertProviderTool(provider.tools.codeExecution, "code_interpreter", "xai.code_interpreter")
        assertProviderTool(provider.tools.fileSearch, "file_search", "xai.file_search")
        assertProviderTool(provider.tools.mcpServer, "mcp", "xai.mcp")
        assertProviderTool(provider.tools.viewImage, "view_image", "xai.view_image")
        assertProviderTool(provider.tools.viewXVideo, "view_x_video", "xai.view_x_video")
        assertProviderTool(provider.tools.webSearch, "web_search", "xai.web_search")
        assertProviderTool(provider.tools.xSearch, "x_search", "xai.x_search")
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.textEmbeddingModel("embed") }
    }

    @Test
    fun `responses path keeps args of xAI provider tools the mapping does not model`() = runTest {
        // x_search / view_image / view_x_video are vended by this SDK but have no field-by-field
        // branch in the Open Responses mapping. Passing the type through without the caller's args
        // silently drops constraints like allowed_x_handles: the request still succeeds and the
        // model searches everything the caller asked it not to.
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.x.ai/v1/responses" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"resp-1",
                              "created_at":1780000001,
                              "model":"grok-4",
                              "output":[
                                {"type":"message","id":"msg-1","role":"assistant","content":[{"type":"output_text","text":"ok"}]}
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
        val provider = Xai(fixture.httpClient(), XaiProviderSettings { apiKey("key") })
        val xSearch = XSearch(
            buildJsonObject {
                put("allowed_x_handles", buildJsonArray { add(JsonPrimitive("grok")) })
            },
        )
        provider.responses(ModelId("grok-4")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("Hi")))
                tools(ToolSet<Any?>(mapOf(xSearch.name to xSearch)).descriptors)
            },
        )

        val tool = fixture.calls.single().requestBodyJson.jsonObject["tools"]?.jsonArray?.single()?.jsonObject
        assertEquals("x_search", tool?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals(
            listOf("grok"),
            tool?.get("allowed_x_handles")?.jsonArray?.map { it.jsonPrimitive.content },
            "x_search args must reach the wire, not be dropped: $tool",
        )
    }

    private fun assertProviderTool(tool: Tool<JsonElement, JsonElement, Any?>, name: String, providerToolId: String) {
        assertEquals(name, tool.name)
        assertEquals(true, tool.providerExecuted)
        assertEquals(JsonPrimitive(providerToolId), tool.metadata["providerToolId"])
        assertIs<JsonElement>(tool.metadata["providerOptions"])
    }

    private fun xaiUnsupportedToolSchema(): String = """
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
    """.trimIndent()

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
