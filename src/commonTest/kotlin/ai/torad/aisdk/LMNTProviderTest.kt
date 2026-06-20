package ai.torad.aisdk
import ai.torad.aisdk.providers.LMNT_VERSION
import ai.torad.aisdk.providers.LMNTProviderSettings

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
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
import ai.torad.aisdk.providers.LMNT

class LMNTProviderTest {
    @Test
    fun `speech model sends lmnt request shape and returns binary audio`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.lmnt.com/v1/ai/speech/bytes" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(1, 2, 3), headers = mapOf(HttpHeaders.ContentType to "audio/wav")),
                ),
            ),
        )
        fixture.server.start()
        val model = LMNT(
            fixture.httpClient(),
            LMNTProviderSettings(apiKey = "key"),
        ).speech("aurora")

        val result = model.generate(
            SpeechGenerationParams(
                text = "hello",
                voice = "ava",
                responseFormat = "wav",
                language = "fr",
                speed = 1.1f,
                providerOptions = mapOf(
                    "lmnt" to buildJsonObject {
                        put("seed", JsonPrimitive(123))
                        put("sampleRate", JsonPrimitive(24000))
                        put("topP", JsonPrimitive(0.9f))
                        put("temperature", JsonPrimitive(0.5f))
                    },
                ),
            ),
        )

        assertEquals("lmnt.speech", model.provider)
        assertEquals("audio/wav", result.audio?.mediaType)
        assertEquals(Base64Codec.encode(byteArrayOf(1, 2, 3)), result.audio?.base64)
        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("https://api.lmnt.com/v1/ai/speech/bytes", request.requestUrl)
        assertEquals("key", request.requestHeaders.headerValue("x-api-key"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/lmnt/$LMNT_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("aurora", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hello", body["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ava", body["voice"]?.jsonPrimitive?.contentOrNull)
        assertEquals("wav", body["response_format"]?.jsonPrimitive?.contentOrNull)
        assertEquals("fr", body["language"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1.1f, body["speed"]?.jsonPrimitive?.floatOrNull)
        assertEquals(123, body["seed"]?.jsonPrimitive?.intOrNull)
        assertEquals(24000, body["sample_rate"]?.jsonPrimitive?.intOrNull)
        assertEquals(0.9f, body["top_p"]?.jsonPrimitive?.floatOrNull)
        assertEquals(0.5f, body["temperature"]?.jsonPrimitive?.floatOrNull)
    }

    @Test
    fun `speech model warns and falls back to mp3 for unsupported format`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.lmnt.com/v1/ai/speech/bytes" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(4, 5, 6)),
                ),
            ),
        )
        fixture.server.start()
        val model = LMNT(
            fixture.httpClient(),
            LMNTProviderSettings(apiKey = "key"),
        ).speech("aurora")

        val result = model.generate(
            SpeechGenerationParams(text = "hello", responseFormat = "flac"),
        )

        assertEquals("unsupported", result.warnings.single().type)
        assertEquals("mp3", fixture.calls.single().requestBodyJson.jsonObject["response_format"]?.jsonPrimitive?.contentOrNull)
        assertEquals("audio/mpeg", result.audio?.mediaType)
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
