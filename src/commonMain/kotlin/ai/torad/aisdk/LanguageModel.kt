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
/** @since 0.3.0-beta01 */
public interface LanguageModel {
    /**
     * Stable identifier for telemetry / routing (`"litertlm/gemma-3-1b"`, `"openai/gpt-5"`).
     * @since 0.3.0-beta01
     */
    public val modelId: String

    /**
     * Provider tag for the model — `"openai"`, `"anthropic"`,
     * `"litert-lm"`, etc. Mirrors v6's
     * `LanguageModelV3.provider` (per historical parity gap #40).
     * Lets routing layers (a fallback chain, a tier picker) make
     * provider-aware decisions without parsing [modelId].
     *
     * Default `"unknown"` — every real provider should override.
     * @since 0.3.0-beta01
     */
    public val provider: String
        get() = "unknown"

    /**
     * Map of URL regex → media-tag list describing which URL shapes
     * the provider accepts inline (so the agent doesn't have to
     * download + base64-encode them itself). Mirrors v6's
     * `LanguageModelV3.supportedUrls`. Empty default — most on-device
     * providers don't accept URLs and fall back to base64.
     * @since 0.3.0-beta01
     */
    public val supportedUrls: Map<String, List<String>>
        get() = emptyMap()

    /** One-shot completion. Requires explicit low-level opt-in at direct call sites. */
    @LowLevelLanguageModelApi
    public suspend fun generate(params: LanguageModelCallParams): LanguageModelResult

    /**
     * Streaming completion. Cold until collected, then drives one upstream call per
     * collection. Requires explicit low-level opt-in at direct call sites.
     * @since 0.3.0-beta01
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
     * @since 0.3.0-beta01
     */
    @LowLevelLanguageModelApi
    public fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        LanguageModelStreamResult(stream = stream(params))
}

/**
 * Single parameter envelope for both `generate` and `stream` so middleware
 * can wrap both the same way and `wrapLanguageModel` only needs one
 * pass-through shape.
 * @since 0.3.0-beta01
 */
