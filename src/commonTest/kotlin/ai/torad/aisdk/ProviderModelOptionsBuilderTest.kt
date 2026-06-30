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
import ai.torad.aisdk.providers.FireworksLanguageModelOptions
import ai.torad.aisdk.providers.FireworksThinkingOptions
import ai.torad.aisdk.providers.GladiaTranscriptionModelOptions
import ai.torad.aisdk.providers.GroqLanguageModelOptions
import ai.torad.aisdk.providers.GroqTranscriptionModelOptions
import ai.torad.aisdk.providers.HumeSpeechModelOptions
import ai.torad.aisdk.providers.KlingAIVideoModelOptions
import ai.torad.aisdk.providers.LMNTSpeechModelOptions
import ai.torad.aisdk.providers.LumaImageModelOptions
import ai.torad.aisdk.providers.MistralLanguageModelOptions
import ai.torad.aisdk.providers.MoonshotAILanguageModelOptions
import ai.torad.aisdk.providers.ProdiaLanguageModelOptions
import ai.torad.aisdk.providers.ProdiaImageModelOptions
import ai.torad.aisdk.providers.ProdiaVideoModelOptions
import ai.torad.aisdk.providers.QuiverAIImageModelOptions
import ai.torad.aisdk.providers.ReplicateImageModelOptions
import ai.torad.aisdk.providers.ReplicateVideoModelOptions
import ai.torad.aisdk.providers.RevaiTranscriptionModelOptions
import ai.torad.aisdk.providers.DeepgramSpeechModelOptions
import ai.torad.aisdk.providers.DeepgramTranscriptionModelOptions
import ai.torad.aisdk.providers.TogetherAIImageModelOptions
import ai.torad.aisdk.providers.TogetherAIRerankingModelOptions
import ai.torad.aisdk.providers.VoyageEmbeddingModelOptions
import ai.torad.aisdk.providers.VoyageRerankingModelOptions
import ai.torad.aisdk.providers.XaiImageModelOptions
import ai.torad.aisdk.providers.XaiVideoModelOptions
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    @Test
    fun `facade media and transcription option DSLs keep value semantics and serialization`() {
        val fireworksThinking = FireworksThinkingOptions {
            type("enabled")
            budgetTokens(256)
        }
        val fireworks = FireworksLanguageModelOptions {
            thinking(fireworksThinking)
            reasoningHistory("previous")
            raw(mapOf("extra" to JsonPrimitive("value")))
        }
        val gladia = GladiaTranscriptionModelOptions {
            contextPrompt("meeting notes")
            customVocabulary(JsonPrimitive("torad"))
            customVocabularyConfig(buildJsonObject { put("default_intensity", JsonPrimitive(0.4)) })
            detectLanguage(true)
            enableCodeSwitching(true)
            codeSwitchingConfig(buildJsonObject { put("languages", buildJsonArray { add(JsonPrimitive("en")) }) })
            language("en")
            callback(true)
            callbackConfig(buildJsonObject { put("url", JsonPrimitive("https://example.test/callback")) })
            subtitles(true)
            subtitlesConfig(buildJsonObject { put("format", JsonPrimitive("srt")) })
            diarization(true)
            diarizationConfig(buildJsonObject { put("min_speakers", JsonPrimitive(1)) })
            translation(true)
            translationConfig(buildJsonObject { put("target_language", JsonPrimitive("es")) })
            summarization(true)
            summarizationConfig(buildJsonObject { put("type", JsonPrimitive("concise")) })
            moderation(true)
            namedEntityRecognition(true)
            chapterization(true)
            nameConsistency(true)
            customSpelling(true)
            customSpellingConfig(buildJsonObject { put("spelling", JsonPrimitive("AI SDK")) })
            structuredDataExtraction(true)
            structuredDataExtractionConfig(buildJsonObject { put("schema", JsonPrimitive("summary")) })
            sentimentAnalysis(true)
            audioToLlm(true)
            audioToLlmConfig(buildJsonObject { put("prompt", JsonPrimitive("summarize")) })
            customMetadata(buildJsonObject { put("job", JsonPrimitive("batch-1")) })
            sentences(true)
            displayMode(true)
            punctuationEnhanced(true)
        }
        val groqLanguage = GroqLanguageModelOptions {
            raw(mapOf("reasoning_effort" to JsonPrimitive("low")))
        }
        val groqTranscription = GroqTranscriptionModelOptions {
            language("en")
            prompt("Domain terms")
            temperature(0.2f)
            responseFormat("json")
        }
        val hume = HumeSpeechModelOptions {
            context(buildJsonObject { put("utterance", JsonPrimitive("hello")) })
        }
        val kling = KlingAIVideoModelOptions {
            mode("std")
            pollIntervalMs(500)
            pollTimeoutMs(10_000)
            negativePrompt("blur")
            sound("on")
            cfgScale(0.7f)
            cameraControl(buildJsonObject { put("type", JsonPrimitive("pan")) })
            imageTail("tail-image")
            staticMask("mask")
            dynamicMasks(buildJsonArray { add(buildJsonObject { put("id", JsonPrimitive("mask-1")) }) })
            multiShot(true)
            shotType("close-up")
            multiPrompt(buildJsonArray { add(JsonPrimitive("shot one")) })
            elementList(buildJsonArray { add(buildJsonObject { put("name", JsonPrimitive("product")) }) })
            voiceList(buildJsonArray { add(buildJsonObject { put("voice", JsonPrimitive("narrator")) }) })
            watermarkEnabled(false)
            videoUrl("https://example.test/source.mp4")
            characterOrientation("front")
            keepOriginalSound("true")
        }
        val luma = LumaImageModelOptions {
            referenceType("style")
            images(buildJsonArray { add(JsonPrimitive("image-ref")) })
            pollIntervalMillis(250)
            maxPollAttempts(4)
        }

        assertEquals(fireworks, aiSdkJson.decodeFromString<FireworksLanguageModelOptions>(aiSdkJson.encodeToString(fireworks)))
        assertEquals(gladia, aiSdkJson.decodeFromString<GladiaTranscriptionModelOptions>(aiSdkJson.encodeToString(gladia)))
        assertEquals(groqLanguage, aiSdkJson.decodeFromString<GroqLanguageModelOptions>(aiSdkJson.encodeToString(groqLanguage)))
        assertEquals(groqTranscription, GroqTranscriptionModelOptions {
            language("en")
            prompt("Domain terms")
            temperature(0.2f)
            responseFormat("json")
        })
        assertEquals(hume, aiSdkJson.decodeFromString<HumeSpeechModelOptions>(aiSdkJson.encodeToString(hume)))
        assertEquals(kling, aiSdkJson.decodeFromString<KlingAIVideoModelOptions>(aiSdkJson.encodeToString(kling)))
        assertEquals(luma, aiSdkJson.decodeFromString<LumaImageModelOptions>(aiSdkJson.encodeToString(luma)))
        assertNotEquals(luma, LumaImageModelOptions {
            referenceType("character")
        })
    }

    @Test
    fun `media audio and image option DSLs keep value semantics and serialization`() {
        val lmnt = LMNTSpeechModelOptions {
            model("aurora")
            format("mp3")
            sampleRate(24000)
            speed(1.1f)
            seed(42)
            conversational(true)
            length(0.8f)
            topP(0.9f)
            temperature(0.4f)
        }
        val revai = RevaiTranscriptionModelOptions {
            metadata("batch-1")
            notification_config(buildJsonObject { put("url", JsonPrimitive("https://example.test/revai")) })
            delete_after_seconds(3600)
            verbatim(true)
            rush(false)
            test_mode(true)
            segments_to_transcribe(buildJsonArray { add(JsonPrimitive("segment-1")) })
            speaker_names(buildJsonArray { add(JsonPrimitive("Ada")) })
            skip_diarization(false)
            skip_postprocessing(false)
            skip_punctuation(false)
            remove_disfluencies(true)
            remove_atmospherics(true)
            filter_profanity(false)
            speaker_channels_count(2)
            speakers_count(2)
            diarization_type("premium")
            custom_vocabulary_id("vocab-1")
            custom_vocabularies(buildJsonArray { add(JsonPrimitive("torad")) })
            strict_custom_vocabulary(true)
            summarization_config(buildJsonObject { put("type", JsonPrimitive("brief")) })
            translation_config(buildJsonObject { put("target_language", JsonPrimitive("es")) })
            language("en")
            forced_alignment(true)
        }
        val replicateImage = ReplicateImageModelOptions {
            maxWaitTimeInSeconds(20.0)
            guidance_scale(3.5)
            num_inference_steps(24.0)
            negative_prompt("blur")
            output_format("webp")
            output_quality(90)
            strength(0.7)
        }
        val replicateVideo = ReplicateVideoModelOptions {
            pollIntervalMs(500)
            pollTimeoutMs(10_000)
            maxWaitTimeInSeconds(30.0)
            guidance_scale(2.0)
            num_inference_steps(18.0)
            motion_bucket_id(127.0)
            cond_aug(0.1)
            decoding_t(7.0)
            video_length("14_frames")
            sizing_strategy("maintain_aspect_ratio")
            frames_per_second(8.0)
            prompt_optimizer(true)
        }
        val prodiaImage = ProdiaImageModelOptions {
            steps(30)
            width(1024)
            height(768)
            stylePreset("photographic")
            loras(listOf("detail"))
            progressive(true)
        }
        val prodiaVideo = ProdiaVideoModelOptions {
            resolution("720p")
        }
        val quiver = QuiverAIImageModelOptions {
            operation("edit")
            instructions("remove background")
            temperature(0.2)
            topP(0.8)
            presencePenalty(0.1)
            maxOutputTokens(256)
            autoCrop(true)
            targetSize(1024)
        }
        val togetherImage = TogetherAIImageModelOptions {
            steps(20)
            guidance(7.5f)
            negativePrompt("low quality")
            disableSafetyChecker(false)
            raw(mapOf("seed" to JsonPrimitive(123)))
        }

        assertEquals(lmnt, aiSdkJson.decodeFromString<LMNTSpeechModelOptions>(aiSdkJson.encodeToString(lmnt)))
        assertEquals(revai, aiSdkJson.decodeFromString<RevaiTranscriptionModelOptions>(aiSdkJson.encodeToString(revai)))
        assertEquals(replicateImage, aiSdkJson.decodeFromString<ReplicateImageModelOptions>(aiSdkJson.encodeToString(replicateImage)))
        assertEquals(replicateVideo, aiSdkJson.decodeFromString<ReplicateVideoModelOptions>(aiSdkJson.encodeToString(replicateVideo)))
        assertEquals(prodiaImage, aiSdkJson.decodeFromString<ProdiaImageModelOptions>(aiSdkJson.encodeToString(prodiaImage)))
        assertEquals(prodiaVideo, ProdiaVideoModelOptions { resolution("720p") })
        assertEquals(quiver, aiSdkJson.decodeFromString<QuiverAIImageModelOptions>(aiSdkJson.encodeToString(quiver)))
        assertNotEquals(quiver, QuiverAIImageModelOptions { operation("generate") })
        assertEquals(togetherImage, aiSdkJson.decodeFromString<TogetherAIImageModelOptions>(aiSdkJson.encodeToString(togetherImage)))

        val togetherJson = aiSdkJson.encodeToString(togetherImage)
        assertTrue(togetherJson.contains("\"negative_prompt\""))
        assertTrue(togetherJson.contains("\"disable_safety_checker\""))
    }

    @Test
    fun `remaining provider model option DSLs keep value semantics and serialization`() {
        val mistral = MistralLanguageModelOptions {
            safePrompt(true)
            documentImageLimit(8)
            documentPageLimit(12)
            structuredOutputs(true)
            strictJsonSchema(false)
            parallelToolCalls(true)
            reasoningEffort("medium")
        }
        val moonshot = MoonshotAILanguageModelOptions {
            raw(mapOf("thinking" to JsonPrimitive("auto")))
        }
        val prodia = ProdiaLanguageModelOptions {
            aspectRatio("16:9")
        }
        val xaiImage = XaiImageModelOptions {
            aspect_ratio("1:1")
            output_format("png")
            sync_mode(true)
            resolution("1024x1024")
            quality("high")
            user("user-1")
        }
        val xaiVideo = XaiVideoModelOptions {
            mode("standard")
            videoUrl("https://example.test/source.mp4")
            referenceImageUrls(listOf("https://example.test/ref.png"))
            pollIntervalMs(250)
            pollTimeoutMs(5_000)
            resolution("720p")
        }

        assertEquals(mistral, aiSdkJson.decodeFromString<MistralLanguageModelOptions>(aiSdkJson.encodeToString(mistral)))
        assertEquals(moonshot, aiSdkJson.decodeFromString<MoonshotAILanguageModelOptions>(aiSdkJson.encodeToString(moonshot)))
        assertEquals(prodia, ProdiaLanguageModelOptions { aspectRatio("16:9") })
        assertEquals(xaiImage, aiSdkJson.decodeFromString<XaiImageModelOptions>(aiSdkJson.encodeToString(xaiImage)))
        assertEquals(xaiVideo, aiSdkJson.decodeFromString<XaiVideoModelOptions>(aiSdkJson.encodeToString(xaiVideo)))
        assertNotEquals(xaiVideo, XaiVideoModelOptions { mode("fast") })

        val xaiImageJson = aiSdkJson.encodeToString(xaiImage)
        assertTrue(xaiImageJson.contains("\"aspect_ratio\""))
        assertTrue(xaiImageJson.contains("\"output_format\""))
        assertTrue(xaiImageJson.contains("\"sync_mode\""))
    }

    @Test
    fun `gateway params use value semantics and auth options use identity semantics`() {
        val spend = GatewaySpendReportParams {
            startDate("2026-06-01")
            endDate("2026-06-03")
            groupBy(GatewaySpendReportGroupBy.Model)
            credentialType(GatewayCredentialType.Byok)
            tags(listOf("prod"))
        }
        val matchingSpend = GatewaySpendReportParams {
            startDate("2026-06-01")
            endDate("2026-06-03")
            groupBy(GatewaySpendReportGroupBy.Model)
            credentialType(GatewayCredentialType.Byok)
            tags(listOf("prod"))
        }
        val generation = GatewayGenerationInfoParams {
            id("gen_123")
        }

        assertEquals(matchingSpend, spend)
        assertEquals(matchingSpend.hashCode(), spend.hashCode())
        assertEquals(GatewayGenerationInfoParams { id("gen_123") }, generation)

        val auth = AuthOptions {
            serverUrl("https://mcp.example.com")
            scope("tools")
        }
        val matchingAuth = AuthOptions {
            serverUrl("https://mcp.example.com")
            scope("tools")
        }

        assertEquals("https://mcp.example.com", auth.serverUrl)
        assertEquals("tools", auth.scope)
        assertNotEquals(matchingAuth, auth)
    }
}
