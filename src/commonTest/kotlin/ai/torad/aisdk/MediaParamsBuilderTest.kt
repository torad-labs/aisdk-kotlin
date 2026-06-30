package ai.torad.aisdk

import ai.torad.aisdk.providers.HuggingFaceResponsesSettings
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class MediaParamsBuilderTest {
    @Test
    fun `completion request options DSL keeps value semantics`() {
        val options = CompletionRequestOptions {
            headers(mapOf("x-test" to "yes"))
            body(mapOf("temperature" to JsonPrimitive(0.2)))
        }
        val equal = CompletionRequestOptions {
            headers(mapOf("x-test" to "yes"))
            body(mapOf("temperature" to JsonPrimitive(0.2)))
        }
        val different = CompletionRequestOptions {
            headers(mapOf("x-test" to "no"))
        }

        assertEquals(equal, options)
        assertEquals(equal.hashCode(), options.hashCode())
        assertNotEquals(different, options)
    }

    @Test
    fun `HuggingFace responses settings DSL keeps value semantics and serialization`() {
        val settings = HuggingFaceResponsesSettings {
            metadata(mapOf("trace" to "abc"))
            instructions("answer briefly")
            strictJsonSchema(true)
            reasoningEffort("low")
        }
        val equal = HuggingFaceResponsesSettings {
            metadata(mapOf("trace" to "abc"))
            instructions("answer briefly")
            strictJsonSchema(true)
            reasoningEffort("low")
        }
        val different = HuggingFaceResponsesSettings {
            instructions("answer fully")
        }

        assertEquals(equal, settings)
        assertEquals(equal.hashCode(), settings.hashCode())
        assertNotEquals(different, settings)
        assertEquals(settings, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(settings)))
    }

    @Test
    fun `regular params DSL preserves fields and keeps identity semantics`() {
        val image = ImageGenerationParams {
            prompt("cat")
            n(2)
            size("512x512")
            files(listOf(ImageGenerationFile(url = "https://example.com/cat.png")))
        }
        val imageEqualShape = ImageGenerationParams {
            prompt("cat")
            n(2)
            size("512x512")
            files(listOf(ImageGenerationFile(url = "https://example.com/cat.png")))
        }
        val transport = DirectCompletionTransport { emptyFlow() }
        val completion = CallCompletionApiOptions {
            prompt("hello")
            transport(transport)
        }

        assertEquals("cat", image.prompt)
        assertEquals(2, image.n)
        assertEquals("512x512", image.size)
        assertEquals("https://example.com/cat.png", image.files.single().url)
        assertNotEquals(imageEqualShape, image)
        assertSame(transport, completion.transport)
        assertEquals("hello", completion.prompt)
    }
}
