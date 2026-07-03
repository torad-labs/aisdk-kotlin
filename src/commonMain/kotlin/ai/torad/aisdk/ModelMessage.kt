package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v6 wire-shape message — the type passed to [LanguageModel] generations.
 * Renamed from `CoreMessage` (v5) per the AI SDK v6 migration.
 *
 * A message has a [role] and a list of [content] parts. For simple text
 * messages, content is a single [ContentPart.Text]. Tool calls and tool
 * results travel as their own content part variants. Tool approval
 * (request and response) likewise rides on dedicated content parts so
 * approval state is persisted in the message log alongside everything else.
 * @since 0.3.0-beta01
 */
@Serializable
@Poko
public class ModelMessage(
    /** @since 0.3.0-beta01 */
    public val role: MessageRole,
    /** @since 0.3.0-beta01 */
    public val content: List<ContentPart>,
)

// Top-level convenience constructors.
// Naming: `<role>Message(...)` for the four role-shaped factories.

/** @since 0.3.0-beta01 */
public fun SystemMessage(text: String): ModelMessage = ModelMessage(MessageRole.System, listOf(ContentPart.Text(text)))

/** @since 0.3.0-beta01 */
public fun UserMessage(text: String): ModelMessage = ModelMessage(MessageRole.User, listOf(ContentPart.Text(text)))

/** @since 0.3.0-beta01 */
public fun AssistantMessage(
    text: String
): ModelMessage = ModelMessage(MessageRole.Assistant, listOf(ContentPart.Text(text)))

/** @since 0.3.0-beta01 */
public fun ToolMessage(toolCallId: String, toolName: String, output: JsonElement): ModelMessage = ModelMessage(
    MessageRole.Tool,
    listOf(ContentPart.ToolResult(toolCallId, toolName, output)),
)

/** @since 0.3.0-beta01 */
public fun ToolApprovalResponseMessage(
    toolCallId: String,
    approved: Boolean,
    reason: String? = null,
    approvalId: String? = null,
): ModelMessage = ModelMessage(
    MessageRole.Tool,
    listOf(ContentPart.ToolApprovalResponse(toolCallId, approved, reason, approvalId)),
)

@Serializable
/** @since 0.3.0-beta01 */
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
/** @since 0.3.0-beta01 */
public sealed class ContentPart {

