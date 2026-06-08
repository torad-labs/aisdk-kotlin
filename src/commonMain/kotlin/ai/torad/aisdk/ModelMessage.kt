package ai.torad.aisdk

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/**
 * v6 wire-shape message — the type passed to [LanguageModel] generations.
 * Renamed from `CoreMessage` (v5) per the AI SDK v6 migration.
 *
 * A message has a [role] and a list of [content] parts. For simple text
 * messages, content is a single [ContentPart.Text]. Tool calls and tool
 * results travel as their own content part variants. Tool approval
 * (request and response) likewise rides on dedicated content parts so
 * approval state is persisted in the message log alongside everything else.
 */
@Serializable
public data class ModelMessage(
    val role: MessageRole,
    val content: List<ContentPart>,
)

// Top-level convenience constructors.
// Naming: `<role>Message(...)` for the four role-shaped factories.

public fun systemMessage(text: String): ModelMessage = ModelMessage(MessageRole.System, listOf(ContentPart.Text(text)))
public fun userMessage(text: String): ModelMessage = ModelMessage(MessageRole.User, listOf(ContentPart.Text(text)))
public fun assistantMessage(text: String): ModelMessage = ModelMessage(MessageRole.Assistant, listOf(ContentPart.Text(text)))
public fun toolMessage(toolCallId: String, toolName: String, output: JsonElement): ModelMessage = ModelMessage(
    MessageRole.Tool,
    listOf(ContentPart.ToolResult(toolCallId, toolName, output)),
)
public fun toolApprovalResponseMessage(
    toolCallId: String,
    approved: Boolean,
    reason: String? = null,
    approvalId: String? = null,
): ModelMessage = ModelMessage(
    MessageRole.Tool,
    listOf(ContentPart.ToolApprovalResponse(toolCallId, approved, reason, approvalId)),
)

@Serializable
public enum class MessageRole { System, User, Assistant, Tool }

/**
 * Content part of a [ModelMessage]. Sealed so consumers exhaust over
 * variants — adding a new content type forces a compile error at every
 * dispatch site.
 *
 * Approval-related parts:
 *   - [ToolApprovalRequest] appears on **assistant** messages when a tool
 *     called by the model needs approval before execution. Per v6
 *     semantics, generation completes after emitting these parts; the
 *     host inspects them and calls [Agent.generate] again with…
 *   - [ToolApprovalResponse] on a **tool** message — one per pending
 *     approval — to resume the loop.
 */
// @SerialName values + the "type" discriminator match the Vercel AI SDK v6
// wire tags, so kotlinx (de)serializes parts under v6's discriminators (H-6).
// Note: a few fields are SDK extensions beyond v6's wire shape (e.g.
// ToolResult.modelVisible), so this is tag-compatible, not byte-identical.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
public sealed interface ContentPart {

