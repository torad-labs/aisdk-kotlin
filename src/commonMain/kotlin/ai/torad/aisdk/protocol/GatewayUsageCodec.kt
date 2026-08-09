package ai.torad.aisdk.protocol

import ai.torad.aisdk.JsonAccess
import ai.torad.aisdk.Usage
import ai.torad.aisdk.protocol.ProtocolJson.intOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object GatewayUsageCodec {
    fun decode(value: JsonElement?): Usage {
        val obj = (value as? JsonObject) ?: return Usage()
        // v3 sends the breakdown NESTED — {inputTokens:{total,noCache,cacheRead,cacheWrite},
        // outputTokens:{total,text,reasoning}}. Read that first; the flat legacy keys stay a
        // fallback for gateways still emitting the pre-restructure shape.
        val input = JsonAccess.obj(obj, "inputTokens")
        val output = JsonAccess.obj(obj, "outputTokens")
        val prompt = count(
            input,
            "total",
            obj,
            "promptTokens",
            "inputTokens",
            "prompt_tokens",
            "input_tokens",
        )
        val completion = count(
            output,
            "total",
            obj,
            "completionTokens",
            "outputTokens",
            "completion_tokens",
            "output_tokens",
        )
        val cacheRead = count(
            input,
            "cacheRead",
            obj,
            "cachedInputTokens",
            "cached_input_tokens",
            "cacheReadInputTokens",
            "cache_read_input_tokens",
        )
        val cacheWrite = count(
            input,
            "cacheWrite",
            obj,
            "cacheCreationInputTokens",
            "cache_creation_input_tokens",
            "cacheWriteInputTokens",
            "cache_write_input_tokens",
        )
        val reasoning = count(output, "reasoning", obj, "reasoningTokens", "reasoning_tokens")
        val noCache = input?.let { intOrNull(it, "noCache") } ?: (prompt - cacheRead - cacheWrite)
        val text = output?.let { intOrNull(it, "text") } ?: (completion - reasoning)
        return Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = prompt,
                noCache = noCache.coerceAtLeast(0),
                cacheRead = cacheRead.coerceAtMost(prompt),
                cacheWrite = cacheWrite.coerceAtMost(prompt),
            ),
            outputTokens = Usage.OutputTokenBreakdown(
                total = completion,
                text = text.coerceAtLeast(0),
                reasoning = reasoning.coerceAtLeast(0),
            ),
            raw = value,
        )
    }

    /** The nested v3 breakdown count if present, else the first matching flat legacy key, else 0. */
    private fun count(nested: JsonObject?, nestedKey: String, obj: JsonObject, vararg flatKeys: String): Int =
        nested?.let { intOrNull(it, nestedKey) } ?: intOrNull(obj, *flatKeys) ?: 0
}
