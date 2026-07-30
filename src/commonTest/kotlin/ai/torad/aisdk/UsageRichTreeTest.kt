package ai.torad.aisdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val usageCodec: Json = Json { encodeDefaults = true }

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
        val u = Usage(promptTokens = 12, completionTokens = 34)

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

        // WHEN
        val encoded = usageCodec.encodeToString(Usage.serializer(), original)
        val decoded = usageCodec.decodeFromString(Usage.serializer(), encoded)

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

    @Test
    fun `given OpenAI usage with reasoning inside completion when parsed then completion is the output total`() {
        // The documented contract: reasoning_tokens is a SUBSET of completion_tokens.
        val usage = UsageFromOpenAI(
            buildJsonObject {
                put("prompt_tokens", JsonPrimitive(100))
                put("completion_tokens", JsonPrimitive(60))
                put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", JsonPrimitive(25)) })
            },
        )

        assertEquals(60, usage.outputTokens.total, "the provider's completion_tokens is authoritative")
        assertEquals(25, usage.outputTokens.reasoning)
        assertEquals(35, usage.outputTokens.text, "text is the non-reasoning remainder")
    }

    @Test
    fun `given OpenAI usage whose reasoning exceeds completion when parsed then reasoning is clamped`() {
        // A response that violates the subset contract. Previously this took an undocumented branch
        // that SUMMED the two into the total; the magnitude test could not distinguish that case
        // from a provider reporting text-only completion_tokens, and for such a provider with SHORT
        // reasoning it silently under-counted instead. reasoning is now clamped into its documented
        // range, symmetric with cached_tokens being clamped into prompt_tokens.
        val usage = UsageFromOpenAI(
            buildJsonObject {
                put("prompt_tokens", JsonPrimitive(10))
                put("completion_tokens", JsonPrimitive(5))
                put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", JsonPrimitive(9)) })
            },
        )

        assertEquals(5, usage.outputTokens.total, "the total never exceeds the reported completion_tokens")
        assertEquals(5, usage.outputTokens.reasoning, "reasoning is clamped to the completion total")
        assertEquals(0, usage.outputTokens.text, "text stays non-negative")
    }

    @Test
    fun `given OpenAI usage with a negative reasoning count when parsed then it clamps to zero`() {
        val usage = UsageFromOpenAI(
            buildJsonObject {
                put("prompt_tokens", JsonPrimitive(10))
                put("completion_tokens", JsonPrimitive(8))
                put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", JsonPrimitive(-3)) })
            },
        )

        assertEquals(8, usage.outputTokens.total)
        assertEquals(0, usage.outputTokens.reasoning)
        assertEquals(8, usage.outputTokens.text)
    }
}