    @Serializable
    @SerialName("text")
    public data class Text(
        val text: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : ContentPart

    @Serializable
    @SerialName("reasoning")
    public data class Reasoning(
        val text: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : ContentPart

    @Serializable
    @SerialName("tool-call")
    public data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val input: JsonElement,
        /** Provider ran the tool itself (e.g. server-side tools). v6 parity. */
        val providerExecuted: Boolean = false,
        /** Call made against a dynamic (runtime-typed) tool. v6 parity. */
        val dynamic: Boolean = false,
        /** Model emitted a malformed/unrepairable tool call. v6 parity. */
        val invalid: Boolean = false,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : ContentPart

    /**
     * Tool execution result. [output] is the canonical FULL payload —
     * persisted, dispatched to UI converters, rendered as the rich
     * card. [modelVisible] is what the LLM provider includes in the
     * prompt on subsequent turns — defaults to [output], but tools
     * with a `toModelOutput` callback (see [Tool.toModelOutput])
     * supply a short summary so multi-turn conversations don't keep
     * re-feeding 10k+ tokens of structured payload into the model's
     * KV cache. The two are intentionally separate: rehydrating the
     * UI card needs the full schema; the model only needs enough
     * context to compose a follow-up response.
     */
    @Serializable
    @SerialName("tool-result")
    public data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val output: JsonElement,
        val isError: Boolean = false,
        val modelVisible: JsonElement = output,
        /** Result of a dynamic (runtime-typed) tool call. v6 parity. */
        val dynamic: Boolean = false,
        /** Tool was executed by the provider. v6 parity. */
        val providerExecuted: Boolean = false,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : ContentPart

    /** Assistant content: the LLM called a tool that requires approval. */
    @Serializable
    @SerialName("tool-approval-request")
    public data class ToolApprovalRequest(
        val toolCallId: String,
        val toolName: String,
        val input: JsonElement,
        /**
         * Approval-identity key distinct from [toolCallId] per v6
         * (gap #7 in AISDK_PORT_GAPS.md). Null defaults to
         * [toolCallId] for the common single-approval case; explicit
         * value lets two approvals share a tool-call id without
         * correlation ambiguity.
         */
        val approvalId: String? = null,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : ContentPart

    /** Tool content: the host's decision on a previously requested approval. */
    @Serializable
    @SerialName("tool-approval-response")
    public data class ToolApprovalResponse(
        val toolCallId: String,
        val approved: Boolean,
        val reason: String? = null,
        val approvalId: String? = null,
    ) : ContentPart

    @Serializable
    @SerialName("source")
    public data class Source(
        val sourceType: StreamEvent.SourcePart.SourceType,
        val url: String? = null,
        val title: String? = null,
        val providerMetadata: Map<String, JsonElement>? = null,
        /** Media type of a document source (e.g. application/pdf). */
        val mediaType: String? = null,
        /** Optional display name of a document source. */
        val filename: String? = null,
    ) : ContentPart

    /**
     * File content — payload data the model produced or that's
     * attached to a turn. Per AISDK_PORT_GAPS.md gap #38, mirrors
     * v6's richer `FilePart.data`. Currently `base64` is the only
     * data shape; a URL-shaped variant lives in [Source]. `filename`
     * is the user-facing label (Anthropic models produce it for
     * artifact-like outputs; v6 has a dedicated slot for it).
     */
    @Serializable
    @SerialName("file")
    public data class File(
        val mediaType: String,
        val base64: String = "",
        /** Optional display name. v6 calls this `filename`. */
        val filename: String? = null,
        val providerMetadata: Map<String, JsonElement>? = null,
        /**
         * Remote (or data) URL for the file content, when not provided inline as
         * [base64]. Mirrors v6's `data: DataContent | URL`. Resolve with
         * [convertToLanguageModelPrompt] for providers that don't accept URLs.
         */
        val url: String? = null,
    ) : ContentPart

    /**
     * Image content — distinct from generic [File] so multimodal
     * models (vision providers, image-generation tools) get a typed
     * variant. Per AISDK_PORT_GAPS.md gap #39, mirrors v6's
     * `ImagePart`. `mediaType` is the MIME type (e.g. `image/png`);
     * `base64` is the raw image bytes encoded.
     *
     * On-device targets (Gemma 4 E2B text-only for now) never emit
     * this — added for forward parity with v6 multimodal providers
     * the port might back later (vision-capable LiteRT-LM bundles,
     * cloud Anthropic / OpenAI).
     */
    @Serializable
    @SerialName("image")
    public data class Image(
        val mediaType: String,
        val base64: String = "",
        val providerMetadata: Map<String, JsonElement>? = null,
        /**
         * Remote (or data) URL for the image, when not provided inline as
         * [base64]. Mirrors v6's `image: DataContent | URL`. Resolve with
         * [convertToLanguageModelPrompt] for providers that don't accept URLs.
         */
        val url: String? = null,
    ) : ContentPart
}

/**
 * Token-usage tracking, surfaced on completed steps and final results.
 * Per AISDK_PORT_GAPS.md gap #19, the shape mirrors v6's rich tree —
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
public data class Usage(
    val inputTokens: InputTokenBreakdown = InputTokenBreakdown(),
    val outputTokens: OutputTokenBreakdown = OutputTokenBreakdown(),
    val raw: JsonElement? = null,
) {
    /**
     * Legacy flat `(promptTokens, completionTokens)` constructor —
     * unambiguous because BOTH params are required. The primary
     * constructor's `Usage()` no-arg path goes through default
     * `InputTokenBreakdown()` / `OutputTokenBreakdown()`.
     */
    public constructor(
        promptTokens: Int,
        completionTokens: Int,
    ) : this(
        inputTokens = InputTokenBreakdown(total = promptTokens),
        outputTokens = OutputTokenBreakdown(total = completionTokens),
    )

    /** Legacy flat accessor — `inputTokens.total`. */
    val promptTokens: Int get() = inputTokens.total

    /** Legacy flat accessor — `outputTokens.total`. */
    val completionTokens: Int get() = outputTokens.total

    /** Legacy flat accessor — `promptTokens + completionTokens`. */
    val totalTokens: Int get() = promptTokens + completionTokens

    @Serializable
    public data class InputTokenBreakdown(
        val total: Int = 0,
        /** Tokens that were billed without cache participation. */
        val noCache: Int = 0,
        /** Tokens read from a provider prompt cache (saves billing). */
        val cacheRead: Int = 0,
        /** Tokens written to a provider prompt cache (first-time cost). */
        val cacheWrite: Int = 0,
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
    public data class OutputTokenBreakdown(
        val total: Int = 0,
        /** Visible response tokens. */
        val text: Int = 0,
        /** Hidden reasoning tokens (Anthropic thinking, OpenAI reasoning). */
        val reasoning: Int = 0,
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

/** Why a generation step ended. */
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
}
