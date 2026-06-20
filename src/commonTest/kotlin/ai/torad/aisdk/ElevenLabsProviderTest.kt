package ai.torad.aisdk
import ai.torad.aisdk.providers.ELEVENLABS_VERSION
import ai.torad.aisdk.providers.ElevenLabsProviderSettings
import ai.torad.aisdk.providers.createElevenLabs
import ai.torad.aisdk.providers.elevenlabs

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

class ElevenLabsProviderTest {
    @Test
    fun `speech model maps query body headers and binary audio`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.elevenlabs.io/v1/text-to-speech/voice-1?output_format=mp3_44100_192&enable_logging=false" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(1, 2, 3), headers = mapOf(HttpHeaders.ContentType to "audio/mpeg")),
                ),
            ),
        )
        fixture.server.start()
        val model = createElevenLabs(
            fixture.httpClient(),
            ElevenLabsProviderSettings(apiKey = "key"),
        ).speech("eleven_multilingual_v2")

        val result = model.generate(
            SpeechGenerationParams(
                text = "hello",
                voice = "voice-1",
                responseFormat = "mp3_192",
                language = "es",
                speed = 1.2f,
                instructions = "ignore",
                providerOptions = mapOf(
                    "elevenlabs" to buildJsonObject {
                        put("seed", JsonPrimitive(123))
                        // languageCode is set too, but params.language ("es") must win (upstream order).
                        put("languageCode", JsonPrimitive("fr"))
                        put("enableLogging", JsonPrimitive(false))
                        put(
                            "voiceSettings",
                            buildJsonObject {
                                put("similarityBoost", JsonPrimitive(0.7f))
                                put("useSpeakerBoost", JsonPrimitive(true))
                            },
                        )
                    },
                ),
            ),
        )

        assertEquals("elevenlabs.speech", model.provider)
        assertEquals(Base64Codec.encode(byteArrayOf(1, 2, 3)), result.audio?.base64)
        assertEquals("unsupported", result.warnings.single().type)
        val request = fixture.calls.single()
        assertEquals("key", request.requestHeaders.headerValue("xi-api-key"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/elevenlabs/$ELEVENLABS_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("hello", body["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("eleven_multilingual_v2", body["model_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("es", body["language_code"]?.jsonPrimitive?.contentOrNull)
        assertEquals(123, body["seed"]?.jsonPrimitive?.intOrNull)
        assertEquals(1.2f, body["voice_settings"]?.jsonObject?.get("speed")?.jsonPrimitive?.floatOrNull)
        assertEquals(0.7f, body["voice_settings"]?.jsonObject?.get("similarity_boost")?.jsonPrimitive?.floatOrNull)
        assertEquals(true, body["voice_settings"]?.jsonObject?.get("use_speaker_boost")?.jsonPrimitive?.contentOrNull.toBoolean())
    }

    @Test
    fun `transcription model sends multipart options and maps words to segments`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.elevenlabs.io/v1/speech-to-text" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"language_code":"en","language_probability":0.99,"text":"hello world","words":[{"text":"hello","type":"word","start":0.1,"end":0.3},{"text":"world","type":"word","start":0.4,"end":0.8}]}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createElevenLabs(
            fixture.httpClient(),
            ElevenLabsProviderSettings(apiKey = "key"),
        ).transcription("scribe_v1")

        val result = model.transcribe(
            TranscriptionParams(
                audio = AudioSource(
                    mediaType = "audio/mpeg",
                    base64 = Base64Codec.encode(byteArrayOf(1, 2, 3)),
                    filename = "clip.mp3",
                ),
                providerOptions = mapOf(
                    "elevenlabs" to buildJsonObject {
                        put("languageCode", JsonPrimitive("en"))
                        put("diarize", JsonPrimitive(false))
                        put("numSpeakers", JsonPrimitive(2))
                        put("timestampsGranularity", JsonPrimitive("word"))
                        put("fileFormat", JsonPrimitive("other"))
                    },
                ),
            ),
        )

        assertEquals("elevenlabs.transcription", model.provider)
        assertEquals("hello world", result.text)
        assertEquals(2, result.segments.size)
        assertEquals(0.1f, result.segments.first().startSeconds)
        assertEquals("en", result.language)
        assertEquals(0.8f, result.durationInSeconds)
        val request = fixture.calls.single()
        assertEquals("key", request.requestHeaders.headerValue("xi-api-key"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/elevenlabs/$ELEVENLABS_VERSION"))
        assertTrue(request.requestBodyText.contains("model_id"))
        assertTrue(request.requestBodyText.contains("scribe_v1"))
        assertTrue(request.requestBodyText.contains("diarize"))
        assertTrue(request.requestBodyText.contains("false"))
        assertTrue(request.requestBodyText.contains("language_code"))
        assertTrue(request.requestBodyText.contains("en"))
        assertTrue(request.requestBodyText.contains("num_speakers"))
        assertTrue(request.requestBodyText.contains("2"))
        assertTrue(request.requestBodyText.contains("timestamps_granularity"))
        assertTrue(request.requestBodyText.contains("word"))
        assertTrue(request.requestBodyText.contains("file_format"))
        assertTrue(request.requestBodyText.contains("other"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
