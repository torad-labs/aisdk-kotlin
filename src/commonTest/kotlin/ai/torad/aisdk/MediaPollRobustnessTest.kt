package ai.torad.aisdk

import ai.torad.aisdk.providers.AssemblyAI
import ai.torad.aisdk.providers.AssemblyAIProviderSettings
import ai.torad.aisdk.providers.Fal
import ai.torad.aisdk.providers.FalProviderSettings
import ai.torad.aisdk.providers.Fireworks
import ai.torad.aisdk.providers.FireworksProviderSettings
import ai.torad.aisdk.providers.GoogleGenerativeAI
import ai.torad.aisdk.providers.GoogleGenerativeAIProviderSettings
import ai.torad.aisdk.providers.KlingAI
import ai.torad.aisdk.providers.KlingAIProviderSettings
import ai.torad.aisdk.providers.Luma
import ai.torad.aisdk.providers.LumaProviderSettings
import ai.torad.aisdk.providers.Revai
import ai.torad.aisdk.providers.RevaiProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPollRobustnessTest {
    @Test
    fun `fal poll GET is cancelled when abort fires in flight`() = runTest {
        val pollStarted = CompletableDeferred<Unit>()
        val pollBody = TestResponseController()
        val controller = AbortController()
        var pollCalls = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.toString()) {
                "https://queue.fal.run/fal-ai/test-video" -> json("""{"response_url":"https://queue.fal.run/result"}""")
                "https://queue.fal.run/result" -> {
                    pollCalls++
                    pollStarted.complete(Unit)
                    respond(
                        content = pollBody.stream,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> json("""{"unexpected":"${request.url}"}""", HttpStatusCode.NotFound)
            }
        })
        val model = Fal(client, FalProviderSettings { apiKey("key") }).video(ModelId("test-video"))

        val pending = async {
            model.generate(VideoGenerationParams {
                prompt("x")
                providerOptions(ProviderOptions.ofPairs(
                    "fal" to buildJsonObject { put("pollIntervalMs", JsonPrimitive(0)) },
                ))
                abortSignal(controller.signal)
            })
        }
        pollStarted.await()
        pollBody.error(AbortError())
        controller.abort()

        assertFailsWith<AbortError> { HttpTransport.withRealTimeout(5_000) { pending.await() } }
        assertEquals(1, pollCalls)
    }

    @Test
    fun `assemblyai poll GET is cancelled when abort fires in flight`() = runTest {
        val pollStarted = CompletableDeferred<Unit>()
        val pollBody = TestResponseController()
        val controller = AbortController()
        var pollCalls = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.toString()) {
                "https://api.assemblyai.com/v2/upload" -> json("""{"upload_url":"https://cdn.example/audio"}""")
                "https://api.assemblyai.com/v2/transcript" -> json("""{"id":"tx1","status":"queued"}""")
                "https://api.assemblyai.com/v2/transcript/tx1" -> {
                    pollCalls++
                    pollStarted.complete(Unit)
                    respond(
                        content = pollBody.stream,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> json("""{"unexpected":"${request.url}"}""", HttpStatusCode.NotFound)
            }
        })
        val model = AssemblyAI(
            client,
            AssemblyAIProviderSettings {
                apiKey("key")
                pollingIntervalMillis(0)
            },
        ).transcription(ModelId("best"))

        val pending = async {
            model.transcribe(TranscriptionParams {
                audio(AudioSource("audio/wav", Base64Codec.encode(byteArrayOf(1))))
                abortSignal(controller.signal)
            })
        }
        pollStarted.await()
        pollBody.error(AbortError())
        controller.abort()

        assertFailsWith<AbortError> { HttpTransport.withRealTimeout(5_000) { pending.await() } }
        assertEquals(1, pollCalls)
    }

    @Test
    fun `revai poll GET is cancelled when abort fires in flight`() = runTest {
        val pollStarted = CompletableDeferred<Unit>()
        val pollBody = TestResponseController()
        val controller = AbortController()
        var pollCalls = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.toString()) {
                "https://api.rev.ai/speechtotext/v1/jobs" -> json("""{"id":"job1","status":"in_progress"}""")
                "https://api.rev.ai/speechtotext/v1/jobs/job1" -> {
                    pollCalls++
                    pollStarted.complete(Unit)
                    respond(
                        content = pollBody.stream,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> json("""{"unexpected":"${request.url}"}""", HttpStatusCode.NotFound)
            }
        })
        val model = Revai(
            client,
            RevaiProviderSettings {
                apiKey("key")
                pollingIntervalMillis(0)
            },
        ).transcription(ModelId("machine"))

        val pending = async {
            model.transcribe(TranscriptionParams {
                audio(AudioSource("audio/wav", Base64Codec.encode(byteArrayOf(1))))
                abortSignal(controller.signal)
            })
        }
        pollStarted.await()
        pollBody.error(AbortError())
        controller.abort()

        assertFailsWith<AbortError> { HttpTransport.withRealTimeout(5_000) { pending.await() } }
        assertEquals(1, pollCalls)
    }

    @Test
    fun `luma poll GET is cancelled when abort fires in flight`() = runTest {
        val pollStarted = CompletableDeferred<Unit>()
        val pollBody = TestResponseController()
        val controller = AbortController()
        var pollCalls = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.toString()) {
                "https://luma.test/dream-machine/v1/generations/image" -> json("""{"id":"gen1","state":"queued"}""")
                "https://luma.test/dream-machine/v1/generations/gen1" -> {
                    pollCalls++
                    pollStarted.complete(Unit)
                    respond(
                        content = pollBody.stream,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> json("""{"unexpected":"${request.url}"}""", HttpStatusCode.NotFound)
            }
        })
        val model = Luma(
            client,
            LumaProviderSettings {
                apiKey("key")
                baseURL("https://luma.test")
            },
        ).image(ModelId("photon-1"))

        val pending = async {
            model.generate(ImageGenerationParams {
                prompt("x")
                providerOptions(ProviderOptions.ofPairs(
                    "luma" to buildJsonObject { put("pollIntervalMillis", JsonPrimitive(0)) },
                ))
                abortSignal(controller.signal)
            })
        }
        pollStarted.await()
        pollBody.error(AbortError())
        controller.abort()

        assertFailsWith<AbortError> { HttpTransport.withRealTimeout(5_000) { pending.await() } }
        assertEquals(1, pollCalls)
    }

    @Test
    fun `klingai poll GET is cancelled when abort fires in flight`() = runTest {
        val pollStarted = CompletableDeferred<Unit>()
        val pollBody = TestResponseController()
        val controller = AbortController()
        var pollCalls = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.toString()) {
                "https://kling.test/v1/videos/text2video" -> json(klingTask("submitted"))
                "https://kling.test/v1/videos/text2video/task-1" -> {
                    pollCalls++
                    pollStarted.complete(Unit)
                    respond(
                        content = pollBody.stream,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> json("""{"unexpected":"${request.url}"}""", HttpStatusCode.NotFound)
            }
        })
        val model = KlingAI(
            client,
            KlingAIProviderSettings {
                accessKey("access")
                secretKey("secret")
                baseURL("https://kling.test")
            },
        ).video(ModelId("kling-v2.6-t2v"))

        val pending = async {
            model.generate(VideoGenerationParams {
                prompt("x")
                providerOptions(ProviderOptions.ofPairs(
                    "klingai" to buildJsonObject { put("pollIntervalMs", JsonPrimitive(0)) },
                ))
                abortSignal(controller.signal)
            })
        }
        pollStarted.await()
        pollBody.error(AbortError())
        controller.abort()

        assertFailsWith<AbortError> { HttpTransport.withRealTimeout(5_000) { pending.await() } }
        assertEquals(1, pollCalls)
    }

    @Test
    fun `fireworks async image poll uses exponential backoff`() = runTest {
        val modelId = "accounts/fireworks/models/flux-kontext-pro"
        var pollCalls = 0
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://fireworks.test/workflows/$modelId" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"request_id":"req1"}""")),
                ),
                "https://fireworks.test/workflows/$modelId/get_result" to UrlHandler {
                    pollCalls++
                    if (pollCalls < 3) {
                        UrlResponse.JsonValue(Json.parseToJsonElement("""{"status":"Pending"}"""))
                    } else {
                        UrlResponse.JsonValue(Json.parseToJsonElement("""{"status":"Ready","result":{"sample":"https://cdn.example/image.png"}}"""))
                    }
                },
                "https://cdn.example/image.png" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(1), headers = mapOf(HttpHeaders.ContentType to "image/png")),
                ),
            ),
        )
        fixture.server.start()
        val model = Fireworks(
            fixture.httpClient(),
            FireworksProviderSettings {
                apiKey("key")
                baseURL("https://fireworks.test")
            },
        ).image(ModelId(modelId))

        val result = model.generate(ImageGenerationParams {
            prompt("x")
        })

        assertEquals(3, pollCalls)
        assertEquals(1, result.images.size)
        assertEquals(1_250L, testScheduler.currentTime)
    }

    @Test
    fun `klingai non-positive poll timeout is bounded by max poll attempts`() = runTest {
        var pollCalls = 0
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://kling.test/v1/videos/text2video" to UrlHandler(UrlResponse.JsonValue(Json.parseToJsonElement(klingTask("submitted")))),
                "https://kling.test/v1/videos/text2video/task-1" to UrlHandler {
                    pollCalls++
                    UrlResponse.JsonValue(Json.parseToJsonElement(klingTask("processing")))
                },
            ),
        )
        fixture.server.start()
        val model = KlingAI(
            fixture.httpClient(),
            KlingAIProviderSettings {
                accessKey("access")
                secretKey("secret")
                baseURL("https://kling.test")
            },
        ).video(ModelId("kling-v2.6-t2v"))

        val error = assertFailsWith<NoVideoGeneratedError> {
            model.generate(VideoGenerationParams {
                prompt("x")
                providerOptions(ProviderOptions.ofPairs(
                    "klingai" to buildJsonObject {
                        put("pollIntervalMs", JsonPrimitive(0))
                        put("pollTimeoutMs", JsonPrimitive(0))
                    },
                ))
            })
        }

        assertTrue(error.message.orEmpty().contains("1 poll attempts"))
        assertEquals(1, pollCalls)
    }

    @Test
    fun `google video poll stops after done response`() = runTest {
        var pollCalls = 0
        val fixture = googleVideoFixture {
            pollCalls++
            UrlResponse.JsonValue(Json.parseToJsonElement(googleVideoDoneResponse()))
        }
        val model = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
                videoPollIntervalMillis(0)
                videoMaxPollAttempts(20)
            },
        ).video(ModelId("veo-3.1-generate-preview"))

        model.generate(VideoGenerationParams {
            prompt("x")
        })

        assertEquals(1, pollCalls)
    }

    @Test
    fun `google video poll detects terminal error without done true`() = runTest {
        val fixture = googleVideoFixture {
            UrlResponse.JsonValue(Json.parseToJsonElement("""{"name":"operations/video-1","error":{"message":"quota exceeded"}}"""))
        }
        val model = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
                videoPollIntervalMillis(0)
                videoMaxPollAttempts(3)
            },
        ).video(ModelId("veo-3.1-generate-preview"))

        val error = assertFailsWith<NoVideoGeneratedError> {
            model.generate(VideoGenerationParams {
                prompt("x")
            })
        }

        assertTrue(error.message.orEmpty().contains("quota exceeded"))
    }

    private fun googleVideoFixture(pollResponse: () -> UrlResponse): CreatedTestServer {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/veo-3.1-generate-preview:predictLongRunning" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"name":"operations/video-1","done":false}""")),
                ),
                "https://google.test/v1beta/operations/video-1" to UrlHandler { pollResponse() },
            ),
        )
        fixture.server.start()
        return fixture
    }

    private fun googleVideoDoneResponse(): String = """
        {
          "name":"operations/video-1",
          "done":true,
          "response":{
            "generateVideoResponse":{
              "generatedSamples":[{"video":{"uri":"https://videos.example/video.mp4"}}]
            }
          }
        }
    """.trimIndent()

    private fun klingTask(status: String): String = """
        {
          "code":0,
          "message":"success",
          "data":{
            "task_id":"task-1",
            "task_status":"$status"
          }
        }
    """.trimIndent()

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
