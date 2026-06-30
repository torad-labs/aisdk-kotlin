package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Provider-neutral model surface. Per invariant I-4 / I-9, application code
 * never depends on a specific provider — it depends on this interface, and
 * provider-specific differences live in middleware (see [LanguageModelMiddleware]).
 *
 * Two low-level execution operations: `generate` for one-shot completions,
 * `stream` for incremental events. Both accept the same [LanguageModelCallParams].
 * Direct execution intentionally requires [LowLevelLanguageModelApi] opt-in;
 * application prompts should normally flow through [Agent] APIs so the agent owns
 * tool loops, middleware, telemetry, persistence, and output handling.
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

    /** One-shot completion. Requires explicit low-level opt-in at direct call sites. */
    @LowLevelLanguageModelApi
    public suspend fun generate(params: LanguageModelCallParams): LanguageModelResult

    /**
     * Streaming completion. Cold until collected, then drives one upstream call per
     * collection. Requires explicit low-level opt-in at direct call sites.
     */
    @LowLevelLanguageModelApi
    public fun stream(params: LanguageModelCallParams): Flow<StreamEvent>

    /**
     * Streaming completion plus metadata available before stream
     * collection. Implementations must keep the returned stream cold, and
     * must not `flowOn`/emit from a foreign context — the collection context
     * is the caller's choice.
     * Providers can override this to expose request bodies and response
     * headers while preserving the v6 `doStream` result shape.
     */
    @LowLevelLanguageModelApi
    public fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        LanguageModelStreamResult(stream = stream(params))
}

/**
 * Single parameter envelope for both `generate` and `stream` so middleware
 * can wrap both the same way and `wrapLanguageModel` only needs one
 * pass-through shape.
 */
@Poko
public class LanguageModelCallParams internal constructor(
    public val messages: List<ModelMessage>,
    public val tools: List<LanguageModelTool> = emptyList(),
    public val toolChoice: ToolChoice = ToolChoice.Auto,
    public val temperature: Float? = null,
    public val topP: Float? = null,
    public val topK: Int? = null,
    public val maxOutputTokens: Int? = null,        // v6 name (was maxTokens)
    public val stopSequences: List<String> = emptyList(),
    public val seed: Int? = null,
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    public val abortSignal: AbortSignal = AbortSignalNever,
    /**
     * Penalty for repeating tokens that already appeared in the
     * response, regardless of frequency. Mirrors v6's `CallSettings`
     * (per historical parity gap #3 closure). Null = provider default.
     */
    public val presencePenalty: Float? = null,
    /**
     * Penalty proportional to how often a token has appeared.
     * Mirrors v6's `CallSettings`. Null = provider default.
     */
    public val frequencyPenalty: Float? = null,
    /**
     * Wire-level shape constraint. Mirrors v6's
     * `LanguageModelV3CallOptions.responseFormat` (per
     * historical parity gap #20). Providers that support constrained
     * decoding honor it; others ignore. Default [ResponseFormat.Text]
     * = no constraint.
     */
    public val responseFormat: ResponseFormat = ResponseFormat.Text,
    public val headers: Map<String, String> = emptyMap(),
) {
    public fun toBuilder(): LanguageModelCallParamsBuilder =
        LanguageModelCallParamsBuilder().also {
            it.messages(messages)
            it.tools(tools)
            it.toolChoice(toolChoice)
            it.temperature(temperature)
            it.topP(topP)
            it.topK(topK)
            it.maxOutputTokens(maxOutputTokens)
            it.stopSequences(stopSequences)
            it.seed(seed)
            it.providerOptions(providerOptions)
            it.abortSignal(abortSignal)
            it.presencePenalty(presencePenalty)
            it.frequencyPenalty(frequencyPenalty)
            it.responseFormat(responseFormat)
            it.headers(headers)
        }
}

@AiSdkDsl
public class LanguageModelCallParamsBuilder internal constructor() {
    private var messages: List<ModelMessage>? = null
    private var tools: List<LanguageModelTool> = emptyList()
    private var toolChoice: ToolChoice = ToolChoice.Auto
    private var temperature: Float? = null
    private var topP: Float? = null
    private var topK: Int? = null
    private var maxOutputTokens: Int? = null
    private var stopSequences: List<String> = emptyList()
    private var seed: Int? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var abortSignal: AbortSignal = AbortSignalNever
    private var presencePenalty: Float? = null
    private var frequencyPenalty: Float? = null
    private var responseFormat: ResponseFormat = ResponseFormat.Text
    private var headers: Map<String, String> = emptyMap()

    public fun messages(value: List<ModelMessage>): LanguageModelCallParamsBuilder = apply {
        messages = value
    }

    public fun tools(value: List<LanguageModelTool>): LanguageModelCallParamsBuilder = apply {
        tools = value
    }

    public fun toolChoice(value: ToolChoice): LanguageModelCallParamsBuilder = apply {
        toolChoice = value
    }

