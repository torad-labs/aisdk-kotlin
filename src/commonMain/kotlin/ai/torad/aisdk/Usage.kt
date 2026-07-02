package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Token-usage tracking, surfaced on completed steps and final results.
 * Per historical parity gap #19, the shape mirrors v6's rich tree —
 * input tokens split into `noCache / cacheRead / cacheWrite` and
 * output tokens split into `text / reasoning` plus a `raw` slot for
 * provider-specific payloads.
 *
 * Provider prompt caching needs explicit cache-hit metrics; before this
 * split there was no surface for cache-hit metrics.
 *
 * **Backwards compatibility.** A secondary constructor accepts the
 * old `(promptTokens, completionTokens, totalTokens?)` shape and maps
 * each value into `inputTokens.total` / `outputTokens.total`. Computed
 * properties `promptTokens` / `completionTokens` / `totalTokens` read
 * back from the nested tree so existing accumulators and assertions
 * keep working unchanged.
 */
@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class Usage(
    /** @since 0.3.0-beta01 */
    public val inputTokens: InputTokenBreakdown = InputTokenBreakdown(),
    /** @since 0.3.0-beta01 */
    public val outputTokens: OutputTokenBreakdown = OutputTokenBreakdown(),
    /** @since 0.3.0-beta01 */
    public val raw: JsonElement? = null,
) {
    public companion object {
        /**
         * Legacy flat `(promptTokens, completionTokens)` factory —
         * replaces the old secondary constructor. Unambiguous because
         * BOTH params are required; the primary constructor's `Usage()`
         * no-arg path goes through default breakdown defaults.
         * @since 0.3.0-beta01
         */
        public fun of(promptTokens: Int, completionTokens: Int): Usage = Usage(
            inputTokens = InputTokenBreakdown(total = promptTokens),
            outputTokens = OutputTokenBreakdown(total = completionTokens),
        )

        /**
         * Usage from an OpenAI-compatible `usage` JSON object: prompt/completion
         * totals plus cached- and reasoning-token breakdowns. Shared by the
         * chat/completion models and the streaming state.
         */
        internal fun fromOpenAI(value: JsonElement?): Usage {
            val obj = (value as? JsonObject) ?: return Usage()
            val promptTokens = (obj["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            val completionTokens = (obj["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            val cachedTokens = (((JsonAccess.obj(obj, "prompt_tokens_details"))?.get("cached_tokens") as? JsonPrimitive)?.intOrNull ?: 0)
                .coerceIn(0, promptTokens)
            val reasoningTokens = (((JsonAccess.obj(obj, "completion_tokens_details"))?.get("reasoning_tokens") as? JsonPrimitive)?.intOrNull ?: 0)
                .coerceAtLeast(0)
            val outputTotal = if (reasoningTokens > completionTokens) {
                completionTokens + reasoningTokens
            } else {
                completionTokens
            }
            return Usage(
                inputTokens = InputTokenBreakdown(
                    total = promptTokens,
                    noCache = promptTokens - cachedTokens,
                    cacheRead = cachedTokens,
                ),
                outputTokens = OutputTokenBreakdown(
                    total = outputTotal,
                    text = outputTotal - reasoningTokens,
                    reasoning = reasoningTokens,
                ),
                raw = value,
            )
        }

        /**
         * Usage from already-extracted token counts — the building block for
         * OpenAI-compatible facade `convertUsage` overrides.
         */
        internal fun fromParts(
            promptTokens: Int,
            completionTokens: Int,
            cacheRead: Int,
            reasoningTokens: Int,
            raw: JsonElement?,
        ): Usage = Usage(
            inputTokens = InputTokenBreakdown(
                total = promptTokens,
                noCache = promptTokens - cacheRead,
                cacheRead = cacheRead,
            ),
            outputTokens = OutputTokenBreakdown(
                total = completionTokens,
                text = completionTokens - reasoningTokens,
                reasoning = reasoningTokens,
            ),
            raw = raw,
        )

        /**
         * Usage from an Anthropic `usage` JSON object: base input/output plus cache
         * write/read counts, summing executor `iterations` (compaction/message) when present.
         */
        internal fun fromAnthropic(element: JsonElement?): Usage {
            val obj = element as? JsonObject ?: return Usage()
            val baseInput = (obj["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            val baseOutput = (obj["output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            val cacheWrite = (obj["cache_creation_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            val cacheRead = (obj["cache_read_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            val iterations = JsonAccess.arr(obj, "iterations")
            val executorIterations = iterations.orEmpty().mapNotNull { it as? JsonObject }
                .filter { (it["type"] as? JsonPrimitive)?.contentOrNull in setOf("compaction", "message") }
            val input = if (executorIterations.isNotEmpty()) {
                executorIterations.sumOf { (it["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0 }
            } else {
                baseInput
            }
            val output = if (executorIterations.isNotEmpty()) {
                executorIterations.sumOf { (it["output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0 }
            } else {
                baseOutput
            }
            return Usage(
                inputTokens = InputTokenBreakdown(
                    total = input + cacheWrite + cacheRead,
                    noCache = input,
                    cacheRead = cacheRead,
                    cacheWrite = cacheWrite,
                ),
                outputTokens = OutputTokenBreakdown(total = output),
                raw = element,
            )
        }

        /**
         * Merge a streaming `message_delta` usage object onto the usage captured at
         * `message_start`. Anthropic's message_delta usually carries only output_tokens, so a
         * full replace (the prior behavior) dropped the input/cache counts to 0 — upstream
         * mutates in place: keep prior input/cache when the delta omits them, update what it
         * provides.
         */
        internal fun mergeAnthropic(existing: Usage, deltaElement: JsonElement?): Usage {
            val obj = deltaElement as? JsonObject ?: return existing
            val base = existing.raw as? JsonObject
            val merged = if (base == null) {
                buildJsonObject {
                    put("input_tokens", JsonPrimitive(existing.inputTokens.noCache))
                    put("output_tokens", JsonPrimitive(existing.outputTokens.total))
                    if (existing.inputTokens.cacheRead != 0) {
                        put("cache_read_input_tokens", JsonPrimitive(existing.inputTokens.cacheRead))
                    }
                    if (existing.inputTokens.cacheWrite != 0) {
                        put("cache_creation_input_tokens", JsonPrimitive(existing.inputTokens.cacheWrite))
                    }
                    obj.forEach { (key, value) -> put(key, value) }
                }
            } else {
                JsonObject(base + obj)
            }
            return fromAnthropic(merged)
        }
    }

    /**
     * Legacy flat accessor — `inputTokens.total`.
     * @since 0.3.0-beta01
     */
    public val promptTokens: Int get() = inputTokens.total

    /**
     * Legacy flat accessor — `outputTokens.total`.
     * @since 0.3.0-beta01
     */
    public val completionTokens: Int get() = outputTokens.total

    /**
     * Legacy flat accessor — `promptTokens + completionTokens`.
     * @since 0.3.0-beta01
     */
    public val totalTokens: Int get() = promptTokens + completionTokens

    @Serializable
    @Poko
    /** @since 0.3.0-beta01 */
    public class InputTokenBreakdown(
        /** @since 0.3.0-beta01 */
        public val total: Int = 0,
        /**
         * Tokens that were billed without cache participation.
         * @since 0.3.0-beta01
         */
        public val noCache: Int = 0,
        /**
         * Tokens read from a provider prompt cache (saves billing).
         * @since 0.3.0-beta01
         */
        public val cacheRead: Int = 0,
        /**
         * Tokens written to a provider prompt cache (first-time cost).
         * @since 0.3.0-beta01
         */
        public val cacheWrite: Int = 0,
    ) {
        init {
            require(total >= 0) { "inputTokens.total must be non-negative." }
            require(noCache >= 0) { "inputTokens.noCache must be non-negative." }
            require(cacheRead >= 0) { "inputTokens.cacheRead must be non-negative." }
            require(cacheWrite >= 0) { "inputTokens.cacheWrite must be non-negative." }
            require(noCache + cacheRead + cacheWrite <= total) {
                "input token breakdown parts must not exceed inputTokens.total."
            }
        }
    }

    @Serializable
    @Poko
    /** @since 0.3.0-beta01 */
    public class OutputTokenBreakdown(
        /** @since 0.3.0-beta01 */
        public val total: Int = 0,
        /**
         * Visible response tokens.
         * @since 0.3.0-beta01
         */
        public val text: Int = 0,
        /**
         * Hidden reasoning tokens (Anthropic thinking, OpenAI reasoning).
         * @since 0.3.0-beta01
         */
        public val reasoning: Int = 0,
    ) {
        init {
            require(total >= 0) { "outputTokens.total must be non-negative." }
            require(text >= 0) { "outputTokens.text must be non-negative." }
            require(reasoning >= 0) { "outputTokens.reasoning must be non-negative." }
            require(text + reasoning <= total) {
                "output token breakdown parts must not exceed outputTokens.total."
            }
        }
    }
}

/**
 * Arithmetic over [Usage]. The `+` operator lives here as a member-extension
 * (decision-C: no loose top-level funs). Call sites bring it into scope with
 * `with(UsageArithmetic) { a + b }` or a member import.
 * @since 0.3.0-beta01
 */
public object UsageArithmetic {
    public operator fun Usage.plus(other: Usage): Usage = Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = inputTokens.total + other.inputTokens.total,
            noCache = inputTokens.noCache + other.inputTokens.noCache,
            cacheRead = inputTokens.cacheRead + other.inputTokens.cacheRead,
            cacheWrite = inputTokens.cacheWrite + other.inputTokens.cacheWrite,
        ),
        outputTokens = Usage.OutputTokenBreakdown(
            total = outputTokens.total + other.outputTokens.total,
            text = outputTokens.text + other.outputTokens.text,
            reasoning = outputTokens.reasoning + other.outputTokens.reasoning,
        ),
        raw = other.raw ?: raw,
    )
}

/**
 * Why a generation step ended.
 * @since 0.3.0-beta01
 */
@Serializable
public enum class FinishReason {
    Stop,
    Length,
    ToolCalls,
    ContentFilter,
    Error,

    /** v6: generation paused because tool(s) need approval. */
    ToolApprovalRequested,
    Other,
    ;

    public companion object {
        /** Map an OpenAI-compatible `finish_reason` wire string to a [FinishReason]. */
        internal fun fromOpenAI(value: String?): FinishReason = when (value) {
            "stop" -> Stop
            "length" -> Length
            "tool_calls", "function_call" -> ToolCalls
            "content_filter" -> ContentFilter
            else -> Other
        }

        /** Map an Anthropic `stop_reason` wire string to a [FinishReason]. */
        internal fun fromAnthropicStopReason(reason: String?): FinishReason = when (reason) {
            "pause_turn", "end_turn", "stop_sequence" -> Stop
            "refusal" -> ContentFilter
            "tool_use" -> ToolCalls
            "max_tokens", "model_context_window_exceeded" -> Length
            else -> Other
        }
    }
}
