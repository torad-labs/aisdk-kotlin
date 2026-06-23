package ai.torad.aisdk.protocol

import ai.torad.aisdk.Usage
import ai.torad.aisdk.protocol.ProtocolJson.intOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object GatewayUsageCodec {
    fun decode(value: JsonElement?): Usage {
        val obj = (value as? JsonObject) ?: return Usage()
        val prompt = intOrNull(obj, "promptTokens", "inputTokens", "prompt_tokens", "input_tokens") ?: 0
        val completion = intOrNull(obj, "completionTokens", "outputTokens", "completion_tokens", "output_tokens") ?: 0
        val cacheRead = intOrNull(
            obj,
            "cachedInputTokens",
            "cached_input_tokens",
            "cacheReadInputTokens",
            "cache_read_input_tokens",
        ) ?: 0
        val cacheWrite = intOrNull(
            obj,
            "cacheCreationInputTokens",
            "cache_creation_input_tokens",
            "cacheWriteInputTokens",
            "cache_write_input_tokens",
        ) ?: 0
        val reasoning = intOrNull(obj, "reasoningTokens", "reasoning_tokens") ?: 0
        return Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = prompt,
                noCache = (prompt - cacheRead - cacheWrite).coerceAtLeast(0),
                cacheRead = cacheRead.coerceAtMost(prompt),
                cacheWrite = cacheWrite.coerceAtMost(prompt),
            ),
            outputTokens = Usage.OutputTokenBreakdown(
                total = completion,
                text = (completion - reasoning).coerceAtLeast(0),
                reasoning = reasoning.coerceAtLeast(0),
            ),
            raw = value,
        )
    }
}