@Poko
public class LanguageModelCallParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val messages: List<ModelMessage>,
    /** @since 0.3.0-beta01 */
    public val tools: List<LanguageModelTool> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val toolChoice: ToolChoice = ToolChoice.Auto,
    /** @since 0.3.0-beta01 */
    public val temperature: Float? = null,
    /** @since 0.3.0-beta01 */
    public val topP: Float? = null,
    /** @since 0.3.0-beta01 */
    public val topK: Int? = null,
    /** @since 0.3.0-beta01 */
    public val maxOutputTokens: Int? = null, // v6 name (was maxTokens)
    /** @since 0.3.0-beta01 */
    public val stopSequences: List<String> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val seed: Int? = null,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
    /**
     * Penalty for repeating tokens that already appeared in the
     * response, regardless of frequency. Mirrors v6's `CallSettings`
     * (per historical parity gap #3 closure). Null = provider default.
     * @since 0.3.0-beta01
     */
    public val presencePenalty: Float? = null,
    /**
     * Penalty proportional to how often a token has appeared.
     * Mirrors v6's `CallSettings`. Null = provider default.
     * @since 0.3.0-beta01
     */
    public val frequencyPenalty: Float? = null,
    /**
     * Wire-level shape constraint. Mirrors v6's
     * `LanguageModelV3CallOptions.responseFormat` (per
     * historical parity gap #20). Providers that support constrained
     * decoding honor it; others ignore. Default [ResponseFormat.Text]
     * = no constraint.
     * @since 0.3.0-beta01
     */
    public val responseFormat: ResponseFormat = ResponseFormat.Text,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
) {
    /** @since 0.3.0-beta01 */
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
/** @since 0.3.0-beta01 */
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

    /** @since 0.3.0-beta01 */
    public fun messages(value: List<ModelMessage>): LanguageModelCallParamsBuilder = apply {
        messages = value
    }

    /** @since 0.3.0-beta01 */
    public fun tools(value: List<LanguageModelTool>): LanguageModelCallParamsBuilder = apply {
        tools = value
    }

    /** @since 0.3.0-beta01 */
    public fun toolChoice(value: ToolChoice): LanguageModelCallParamsBuilder = apply {
        toolChoice = value
    }

    /** @since 0.3.0-beta01 */
    public fun temperature(value: Float?): LanguageModelCallParamsBuilder = apply {
        temperature = value
    }

    /** @since 0.3.0-beta01 */
    public fun topP(value: Float?): LanguageModelCallParamsBuilder = apply {
        topP = value
    }

    /** @since 0.3.0-beta01 */
    public fun topK(value: Int?): LanguageModelCallParamsBuilder = apply {
        topK = value
    }

    /** @since 0.3.0-beta01 */
    public fun maxOutputTokens(value: Int?): LanguageModelCallParamsBuilder = apply {
        maxOutputTokens = value
    }

    /** @since 0.3.0-beta01 */
    public fun stopSequences(value: List<String>): LanguageModelCallParamsBuilder = apply {
        stopSequences = value
    }

    /** @since 0.3.0-beta01 */
    public fun seed(value: Int?): LanguageModelCallParamsBuilder = apply {
        seed = value
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): LanguageModelCallParamsBuilder = apply {
        providerOptions = value
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): LanguageModelCallParamsBuilder = apply {
        abortSignal = value
    }

    /** @since 0.3.0-beta01 */
    public fun presencePenalty(value: Float?): LanguageModelCallParamsBuilder = apply {
        presencePenalty = value
    }

    /** @since 0.3.0-beta01 */
    public fun frequencyPenalty(value: Float?): LanguageModelCallParamsBuilder = apply {
        frequencyPenalty = value
    }

    /** @since 0.3.0-beta01 */
    public fun responseFormat(value: ResponseFormat): LanguageModelCallParamsBuilder = apply {
        responseFormat = value
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): LanguageModelCallParamsBuilder = apply {
        headers = value
    }

    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public fun LanguageModelCallParams(
    block: LanguageModelCallParamsBuilder.() -> Unit,
): LanguageModelCallParams =
    LanguageModelCallParamsBuilder().apply(block).build()

/**
 * Tool advertisement at the model surface — the JSON-schema shape the
 * provider needs. Distinct from the application-side [Tool] which carries
 * a Kotlin executor; this is the wire shape that crosses into a provider.
 * @since 0.3.0-beta01
 */
@Poko
public class LanguageModelTool(
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val description: String,
    /** @since 0.3.0-beta01 */
    public val parametersSchemaJson: String,
    /** @since 0.3.0-beta01 */
    public val providerExecuted: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val metadata: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val strict: Boolean? = null,
    /**
     * Provider-specific config sent to the model for this tool (upstream's `tool.providerOptions`).
     * @since 0.3.0-beta01
     */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
) {
    /**
     * Parsed once and cached — not a constructor arg, so not serialized.
     * @since 0.3.0-beta01
     */
    public val parametersSchema: JsonElement by lazy { aiSdkJson.parseToJsonElement(parametersSchemaJson) }
}

/**
 * One-shot generate result.
 * @since 0.3.0-beta01
 */
@Poko
public class LanguageModelResult(
    /** @since 0.3.0-beta01 */
    public val text: String,
    /** @since 0.3.0-beta01 */
    public val toolCalls: List<ContentPart.ToolCall> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val finishReason: FinishReason,
    /** @since 0.3.0-beta01 */
    public val usage: Usage,
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /** @since 0.3.0-beta01 */
    public val content: List<ContentPart> = buildList {
        if (text.isNotEmpty()) add(ContentPart.Text(text))
        addAll(toolCalls)
    },
    /** @since 0.3.0-beta01 */
    public val rawFinishReason: String? = null,
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
)

/**
 * Provider stream plus request/response metadata known before collection.
 * @since 0.3.0-beta01
 */
@Poko
public class LanguageModelStreamResult(
    /** @since 0.3.0-beta01 */
    public val stream: Flow<StreamEvent>,
    /** @since 0.3.0-beta01 */
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
)

/**
 * Provider warning for a call that still completed. Mirrors v6's
 * `CallWarning` shape without baking provider-specific warning enums
 * into common code.
 * @since 0.3.0-beta01
 */
@Serializable
@Poko
public class CallWarning(
    /** @since 0.3.0-beta01 */
    public val type: String,
    /** @since 0.3.0-beta01 */
    public val message: String? = null,
    /** @since 0.3.0-beta01 */
    public val details: JsonElement? = null,
) {
    /** Render this warning for the logger seam (upstream's logWarnings). */
    internal fun format(): String =
        "AI SDK Warning [$type]: ${message ?: details?.toString().orEmpty()}"
}

/**
 * Request metadata recorded by HTTP-backed or gateway providers.
 * @since 0.3.0-beta01
 */
@Poko
public class LanguageModelRequestMetadata(
    /** @since 0.3.0-beta01 */
    public val body: JsonElement? = null,
)

/**
 * Response metadata recorded by HTTP-backed or gateway providers.
 * @since 0.3.0-beta01
 */
@Poko
public class LanguageModelResponseMetadata(
    /** @since 0.3.0-beta01 */
    public val id: String? = null,
    /** @since 0.3.0-beta01 */
    public val timestampMillis: Long? = null,
    /** @since 0.3.0-beta01 */
    public val modelId: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
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
