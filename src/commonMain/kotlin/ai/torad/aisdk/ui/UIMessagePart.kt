@file:OptIn(ExperimentalSerializationApi::class)

package ai.torad.aisdk.ui

import ai.torad.aisdk.ProviderMetadata
import ai.torad.aisdk.decodeAs
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * Per-message UI part. Mirrors v6's part taxonomy — `text` and tool parts
 * with `tool-{toolName}` discrimination via [ToolUI.toolName] (rather
 * than v5's generic `tool-invocation`).
 *
 * The render-time pattern in Compose:
 * ```
 * when (part) {
 *     is UIMessagePart.Text -> TextBubble(part.text)
 *     is UIMessagePart.ToolUI -> when (part.toolName) {
 *         "getLineup" -> LineupCard(outputAs(part, serializer<Lineup>()), part.state)
 *         "saveNote"  -> NoteSavedCard(outputAs(part, serializer<Note>()), part.state)
 *         else        -> UnknownToolCard(part)
 *     }
 *     is UIMessagePart.Reasoning      -> ReasoningCollapsible(part.text)
 *     is UIMessagePart.SourceUrl      -> SourceChip(part.url, part.title)
 *     is UIMessagePart.SourceDocument -> DocumentChip(part.title, part.mediaType)
 *     is UIMessagePart.File           -> FileBubble(part.mediaType, part.base64)
 *     is UIMessagePart.Error     -> ErrorBanner(part.message)
 * }
 * ```
 */
/**
 * Streaming-vs-final state for [UIMessagePart.Text] and
 * [UIMessagePart.Reasoning] (per historical parity gap #30, mirrors
 * v6's `TextUIPart.state: 'streaming' | 'done'`). Renderers use this
 * to drive a typing cursor / fade animation while the model is still
 * producing the part, and to commit a final layout pass on `Done`.
 *
 * Default value is [Done] so existing consumers that construct
 * Text/Reasoning parts directly (rehydration from storage,
 * test fixtures) keep working unchanged. The agent loop's
 * [streamToUiMessages] emits [Streaming] during `TextDelta` /
 * `ReasoningDelta` and flips to [Done] on the matching `*End`.
 */
@Serializable
public enum class TextUIPartState {
    Streaming,
    Done,
}

@Serializable
public sealed interface UIMessagePart {

    @Serializable
    public data class Text(
        val text: String,
        val state: TextUIPartState = TextUIPartState.Done,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : UIMessagePart

    /**
     * One tool call's full lifecycle in the UI. State progresses
     * `InputStreaming → InputAvailable → OutputAvailable` (or `Error`,
     * or `ApprovalRequired` when the loop is paused on user decision).
     * Component renderers can show the same card across states ("Looking
     * up lineup..." → "Found 12 sets" → final card).
     *
     * Per v6 naming: this is the equivalent of `part.type === 'tool-X'`
     * in TypeScript. We discriminate on [toolName] string at the render
     * site since Kotlin lacks TypeScript's literal-type narrowing.
     */
    @Serializable
    public data class ToolUI(
        val toolCallId: String,
        val toolName: String,
        val state: ToolCallState,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val error: String? = null,
        /**
         * True when [output] is an intermediate snapshot from a
         * `streamingTool` executor and a final value is still pending.
         * False (default) for one-shot tool outputs and for the final
         * emission from a streaming tool. UI uses this to show a
         * "loading more" indicator on a still-incomplete card without
         * collapsing back to `InputAvailable`. Mirrors v6's
         * `preliminary?: boolean` on `output-available`.
         */
        val preliminary: Boolean = false,
        /**
         * Approval identity for `ApprovalRequested`/`ApprovalResponded`
         * states — carried so a UI round-trip (convert to model messages,
         * resume) preserves the approval's correlation key. Null outside
         * the approval states. Mirrors v6's `approval.id`.
         */
        val approvalId: String? = null,
        /**
         * HMAC-SHA256 approval signature (v6.0.202, `approval.signature`).
         * Must survive the UI round-trip untouched: with a configured
         * approval secret, a replay missing it is denied fail-closed.
         */
        val signature: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : UIMessagePart

    @Serializable
    public data class Reasoning(
        val text: String,
        val state: TextUIPartState = TextUIPartState.Done,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : UIMessagePart

    /**
     * URL-shaped citation / web grounding source. Per
     * historical parity gap #29, this is the split from a single
     * unified `Source` variant — v6's `SourceUrlUIPart`.
     *
     * `sourceId` is the provider's stable handle for the source so
     * repeated mentions across multiple parts can be deduped.
     */
    @Serializable
    public data class SourceUrl(
        val sourceId: String,
        val url: String,
        val title: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : UIMessagePart

    /**
     * Document-shaped source — a referenced file the model used as
     * grounding (PDF, image, etc.). Per v6's `SourceDocumentUIPart`.
     * Distinct from [File] which carries the file's payload — this
     * variant is a CITATION POINTER to a document the model read,
     * not a file the model produced.
     */
    @Serializable
    public data class SourceDocument(
        val sourceId: String,
        val mediaType: String,
        val title: String,
        val filename: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : UIMessagePart

    /** Generated file (image, audio, etc.). */
    @Serializable
    public data class File(
        val mediaType: String,
        val base64: String,
        /** Optional display name (v6's `filename`), carried through to the model. */
        val filename: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : UIMessagePart

    @Serializable
    public data class Error(val message: String) : UIMessagePart

    /**
     * Typed custom data part. Mirrors v6 `data-*` UI parts while using
     * an explicit [type] string instead of TypeScript literal keys.
     */
    @Serializable
    public data class Data(
        val type: String,
        val data: JsonElement,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val providerMetadata: ProviderMetadata = ProviderMetadata.None,
        /**
         * Optional stable id. Streaming data parts that share an id replace the
         * prior one in place (e.g. a progress indicator that updates) rather than
         * appending a duplicate — matching v6's keyed data parts.
         */
        val id: String? = null,
        /**
         * Transient parts are shown live but not meant to be persisted in stored
         * message history. Mirrors v6's `transient` data-chunk flag.
         */
        val transient: Boolean = false,
    ) : UIMessagePart

    /**
     * Step boundary marker — emitted between LLM call iterations
     * when the agent does multi-step work (tool-call → tool-result →
     * next LLM call). Preserves the v6 `step-start` UI part so a
     * subagent handoff or multi-tool flow can render a visible
     * divider in the chat list. Per historical parity gap #8.
     */
    @Serializable
    public data class StepStart(val stepNumber: Int) : UIMessagePart

    /**
     * Runtime-typed tool invocation — same lifecycle shape as
     * [ToolUI] but for tools whose schema isn't known at compile
     * time (the common case in subagents, where the parent agent's
     * static `ToolPartHandlerRegistry` can't dispatch). Renderers
     * branch on `is DynamicToolUI` and fall back to a generic
     * "Calling [toolName]…" card with JSON-printed input / output.
     * Per historical parity gap #9.
     */
    @Serializable
    public data class DynamicToolUI(
        val toolCallId: String,
        val toolName: String,
        val state: ToolCallState,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val error: String? = null,
        val preliminary: Boolean = false,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    ) : UIMessagePart
}

/**
 * Type-safe extraction at the UI render seam. Pulls a typed `TOutput`
 * out of [UIMessagePart.ToolUI.output]. Returns `null` if state isn't
 * `OutputAvailable` (caller should branch on `state` first).
 *
 * Call as `outputAs(part, serializer<Result>())`.
 */
public fun <TOutput> outputAs(part: UIMessagePart.ToolUI, serializer: KSerializer<TOutput>): TOutput? {
    if (part.state != ToolCallState.OutputAvailable) return null
    val raw = part.output ?: return null
    return raw.decodeAs(serializer)
}

public inline fun <reified TOutput> UIMessagePart.ToolUI.outputAs(): TOutput? =
    outputAs(this, serializer())

/**
 * Type-safe extraction of input args while the tool is mid-flight or
 * complete. Returns `null` if input isn't yet available.
 */
public fun <TInput> inputAs(part: UIMessagePart.ToolUI, serializer: KSerializer<TInput>): TInput? {
    if (part.state == ToolCallState.InputStreaming) return null
    val raw = part.input ?: return null
    return raw.decodeAs(serializer)
}

public inline fun <reified TInput> UIMessagePart.ToolUI.inputAs(): TInput? =
    inputAs(this, serializer())

public fun <TData> dataAs(part: UIMessagePart.Data, serializer: KSerializer<TData>): TData =
    part.data.decodeAs(serializer)

public inline fun <reified TData> UIMessagePart.Data.dataAs(): TData =
    dataAs(this, serializer())

public fun <TOutput> dynamicOutputAs(
    part: UIMessagePart.DynamicToolUI,
    serializer: KSerializer<TOutput>,
): TOutput? {
    if (part.state != ToolCallState.OutputAvailable) return null
    val raw = part.output ?: return null
    return raw.decodeAs(serializer)
}

public inline fun <reified TOutput> UIMessagePart.DynamicToolUI.outputAs(): TOutput? =
    dynamicOutputAs(this, serializer())

public fun <TInput> dynamicInputAs(
    part: UIMessagePart.DynamicToolUI,
    serializer: KSerializer<TInput>,
): TInput? {
    if (part.state == ToolCallState.InputStreaming) return null
    val raw = part.input ?: return null
    return raw.decodeAs(serializer)
}

public inline fun <reified TInput> UIMessagePart.DynamicToolUI.inputAs(): TInput? =
    dynamicInputAs(this, serializer())
