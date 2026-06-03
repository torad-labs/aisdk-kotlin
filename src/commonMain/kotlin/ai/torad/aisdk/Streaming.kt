package ai.torad.aisdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

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
 * **`providerMetadata`** (per AISDK_PORT_GAPS.md gap #11) rides on every
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
@Serializable
sealed interface StreamEvent {

    /** Stream began. Emitted exactly once at the very top. */
    @Serializable
    data class StreamStart(
        val warnings: List<CallWarning> = emptyList(),
    ) : StreamEvent

    /**
     * Provider response metadata that becomes available after the
     * stream request starts. Mirrors v6's `response-metadata` stream
     * part so [StreamTextResult.response] can expose IDs, timestamps,
     * model IDs, headers, and retained response bodies.
     */
    @Serializable
    data class ResponseMetadata(
        val id: String? = null,
        val timestampMillis: Long? = null,
        val modelId: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val body: JsonElement? = null,
    ) : StreamEvent

    /** New step (one LLM call) began. Emitted at the start of every loop iteration. */
    @Serializable
    data class StepStart(
        val stepNumber: Int,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    @Serializable
    data class TextStart(
        val id: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    @Serializable
    data class TextDelta(
        val id: String,
        val text: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    @Serializable
    data class TextEnd(
        val id: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    @Serializable
    data class ReasoningStart(
        val id: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    @Serializable
    data class ReasoningDelta(
        val id: String,
        val text: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    @Serializable
    data class ReasoningEnd(
        val id: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /** Citation / web grounding source. */
    @Serializable
    data class SourcePart(
        val id: String,
        val sourceType: SourceType,
        val url: String? = null,
        val title: String? = null,
        val mediaType: String? = null,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent {
        @Serializable
        enum class SourceType { Url, Document }
    }

    /** Generated file (image, audio, etc.). */
    @Serializable
    data class FilePart(
        val id: String,
        val mediaType: String,
        /** Base64-encoded contents — keep small, large files should stream via providerMetadata URLs. */
        val base64: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /** Tool input streaming opens — the model has decided which tool to call. */
    @Serializable
    data class ToolInputStart(
        val id: String,
        val toolName: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /** Incremental input bytes for the in-flight tool call. */
    @Serializable
    data class ToolInputDelta(
        val id: String,
        val delta: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /** Tool input streaming ends — full JSON has been received. */
    @Serializable
    data class ToolInputEnd(
        val id: String,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /** Final, parsed tool call envelope. Emitted once `ToolInputEnd` fires and JSON parses. */
    @Serializable
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val inputJson: JsonElement,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /**
     * Tool execution emitted a result. By default this is the final
     * result — appended to the model's message log + dispatched to UI
     * converters.
     *
     * Tools whose executor is a [kotlinx.coroutines.flow.Flow] (built
     * via `streamingTool { ... }`) can emit intermediate progress
     * snapshots before the final value; those land as
     * `ToolResult(preliminary = true)`. Preliminary results show in
     * the UI (state stays `OutputAvailable`, `preliminary` flag set)
     * but do NOT go to the model — the model only sees the final
     * value once the Flow completes.
     */
    @Serializable
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val outputJson: JsonElement,
        val output: ToolResultOutput = toolResultOutputFromJson(outputJson),
        val modelOutput: ToolResultOutput = output,
        val isError: Boolean = modelOutput is ToolResultOutput.Error ||
            modelOutput is ToolResultOutput.ErrorJson ||
            modelOutput is ToolResultOutput.ExecutionDenied,
        val preliminary: Boolean = false,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /**
     * Tool execution failed. [error] carries the typed [AgentError]
     * (NoSuchTool / InvalidToolInput / ToolExecution / ToolCallRepairFailed)
     * so in-process consumers can `when (event.error)` instead of
     * substring-matching [message]. `@Transient` — it's dropped on
     * serialization (AgentError isn't `@Serializable`); [message] is the
     * wire-stable rendering and stays the single source of display text.
     */
    @Serializable
    data class ToolError(
        val toolCallId: String,
        val toolName: String,
        val message: String,
        @Transient val error: AgentError? = null,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /**
     * Tool requires approval. Per v6 RPC semantics, the loop **ends** after
     * emitting this event — the corresponding [ai.torad.aisdk.ContentPart.ToolApprovalRequest]
     * is added to the assistant message in the result, and the host calls
     * [Agent.generate] again with a tool message containing
     * [ai.torad.aisdk.ContentPart.ToolApprovalResponse] to resume.
     */
    @Serializable
    data class ToolApprovalRequest(
        val toolCallId: String,
        val toolName: String,
        val inputJson: JsonElement,
        /**
         * Approval-identity key, distinct from [toolCallId] per v6.
         * Null defaults to [toolCallId] for the common case (single
         * approval per call). Explicit when two approvals share a
         * tool-call id and need separate correlation.
         */
        val approvalId: String? = null,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /**
     * The host denied a previously requested approval. v6's
     * `tool-output-denied` (per AISDK_PORT_GAPS.md gaps #6 + #7).
     * Distinct from [ToolError] — denial is a CHOICE, not a failure.
     * The matching UI part transitions through
     * [ai.torad.aisdk.ui.ToolCallState.OutputDenied].
     */
    @Serializable
    data class ToolOutputDenied(
        val toolCallId: String,
        val toolName: String,
        val approvalId: String,
        val reason: String? = null,
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /** Step ended — aggregated finish reason + usage for that one step.
     *  Per AISDK_PORT_GAPS.md gap #18 (slice), [providerMetadata]
     *  carries provider-specific payloads (Anthropic prompt-cache
     *  hints, OpenAI reasoning trace tokens, etc.) so consumers can
     *  measure cache-hit rate per step without parsing raw streams. */
    @Serializable
    data class StepFinish(
        val stepNumber: Int,
        val finishReason: FinishReason,
        val usage: Usage,
        /** Optional provider-specific payload; null on providers that
         *  don't expose it. Mirrors v6's `finishStep.providerMetadata`. */
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /** Loop ended — final aggregated finish reason + usage. */
    @Serializable
    data class Finish(
        val totalSteps: Int,
        val finishReason: FinishReason,
        val usage: Usage,
        /** Provider-specific summary payload on completion (per
         *  AISDK_PORT_GAPS.md gap #18 slice). Routing layers measure
         *  end-to-end cache rate here without parsing each step. */
        val providerMetadata: Map<String, JsonElement>? = null,
    ) : StreamEvent

    /** Generation aborted via [AbortSignal]. Loop unwinds. */
    @Serializable
    data object Abort : StreamEvent

    /** Terminal error. Loop unwinds. */
    @Serializable
    data class Error(val message: String) : StreamEvent

    /**
     * Unprocessed provider-specific chunk. Escape hatch for cutting-edge
     * provider features that the SDK doesn't yet wrap in a typed event.
     * Off by default — providers opt in via call params. [rawValue] already
     * IS the provider payload, so no separate `providerMetadata` slot.
     */
    @Serializable
    data class Raw(val rawValue: JsonElement) : StreamEvent
}
