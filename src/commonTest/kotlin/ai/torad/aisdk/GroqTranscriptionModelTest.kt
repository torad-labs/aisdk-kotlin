package ai.torad.aisdk

import ai.torad.aisdk.providers.Groq
import ai.torad.aisdk.providers.GroqProviderSettings
import ai.torad.aisdk.providers.GroqTranscriptionModelOptions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class GroqTranscriptionModelTest {
    @Test
    fun `typed camel response format omits nullable defaults and keeps canonical JSON`() = runTest {
        val (legacyOptions, rawResponseFormat) = legacyTextResponseFormatOptions()
        val serializedLegacyOptions = aiSdkOutputJson.encodeToJsonElement(
            GroqTranscriptionModelOptions.serializer(),
            legacyOptions,
        ).jsonObject
        val (result, fixture) = transcribeWithGroqOptions(
            ProviderOptions("groq" to serializedLegacyOptions),
        )

        assertEquals("text", rawResponseFormat)
        assertEquals("text", serializedLegacyOptions["responseFormat"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, serializedLegacyOptions["prompt"])
        assertEquals(JsonNull, serializedLegacyOptions["temperature"])
        assertEquals("decoded JSON response", result.text)
        assertEquals(1, fixture.calls.size)
        val request = fixture.calls.single()
        val multipart = assertNotNull(request.requestBodyMultipart)
        assertEquals("whisper-large-v3", multipart["model"])
        assertEquals("json", multipart["response_format"])
        assertEquals("en", multipart["language"])
        assertEquals(1, request.requestBodyText.multipartFieldCount("response_format"))
        assertEquals(
            emptyMap(),
            multipart.filterKeys { it == "prompt" || it == "temperature" },
            "JsonNull prompt and temperature must be omitted",
        )
        assertEquals(
            0,
            request.requestBodyText.multipartFieldCount("responseFormat"),
            "camel responseFormat must be excluded",
        )
        assertFalse("responseFormat" in multipart)
        assertEquals(setOf("model", "response_format", "language", "file"), multipart.keys)
    }

    @Test
    fun `raw snake response format preserves canonical JSON field`() = runTest {
        val providerOptions = ProviderOptions(
            "groq" to JsonObject(
                mapOf(
                    "response_format" to JsonPrimitive("text"),
                    "language" to JsonPrimitive("en"),
                ),
            ),
        )
        val (result, fixture) = transcribeWithGroqOptions(providerOptions)

        assertEquals("decoded JSON response", result.text)
        assertEquals(1, fixture.calls.size)
        val request = fixture.calls.single()
        val multipart = assertNotNull(request.requestBodyMultipart)
        assertEquals("whisper-large-v3", multipart["model"])
        assertEquals(
            1,
            request.requestBodyText.multipartFieldCount("response_format"),
            "raw snake response_format must not duplicate the canonical field",
        )
        assertEquals("json", multipart["response_format"])
        assertEquals("en", multipart["language"])
        assertEquals(0, request.requestBodyText.multipartFieldCount("responseFormat"))
        assertFalse("responseFormat" in multipart)
        assertEquals(setOf("model", "response_format", "language", "file"), multipart.keys)
    }

    private suspend fun transcribeWithGroqOptions(
        providerOptions: ProviderOptions,
    ): Pair<TranscribeResult, CreatedTestServer> {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://groq.test/openai/v1/audio/transcriptions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"text":"decoded JSON response","segments":[]}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = Groq(
            fixture.httpClient(),
            GroqProviderSettings {
                baseURL("https://groq.test/openai/v1")
            },
        ).transcription("whisper-large-v3")
        val result = Transcription.transcribe(
            model = model,
            audio = AudioSource(
                mediaType = "audio/wav",
                base64 = Base64Codec.encode("audio".encodeToByteArray()),
                filename = "sample.wav",
            ),
            providerOptions = providerOptions,
        )
        return result to fixture
    }

    @Suppress("DEPRECATION")
    private fun legacyTextResponseFormatOptions(): Pair<GroqTranscriptionModelOptions, String?> {
        val options = GroqTranscriptionModelOptions {
            language("en")
            responseFormat("text")
        }
        return options to options.responseFormat
    }

    private fun String.multipartFieldCount(name: String): Int =
        split("name=\"$name\"").size - 1
}
