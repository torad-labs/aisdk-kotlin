package ai.torad.aisdk

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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

class AssemblyAIProviderTest {
    @Test
    fun `transcription model uploads audio submits transcript and maps completed result`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.assemblyai.com/v2/upload" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"upload_url":"https://cdn.example/audio"}""")),
                ),
                "https://api.assemblyai.com/v2/transcript" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"tx1","status":"queued"}""")),
                ),
                "https://api.assemblyai.com/v2/transcript/tx1" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"tx1","status":"completed","text":"hello world","language_code":"en","audio_duration":4.2,"words":[{"start":0.1,"end":0.3,"text":"hello"},{"start":0.4,"end":0.8,"text":"world"}]}""",
                        ),
                        headers = mapOf("x-final" to "true"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createAssemblyAI(
            fixture.httpClient(),
            AssemblyAIProviderSettings(apiKey = "key"),
        ).transcription("best")

        val result = model.transcribe(
            TranscriptionParams(
                audio = AudioSource(
                    mediaType = "audio/mpeg",
                    base64 = convertByteArrayToBase64("abc".encodeToByteArray()),
                    filename = "clip.mp3",
                ),
                language = "en_us",
                providerOptions = mapOf(
                    "assemblyai" to buildJsonObject {
                        put("audioEndAt", JsonPrimitive(10))
                        put("autoChapters", JsonPrimitive(true))
                        put("contentSafetyConfidence", JsonPrimitive(75))
                        put("customSpelling", buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("from", buildJsonArray { add(JsonPrimitive("sdk")) })
                                    put("to", JsonPrimitive("SDK"))
                                },
                            )
                        })
                        put("languageCode", JsonPrimitive("en"))
                        put("languageDetection", JsonPrimitive(true))
                        put("speakerLabels", JsonPrimitive(true))
                        put("speechThreshold", JsonPrimitive(0.7f))
                        put("wordBoost", buildJsonArray { add(JsonPrimitive("Kotlin")) })
                    },
                ),
            ),
        )

        assertEquals("assemblyai.transcription", model.provider)
        assertEquals("hello world", result.text)
        assertEquals(2, result.segments.size)
        assertEquals("hello", result.segments.first().text)
        assertEquals(0.1f, result.segments.first().startSeconds)
        assertEquals(0.8f, result.segments.last().endSeconds)
        assertEquals("true", result.response.headers["x-final"])
        assertEquals("completed", result.response.body?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull)
        assertEquals("completed", result.providerMetadata["assemblyai"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull)

        val upload = fixture.calls[0]
        assertEquals("POST", upload.requestMethod)
        assertEquals("key", upload.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertTrue(upload.requestUserAgent.orEmpty().contains("ai-sdk/assemblyai/$ASSEMBLYAI_VERSION"))
        assertEquals("abc", upload.requestBodyText)

        val submit = fixture.calls[1]
        assertEquals("POST", submit.requestMethod)
        assertEquals("key", submit.requestHeaders.headerValue(HttpHeaders.Authorization))
        val body = submit.requestBodyJson.jsonObject
        assertEquals("best", body["speech_model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://cdn.example/audio", body["audio_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals(10, body["audio_end_at"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, body["auto_chapters"]?.jsonPrimitive?.contentOrNull.toBoolean())
        assertEquals(75, body["content_safety_confidence"]?.jsonPrimitive?.intOrNull)
        assertEquals("SDK", body["custom_spelling"]?.jsonArray?.single()?.jsonObject?.get("to")?.jsonPrimitive?.contentOrNull)
        assertEquals("en", body["language_code"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["language_detection"]?.jsonPrimitive?.contentOrNull.toBoolean())
        assertEquals(true, body["speaker_labels"]?.jsonPrimitive?.contentOrNull.toBoolean())
        assertEquals(0.7f, body["speech_threshold"]?.jsonPrimitive?.floatOrNull)
        assertEquals("Kotlin", body["word_boost"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)

        val poll = fixture.calls[2]
        assertEquals("GET", poll.requestMethod)
        assertEquals("key", poll.requestHeaders.headerValue(HttpHeaders.Authorization))
    }

    @Test
    fun `transcription model uses top-level language when provider option is omitted`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.assemblyai.com/v2/upload" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"upload_url":"https://cdn.example/audio"}""")),
                ),
                "https://api.assemblyai.com/v2/transcript" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"tx1","status":"queued"}""")),
                ),
                "https://api.assemblyai.com/v2/transcript/tx1" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"tx1","status":"completed","text":"ola"}""")),
                ),
            ),
        )
        fixture.server.start()
        val model = createAssemblyAI(
            fixture.httpClient(),
            AssemblyAIProviderSettings(apiKey = "key"),
        ).transcription("nano")

        model.transcribe(
            TranscriptionParams(
                audio = AudioSource("audio/wav", convertByteArrayToBase64(byteArrayOf(1))),
                language = "pt",
            ),
        )

        assertEquals("pt", fixture.calls[1].requestBodyJson.jsonObject["language_code"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `transcription model throws when transcript finishes with error`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.assemblyai.com/v2/upload" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"upload_url":"https://cdn.example/audio"}""")),
                ),
                "https://api.assemblyai.com/v2/transcript" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"tx1","status":"queued"}""")),
                ),
                "https://api.assemblyai.com/v2/transcript/tx1" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"tx1","status":"error","error":"bad audio"}""")),
                ),
            ),
        )
        fixture.server.start()
        val model = createAssemblyAI(
            fixture.httpClient(),
            AssemblyAIProviderSettings(apiKey = "key", pollingIntervalMillis = 0),
        ).transcription("best")

        val error = assertFailsWith<AiSdkException> {
            model.transcribe(TranscriptionParams(audio = AudioSource("audio/wav", convertByteArrayToBase64(byteArrayOf(1)))))
        }
        assertTrue(error.message.orEmpty().contains("bad audio"))
    }

    @Test
    fun `default provider and unsupported model families fail explicitly`() {
        val fixture = createTestServer(mutableMapOf())
        val provider = createAssemblyAI(fixture.httpClient(), AssemblyAIProviderSettings(apiKey = "key"))

        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<AiSdkException> { assemblyai.transcription("best") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
