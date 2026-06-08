package ai.torad.aisdk
import ai.torad.aisdk.providers.REVAI_VERSION
import ai.torad.aisdk.providers.revai
import ai.torad.aisdk.providers.RevaiProviderSettings
import ai.torad.aisdk.providers.createRevai

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RevaiProviderTest {
    @Test
    fun `transcription model submits multipart job polls status and maps transcript`() = runTest {
        val fixture = createTestServer(
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
                            """{"monologues":[{"elements":[{"type":"text","value":"Hello","ts":0.1,"end_ts":0.3},{"type":"punct","value":" "},{"type":"text","value":"world","ts":0.4,"end_ts":0.8}]}]}""",
                        ),
                        headers = mapOf("x-final" to "true"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createRevai(
            fixture.httpClient(),
            RevaiProviderSettings(apiKey = "key", pollingIntervalMillis = 0),
        ).transcription("machine")

        val result = model.transcribe(
            TranscriptionParams(
                audio = AudioSource(
                    mediaType = "audio/wav",
                    base64 = convertByteArrayToBase64("abc".encodeToByteArray()),
                    filename = "clip.wav",
                ),
                language = "en",
                providerOptions = mapOf(
                    "revai" to buildJsonObject {
                        put("metadata", JsonPrimitive("job-metadata"))
                        put("rush", JsonPrimitive(true))
                        put("test_mode", JsonPrimitive(true))
                        put("skip_diarization", JsonPrimitive(true))
                        put("filter_profanity", JsonPrimitive(true))
                        put("language", JsonPrimitive("en-us"))
                        put("forced_alignment", JsonPrimitive(true))
                        put("segments_to_transcribe", buildJsonArray {
                            add(buildJsonObject {
                                put("start", JsonPrimitive(0))
                                put("end", JsonPrimitive(60))
                            })
                        })
                        put(
                            "notification_config",
                            buildJsonObject {
                                put("url", JsonPrimitive("https://example.com/hook"))
                                put("auth_headers", buildJsonObject {
                                    put("Authorization", JsonPrimitive("Bearer hook"))
                                })
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
                                put("target_languages", buildJsonArray {
                                    add(buildJsonObject { put("language", JsonPrimitive("es")) })
                                })
                            },
                        )
                    },
                ),
            ),
        )

        assertEquals("revai.transcription", model.provider)
        assertEquals("Hello world", result.text)
        assertEquals(2, result.segments.size)
        assertEquals("Hello", result.segments.first().text)
        assertEquals(0.1f, result.segments.first().startSeconds)
        assertEquals("world", result.segments.last().text)
        assertEquals(0.8f, result.segments.last().endSeconds)
        assertEquals("en", result.language)
        assertEquals(0.8f, result.durationInSeconds)
        assertEquals("true", result.response.headers["x-final"])
        assertEquals("Hello", result.providerMetadata["revai"]?.jsonObject
            ?.get("monologues")?.jsonArray?.first()?.jsonObject
            ?.get("elements")?.jsonArray?.first()?.jsonObject
            ?.get("value")?.jsonPrimitive?.contentOrNull)

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
    fun `transcription model throws when submission fails`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.rev.ai/speechtotext/v1/jobs" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"job1","status":"failed"}""")),
                ),
            ),
        )
        fixture.server.start()
        val model = createRevai(
            fixture.httpClient(),
            RevaiProviderSettings(apiKey = "key", pollingIntervalMillis = 0),
        ).transcription("machine")

        val error = assertFailsWith<AiSdkException> {
            model.transcribe(TranscriptionParams(audio = AudioSource("audio/wav", convertByteArrayToBase64(byteArrayOf(1)))))
        }
        assertTrue(error.message.orEmpty().contains("Failed to submit"))
    }

    @Test
    fun `default provider and unsupported model families fail explicitly`() {
        val fixture = createTestServer(mutableMapOf())
        val provider = createRevai(fixture.httpClient(), RevaiProviderSettings(apiKey = "key"))

        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<AiSdkException> { revai.transcription("machine") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
