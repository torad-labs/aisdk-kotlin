package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Surface tests for the Phase 4C #18 slice — `providerMetadata` on
 * `StreamEvent.StepFinish` and `StreamEvent.Finish`. The full sweep
 * across every event variant is a follow-up; this slice unblocks
 * end-of-step cache-rate measurement (used for
 * Anthropic apps).
 *
 * Three behaviors:
 *  1. Default null preserves backwards compatibility.
 *  2. Anthropic prompt-cache shape survives round-trip serialization.
 *  3. Same for OpenAI reasoning-token shape on the loop's `Finish`.
 */
class ProviderMetadataTest {

    private val codec = Json { encodeDefaults = true }

    @Test
    fun `given StepFinish constructed without providerMetadata when read then field is None`() {
        // GIVEN — backwards-compat path (existing call sites).
        val ev = StreamEvent.StepFinish(
            stepNumber = 1,
            finishReason = FinishReason.Stop,
            usage = Usage(),
        )

        // WHEN/THEN
        assertEquals(ProviderMetadata.None, ev.providerMetadata)
    }

    @Test
    fun `given StepFinish with Anthropic cache metadata when round-tripped then values survive`() {
        // GIVEN — Anthropic's `cache_creation_input_tokens` /
        // `cache_read_input_tokens` come back under
        // `anthropic` provider key.
        val original = StreamEvent.StepFinish(
            stepNumber = 2,
            finishReason = FinishReason.Stop,
            usage = Usage(
                inputTokens = Usage.InputTokenBreakdown(total = 1200, cacheRead = 1000, cacheWrite = 50),
                outputTokens = Usage.OutputTokenBreakdown(total = 80, text = 80),
            ),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                "anthropic" to buildJsonObject {
                    put("cache_creation_input_tokens", JsonPrimitive(50))
                    put("cache_read_input_tokens", JsonPrimitive(1000))
                },
            ))),
        )

        // WHEN
        val encoded = codec.encodeToString(StreamEvent.serializer(), original)
        val decoded = codec.decodeFromString(StreamEvent.serializer(), encoded) as StreamEvent.StepFinish

        // THEN
        assertNotNull(decoded.providerMetadata)
        val anthropic = decoded.providerMetadata.toMap()["anthropic"]
        assertNotNull(anthropic, "anthropic key survives")
        assertEquals(1000, decoded.usage.inputTokens.cacheRead, "cache split readable for billing")
    }

    @Test
    fun `given Finish with OpenAI reasoning trace metadata when round-tripped then it survives`() {
        // GIVEN
        val original = StreamEvent.Finish(
            totalSteps = 3,
            finishReason = FinishReason.Stop,
            usage = Usage.of(promptTokens = 500, completionTokens = 200),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                "openai" to buildJsonObject {
                    put("reasoning_effort", JsonPrimitive("high"))
                    put("reasoning_tokens", JsonPrimitive(150))
                },
            ))),
        )

        // WHEN
        val encoded = codec.encodeToString(StreamEvent.serializer(), original)
        val decoded = codec.decodeFromString(StreamEvent.serializer(), encoded) as StreamEvent.Finish

        // THEN
        assertNotNull(decoded.providerMetadata)
        assertEquals(500, decoded.usage.promptTokens)
        val openai = decoded.providerMetadata.toMap()["openai"]
        assertNotNull(openai, "openai provider payload survives")
    }
}
