package ai.torad.aisdk

import ai.torad.aisdk.providers.REVAI_VERSION
import ai.torad.aisdk.providers.Revai
import ai.torad.aisdk.providers.RevaiProviderSettings
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RevaiProviderTest {
    @Test
    fun `transcription model submits multipart job polls status and maps transcript`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.rev.ai/speechtotext/v1/jobs" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"job1","status":"in_progress","language":"en"}""")),
                ),
                "https://api.rev.ai/speechtotext/v1/jobs/job1" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"job1","status":"transcribed","language":"en"}""")),
                ),
                "https://api.rev.ai/speechtotext/v1/jobs/job1/transcript" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"monologues":[{"elements":[{"type":"text","value":"Hello","ts":0.1,"end_ts":0.3},{"type":"punct","value":", "},{"type":"text","value":"world","ts":0.4,"end_ts":0.8}]}]}""",
                        ),
                        headers = mapOf("x-final" to "true"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = Revai(
            fixture.httpClient(),
            RevaiProviderSettings {
                apiKey("key")
                pollingIntervalMillis(0)
            },
        ).transcription(ModelId("machine"))

        val result = model.transcribe(
            TranscriptionParams {
                audio(
                    AudioSource(
                        mediaType = "audio/wav",
                        base64 = Base64Codec.encode("abc".encodeToByteArray()),
                        filename = "clip.wav",
                    )
                )
                language("en")
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "revai" to buildJsonObject {
                                    put("metadata", JsonPrimitive("job-metadata"))
                                    put("rush", JsonPrimitive(true))
                                    put("test_mode", JsonPrimitive(true))
                                    put("skip_diarization", JsonPrimitive(true))
                                    put("filter_profanity", JsonPrimitive(true))
                                    put("language", JsonPrimitive("en-us"))
                                    put("forced_alignment", JsonPrimitive(true))
                                    put(
                                        "segments_to_transcribe",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("start", JsonPrimitive(0))
                                                    put("end", JsonPrimitive(60))
                                                }
                                            )
                                        }
                                    )
                                    put(
                                        "notification_config",
                                        buildJsonObject {
                                            put("url", JsonPrimitive("https://example.com/hook"))
                                            put(
                                                "auth_headers",
                                                buildJsonObject {
                                                    put("Authorization", JsonPrimitive("Bearer hook"))
                                                }
                                            )
                                        },
                                    )
                                    put(
                                        "summarization_config",
                                        buildJsonObject {
                                            put("model", JsonPrimitive("premium"))
                                            put("type", JsonPrimitive("bullets"))
                                        },
                                    )
                                    put(
                                        "translation_config",
                                        buildJsonObject {
                                            put(
                                                "target_languages",
                                                buildJsonArray {
                                                    add(buildJsonObject { put("language", JsonPrimitive("es")) })
                                                }
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

        assertEquals("revai.transcription", model.provider)
        // Top-level text keeps punctuation (joins all element values); segment text must NOT —
        // the inter-word ", " punct must not prepend into the "world" segment (was ", world").
        assertEquals("Hello, world", result.text)
        assertEquals(2, result.segments.size)
        assertEquals("Hello", result.segments.first().text)
        assertEquals(0.1f, result.segments.first().startSeconds)
        assertEquals("world", result.segments.last().text)
        assertEquals(0.8f, result.segments.last().endSeconds)
        assertEquals("en", result.language)
        assertEquals(0.8f, result.durationInSeconds)
        assertEquals("true", result.response.headers["x-final"])
        assertEquals(
            "Hello",
            result.providerMetadata.toMap()["revai"]?.jsonObject
                ?.get("monologues")?.jsonArray?.first()?.jsonObject
                ?.get("elements")?.jsonArray?.first()?.jsonObject
                ?.get("value")?.jsonPrimitive?.contentOrNull
        )

        assertEquals(3, fixture.calls.size)
        val submit = fixture.calls[0]
        assertEquals("POST", submit.requestMethod)
        assertEquals("Bearer key", submit.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertTrue(submit.requestUserAgent.orEmpty().contains("ai-sdk/revai/$REVAI_VERSION"))
        assertTrue(submit.requestBodyText.contains("clip.wav"))
        assertTrue(submit.requestBodyText.contains("abc"))
        assertTrue(submit.requestBodyText.contains("\"transcriber\":\"machine\""))
        assertTrue(submit.requestBodyText.contains("\"metadata\":\"job-metadata\""))
        assertTrue(submit.requestBodyText.contains("\"language\":\"en-us\""))
        assertTrue(submit.requestBodyText.contains("\"forced_alignment\":true"))
        assertTrue(submit.requestBodyText.contains("\"summarization_config\""))
        assertTrue(submit.requestBodyText.contains("\"translation_config\""))

        val poll = fixture.calls[1]
        assertEquals("GET", poll.requestMethod)
        assertEquals("Bearer key", poll.requestHeaders.headerValue(HttpHeaders.Authorization))
    }

    @Test
    fun `failed submission retains precedence over missing id`() = runTest {
        val fixture = revaiFixture(submitBody = """{"status":"failed"}""")
        val model = revaiModel(fixture)

        val error = assertFailsWith<NoTranscriptGeneratedError> {
            model.transcribe(revaiTranscriptionParams())
        }

        assertTrue(error.message.orEmpty().contains("Failed to submit"))
        assertEquals(1, fixture.calls.size)
        assertEquals("POST", fixture.calls.single().requestMethod)
    }

    @Test
    fun `failed submission with id stops after post`() = runTest {
        val fixture = revaiFixture(submitBody = """{"id":"job1","status":"failed"}""")
        val model = revaiModel(fixture)

        val error = assertFailsWith<NoTranscriptGeneratedError> {
            model.transcribe(revaiTranscriptionParams())
        }

        assertTrue(error.message.orEmpty().contains("Failed to submit"))
        assertEquals(1, fixture.calls.size)
        assertEquals("POST", fixture.calls.single().requestMethod)
    }

    @Test
    fun `transcribed submission fetches transcript without polling status`() = runTest {
        val fixture = revaiFixture(
            submitBody = """{"id":"job1","status":"transcribed","language":"en"}""",
            transcriptBody = """{"monologues":[]}""",
        )
        val model = revaiModel(fixture)

        val result = model.transcribe(revaiTranscriptionParams())

        assertEquals("en", result.language)
        assertEquals(2, fixture.calls.size)
        assertEquals("POST", fixture.calls[0].requestMethod)
        assertEquals("GET", fixture.calls[1].requestMethod)
        assertEquals(
            "https://api.rev.ai/speechtotext/v1/jobs/job1/transcript",
            fixture.calls[1].requestUrl,
        )
    }

    @Test
    fun `failed poll stops after one status request`() = runTest {
        val fixture = revaiFixture(
            submitBody = """{"id":"job1","status":"in_progress"}""",
            pollBody = """{"id":"job1","status":"failed"}""",
        )
        val model = revaiModel(fixture)

        val error = assertFailsWith<NoTranscriptGeneratedError> {
            model.transcribe(revaiTranscriptionParams())
        }

        assertTrue(error.message.orEmpty().contains("job failed"))
        assertEquals(2, fixture.calls.size)
        assertEquals(listOf("POST", "GET"), fixture.calls.map { it.requestMethod })
    }

    @Test
    fun `pending poll exhausts exact attempt bound before timeout`() = runTest {
        val fixture = revaiFixture(
            submitBody = """{"id":"job1","status":"in_progress"}""",
            pollBody = """{"id":"job1","status":"in_progress"}""",
        )
        val model = revaiModel(fixture, maxPollAttempts = 2)

        val error = assertFailsWith<NoTranscriptGeneratedError> {
            model.transcribe(revaiTranscriptionParams())
        }

        assertTrue(error.message.orEmpty().contains("timed out"))
        assertEquals(3, fixture.calls.size)
        assertEquals(listOf("POST", "GET", "GET"), fixture.calls.map { it.requestMethod })
    }

    @Test
    fun `missing id retains precedence over malformed initial status`() = runTest {
        val fixture = revaiFixture(submitBody = """{"status":1}""")
        val model = revaiModel(fixture)

        val error = assertFailsWith<InvalidResponseDataError> {
            model.transcribe(revaiTranscriptionParams())
        }

        assertTrue(error.message.orEmpty().contains("missing id"))
        assertEquals(1, fixture.calls.size)
        assertEquals("POST", fixture.calls.single().requestMethod)
    }

    @Test
    fun `missing initial status fails without polling`() = runTest {
        assertInvalidInitialStatus("""{"id":"job1"}""")
    }

    @Test
    fun `non-string initial status fails without polling`() = runTest {
        assertInvalidInitialStatus("""{"id":"job1","status":1}""")
    }

    @Test
    fun `unknown initial status fails without polling`() = runTest {
        assertInvalidInitialStatus("""{"id":"job1","status":"queued"}""")
    }

    @Test
    fun `malformed initial status retains entire response`() = runTest {
        val body = """{"id":"job1","status":{"future":true},"provider_field":"must-survive"}"""
        val response = Json.parseToJsonElement(body)
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.rev.ai/speechtotext/v1/jobs" to UrlHandler(
                    UrlResponse.JsonValue(response),
                ),
            ),
        ).also { it.server.start() }
        val model = revaiModel(fixture)

        val error = assertFailsWith<InvalidResponseDataError> {
            model.transcribe(revaiTranscriptionParams())
        }

        assertEquals(response, error.data)
        assertEquals(1, fixture.calls.size)
        assertEquals("POST", fixture.calls.single().requestMethod)
    }

    @Test
    fun `malformed polled statuses retain entire response`() = runTest {
        val pollBodies = listOf(
            """{"id":"job1","status":{"future":true},"provider_field":"must-survive"}""",
            """{"id":"job1","status":null,"provider_field":"must-survive"}""",
            """{"id":"job1","status":[],"provider_field":"must-survive"}""",
            """{"id":"job1","status":true,"provider_field":"must-survive"}""",
        )
        for (body in pollBodies) {
            val response = Json.parseToJsonElement(body)
            val fixture = TestServer.createTestServer(
                mutableMapOf(
                    "https://api.rev.ai/speechtotext/v1/jobs" to UrlHandler(
                        UrlResponse.JsonValue(
                            Json.parseToJsonElement("""{"id":"job1","status":"in_progress"}"""),
                        ),
                    ),
                    "https://api.rev.ai/speechtotext/v1/jobs/job1" to UrlHandler(
                        UrlResponse.JsonValue(response),
                    ),
                ),
            ).also { it.server.start() }
            val model = revaiModel(fixture)

            val error = assertFailsWith<InvalidResponseDataError> {
                model.transcribe(revaiTranscriptionParams())
            }

            assertEquals(response, error.data)
            assertEquals(2, fixture.calls.size)
            assertEquals(listOf("POST", "GET"), fixture.calls.map { it.requestMethod })
            assertEquals(
                "https://api.rev.ai/speechtotext/v1/jobs/job1",
                fixture.calls[1].requestUrl,
            )
        }
    }

    @Test
    fun `pending then pending then transcribed delays once and fetches transcript`() = runTest {
        var statusCalls = 0
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.rev.ai/speechtotext/v1/jobs" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"id":"job1","status":"in_progress"}"""),
                    ),
                ),
                "https://api.rev.ai/speechtotext/v1/jobs/job1" to UrlHandler { _ ->
                    when (statusCalls++) {
                        0 -> UrlResponse.JsonValue(
                            Json.parseToJsonElement("""{"id":"job1","status":"in_progress"}"""),
                        )
                        1 -> UrlResponse.JsonValue(
                            Json.parseToJsonElement(
                                """{"id":"job1","status":"transcribed","language":"en"}""",
                            ),
                        )
                        else -> throw AssertionError("unexpected additional Rev.ai status request")
                    }
                },
                "https://api.rev.ai/speechtotext/v1/jobs/job1/transcript" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"monologues":[]}""")),
                ),
            ),
        ).also { it.server.start() }
        val model = revaiModel(
            fixture = fixture,
            pollingIntervalMillis = 1,
            maxPollAttempts = 3,
        )

        val result = model.transcribe(revaiTranscriptionParams())

        assertEquals("en", result.language)
        assertEquals(1L, testScheduler.currentTime)
        assertEquals(4, fixture.calls.size)
        assertEquals(listOf("POST", "GET", "GET", "GET"), fixture.calls.map { it.requestMethod })
        assertEquals(
            listOf(
                "https://api.rev.ai/speechtotext/v1/jobs",
                "https://api.rev.ai/speechtotext/v1/jobs/job1",
                "https://api.rev.ai/speechtotext/v1/jobs/job1",
                "https://api.rev.ai/speechtotext/v1/jobs/job1/transcript",
            ),
            fixture.calls.map { it.requestUrl },
        )
        assertEquals(1, fixture.calls.count { it.requestUrl.endsWith("/transcript") })
    }

    @Test
    fun `nonpositive attempt bound still polls once`() = runTest {
        for (maxPollAttempts in listOf(0, -1)) {
            val fixture = revaiFixture(
                submitBody = """{"id":"job1","status":"in_progress"}""",
                pollBody = """{"id":"job1","status":"in_progress"}""",
            )
            val model = revaiModel(
                fixture = fixture,
                pollingIntervalMillis = 0,
                maxPollAttempts = maxPollAttempts,
            )

            val error = assertFailsWith<NoTranscriptGeneratedError> {
                model.transcribe(revaiTranscriptionParams())
            }

            assertTrue(error.message.orEmpty().contains("timed out"))
            assertEquals(2, fixture.calls.size)
            assertEquals(listOf("POST", "GET"), fixture.calls.map { it.requestMethod })
        }
    }

    @Test
    fun `default provider and unsupported model families fail explicitly`() {
        val fixture = TestServer.createTestServer(mutableMapOf())
        val provider = Revai(fixture.httpClient(), RevaiProviderSettings { apiKey("key") })

        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
    }

    private fun revaiFixture(
        submitBody: String,
        pollBody: String? = null,
        transcriptBody: String? = null,
    ): CreatedTestServer {
        val routes = mutableMapOf(
            "https://api.rev.ai/speechtotext/v1/jobs" to UrlHandler(
                UrlResponse.JsonValue(Json.parseToJsonElement(submitBody)),
            ),
        )
        pollBody?.let {
            routes["https://api.rev.ai/speechtotext/v1/jobs/job1"] = UrlHandler(
                UrlResponse.JsonValue(Json.parseToJsonElement(it)),
            )
        }
        transcriptBody?.let {
            routes["https://api.rev.ai/speechtotext/v1/jobs/job1/transcript"] = UrlHandler(
                UrlResponse.JsonValue(Json.parseToJsonElement(it)),
            )
        }
        return TestServer.createTestServer(routes).also { it.server.start() }
    }

    private fun revaiModel(
        fixture: CreatedTestServer,
        pollingIntervalMillis: Long = 0,
        maxPollAttempts: Int = 60,
    ): TranscriptionModel =
        Revai(
            fixture.httpClient(),
            RevaiProviderSettings {
                apiKey("key")
                pollingIntervalMillis(pollingIntervalMillis)
                maxPollAttempts(maxPollAttempts)
            },
        ).transcription(ModelId("machine"))

    private fun revaiTranscriptionParams(): TranscriptionParams =
        TranscriptionParams {
            audio(AudioSource("audio/wav", Base64Codec.encode(byteArrayOf(1))))
        }

    private suspend fun assertInvalidInitialStatus(submitBody: String) {
        val fixture = revaiFixture(submitBody = submitBody)
        val model = revaiModel(fixture)

        assertFailsWith<InvalidResponseDataError> {
            model.transcribe(revaiTranscriptionParams())
        }

        assertEquals(1, fixture.calls.size)
        assertEquals("POST", fixture.calls.single().requestMethod)
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
