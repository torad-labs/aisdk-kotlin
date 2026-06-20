package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Provider-neutral model surface. Per invariant I-4 / I-9, application code
 * never depends on a specific provider — it depends on this interface, and
 * provider-specific differences live in middleware (see [LanguageModelMiddleware]).
 *
 * Two operations: `generate` for one-shot completions, `stream` for
 * incremental events. Both accept the same [LanguageModelCallParams].
 *
 * Provider implementations live in their own modules:
 *   - `aisdk-provider-litert` (Android, on-device)
 *   - `aisdk-provider-mlx`    (iOS, on-device)
 *   - `aisdk-provider-anthropic` / `aisdk-provider-openai` (cloud, future)
 *
 * This module ships only [ai.torad.aisdk.providers.MockLanguageModel] for
 * tests — real providers are out of scope here.
 */
public interface LanguageModel {
    /** Stable identifier for telemetry / routing (`"litertlm/gemma-3-1b"`, `"openai/gpt-5"`). */
    public val modelId: String

    /**
     * Provider tag for the model — `"openai"`, `"anthropic"`,
     * `"litert-lm"`, etc. Mirrors v6's
     * `LanguageModelV3.provider` (per historical parity gap #40).
     * Lets routing layers (a fallback chain, a tier picker) make
     * provider-aware decisions without parsing [modelId].
     *
     * Default `"unknown"` — every real provider should override.
     */
    public val provider: String
        get() = "unknown"

    /**
     * Map of URL regex → media-tag list describing which URL shapes
     * the provider accepts inline (so the agent doesn't have to
     * download + base64-encode them itself). Mirrors v6's
     * `LanguageModelV3.supportedUrls`. Empty default — most on-device
     * providers don't accept URLs and fall back to base64.
     */
    public val supportedUrls: Map<String, List<String>>
        get() = emptyMap()

    /** One-shot completion. */
    public suspend fun generate(params: LanguageModelCallParams): LanguageModelResult

    /** Streaming completion. Cold until collected, then drives one upstream call per collection. */
    public fun stream(params: LanguageModelCallParams): Flow<StreamEvent>

    /**
     * Streaming completion plus metadata available before stream
     * collection. Implementations must keep the returned stream cold, and
     * must not `flowOn`/emit from a foreign context — the collection context
     * is the caller's choice.
     * Providers can override this to expose request bodies and response
     * headers while preserving the v6 `doStream` result shape.
     */
    public fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        LanguageModelStreamResult(stream = stream(params))
}

/**
 * Single parameter envelope for both `generate` and `stream` so middleware
 * can wrap both the same way and `wrapLanguageModel` only needs one
 * pass-through shape.
 */
public data class LanguageModelCallParams(
    val messages: List<ModelMessage>,
    val tools: List<LanguageModelTool> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,        // v6 name (was maxTokens)
    val stopSequences: List<String> = emptyList(),
    val seed: Int? = null,
    val providerOptions: ProviderOptions = ProviderOptions.None,
    val abortSignal: AbortSignal = AbortSignalNever,
    /**
     * Penalty for repeating tokens that already appeared in the
     * response, regardless of frequency. Mirrors v6's `CallSettings`
     * (per historical parity gap #3 closure). Null = provider default.
     */
    val presencePenalty: Float? = null,
    /**
     * Penalty proportional to how often a token has appeared.
     * Mirrors v6's `CallSettings`. Null = provider default.
     */
    val frequencyPenalty: Float? = null,
    /**
     * Wire-level shape constraint. Mirrors v6's
     * `LanguageModelV3CallOptions.responseFormat` (per
     * historical parity gap #20). Providers that support constrained
     * decoding honor it; others ignore. Default [ResponseFormat.Text]
     * = no constraint.
     */
    val responseFormat: ResponseFormat = ResponseFormat.Text,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Tool advertisement at the model surface — the JSON-schema shape the
 * provider needs. Distinct from the application-side [Tool] which carries
 * a Kotlin executor; this is the wire shape that crosses into a provider.
 */
public data class LanguageModelTool(
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
    val providerExecuted: Boolean = false,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val strict: Boolean = true,
    /** Provider-specific config sent to the model for this tool (upstream's `tool.providerOptions`). */
    val providerOptions: ProviderOptions = ProviderOptions.None,
) {
    /** Parsed once and cached — not a constructor arg, so not serialized. */
    val parametersSchema: JsonElement by lazy { aiSdkJson.parseToJsonElement(parametersSchemaJson) }
}

/** One-shot generate result. */
public data class LanguageModelResult(
    val text: String,
    val toolCalls: List<ContentPart.ToolCall> = emptyList(),
    val finishReason: FinishReason,
    val usage: Usage,
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    val content: List<ContentPart> = buildList {
        if (text.isNotEmpty()) add(ContentPart.Text(text))
        addAll(toolCalls)
    },
    val rawFinishReason: String? = null,
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
)

/** Provider stream plus request/response metadata known before collection. */
public data class LanguageModelStreamResult(
    val stream: Flow<StreamEvent>,
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
)

/**
 * Provider warning for a call that still completed. Mirrors v6's
 * `CallWarning` shape without baking provider-specific warning enums
 * into common code.
 */
@Serializable
public data class CallWarning(
    val type: String,
    val message: String? = null,
    val details: JsonElement? = null,
)

/** Request metadata recorded by HTTP-backed or gateway providers. */
public data class LanguageModelRequestMetadata(
    val body: JsonElement? = null,
)

/** Response metadata recorded by HTTP-backed or gateway providers. */
public data class LanguageModelResponseMetadata(
    val id: String? = null,
    val timestampMillis: Long? = null,
    val modelId: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
    /**
     * The messages produced by this response (assistant + tool messages), for
     * multi-turn persistence without holding the full conversation list. Empty
     * unless the caller/provider populates it. Mirrors upstream `response.messages`.
     */
    val messages: List<ModelMessage> = emptyList(),
)
