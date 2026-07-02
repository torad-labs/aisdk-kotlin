package ai.torad.aisdk

import ai.torad.aisdk.protocol.ProtocolAdapters
import dev.drewhamilton.poko.Poko
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Sealed event stream emitted by [LanguageModel.stream] and surfaced to
 * application code via [Agent.stream]. Mirrors the v6 stream-part taxonomy
 * (per `packages/ai/src/generate-text/stream-text-result.ts` in vercel/ai)
 * adapted to Kotlin sealed classes for exhaustive `when`.
 *
 * v6 organizes blocks (text, reasoning, tool input) with **block IDs** so
 * a single message can interleave multiple separate text segments,
 * reasoning blocks, and tool calls without merging them. Each `*-start`
 * event opens a block by ID; `*-delta` events grow it; `*-end` closes it.
 *
 * **`providerMetadata`** (per historical parity gap #11) rides on every
 * content + tool-lifecycle variant — the `Map<String, JsonElement>?`
 * escape hatch v6 uses for provider-specific payloads (Anthropic
 * thinking signatures + `cache_control`, OpenAI reasoning effort /
 * logprobs, prompt-cache hints). On-device LiteRT-LM leaves it null;
 * it's the slot a cloud provider fills. Excluded from the pure control
 * singletons ([StreamStart], [Abort]), the terminal [Error], and [Raw]
 * (whose `rawValue` already IS the provider payload).
 *
 * Sequence the model can produce in one step:
 * ```
 * StreamStart(warnings)
 *   StepStart
 *     TextStart("t1") → TextDelta("t1", "...") → TextEnd("t1")
 *     ReasoningStart("r1") → ReasoningDelta("r1", "...") → ReasoningEnd("r1")
 *     ToolInputStart("ti1", "weather") → ToolInputDelta("ti1", "{...") → ToolInputEnd("ti1")
 *     ToolCall("call_1", "weather", inputJson)
 *     [ToolApprovalRequest("call_1", "weather", inputJson) → loop ends]
 *     [or] ToolResult("call_1", "weather", outputJson)
 *     [or] ToolError("call_1", "weather", message)
 *     TextStart("t2") → TextDelta(...) → TextEnd("t2")
 *     SourcePart(...) → FilePart(...)
 *     Raw(...) — provider-specific escape hatch
 *   StepFinish(stepNumber, finishReason, usage)
 * ...
 * Finish(totalSteps, finishReason, usage)
 * Abort | Error  — terminal alternatives
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
/** @since 0.3.0-beta01 */
public sealed class StreamEvent {

    /**
     * Stream began. Emitted exactly once at the very top.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("stream-start")
    @Poko
    public class StreamStart(
        /** @since 0.3.0-beta01 */
        public val warnings: List<CallWarning> = emptyList(),
    ) : StreamEvent()

    /**
     * Provider response metadata that becomes available after the
     * stream request starts. Mirrors v6's `response-metadata` stream
     * part so [StreamTextResult.response] can expose IDs, timestamps,
     * model IDs, headers, and retained response bodies.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("response-metadata")
    @Poko
    public class ResponseMetadata(
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
    ) : StreamEvent() {
        internal fun toLanguageModelResponseMetadata(): LanguageModelResponseMetadata =
            LanguageModelResponseMetadata(
                id = id,
                timestampMillis = timestampMillis,
                modelId = modelId,
                headers = headers,
                body = body,
            )

        public companion object {
            /**
             * Response metadata from an OpenAI-compatible stream chunk — `null`
             * when the chunk carries no id / model / timestamp.
             */
            internal fun fromOpenAI(obj: JsonObject): ResponseMetadata? {
                // `as?` (not `?.jsonPrimitive`, which throws on a non-primitive value): a quirky
                // object/array id/model/created must degrade to null, not abort the whole stream.
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
                val modelId = (obj["model"] as? JsonPrimitive)?.contentOrNull
                val timestampMillis = (obj["created"] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
                if (id == null && modelId == null && timestampMillis == null) return null
                return ResponseMetadata(
                    id = id,
                    modelId = modelId,
                    timestampMillis = timestampMillis,
                )
            }
        }
    }

    /**
     * New step (one LLM call) began. Emitted at the start of every loop iteration.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("step-start")
    @Poko
    public class StepStart(
        /** @since 0.3.0-beta01 */
        public val stepNumber: Int,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    @Serializable
    @SerialName("text-start")
    @Poko
    /** @since 0.3.0-beta01 */
    public class TextStart(
        /** @since 0.3.0-beta01 */
        public val id: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    @Serializable
    @SerialName("text-delta")
    @Poko
    /** @since 0.3.0-beta01 */
    public class TextDelta(
        /** @since 0.3.0-beta01 */
        public val id: String,
        /** @since 0.3.0-beta01 */
        public val text: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    @Serializable
    @SerialName("text-end")
    @Poko
    /** @since 0.3.0-beta01 */
    public class TextEnd(
        /** @since 0.3.0-beta01 */
        public val id: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    @Serializable
    @SerialName("reasoning-start")
    @Poko
    /** @since 0.3.0-beta01 */
    public class ReasoningStart(
        /** @since 0.3.0-beta01 */
        public val id: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    @Serializable
    @SerialName("reasoning-delta")
    @Poko
    /** @since 0.3.0-beta01 */
    public class ReasoningDelta(
        /** @since 0.3.0-beta01 */
        public val id: String,
        /** @since 0.3.0-beta01 */
        public val text: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    @Serializable
    @SerialName("reasoning-end")
    @Poko
    /** @since 0.3.0-beta01 */
    public class ReasoningEnd(
        /** @since 0.3.0-beta01 */
        public val id: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /**
     * Citation / web grounding source.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("source")
    @Poko
    public class SourcePart(
        /** @since 0.3.0-beta01 */
        public val id: String,
        /** @since 0.3.0-beta01 */
        public val sourceType: SourceType,
        /** @since 0.3.0-beta01 */
        public val url: String? = null,
        /** @since 0.3.0-beta01 */
        public val title: String? = null,
        /** @since 0.3.0-beta01 */
        public val mediaType: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent() {
        @Serializable
        /** @since 0.3.0-beta01 */
        public enum class SourceType { Url, Document }
    }

    /**
     * Generated file (image, audio, etc.).
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("file")
    @Poko
    public class FilePart(
        /** @since 0.3.0-beta01 */
        public val id: String,
        /** @since 0.3.0-beta01 */
        public val mediaType: String,
        /**
         * Base64-encoded contents — keep small, large files should stream via providerMetadata URLs.
         * @since 0.3.0-beta01
         */
        public val base64: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /**
     * Custom UI data part. Encodes to the v6 UI-message chunk shape
     * `{ type: "data-$name", id?, data, transient? }`.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("data")
    @Poko
    public class Data(
        /**
         * Name suffix for the UI chunk type, without the `data-` prefix.
         * @since 0.3.0-beta01
         */
        public val name: String,
        /** @since 0.3.0-beta01 */
        public val data: JsonElement,
        /** @since 0.3.0-beta01 */
        public val id: String? = null,
        /** @since 0.3.0-beta01 */
        public val transient: Boolean = false,
    ) : StreamEvent()

    /**
     * Tool input streaming opens — the model has decided which tool to call.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("tool-input-start")
    @Poko
    public class ToolInputStart(
        /** @since 0.3.0-beta01 */
        public val id: String,
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /**
     * Incremental input bytes for the in-flight tool call.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("tool-input-delta")
    @Poko
    public class ToolInputDelta(
        /** @since 0.3.0-beta01 */
        public val id: String,
        /** @since 0.3.0-beta01 */
        public val delta: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /**
     * Tool input streaming ends — full JSON has been received.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("tool-input-end")
    @Poko
    public class ToolInputEnd(
        /** @since 0.3.0-beta01 */
        public val id: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /**
     * Final, parsed tool call envelope. Emitted once `ToolInputEnd` fires and JSON parses.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("tool-call")
    @Poko
    public class ToolCall(
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val inputJson: JsonElement,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /**
     * Tool execution emitted a result. By default this is the final
     * result — appended to the model's message log + dispatched to UI
     * converters.
     *
     * Tools whose executor is a [kotlinx.coroutines.flow.Flow] (built
     * via `StreamingTool { ... }`) can emit intermediate progress
     * snapshots before the final value; those land as
     * `ToolResult(preliminary = true)`. Preliminary results show in
     * the UI (state stays `OutputAvailable`, `preliminary` flag set)
     * but do NOT go to the model — the model only sees the final
     * value once the Flow completes.
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
        public val outputJson: JsonElement,
        /** @since 0.3.0-beta01 */
        public val output: ToolResultOutput = ToolResultOutputs.toolResultOutputFromJson(outputJson),
        /** @since 0.3.0-beta01 */
        public val modelOutput: ToolResultOutput = output,
        // Canonical error check — also true for Content(isError = true) (the MCP shape),
        // which the old inline expression missed, diverging from isToolResultError().
        /** @since 0.3.0-beta01 */
        public val isError: Boolean = with(ToolResultOutputs) { modelOutput.isToolResultError() },
        /** @since 0.3.0-beta01 */
        public val preliminary: Boolean = false,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /**
     * Tool execution failed. [error] carries the typed [AgentError]
     * (NoSuchTool / InvalidToolInput / ToolExecution / ToolCallRepairFailed)
     * so in-process consumers can `when (event.error)` instead of
     * substring-matching [message]. `@Transient` — it's dropped on
     * serialization (AgentError isn't `@Serializable`); [message] is the
     * wire-stable rendering and stays the single source of display text.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("tool-error")
    @Poko
    public class ToolError(
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val message: String,
        @Transient public val error: AgentError? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /**
     * Tool requires approval. Per v6 RPC semantics, the loop **ends** after
     * emitting this event — the corresponding [ai.torad.aisdk.ContentPart.ToolApprovalRequest]
     * is added to the assistant message in the result, and the host calls
     * [Agent.generate] again with a tool message containing
     * [ai.torad.aisdk.ContentPart.ToolApprovalResponse] to resume.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("tool-approval-request")
    @Poko
    public class ToolApprovalRequest(
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val inputJson: JsonElement,
        /**
         * Approval-identity key, distinct from [toolCallId] per v6.
         * Null defaults to [toolCallId] for the common case (single
         * approval per call). Explicit when two approvals share a
         * tool-call id and need separate correlation.
         * @since 0.3.0-beta01
         */
        public val approvalId: String? = null,
        /**
         * HMAC-SHA256 approval signature (v6.0.202) — set only when the agent holds an approval secret.
         * @since 0.3.0-beta01
         */
        public val signature: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /**
     * The host denied a previously requested approval. v6's
     * `tool-output-denied` (per historical parity gaps #6 + #7).
     * Distinct from [ToolError] — denial is a CHOICE, not a failure.
     * The matching UI part transitions through
     * [ai.torad.aisdk.ui.ToolCallState.OutputDenied].
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("tool-output-denied")
    @Poko
    public class ToolOutputDenied(
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val approvalId: String,
        /** @since 0.3.0-beta01 */
        public val reason: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        /** @since 0.3.0-beta01 */
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /** Step ended — aggregated finish reason + usage for that one step.
     *  Per historical parity gap #18 (slice), [providerMetadata]
     *  carries provider-specific payloads (Anthropic prompt-cache
     *  hints, OpenAI reasoning trace tokens, etc.) so consumers can
     * @since 0.3.0-beta01
     *  measure cache-hit rate per step without parsing raw streams. */
    @Serializable
    @SerialName("step-finish")
    @Poko
    public class StepFinish(
        /** @since 0.3.0-beta01 */
        public val stepNumber: Int,
        /** @since 0.3.0-beta01 */
        public val finishReason: FinishReason,
        /** @since 0.3.0-beta01 */
        public val usage: Usage,
        /** Optional provider-specific payload; null on providers that
         * @since 0.3.0-beta01
         *  don't expose it. Mirrors v6's `finishStep.providerMetadata`. */
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : StreamEvent()

    /**
     * Loop ended — final aggregated finish reason + usage.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("finish")
    @Poko
    public class Finish(
        /** @since 0.3.0-beta01 */
        public val totalSteps: Int,
        /** @since 0.3.0-beta01 */
        public val finishReason: FinishReason,
        /** @since 0.3.0-beta01 */
        public val usage: Usage,
        /** Provider-specific summary payload on completion (per
         *  historical parity gap #18 slice). Routing layers measure
         * @since 0.3.0-beta01
         *  end-to-end cache rate here without parsing each step. */
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
        /** The provider's OWN finish-reason string, before mapping. The mapped [finishReason] collapses
         *  unknown values to [FinishReason.Other] — but for diagnosing a provider-side abort the raw
         *  string is the evidence; never discard it.
         *
         *  Populated by: Gemini (generateContent `finishReason`), Gemini Interactions (`status`),
         *  Anthropic (`stop_reason`), OpenAI Responses (`incomplete_details.reason` / error code),
         *  Cohere (from `LanguageModelResult.rawFinishReason`), Hugging Face Responses
         *  (`incomplete_details.reason`), Amazon Bedrock (`stopReason`), and
         *  `ai.torad.aisdk.middleware.simulateStreamingMiddleware` (propagated from the generate result).
         *  Null when the provider does not send a finish-reason string (e.g. KtorGatewayTransport,
         * @since 0.3.0-beta01
         *  which receives an already-mapped enum value on the wire). */
        public val rawFinishReason: String? = null,
    ) : StreamEvent()

    /**
     * Generation aborted via [AbortSignal]. Loop unwinds.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("abort")
    public data object Abort : StreamEvent()

    /**
     * Terminal error. Loop unwinds.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("error")
    @Poko
    public class Error(
        /** @since 0.3.0-beta01 */
        public val message: String,
        /** Typed cause when available, preserved to the boundary; not serialized. */
        @Transient public val cause: Throwable? = null,
    ) : StreamEvent()

    /**
     * Unprocessed provider-specific chunk. Escape hatch for cutting-edge
     * provider features that the SDK doesn't yet wrap in a typed event.
     * Off by default — providers opt in via call params. [rawValue] already
     * IS the provider payload, so no separate `providerMetadata` slot.
     * @since 0.3.0-beta01
     */
    @Serializable
    @SerialName("raw")
    @Poko
    public class Raw(public val rawValue: JsonElement) : StreamEvent()

    /**
     * Wire-protocol mapping helpers for [ai.torad.aisdk.ui.ToUIMessageStream]: the
     * StreamEvent→chunk dispatch table plus the JSON-chunk builder and finish-reason
     * mapping. Member-extensions so callers use a member-import, not a loose top-level fn.
     */
    public companion object {
        internal fun StreamEvent.toUIMessageChunk(): JsonObject? =
            ProtocolAdapters.uiMessageChunk(this)
    }
}
