package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Surface tests for Phase 4F #38 + #39 — [ContentPart.File] richer
 * surface (gained `filename`) and the new [ContentPart.Image] variant.
 * Both additive; existing call sites stay green.
 */
class ContentPartPolishTest {

    private val codec = Json { encodeDefaults = true }

    @Test
    fun `given a File constructed without filename when read then filename is null`() {
        // GIVEN — existing call sites pass only mediaType + base64.
        val file: ContentPart = ContentPart.File(mediaType = "text/plain", base64 = "aGVsbG8=")

        // WHEN/THEN — filename default null preserves backwards compat.
        assertTrue(file is ContentPart.File)
        assertNull(file.filename)
    }

    @Test
    fun `given a File with filename when round-tripped then filename survives`() {
        // GIVEN
        val original = ContentPart.File(
            mediaType = "application/pdf",
            base64 = "JVBERi0xLjQK",
            filename = "lineup.pdf",
        )

        // WHEN
        val encoded = codec.encodeToString(ContentPart.serializer(), original)
        val decoded = codec.decodeFromString(ContentPart.serializer(), encoded) as ContentPart.File

        // THEN
        assertEquals("lineup.pdf", decoded.filename)
        assertEquals("application/pdf", decoded.mediaType)
    }

    @Test
    fun `given an Image content part when round-tripped then mediaType and base64 survive`() {
        // GIVEN — multimodal model output. Not produced by Gemma 4 E2B
        // today but the surface stays parity with v6 for future vision
        // providers.
        val original: ContentPart = ContentPart.Image(
            mediaType = "image/png",
            base64 = "iVBORw0KGgoAAAA=",
        )

        // WHEN
        val encoded = codec.encodeToString(ContentPart.serializer(), original)
        val decoded = codec.decodeFromString(ContentPart.serializer(), encoded)

        // THEN
        assertTrue(decoded is ContentPart.Image)
        assertEquals("image/png", decoded.mediaType)
        assertEquals("iVBORw0KGgoAAAA=", decoded.base64)
    }

    @Test
    fun `given an OnStepStartEvent with request and priorSteps when read then they round-trip`() {
        // GIVEN — the producer obligation: request + priorSteps are REQUIRED, so
        // the loop must supply the data it already has in scope (no empty default).
        val params = LanguageModelCallParams(messages = listOf(UserMessage("hi")))
        val prior = StepResult(
            stepNumber = 1,
            text = "step 1 text",
            reasoning = "",
            toolCalls = emptyList(),
            toolResults = emptyList(),
            toolApprovalRequests = emptyList(),
            finishReason = FinishReason.Stop,
            usage = Usage(),
        )
        val ev = OnStepStartEvent(
            stepNumber = 2,
            messages = emptyList(),
            request = params,
            priorSteps = listOf(prior),
        )

        // WHEN/THEN — the fields carry the real data, not a silently-empty default.
        assertEquals(params, ev.request, "request round-trips the prepared call params")
        assertEquals(listOf(prior), ev.priorSteps, "priorSteps round-trips the accumulated steps")
        assertEquals("step 1 text", ev.priorSteps.single().text)
    }
}
