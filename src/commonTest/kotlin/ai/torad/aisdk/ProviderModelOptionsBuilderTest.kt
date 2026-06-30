package ai.torad.aisdk

import ai.torad.aisdk.providers.BasetenEmbeddingModelOptions
import ai.torad.aisdk.providers.AlibabaEmbeddingModelOptions
import ai.torad.aisdk.providers.AlibabaLanguageModelOptions
import ai.torad.aisdk.providers.AlibabaVideoModelOptions
import ai.torad.aisdk.providers.AssemblyAICustomSpelling
import ai.torad.aisdk.providers.AssemblyAITranscriptionModelOptions
import ai.torad.aisdk.providers.BlackForestLabsImageModelOptions
import ai.torad.aisdk.providers.ByteDanceVideoProviderOptions
import ai.torad.aisdk.providers.CohereEmbeddingModelOptions
import ai.torad.aisdk.providers.CohereLanguageModelOptions
import ai.torad.aisdk.providers.CohereRerankingModelOptions
import ai.torad.aisdk.providers.CohereThinkingOptions
import ai.torad.aisdk.providers.DeepSeekLanguageModelOptions
import ai.torad.aisdk.providers.ElevenLabsSpeechModelOptions
import ai.torad.aisdk.providers.ElevenLabsTranscriptionModelOptions
import ai.torad.aisdk.providers.FalImageModelOptions
import ai.torad.aisdk.providers.FalSpeechModelOptions
import ai.torad.aisdk.providers.FalTranscriptionModelOptions
import ai.torad.aisdk.providers.FalVideoModelOptions
import ai.torad.aisdk.providers.FireworksEmbeddingModelOptions
import ai.torad.aisdk.providers.DeepgramSpeechModelOptions
import ai.torad.aisdk.providers.DeepgramTranscriptionModelOptions
import ai.torad.aisdk.providers.TogetherAIRerankingModelOptions
import ai.torad.aisdk.providers.VoyageEmbeddingModelOptions
import ai.torad.aisdk.providers.VoyageRerankingModelOptions
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProviderModelOptionsBuilderTest {
    @Test
    fun `Cohere model options DSL keeps value semantics and serialization`() {
        val thinking = CohereThinkingOptions {
            type("enabled")
            tokenBudget(512)
        }
        val options = CohereLanguageModelOptions {
            thinking(thinking)
        }
        val equal = CohereLanguageModelOptions {
            thinking(CohereThinkingOptions {
                type("enabled")
                tokenBudget(512)
            })
        }
        val different = CohereLanguageModelOptions {
            thinking(CohereThinkingOptions {
                type("disabled")
            })
        }

        assertEquals(equal, options)
        assertEquals(equal.hashCode(), options.hashCode())
        assertNotEquals(different, options)
        assertEquals(options, aiSdkJson.decodeFromString<CohereLanguageModelOptions>(aiSdkJson.encodeToString(options)))
    }

    @Test
    fun `embedding option DSLs keep field values and value semantics`() {
        val cohere = CohereEmbeddingModelOptions {
            inputType("search_document")
            truncate("END")
            outputDimension(512)
        }
        val voyage = VoyageEmbeddingModelOptions {
            inputType("document")
            truncation(true)
            outputDimension(1024)
            outputDtype("float")
        }
        val baseten = BasetenEmbeddingModelOptions {
            raw(mapOf("normalize" to JsonPrimitive(true)))
        }

        assertEquals("search_document", cohere.inputType)
        assertEquals(cohere, CohereEmbeddingModelOptions {
            inputType("search_document")
            truncate("END")
            outputDimension(512)
        })
        assertEquals(voyage, aiSdkJson.decodeFromString<VoyageEmbeddingModelOptions>(aiSdkJson.encodeToString(voyage)))
        assertEquals(baseten, BasetenEmbeddingModelOptions {
            raw(mapOf("normalize" to JsonPrimitive(true)))
        })
        assertNotEquals(baseten, BasetenEmbeddingModelOptions {
            raw(mapOf("normalize" to JsonPrimitive(false)))
        })
    }

    @Test
    fun `reranking option DSLs keep value semantics`() {
        val cohere = CohereRerankingModelOptions {
            maxTokensPerDoc(400)
            priority(2)
        }
        val voyage = VoyageRerankingModelOptions {
            returnDocuments(true)
            truncation(false)
        }
        val together = TogetherAIRerankingModelOptions {
            rankFields(listOf("title", "body"))
        }

        assertEquals(cohere, CohereRerankingModelOptions {
            maxTokensPerDoc(400)
            priority(2)
        })
        assertEquals(voyage, aiSdkJson.decodeFromString<VoyageRerankingModelOptions>(aiSdkJson.encodeToString(voyage)))
        assertEquals(together, TogetherAIRerankingModelOptions {
            rankFields(listOf("title", "body"))
        })
        assertNotEquals(together, TogetherAIRerankingModelOptions {
            rankFields(listOf("body"))
        })
    }

    @Test
    fun `media provider option DSLs keep value semantics and serialization`() {
        val alibabaEmbedding = AlibabaEmbeddingModelOptions {
            textType("query")
            dimension(1536)
            outputType("dense")
        }
        val alibabaLanguage = AlibabaLanguageModelOptions {
            enableThinking(true)
            thinkingBudget(512)
            parallelToolCalls(false)
        }
        val alibabaVideo = AlibabaVideoModelOptions {
            negativePrompt("low quality")
            audioUrl("https://example.test/audio.wav")
            promptExtend(true)
            shotType("close-up")
            watermark(false)
            audio(true)
            referenceUrls(listOf("https://example.test/reference.png"))
            pollIntervalMs(250)
            pollTimeoutMs(5_000)
        }
        val assembly = AssemblyAITranscriptionModelOptions {
            languageCode("en")
            autoChapters(true)
            customSpelling(listOf(AssemblyAICustomSpelling(listOf("ai sdk"), "AI SDK")))
            wordBoost(listOf("torad"))
        }
        val blackForestLabs = BlackForestLabsImageModelOptions {
            imagePrompt("product shot")
            imagePromptStrength(0.6)
            inputImage("base64-image")
            inputImage2("base64-image-2")
            steps(30)
            guidance(3.5)
            width(1024)
            height(768)
            outputFormat("png")
            promptUpsampling(true)
            raw(false)
            safetyTolerance(2)
            webhookUrl("https://example.test/hook")
            pollIntervalMillis(500)
            pollTimeoutMillis(10_000)
        }
        val byteDance = ByteDanceVideoProviderOptions {
            watermark(false)
            generateAudio(true)
            cameraFixed(true)
            returnLastFrame(false)
            serviceTier("standard")
            draft(false)
            lastFrameImage("last-frame")
            referenceImages(listOf("image-1"))
            referenceVideos(listOf("video-1"))
            referenceAudio(listOf("audio-1"))
            pollIntervalMs(300)
            pollTimeoutMs(6_000)
        }
        val deepgramSpeech = DeepgramSpeechModelOptions {
            bitRate(JsonPrimitive(128000))
            container("wav")
            encoding("linear16")
            sampleRate(16000)
            callback("https://example.test/callback")
            callbackMethod("POST")
            mipOptOut(true)
            tag(JsonPrimitive("batch-1"))
        }
        val deepgramTranscription = DeepgramTranscriptionModelOptions {
            language("en")
            detectLanguage(false)
            smartFormat(true)
            punctuate(true)
            paragraphs(true)
            summarize(JsonPrimitive("v2"))
            redact(JsonPrimitive("pci"))
            diarize(true)
            uttSplit(0.8f)
            fillerWords(false)
        }

        assertEquals(alibabaEmbedding, aiSdkJson.decodeFromString<AlibabaEmbeddingModelOptions>(aiSdkJson.encodeToString(alibabaEmbedding)))
        assertEquals(alibabaLanguage, AlibabaLanguageModelOptions {
            enableThinking(true)
            thinkingBudget(512)
            parallelToolCalls(false)
        })
        assertNotEquals(alibabaVideo, AlibabaVideoModelOptions {
            negativePrompt("different")
        })
        assertEquals(assembly, aiSdkJson.decodeFromString<AssemblyAITranscriptionModelOptions>(aiSdkJson.encodeToString(assembly)))
        assertEquals(blackForestLabs, aiSdkJson.decodeFromString<BlackForestLabsImageModelOptions>(aiSdkJson.encodeToString(blackForestLabs)))
        assertEquals(byteDance, ByteDanceVideoProviderOptions {
            watermark(false)
            generateAudio(true)
            cameraFixed(true)
            returnLastFrame(false)
            serviceTier("standard")
            draft(false)
            lastFrameImage("last-frame")
            referenceImages(listOf("image-1"))
            referenceVideos(listOf("video-1"))
            referenceAudio(listOf("audio-1"))
            pollIntervalMs(300)
            pollTimeoutMs(6_000)
        })
        assertEquals(deepgramSpeech, aiSdkJson.decodeFromString<DeepgramSpeechModelOptions>(aiSdkJson.encodeToString(deepgramSpeech)))
        assertEquals(
            deepgramTranscription,
            aiSdkJson.decodeFromString<DeepgramTranscriptionModelOptions>(aiSdkJson.encodeToString(deepgramTranscription)),
        )
    }

    @Test
    fun `additional provider option DSLs keep value semantics and serialization`() {
        val deepSeek = DeepSeekLanguageModelOptions {
            raw(mapOf("temperature" to JsonPrimitive(0.2)))
        }
        val elevenLabsSpeech = ElevenLabsSpeechModelOptions {
            languageCode("en")
            voiceSettings(buildJsonObject { put("stability", JsonPrimitive(0.5)) })
            seed(12)
            previousText("before")
            nextText("after")
            previousRequestIds(listOf("prev"))
            nextRequestIds(listOf("next"))
            applyTextNormalization("auto")
            applyLanguageTextNormalization(true)
            enableLogging(false)
        }
        val elevenLabsTranscription = ElevenLabsTranscriptionModelOptions {
            languageCode("en")
            tagAudioEvents(true)
            numSpeakers(2)
            timestampsGranularity("word")
            diarize(true)
            fileFormat("mp3")
        }
        val falImage = FalImageModelOptions {
            useMultipleImages(true)
        }
        val falSpeech = FalSpeechModelOptions {
            voice_setting(buildJsonObject { put("similarity_boost", JsonPrimitive(0.7)) })
            audio_setting(buildJsonObject { put("format", JsonPrimitive("mp3")) })
            language_boost("en")
            pronunciation_dict(buildJsonObject { put("id", JsonPrimitive("dict-1")) })
        }
        val falTranscription = FalTranscriptionModelOptions {
            language("es")
            diarize(false)
            chunkLevel("word")
            version("4")
            batchSize(32)
            numSpeakers(2)
        }
        val falVideo = FalVideoModelOptions {
            loop(true)
            motionStrength(0.4f)
            pollIntervalMs(250)
            pollTimeoutMs(5_000)
            resolution("720p")
            negativePrompt("blur")
            promptOptimizer(true)
        }
        val fireworks = FireworksEmbeddingModelOptions {
            raw(mapOf("user" to JsonPrimitive("test-user")))
        }

        assertEquals(deepSeek, aiSdkJson.decodeFromString<DeepSeekLanguageModelOptions>(aiSdkJson.encodeToString(deepSeek)))
        assertEquals(elevenLabsSpeech, aiSdkJson.decodeFromString<ElevenLabsSpeechModelOptions>(aiSdkJson.encodeToString(elevenLabsSpeech)))
        assertEquals(elevenLabsTranscription, ElevenLabsTranscriptionModelOptions {
            languageCode("en")
            tagAudioEvents(true)
            numSpeakers(2)
            timestampsGranularity("word")
            diarize(true)
            fileFormat("mp3")
        })
        assertEquals(falImage, aiSdkJson.decodeFromString<FalImageModelOptions>(aiSdkJson.encodeToString(falImage)))
        assertEquals(falSpeech, aiSdkJson.decodeFromString<FalSpeechModelOptions>(aiSdkJson.encodeToString(falSpeech)))
        assertEquals(falTranscription, aiSdkJson.decodeFromString<FalTranscriptionModelOptions>(aiSdkJson.encodeToString(falTranscription)))
        assertNotEquals(falVideo, FalVideoModelOptions { resolution("1080p") })
        assertEquals(fireworks, aiSdkJson.decodeFromString<FireworksEmbeddingModelOptions>(aiSdkJson.encodeToString(fireworks)))

        val falTranscriptionDefaults = FalTranscriptionModelOptions()
        assertEquals("en", falTranscriptionDefaults.language)
        assertEquals(true, falTranscriptionDefaults.diarize)
        assertEquals("segment", falTranscriptionDefaults.chunkLevel)
        assertEquals("3", falTranscriptionDefaults.version)
        assertEquals(64, falTranscriptionDefaults.batchSize)
    }
}
