package ai.torad.aisdk

import ai.torad.aisdk.providers.GoogleTools
import ai.torad.aisdk.providers.GroqTools
import ai.torad.aisdk.providers.OpenAITools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProviderToolsPokoTest {
    @Test
    fun `provider tool namespace holders keep value semantics`() {
        val google = GoogleTools()
        val equalGoogle = GoogleTools(
            googleSearch = google.googleSearch,
            enterpriseWebSearch = google.enterpriseWebSearch,
            googleMaps = google.googleMaps,
            urlContext = google.urlContext,
            fileSearch = google.fileSearch,
            codeExecution = google.codeExecution,
            vertexRagStore = google.vertexRagStore,
        )
        val differentGoogle = GoogleTools(
            googleSearch = google.enterpriseWebSearch,
            enterpriseWebSearch = google.enterpriseWebSearch,
            googleMaps = google.googleMaps,
            urlContext = google.urlContext,
            fileSearch = google.fileSearch,
            codeExecution = google.codeExecution,
            vertexRagStore = google.vertexRagStore,
        )
        assertEquals(google, equalGoogle)
        assertEquals(google.hashCode(), equalGoogle.hashCode())
        assertNotEquals(google, differentGoogle)

        val openai = OpenAITools()
        val equalOpenai = OpenAITools(
            applyPatch = openai.applyPatch,
            codeInterpreter = openai.codeInterpreter,
            fileSearch = openai.fileSearch,
            imageGeneration = openai.imageGeneration,
            localShell = openai.localShell,
            shell = openai.shell,
            webSearchPreview = openai.webSearchPreview,
            webSearch = openai.webSearch,
            mcp = openai.mcp,
            toolSearch = openai.toolSearch,
        )
        val differentOpenai = OpenAITools(
            applyPatch = openai.codeInterpreter,
            codeInterpreter = openai.codeInterpreter,
            fileSearch = openai.fileSearch,
            imageGeneration = openai.imageGeneration,
            localShell = openai.localShell,
            shell = openai.shell,
            webSearchPreview = openai.webSearchPreview,
            webSearch = openai.webSearch,
            mcp = openai.mcp,
            toolSearch = openai.toolSearch,
        )
        assertEquals(openai, equalOpenai)
        assertEquals(openai.hashCode(), equalOpenai.hashCode())
        assertNotEquals(openai, differentOpenai)

        val groq = GroqTools()
        val equalGroq = GroqTools(browserSearch = groq.browserSearch)
        val differentGroq = GroqTools(browserSearch = google.googleSearch)
        assertEquals(groq, equalGroq)
        assertEquals(groq.hashCode(), equalGroq.hashCode())
        assertNotEquals(groq, differentGroq)
    }
}
