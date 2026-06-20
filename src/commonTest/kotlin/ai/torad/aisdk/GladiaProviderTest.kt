package ai.torad.aisdk
import ai.torad.aisdk.providers.GLADIA_VERSION
import ai.torad.aisdk.providers.GladiaProviderSettings
import ai.torad.aisdk.providers.createGladia
import ai.torad.aisdk.providers.gladia

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

class GladiaProviderTest {
    @Test
    fun `transcription model uploads audio initializes job polls result and maps options`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.gladia.io/v2/upload" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"audio_url":"https://cdn.example/audio"}""")),
                ),
                "https://api.gladia.io/v2/pre-recorded" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"result_url":"https://api.gladia.io/v2/pre-recorded/job"}""")),
                ),
                "https://api.gladia.io/v2/pre-recorded/job" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"status":"done","result":{"metadata":{"audio_duration":3.5},"transcription":{"full_transcript":"hello world","languages":["en"],"utterances":[{"start":0.1,"end":0.3,"text":"hello"},{"start":0.4,"end":0.8,"text":"world"}]}}}""",
                        ),
                        headers = mapOf("x-final" to "true"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createGladia(
            fixture.httpClient(),
            GladiaProviderSettings(apiKey = "key", pollingIntervalMillis = 0),
        ).transcription()

        val result = model.transcribe(
            TranscriptionParams(
                audio = AudioSource(
                    mediaType = "audio/mpeg",
                    base64 = Base64Codec.encode("abc".encodeToByteArray()),
                    filename = "clip.mp3",
                ),
                language = "en",
                providerOptions = mapOf(
                    "gladia" to buildJsonObject {
                        put("contextPrompt", JsonPrimitive("domain words"))
                        put("customVocabulary", buildJsonArray { add(JsonPrimitive("Kotlin")) })
                        put("detectLanguage", JsonPrimitive(true))
                        put("diarization", JsonPrimitive(true))
                        put("translation", JsonPrimitive(true))
                        put("summarization", JsonPrimitive(true))
                        put("namedEntityRecognition", JsonPrimitive(true))
                        put("structuredDataExtraction", JsonPrimitive(true))
                        put("audioToLlm", JsonPrimitive(true))
                        put("customMetadata", buildJsonObject { put("source", JsonPrimitive("test")) })
                        put("sentences", JsonPrimitive(true))
                        put(
                            "customVocabularyConfig",
                            buildJsonObject {
                                put("vocabulary", buildJsonArray { add(JsonPrimitive("AISDK")) })
                                put("defaultIntensity", JsonPrimitive(0.7f))
                            },
                        )
                        put(
                            "callbackConfig",
                            buildJsonObject {
                                put("url", JsonPrimitive("https://example.com/hook"))
                                put("method", JsonPrimitive("POST"))
                            },
                        )
                        put(
                            "subtitlesConfig",
                            buildJsonObject {
                                put("formats", buildJsonArray { add(JsonPrimitive("srt")) })
                                put("maximumCharactersPerRow", JsonPrimitive(42))
                            },
                        )
                        put(
                            "diarizationConfig",
                            buildJsonObject {
                                put("numberOfSpeakers", JsonPrimitive(2))
                            },
                        )
                        put(
                            "translationConfig",
                            buildJsonObject {
                                put("targetLanguages", buildJsonArray { add(JsonPrimitive("es")) })
                                put("model", JsonPrimitive("enhanced"))
                                put("matchOriginalUtterances", JsonPrimitive(true))
                            },
                        )
                        put("summarizationConfig", buildJsonObject { put("type", JsonPrimitive("bullet_points")) })
                        put(
                            "customSpellingConfig",
                            buildJsonObject {
                                put("spellingDictionary", buildJsonObject {
                                    put("KMP", buildJsonArray { add(JsonPrimitive("k m p")) })
                                })
                            },
                        )
                    },
                ),
            ),
        )

        assertEquals("gladia.transcription", model.provider)
        assertEquals("hello world", result.text)
        assertEquals(2, result.segments.size)
        assertEquals(0.1f, result.segments.first().startSeconds)
        assertEquals(0.8f, result.segments.last().endSeconds)
        assertEquals("en", result.language)
        assertEquals(3.5f, result.durationInSeconds)
        assertEquals("true", result.response.headers["x-final"])
        assertEquals("done", result.providerMetadata["gladia"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull)

        val upload = fixture.calls[0]
        assertEquals("POST", upload.requestMethod)
        assertEquals("key", upload.requestHeaders.headerValue("x-gladia-key"))
        assertTrue(upload.requestUserAgent.orEmpty().contains("ai-sdk/gladia/$GLADIA_VERSION"))
        assertTrue(upload.requestBodyText.contains("clip.mp3"))
        assertTrue(upload.requestBodyText.contains("abc"))

        val init = fixture.calls[1]
        assertEquals("POST", init.requestMethod)
        assertEquals("key", init.requestHeaders.headerValue("x-gladia-key"))
        val body = init.requestBodyJson.jsonObject
        assertEquals("https://cdn.example/audio", body["audio_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("domain words", body["context_prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("en", body["language"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Kotlin", body["custom_vocabulary"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
        assertEquals(0.7f, body["custom_vocabulary_config"]?.jsonObject?.get("default_intensity")?.jsonPrimitive?.floatOrNull)
        assertEquals("POST", body["callback_config"]?.jsonObject?.get("method")?.jsonPrimitive?.contentOrNull)
        assertEquals(42, body["subtitles_config"]?.jsonObject?.get("maximum_characters_per_row")?.jsonPrimitive?.intOrNull)
        assertEquals(2, body["diarization_config"]?.jsonObject?.get("number_of_speakers")?.jsonPrimitive?.intOrNull)
        assertEquals("es", body["translation_config"]?.jsonObject?.get("target_languages")?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
        assertEquals("bullet_points", body["summarization_config"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("test", body["custom_metadata"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull)
        assertEquals("k m p", body["custom_spelling_config"]?.jsonObject
            ?.get("spelling_dictionary")?.jsonObject
            ?.get("KMP")?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)

        val poll = fixture.calls[2]
        assertEquals("GET", poll.requestMethod)
        assertEquals("key", poll.requestHeaders.headerValue("x-gladia-key"))
    }

    @Test
    fun `transcription model throws when job fails`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.gladia.io/v2/upload" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"audio_url":"https://cdn.example/audio"}""")),
                ),
                "https://api.gladia.io/v2/pre-recorded" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"result_url":"https://api.gladia.io/v2/pre-recorded/job"}""")),
                ),
                "https://api.gladia.io/v2/pre-recorded/job" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"status":"error"}""")),
                ),
            ),
        )
        fixture.server.start()
        val model = createGladia(
            fixture.httpClient(),
            GladiaProviderSettings(apiKey = "key", pollingIntervalMillis = 0),
        ).transcription()

        val error = assertFailsWith<AiSdkException> {
            model.transcribe(TranscriptionParams(audio = AudioSource("audio/wav", Base64Codec.encode(byteArrayOf(1)))))
        }
        assertTrue(error.message.orEmpty().contains("failed"))
    }

    @Test
    fun `default provider and unsupported model families fail explicitly`() {
        val fixture = createTestServer(mutableMapOf())
        val provider = createGladia(fixture.httpClient(), GladiaProviderSettings(apiKey = "key"))

        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<AiSdkException> { gladia.transcription() }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