    public fun temperature(value: Float?): LanguageModelCallParamsBuilder = apply {
        temperature = value
    }

    public fun topP(value: Float?): LanguageModelCallParamsBuilder = apply {
        topP = value
    }

    public fun topK(value: Int?): LanguageModelCallParamsBuilder = apply {
        topK = value
    }

    public fun maxOutputTokens(value: Int?): LanguageModelCallParamsBuilder = apply {
        maxOutputTokens = value
    }

    public fun stopSequences(value: List<String>): LanguageModelCallParamsBuilder = apply {
        stopSequences = value
    }

    public fun seed(value: Int?): LanguageModelCallParamsBuilder = apply {
        seed = value
    }

    public fun providerOptions(value: ProviderOptions): LanguageModelCallParamsBuilder = apply {
        providerOptions = value
    }

    public fun abortSignal(value: AbortSignal): LanguageModelCallParamsBuilder = apply {
        abortSignal = value
    }

    public fun presencePenalty(value: Float?): LanguageModelCallParamsBuilder = apply {
        presencePenalty = value
    }

    public fun frequencyPenalty(value: Float?): LanguageModelCallParamsBuilder = apply {
        frequencyPenalty = value
    }

    public fun responseFormat(value: ResponseFormat): LanguageModelCallParamsBuilder = apply {
        responseFormat = value
    }

    public fun headers(value: Map<String, String>): LanguageModelCallParamsBuilder = apply {
        headers = value
    }

    public fun build(): LanguageModelCallParams =
        LanguageModelCallParams(
            messages = requireNotNull(messages) { "LanguageModelCallParams.messages is required" },
            tools = tools,
            toolChoice = toolChoice,
            temperature = temperature,
            topP = topP,
            topK = topK,
            maxOutputTokens = maxOutputTokens,
            stopSequences = stopSequences,
            seed = seed,
            providerOptions = providerOptions,
            abortSignal = abortSignal,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty,
            responseFormat = responseFormat,
            headers = headers,
        )
}

public fun LanguageModelCallParams(
    block: LanguageModelCallParamsBuilder.() -> Unit,
): LanguageModelCallParams =
    LanguageModelCallParamsBuilder().apply(block).build()

/**
 * Tool advertisement at the model surface — the JSON-schema shape the
 * provider needs. Distinct from the application-side [Tool] which carries
 * a Kotlin executor; this is the wire shape that crosses into a provider.
 */
@Poko
public class LanguageModelTool(
    public val name: String,
    public val description: String,
    public val parametersSchemaJson: String,
    public val providerExecuted: Boolean = false,
    public val metadata: Map<String, JsonElement> = emptyMap(),
    public val strict: Boolean? = null,
    /** Provider-specific config sent to the model for this tool (upstream's `tool.providerOptions`). */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
) {
    /** Parsed once and cached — not a constructor arg, so not serialized. */
    public val parametersSchema: JsonElement by lazy { aiSdkJson.parseToJsonElement(parametersSchemaJson) }
}

/** One-shot generate result. */
@Poko
public class LanguageModelResult(
    public val text: String,
    public val toolCalls: List<ContentPart.ToolCall> = emptyList(),
    public val finishReason: FinishReason,
    public val usage: Usage,
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    public val content: List<ContentPart> = buildList {
        if (text.isNotEmpty()) add(ContentPart.Text(text))
        addAll(toolCalls)
    },
    public val rawFinishReason: String? = null,
    public val warnings: List<CallWarning> = emptyList(),
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
)

/** Provider stream plus request/response metadata known before collection. */
@Poko
public class LanguageModelStreamResult(
    public val stream: Flow<StreamEvent>,
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
)

/**
 * Provider warning for a call that still completed. Mirrors v6's
 * `CallWarning` shape without baking provider-specific warning enums
 * into common code.
 */
@Serializable
@Poko
public class CallWarning(
    public val type: String,
    public val message: String? = null,
    public val details: JsonElement? = null,
) {
    /** Render this warning for the logger seam (upstream's logWarnings). */
    internal fun format(): String =
        "AI SDK Warning [$type]: ${message ?: details?.toString().orEmpty()}"
}

/** Request metadata recorded by HTTP-backed or gateway providers. */
@Poko
public class LanguageModelRequestMetadata(
    public val body: JsonElement? = null,
)

/** Response metadata recorded by HTTP-backed or gateway providers. */
@Poko
public class LanguageModelResponseMetadata(
    public val id: String? = null,
    public val timestampMillis: Long? = null,
    public val modelId: String? = null,
    public val headers: Map<String, String> = emptyMap(),
    public val body: JsonElement? = null,
) {
    internal fun merge(other: LanguageModelResponseMetadata): LanguageModelResponseMetadata =
        LanguageModelResponseMetadata(
            id = other.id ?: id,
            timestampMillis = other.timestampMillis ?: timestampMillis,
            modelId = other.modelId ?: modelId,
            headers = headers + other.headers,
            body = other.body ?: body,
        )
}
