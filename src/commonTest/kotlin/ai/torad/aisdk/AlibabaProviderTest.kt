package ai.torad.aisdk
import ai.torad.aisdk.providers.ALIBABA_VERSION
import ai.torad.aisdk.providers.AlibabaProviderSettings

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ai.torad.aisdk.providers.Alibaba

class AlibabaProviderTest {
    @Test
    fun `chat model maps Alibaba provider options and usage details`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://alibaba.test/compatible-mode/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"chat-1",
                              "created":1780000000,
                              "model":"qwen-plus",
                              "choices":[
                                {
                                  "message":{
                                    "role":"assistant",
                                    "content":"The answer is Paris.",
                                    "reasoning_content":"I checked the cached context.",
                                    "tool_calls":[
                                      {
                                        "id":"call-1",
                                        "type":"function",
                                        "function":{"name":"lookup","arguments":"{\"city\":\"Paris\"}"}
                                      }
                                    ]
                                  },
                                  "finish_reason":"tool_calls"
                                }
                              ],
                              "usage":{
                                "prompt_tokens":100,
                                "completion_tokens":50,
                                "total_tokens":150,
                                "prompt_tokens_details":{"cached_tokens":80,"cache_creation_input_tokens":20},
                                "completion_tokens_details":{"reasoning_tokens":10}
                              }
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Alibaba(
            fixture.httpClient(),
            AlibabaProviderSettings(
                apiKey = "key",
                baseURL = "https://alibaba.test/compatible-mode/v1",
                headers = mapOf("X-Provider" to "provider"),
            ),
        )

