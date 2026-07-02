package ai.torad.aisdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Validates Phase 4C #19 — `Usage` rich tree with input/output token
 * breakdowns. Mirrors v6's `LanguageModelV3Usage` shape
 * (`inputTokens.{total, noCache, cacheRead, cacheWrite}` +
 * `outputTokens.{total, text, reasoning}` + provider-specific `raw`).
 *
 * Provider prompt caching needs explicit cache-hit metrics —
 * before this split there was no way to measure cache-hit rate.
 *
 * Covers four behaviors:
 * 1. Legacy flat `(promptTokens, completionTokens)` constructor still
 *    works — values map into `inputTokens.total` / `outputTokens.total`.
 * 2. Default no-arg construction yields all-zero breakdowns.
 * 3. Rich tree construction surfaces split values back through legacy
 *    accessors.
 * 4. Round-trip serialization preserves the full tree.
 */
class UsageRichTreeTest {

    @Test
    fun `given legacy flat constructor Usage promptTokens completionTokens when read via legacy accessors then values match`() {
        // GIVEN — old-style construction used in tests + ToolLoopAgent.
        val u = Usage.of(promptTokens = 12, completionTokens = 34)

        // WHEN/THEN
        assertEquals(12, u.promptTokens, "legacy accessor reads inputTokens.total")
        assertEquals(34, u.completionTokens, "legacy accessor reads outputTokens.total")
        assertEquals(46, u.totalTokens, "totalTokens = prompt + completion")
        // And the rich tree carries the same values:
        assertEquals(12, u.inputTokens.total)
        assertEquals(34, u.outputTokens.total)
    }

    @Test
    fun `given default Usage when read then all breakdowns are zero`() {
        // GIVEN/WHEN
        val u = Usage()

        // THEN
        assertEquals(0, u.inputTokens.total)
        assertEquals(0, u.inputTokens.noCache)
        assertEquals(0, u.inputTokens.cacheRead)
        assertEquals(0, u.inputTokens.cacheWrite)
        assertEquals(0, u.outputTokens.total)
        assertEquals(0, u.outputTokens.text)
        assertEquals(0, u.outputTokens.reasoning)
        assertNull(u.raw, "raw is null by default")
    }

    @Test
    fun `given rich tree construction with cache splits when read via legacy total then it equals the breakdown total`() {
        // GIVEN — Anthropic prompt-cache scenario:
        // 800 cacheRead + 200 noCache + 50 cacheWrite = 1050 input tokens.
        val u = Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = 1050,
                noCache = 200,
                cacheRead = 800,
                cacheWrite = 50,
            ),
            outputTokens = Usage.OutputTokenBreakdown(
                total = 300,
                text = 220,
                reasoning = 80,
            ),
        )

        // WHEN/THEN — legacy accessors map through, splits readable.
        assertEquals(1050, u.promptTokens)
        assertEquals(300, u.completionTokens)
        assertEquals(1350, u.totalTokens)
        assertEquals(800, u.inputTokens.cacheRead, "cache-hit count readable for billing telemetry")
        assertEquals(80, u.outputTokens.reasoning, "reasoning tokens billable separately on some providers")
    }

    @Test
    fun `given raw provider payload when set then it survives round-trip serialization`() {
        // GIVEN — provider stuffs its own usage shape under `raw`.
        val rawPayload = buildJsonObject {
            put("anthropic_internal_cache_id", JsonPrimitive("cache_abc"))
        }
        val original = Usage(
            inputTokens = Usage.InputTokenBreakdown(total = 50, cacheRead = 50),
            raw = rawPayload,
        )
        val codec = Json { encodeDefaults = true }

        // WHEN
        val encoded = codec.encodeToString(Usage.serializer(), original)
        val decoded = codec.decodeFromString(Usage.serializer(), encoded)

        // THEN
        assertEquals(50, decoded.inputTokens.cacheRead)
        assertEquals(rawPayload, decoded.raw, "provider-specific raw payload survives")
        assertTrue(encoded.contains("anthropic_internal_cache_id"))
    }

    @Test
    fun `given input breakdown parts exceeding total when constructed then it rejects impossible usage`() {
        assertFailsWith<IllegalArgumentException> {
            Usage.InputTokenBreakdown(
                total = 100,
                noCache = 60,
                cacheRead = 41,
            )
        }
    }

    @Test
    fun `given output breakdown parts exceeding total when constructed then it rejects impossible usage`() {
        assertFailsWith<IllegalArgumentException> {
            Usage.OutputTokenBreakdown(
                total = 20,
                text = 15,
                reasoning = 6,
            )
        }
    }
}
