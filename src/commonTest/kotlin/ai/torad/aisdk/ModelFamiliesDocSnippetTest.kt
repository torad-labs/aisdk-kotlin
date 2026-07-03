package ai.torad.aisdk

import ai.torad.aisdk.GeneratedFiles.fileData
import ai.torad.aisdk.providers.MockAudioSource
import ai.torad.aisdk.providers.MockEmbeddingModel
import ai.torad.aisdk.providers.MockImageModel
import ai.torad.aisdk.providers.MockRerankingModel
import ai.torad.aisdk.providers.MockSpeechModel
import ai.torad.aisdk.providers.MockTranscriptionModel
import ai.torad.aisdk.providers.MockVideoModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compiles and executes the model-families wiki snippets against the real helper objects. */
class ModelFamiliesDocSnippetTest {
    data class Chunk(val id: String, val text: String)

    @Test
    fun `model families embedding reranking and media helper snippets compile and run`() = runTest {
        val vectorStore = mutableMapOf<String, List<Float>>()
        val embeddingModel = MockEmbeddingModel()
        val imageModel = MockImageModel()
        val provider = Provider(
            providerId = "mock",
            embeddingModels = mapOf("text-embedding-3-small" to embeddingModel),
            rerankingModels = mapOf("rerank-v3.5" to MockRerankingModel()),
            imageModels = mapOf("image-model" to imageModel),
            speechModels = mapOf("tts-model" to MockSpeechModel()),
            transcriptionModels = mapOf("transcribe-model" to MockTranscriptionModel(transcript = "transcribed call")),
            videoModels = mapOf("video-model" to MockVideoModel()),
        )

        val result = Embedding.embed(
            model = provider.embeddingModel("text-embedding-3-small"),
            value = "AI SDK Kotlin streams UI messages.",
        )

        vectorStore["doc-1"] = result.embedding

        val chunks = listOf(Chunk("c1", "AI SDK Kotlin"), Chunk("c2", "streams UI messages"))
        val many = Embedding.embedMany(
            model = embeddingModel,
            values = chunks.map { it.text },
            maxParallelCalls = 4,
        )

        many.embeddings.zip(chunks).forEach { (embedding, chunk) ->
            vectorStore[chunk.id] = embedding
        }

        val candidateDocs = listOf(Chunk("d1", "Resume streams with stored ids."), Chunk("d2", "Other content."))
        val reranked = Reranking.rerank(
            model = provider.rerankingModel("rerank-v3.5"),
            query = "How do I resume streams?",
            documents = candidateDocs.map { it.text },
            topN = 5,
        )
        val bestDocs = reranked.results.map { scored ->
            candidateDocs[scored.index]
        }

        val image = ImageGeneration.generateImage(
            model = provider.imageModel("image-model"),
            prompt = "A clean diagram of Kotlin Flow streaming into UI messages.",
            n = 2,
            aspectRatio = "16:9",
            maxParallelCalls = 4,
        )

        val inputBytes = byteArrayOf(1, 2, 3)
        val maskBytes = byteArrayOf(4, 5, 6)
        val edited = ImageGeneration.generateImage(
            model = provider.imageModel("image-model"),
            prompt = "Replace the background with a simple studio surface.",
            files = listOf(ImageGenerationFile(FileData.Bytes(inputBytes, "image/png"))),
            mask = ImageGenerationFile(FileData.Bytes(maskBytes, "image/png")),
        )

        val speech = SpeechGeneration.generateSpeech(
            model = provider.speechModel("tts-model"),
            text = "Your report is ready.",
            voice = "alloy",
            responseFormat = "mp3",
        )

        val transcript = Transcription.transcribe(
            model = provider.transcriptionModel("transcribe-model"),
            audio = MockAudioSource(),
            language = "en",
        )

        val video = VideoGeneration.generateVideo(
            model = provider.videoModel("video-model"),
            prompt = "A calm product walkthrough animation.",
            durationSeconds = 5f,
            aspectRatio = "16:9",
            maxParallelCalls = 2,
        )

        val generated = GeneratedFile(
            FileData.Url(
                value = "https://example.com/source.png",
                mediaType = "image/png",
                filename = "source.png",
            ),
        )

        val local = generated.fileData()

        assertEquals(3, vectorStore.size)
        assertEquals("d1", bestDocs.first().id)
        assertEquals(2, image.images.size)
        assertEquals("image/png", image.image.mediaType)
        assertEquals("image/png", edited.image.mediaType)
        assertEquals("audio/mpeg", speech.audio.mediaType)
        assertEquals("transcribed call", transcript.text)
        assertEquals("video/mp4", video.video.mediaType)
        assertEquals("https://example.com/source.png", (local as FileData.Url).value)
        assertEquals("AQID", imageModel.captured?.files?.single()?.base64)
        assertEquals("BAUG", imageModel.captured?.mask?.base64)
    }
}