        val result = provider.chatModel("qwen-plus").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("Hello")),
                tools = listOf(
                    LanguageModelTool("lookup", "Lookup city details.", """{"type":"object","properties":{"city":{"type":"string"}}}"""),
                ),
                toolChoice = ToolChoice.Required,
                providerOptions = mapOf(
                    "alibaba" to buildJsonObject {
                        put("enableThinking", JsonPrimitive(true))
                        put("thinkingBudget", JsonPrimitive(2048))
                        put("parallelToolCalls", JsonPrimitive(false))
                    },
                ),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("alibaba.chat", provider("qwen-plus").provider)
        assertEquals("The answer is Paris.", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals("I checked the cached context.", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertEquals("lookup", result.toolCalls.single().toolName)
        assertEquals("Paris", result.toolCalls.single().input.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        assertEquals(100, result.usage.promptTokens)
        assertEquals(80, result.usage.inputTokens.cacheRead)
        assertEquals(20, result.usage.inputTokens.cacheWrite)
        assertEquals(0, result.usage.inputTokens.noCache)
        assertEquals(50, result.usage.completionTokens)
        assertEquals(10, result.usage.outputTokens.reasoning)
        assertEquals(40, result.usage.outputTokens.text)

        val request = fixture.calls.single()
        assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/alibaba/$ALIBABA_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("qwen-plus", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["enable_thinking"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(2048, body["thinking_budget"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.contentOrNull)
        assertEquals("lookup", body["tools"]?.jsonArray?.single()?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `video model submits DashScope task polls and maps metadata`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://dash.test/api/v1/services/aigc/video-generation/video-synthesis" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"output":{"task_status":"PENDING","task_id":"task-1"},"request_id":"create-1"}""")),
                ),
                "https://dash.test/api/v1/tasks/task-1" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "output":{
                                "task_id":"task-1",
                                "task_status":"SUCCEEDED",
                                "video_url":"https://cdn.alibaba.test/video.mp4",
                                "actual_prompt":"expanded prompt"
                              },
                              "usage":{"duration":5,"output_video_duration":4,"SR":720,"size":"1280*720"},
                              "request_id":"poll-1"
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = Alibaba(
            fixture.httpClient(),
            AlibabaProviderSettings(apiKey = "key", videoBaseURL = "https://dash.test"),
        ).video("wan2.6-i2v")

        val result = model.generate(
            VideoGenerationParams(
                prompt = "Animate this",
                image = GeneratedFile(mediaType = "image/png", base64 = "", url = "https://example.com/start.png"),
                durationSeconds = 5f,
                seed = 12,
                resolution = "1280x720",
                aspectRatio = "16:9",
                fps = 24,
                n = 2,
                providerOptions = mapOf(
                    "alibaba" to buildJsonObject {
                        put("pollIntervalMs", JsonPrimitive(0))
                        put("pollTimeoutMs", JsonPrimitive(1_000))
                        put("negativePrompt", JsonPrimitive("blur"))
                        put("audioUrl", JsonPrimitive("https://example.com/audio.mp3"))
                        put("promptExtend", JsonPrimitive(true))
                        put("shotType", JsonPrimitive("single"))
                        put("watermark", JsonPrimitive(false))
                        put("audio", JsonPrimitive(true))
                        put("custom_parameter", JsonPrimitive("kept"))
                    },
                ),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("alibaba.video", model.provider)
        assertEquals("video/mp4", result.videos.single().mediaType)
        assertEquals("https://cdn.alibaba.test/video.mp4", result.videos.single().url)
        assertTrue(result.warnings.any { it.message.orEmpty().contains("explicit size") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("FPS") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("1 video") })
        val metadata = result.providerMetadata["alibaba"]?.jsonObject
        assertEquals("task-1", metadata?.get("taskId")?.jsonPrimitive?.contentOrNull)
        assertEquals("expanded prompt", metadata?.get("actualPrompt")?.jsonPrimitive?.contentOrNull)
        assertEquals(4, metadata?.get("usage")?.jsonObject?.get("outputVideoDuration")?.jsonPrimitive?.intOrNull)

        val create = fixture.calls[0]
        assertEquals("POST", create.requestMethod)
        assertEquals("Bearer key", create.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("enable", create.requestHeaders.headerValue("X-DashScope-Async"))
        assertEquals("request", create.requestHeaders.headerValue("X-Request"))
        assertTrue(create.requestUserAgent.orEmpty().contains("ai-sdk/alibaba/$ALIBABA_VERSION"))
        val body = create.requestBodyJson.jsonObject
        val input = body["input"]?.jsonObject
        val parameters = body["parameters"]?.jsonObject
        assertEquals("wan2.6-i2v", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Animate this", input?.get("prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/start.png", input?.get("img_url")?.jsonPrimitive?.contentOrNull)
        assertEquals("blur", input?.get("negative_prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/audio.mp3", input?.get("audio_url")?.jsonPrimitive?.contentOrNull)
        assertEquals(5f, parameters?.get("duration")?.jsonPrimitive?.floatOrNull)
        assertEquals(12, parameters?.get("seed")?.jsonPrimitive?.intOrNull)
        assertEquals("720P", parameters?.get("resolution")?.jsonPrimitive?.contentOrNull)
        assertEquals(true, parameters?.get("prompt_extend")?.jsonPrimitive?.booleanOrNull)
        assertEquals("single", parameters?.get("shot_type")?.jsonPrimitive?.contentOrNull)
        assertEquals(false, parameters?.get("watermark")?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, parameters?.get("audio")?.jsonPrimitive?.booleanOrNull)
        assertEquals("kept", parameters?.get("custom_parameter")?.jsonPrimitive?.contentOrNull)
        assertEquals("GET", fixture.calls[1].requestMethod)
        assertEquals("https://dash.test/api/v1/tasks/task-1", fixture.calls[1].requestUrl)
    }

    @Test
    fun `embedding model posts DashScope native request and maps sorted embeddings and usage`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://alibaba.test/api/v1/services/embeddings/text-embedding/text-embedding" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "output":{
                                "embeddings":[
                                  {"text_index":1,"embedding":[0.4,0.5]},
                                  {"text_index":0,"embedding":[0.1,0.2,0.3]}
                                ]
                              },
                              "usage":{"total_tokens":7}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Alibaba(
            fixture.httpClient(),
            AlibabaProviderSettings(apiKey = "key", embeddingBaseURL = "https://alibaba.test/api/v1"),
        )

        val result = provider.embeddingModel("text-embedding-v4").embed(
            EmbeddingModelCallParams(
                values = listOf("hello", "world"),
                providerOptions = mapOf(
                    "alibaba" to buildJsonObject {
                        put("textType", JsonPrimitive("query"))
                        put("dimension", JsonPrimitive(2))
                    },
                ),
            ),
        )

        assertEquals(10, provider.embeddingModel("text-embedding-v4").maxEmbeddingsPerCall)
        assertEquals(false, provider.embeddingModel("text-embedding-v4").supportsParallelCalls)
        // Reordered by text_index: index 0 (3-dim) first, then index 1 (2-dim).
        assertEquals(listOf(listOf(0.1f, 0.2f, 0.3f), listOf(0.4f, 0.5f)), result.embeddings)
        assertEquals(7, result.usage.tokens)
    }

    @Test
    fun `embedding model rejects sparse output and over-limit batches`() = runTest {
        val provider = Alibaba(createTestServer(mutableMapOf()).httpClient(), AlibabaProviderSettings(apiKey = "key"))
        val model = provider.embeddingModel("text-embedding-v4")
        assertFailsWith<UnsupportedFunctionalityError> {
            model.embed(
                EmbeddingModelCallParams(
                    values = listOf("x"),
                    providerOptions = mapOf(
                        "alibaba" to buildJsonObject { put("outputType", JsonPrimitive("sparse")) },
                    ),
                ),
            )
        }
        assertFailsWith<TooManyEmbeddingValuesForCallError> {
            model.embed(EmbeddingModelCallParams(values = List(11) { "v$it" }))
        }
    }

    @Test
    fun `unsupported Alibaba surfaces and unconfigured singleton fail explicitly`() {
        val provider = Alibaba(createTestServer(mutableMapOf()).httpClient(), AlibabaProviderSettings(apiKey = "key"))

        assertFailsWith<NoSuchModelError> { provider.imageModel("image") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