    @Serializable
    @SerialName("text")
    @Poko
    /** @since 0.3.0-beta01 */
    public class Text(
        /** @since 0.3.0-beta01 */
        public val text: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : ContentPart()

    @Serializable
    @SerialName("reasoning")
    @Poko
    /** @since 0.3.0-beta01 */
    public class Reasoning(
        /** @since 0.3.0-beta01 */
        public val text: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : ContentPart()

    @Serializable
    @SerialName("tool-call")
    @Poko
    /** @since 0.3.0-beta01 */
    public class ToolCall(
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val input: JsonElement,
        /**
         * Provider ran the tool itself (e.g. server-side tools). v6 parity.
         * @since 0.3.0-beta01
         */
        public val providerExecuted: Boolean = false,
        /**
         * Call made against a dynamic (runtime-typed) tool. v6 parity.
         * @since 0.3.0-beta01
         */
        public val dynamic: Boolean = false,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : ContentPart() {
        public companion object {
            /**
             * Tool-call `input` decoded from an OpenAI-compatible arguments
             * string: blank → empty object, valid JSON → parsed, otherwise the
             * raw string. Shared by the chat model (full response) and the
             * streaming state (incremental deltas).
             */
            internal fun parseOpenAIToolInput(value: String?): JsonElement =
                if (value.isNullOrBlank()) {
                    JsonObject(emptyMap())
                } else {
                    runCatching { aiSdkJson.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
                }

            /** Whether a streamed OpenAI-compatible arguments buffer is now complete (parseable) JSON. */
            internal fun isParsableOpenAIJson(value: String): Boolean =
                value.isNotBlank() && runCatching { aiSdkJson.parseToJsonElement(value) }.isSuccess

            /** Google `thought_signature` provider-metadata pulled from an OpenAI-compatible tool-call object. */
            internal fun thoughtSignatureMetadata(value: JsonObject): Map<String, JsonElement>? {
                val google = (JsonAccess.obj(value, "extra_content"))?.get("google") as? JsonObject
                val element = google?.get("thought_signature")
                val signature = (element as? JsonPrimitive)?.contentOrNull
                return signature?.let { mapOf("thoughtSignature" to JsonPrimitive(it)) }
            }
        }
    }

    /**
     * Tool execution result. [output] is the canonical FULL payload —
     * persisted, dispatched to UI converters, rendered as the rich
     * card. `modelVisible` is what the LLM provider includes in the
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
    @Poko
    /** @since 0.3.0-beta01 */
    public class ToolResult(
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val output: JsonElement,
        /** @since 0.3.0-beta01 */
        public val isError: Boolean = false,
        /** @since 0.3.0-beta01 */
        public val modelVisible: JsonElement = output,
        /**
         * Result of a dynamic (runtime-typed) tool call. v6 parity.
         * @since 0.3.0-beta01
         */
        public val dynamic: Boolean = false,
        /**
         * Tool was executed by the provider. v6 parity.
         * @since 0.3.0-beta01
         */
        public val providerExecuted: Boolean = false,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : ContentPart()

    /**
     * Assistant content: the LLM called a tool that requires approval.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("tool-approval-request")
    public data class ToolApprovalRequest(
        val toolCallId: String,
        val toolName: String,
        val input: JsonElement,
        /**
         * Approval-identity key distinct from [toolCallId] per v6
         * (gap #7 in historical parity work). Null defaults to
         * [toolCallId] for the common single-approval case; explicit
         * value lets two approvals share a tool-call id without
         * correlation ambiguity.
         */
        val approvalId: String? = null,
        /**
         * HMAC-SHA256 signature binding this approval to its tool call
         * (v6.0.202). Present only when the agent is configured with
         * `experimental_toolApprovalSecret`; verified fail-closed when
         * the approval is replayed.
         */
        val signature: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : ContentPart()

    /**
     * Tool content: the host's decision on a previously requested approval.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("tool-approval-response")
    @Poko
    public class ToolApprovalResponse(
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val approved: Boolean,
        /** @since 0.3.0-beta01 */
        public val reason: String? = null,
        /** @since 0.3.0-beta01 */
        public val approvalId: String? = null,
    ) : ContentPart()

    @Serializable
    @SerialName("source")
    @Poko
    /** @since 0.3.0-beta01 */
    public class Source(
        /** @since 0.3.0-beta01 */
        public val sourceType: StreamEvent.SourcePart.SourceType,
        /** Provider's stable handle for the source so repeated mentions can be deduped;
         * @since 0.3.0-beta01
         *  survives the UIMessage -> ModelMessage round-trip (was silently dropped). */
        public val sourceId: String? = null,
        /** @since 0.3.0-beta01 */
        public val url: String? = null,
        /** @since 0.3.0-beta01 */
        public val title: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
        /**
         * Media type of a document source (e.g. application/pdf).
         * @since 0.3.0-beta01
         */
        public val mediaType: String? = null,
        /**
         * Optional display name of a document source.
         * @since 0.3.0-beta01
         */
        public val filename: String? = null,
    ) : ContentPart()

    /**
     * File content — payload data the model produced or that's
     * attached to a turn. Per historical parity gap #38, mirrors
     * v6's richer `FilePart.data`. Currently `base64` is the only
     * data shape; a URL-shaped variant lives in [Source]. `filename`
     * is the user-facing label (Anthropic models produce it for
     * artifact-like outputs; v6 has a dedicated slot for it).
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("file")
    @Poko
    public class File(
        /** @since 0.3.0-beta01 */
        public val mediaType: String,
        /** @since 0.3.0-beta01 */
        public val base64: String = "",
        /**
         * Optional display name. v6 calls this `filename`.
         * @since 0.3.0-beta01
         */
        public val filename: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
        /**
         * Remote (or data) URL for the file content, when not provided inline as
         * [base64]. Mirrors v6's `data: DataContent | URL`. Resolve with
         * `convertToLanguageModelPrompt` for providers that don't accept URLs.
         * @since 0.3.0-beta01
         */
        public val url: String? = null,
    ) : ContentPart()

    /**
     * Image content — distinct from generic [File] so multimodal
     * models (vision providers, image-generation tools) get a typed
     * variant. Per historical parity gap #39, mirrors v6's
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
    @Poko
    /** @since 0.3.0-beta01 */
    public class Image(
        /** @since 0.3.0-beta01 */
        public val mediaType: String,
        /** @since 0.3.0-beta01 */
        public val base64: String = "",
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
        /**
         * Remote (or data) URL for the image, when not provided inline as
         * [base64]. Mirrors v6's `image: DataContent | URL`. Resolve with
         * `convertToLanguageModelPrompt` for providers that don't accept URLs.
         * @since 0.3.0-beta01
         */
        public val url: String? = null,
    ) : ContentPart()

    /**
     * Forward-compatible escape hatch for content parts a gateway or provider
     * knows about before this SDK does. The raw JSON is preserved so callers
     * can inspect or round-trip it instead of silently losing content.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("raw")
    @Poko
    public class Raw(public val rawValue: JsonElement) : ContentPart()
}
