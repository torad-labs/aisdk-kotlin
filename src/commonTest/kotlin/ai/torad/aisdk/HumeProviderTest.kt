package ai.torad.aisdk
import ai.torad.aisdk.providers.HUME_VERSION
import ai.torad.aisdk.providers.HumeProviderSettings
import ai.torad.aisdk.providers.createHume
import ai.torad.aisdk.providers.hume

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertTrue

class HumeProviderTest {
    @Test
    fun `speech model sends hume request shape and returns binary audio`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.hume.ai/v0/tts/file" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(1, 2, 3), headers = mapOf(HttpHeaders.ContentType to "audio/wav")),
                ),
            ),
        )
        fixture.server.start()
        val model = createHume(
            fixture.httpClient(),
            HumeProviderSettings(apiKey = "key"),
        ).speech()

        val result = model.generate(
            SpeechGenerationParams(
                text = "hello",
                voice = "voice-1",
                instructions = "calm",
                speed = 1.1f,
                responseFormat = "wav",
                providerOptions = mapOf(
                    "hume" to buildJsonObject {
                        put(
                            "context",
                            buildJsonObject {
                                put(
                                    "utterances",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("text", JsonPrimitive("prior"))
                                                put("trailingSilence", JsonPrimitive(0.2f))
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

        assertEquals("hume.speech", model.provider)
        assertEquals(convertByteArrayToBase64(byteArrayOf(1, 2, 3)), result.audio?.base64)
        val request = fixture.calls.single()
        assertEquals("key", request.requestHeaders.headerValue("X-Hume-Api-Key"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/hume/$HUME_VERSION"))
        val body = request.requestBodyJson.jsonObject
        val utterance = body["utterances"]?.jsonArray?.single()?.jsonObject
        assertEquals("hello", utterance?.get("text")?.jsonPrimitive?.contentOrNull)
        assertEquals("calm", utterance?.get("description")?.jsonPrimitive?.contentOrNull)
        assertEquals(1.1f, utterance?.get("speed")?.jsonPrimitive?.floatOrNull)
        assertEquals("voice-1", utterance?.get("voice")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull)
        assertEquals("wav", body["format"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        val context = body["context"]?.jsonObject?.get("utterances")?.jsonArray?.single()?.jsonObject
        assertEquals("prior", context?.get("text")?.jsonPrimitive?.contentOrNull)
        assertEquals(0.2f, context?.get("trailing_silence")?.jsonPrimitive?.floatOrNull)
    }

    @Test
    fun `speech model warns and falls back to mp3 for unsupported format`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.hume.ai/v0/tts/file" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(4, 5, 6)),
                ),
            ),
        )
        fixture.server.start()
        val model = createHume(
            fixture.httpClient(),
            HumeProviderSettings(apiKey = "key"),
        ).speech()

        val result = model.generate(SpeechGenerationParams(text = "hello", responseFormat = "flac"))

        assertEquals("unsupported", result.warnings.single().type)
        assertEquals("mp3", fixture.calls.single().requestBodyJson.jsonObject["format"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("audio/mpeg", result.audio?.mediaType)
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
