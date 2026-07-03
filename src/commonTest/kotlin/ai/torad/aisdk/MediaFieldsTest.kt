package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MediaFieldsTest {
    @Test
    fun `media result Poko types keep value semantics`() {
        val file = GeneratedFile(mediaType = "image/png", base64 = "aW1hZ2U=", filename = "image.png")
        val equalFile = GeneratedFile(mediaType = "image/png", base64 = "aW1hZ2U=", filename = "image.png")
        val differentFile = GeneratedFile(mediaType = "image/png", base64 = "ZGlmZmVyZW50", filename = "image.png")
        assertEquals(file, equalFile)
        assertEquals(file.hashCode(), equalFile.hashCode())
        assertNotEquals(file, differentFile)

        val imageResult = GenerateImageResult(images = listOf(file), usage = ImageModelUsage(inputTokens = 1))
        val equalImageResult = GenerateImageResult(images = listOf(equalFile), usage = ImageModelUsage(inputTokens = 1))
        val differentImageResult = GenerateImageResult(
            images = listOf(differentFile),
            usage = ImageModelUsage(inputTokens = 1)
        )
        assertEquals(imageResult, equalImageResult)
        assertEquals(imageResult.hashCode(), equalImageResult.hashCode())
        assertNotEquals(imageResult, differentImageResult)

        val transcript = TranscribeResult(
            text = "hello",
            segments = listOf(TranscriptSegment(text = "hello", startSeconds = 0f, endSeconds = 1.5f)),
            language = "en",
        )
        val equalTranscript = TranscribeResult(
            text = "hello",
            segments = listOf(TranscriptSegment(text = "hello", startSeconds = 0f, endSeconds = 1.5f)),
            language = "en",
        )
        val differentTranscript = TranscribeResult(
            text = "hello",
            segments = listOf(TranscriptSegment(text = "hello", startSeconds = 0f, endSeconds = 2f)),
            language = "en",
        )
        assertEquals(transcript, equalTranscript)
        assertEquals(transcript.hashCode(), equalTranscript.hashCode())
        assertNotEquals(transcript, differentTranscript)
    }

    @Test
    fun `sumImageUsage adds per-batch token counts and keeps null when all null`() {
        val summed = ImageModelUsage.sum(
            listOf(
                ImageModelUsage(inputTokens = 3, totalTokens = 3),
                ImageModelUsage(inputTokens = 4, outputTokens = 1, totalTokens = 5),
            ),
        )
        assertEquals(7, summed.inputTokens)
        assertEquals(1, summed.outputTokens)
        assertEquals(8, summed.totalTokens)
        // all-null field stays null
        assertEquals(null, ImageModelUsage.sum(listOf(ImageModelUsage(), ImageModelUsage())).inputTokens)
    }

    @Test
    fun `generateImage surfaces aggregated usage and routes warnings to the logger`() = runTest {
        val logged = mutableListOf<String>()
        val logger = object : Logger {
            override fun warn(message: String, throwable: Throwable?) { logged += message }
            override fun info(message: String) = Unit
            override fun debug(message: String) = Unit
        }
        val model = object : ImageModel {
            override val modelId = "m"
            override val maxImagesPerCall = 1
            override suspend fun generate(params: ImageGenerationParams) = ImageModelResult(
                images = List(params.n) { GeneratedFile("image/png", "x") },
                warnings = listOf(CallWarning(type = "unsupported-setting", message = "size ignored")),
                usage = ImageModelUsage(inputTokens = 2, totalTokens = 2),
            )
        }
        val result = ImageGeneration.generateImage(model, prompt = "cat", n = 2, logger = logger)
        assertEquals(4, result.usage.inputTokens, "usage summed across the 2 batched calls")
        assertEquals(2, logged.size, "one warning logged per batched call")
    }

    @Test
    fun `transcription result carries language and durationInSeconds`() = runTest {
        val model = object : TranscriptionModel {
            override val modelId = "m"
            override suspend fun transcribe(params: TranscriptionParams) = TranscriptionModelResult(
                text = "hello",
                language = "en",
                durationInSeconds = 12.5f,
            )
        }
        val result = Transcription.transcribe(model, AudioSource("audio/wav", "AA=="))
        assertEquals("en", result.language)
        assertEquals(12.5f, result.durationInSeconds)
        assertEquals(1, result.responses.size)
    }
}
