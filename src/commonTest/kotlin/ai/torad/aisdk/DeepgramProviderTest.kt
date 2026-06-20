package ai.torad.aisdk
import ai.torad.aisdk.providers.DEEPGRAM_VERSION
import ai.torad.aisdk.providers.DeepgramProviderSettings
import ai.torad.aisdk.providers.createDeepgram
import ai.torad.aisdk.providers.deepgram

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

class DeepgramProviderTest {
    @Test
    fun `speech model maps output format provider options warnings and binary audio`() = runTest {
        val url = "https://api.deepgram.com/v1/speak?model=aura-2-helena-en&encoding=opus&container=ogg&bit_rate=64000&callback=https%3A%2F%2Fexample.com%2Fhook&callback_method=POST&mip_opt_out=true&tag=alpha%2Cbeta"
        val fixture = createTestServer(
            mutableMapOf(
                url to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(1, 2, 3), headers = mapOf(HttpHeaders.ContentType to "audio/ogg")),
                ),
            ),
        )
        fixture.server.start()
        val model = createDeepgram(
            fixture.httpClient(),
            DeepgramProviderSettings(apiKey = "key"),
        ).speech("aura-2-helena-en")

        val result = model.generate(
            SpeechGenerationParams(
                text = "hello",
                voice = "different",
                speed = 1.2f,
                instructions = "soft",
                responseFormat = "opus",
                providerOptions = mapOf(
                    "deepgram" to buildJsonObject {
                        put("bitRate", JsonPrimitive(64000))
                        put("sampleRate", JsonPrimitive(16000))
                        put("callback", JsonPrimitive("https://example.com/hook"))
                        put("callbackMethod", JsonPrimitive("POST"))
                        put("mipOptOut", JsonPrimitive(true))
                        put("tag", buildJsonArray {
                            add(JsonPrimitive("alpha"))
                            add(JsonPrimitive("beta"))
                        })
                    },
                ),
            ),
        )

        assertEquals("deepgram.speech", model.provider)
        assertEquals("audio/ogg", result.audio?.mediaType)
        assertEquals(Base64Codec.encode(byteArrayOf(1, 2, 3)), result.audio?.base64)
        assertEquals(4, result.warnings.size)
        assertTrue(result.warnings.any { it.message.orEmpty().contains("sample_rate") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("voice parameter") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("speed adjustment") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("instructions") })

        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("Token key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/deepgram/$DEEPGRAM_VERSION"))
        assertEquals("hello", request.requestBodyJson.jsonObject["text"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `transcription model maps query options audio body and word segments`() = runTest {
        val url = "https://api.deepgram.com/v1/listen?model=nova-3&diarize=false&detect_language=true&filler_words=true&language=en&punctuate=true&redact=pii%2Cssn&search=Kotlin&smart_format=true&summarize=v2&topics=true&utterances=true&utt_split=0.8&paragraphs=true&intents=true&sentiment=true&replace=redacted&keyterm=sdk"
        val fixture = createTestServer(
            mutableMapOf(
                url to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"metadata":{"duration":3.5},"results":{"channels":[{"detected_language":"en","alternatives":[{"transcript":"hello world","words":[{"word":"hello","start":0.1,"end":0.3},{"word":"world","start":0.4,"end":0.8}]}]}]}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createDeepgram(
            fixture.httpClient(),
            DeepgramProviderSettings(apiKey = "key"),
        ).transcription("nova-3")

        val result = model.transcribe(
            TranscriptionParams(
                audio = AudioSource(
                    mediaType = "audio/wav",
                    base64 = Base64Codec.encode("abc".encodeToByteArray()),
                    filename = "clip.wav",
                ),
                providerOptions = mapOf(
                    "deepgram" to buildJsonObject {
                        put("diarize", JsonPrimitive(false))
                        put("detectLanguage", JsonPrimitive(true))
                        put("fillerWords", JsonPrimitive(true))
                        put("language", JsonPrimitive("en"))
                        put("punctuate", JsonPrimitive(true))
                        put("redact", buildJsonArray {
                            add(JsonPrimitive("pii"))
                            add(JsonPrimitive("ssn"))
                        })
                        put("search", JsonPrimitive("Kotlin"))
                        put("smartFormat", JsonPrimitive(true))
                        put("summarize", JsonPrimitive("v2"))
                        put("topics", JsonPrimitive(true))
                        put("utterances", JsonPrimitive(true))
                        put("uttSplit", JsonPrimitive(0.8f))
                        put("paragraphs", JsonPrimitive(true))
                        put("intents", JsonPrimitive(true))
                        put("sentiment", JsonPrimitive(true))
                        put("replace", JsonPrimitive("redacted"))
                        put("keyterm", JsonPrimitive("sdk"))
                    },
                ),
            ),
        )

        assertEquals("deepgram.transcription", model.provider)
        assertEquals("hello world", result.text)
        assertEquals(2, result.segments.size)
        assertEquals(0.1f, result.segments.first().startSeconds)
        assertEquals(0.8f, result.segments.last().endSeconds)
        assertEquals("en", result.language)
        assertEquals(3.5f, result.durationInSeconds)
        assertEquals("hello world", result.response.body?.jsonObject
            ?.get("results")?.jsonObject
            ?.get("channels")?.jsonArray?.first()?.jsonObject
            ?.get("alternatives")?.jsonArray?.first()?.jsonObject
            ?.get("transcript")?.jsonPrimitive?.contentOrNull)

        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("Token key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/deepgram/$DEEPGRAM_VERSION"))
        assertEquals("abc", request.requestBodyText)
    }

    @Test
    fun `transcription model uses top-level language when deepgram option is omitted`() = runTest {
        val url = "https://api.deepgram.com/v1/listen?model=nova-3&diarize=true&language=pt"
        val fixture = createTestServer(
            mutableMapOf(
                url to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"results":{"channels":[{"alternatives":[{"transcript":"ola","words":[]}]}]}}""")),
                ),
            ),
        )
        fixture.server.start()
        val model = createDeepgram(
            fixture.httpClient(),
            DeepgramProviderSettings(apiKey = "key"),
        ).transcription("nova-3")

        val result = model.transcribe(
            TranscriptionParams(
                audio = AudioSource("audio/wav", Base64Codec.encode(byteArrayOf(1))),
                language = "pt",
            ),
        )

        assertEquals("ola", result.text)
    }

    @Test
    fun `default provider and unsupported model families fail explicitly`() {
        val fixture = createTestServer(mutableMapOf())
        val provider = createDeepgram(fixture.httpClient(), DeepgramProviderSettings(apiKey = "key"))

        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<AiSdkException> { deepgram.speech("aura-2-helena-en") }
        assertFailsWith<AiSdkException> { deepgram.transcription("nova-3") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
