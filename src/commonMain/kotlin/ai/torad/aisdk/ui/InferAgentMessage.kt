package ai.torad.aisdk.ui

import ai.torad.aisdk.AiSdkDsl
import ai.torad.aisdk.Tool
import dev.drewhamilton.poko.Poko

/**
 * Kotlin substitute for v6's `InferAgentUIMessage<typeof agent>` type
 * inference. Kotlin lacks TypeScript's literal-type narrowing on string
 * discriminants, so we provide an explicit registry: tools register typed
 * renderers; the message renderer dispatches via [ToolPartHandlerRegistry].
 *
 * This is the type-safe seam from tool definition → UI component. Adding
 * a new tool means: define the tool, register a handler, the renderer
 * dispatches automatically.
 *
 * Idiomatic use in an application:
 * ```
 * val handlers = ToolPartHandlerRegistry(
 *     fallback = { part -> UnknownToolCard(part) },
 * ) {
 *     register(searchDocsTool) { invocation ->
 *         SearchResultsView(
 *             results = invocation.output.orEmpty(),
 *             state = invocation.state,
 *         )
 *     }
 *     register(createTicketTool) { invocation ->
 *         TicketView(
 *             ticket = invocation.output,
 *             state = invocation.state,
 *         )
 *     }
 * }
 *
 * @Composable
 * fun MessagePartRenderer(part: UIMessagePart) {
 *     when (part) {
 *         is UIMessagePart.Text -> TextBubble(part.text)
 *         is UIMessagePart.ToolUI -> handlers.render(part)
 *         is UIMessagePart.Reasoning -> ReasoningCollapsible(part.text)
 *         is UIMessagePart.Error -> ErrorBanner(part.message)
 *     }
 * }
 * ```
 */

/**
 * Typed invocation handle — what a per-tool renderer receives. Carries
 * the typed input + output via the tool's own serializers.
  * @since 0.3.0-beta01
 */
@Poko
public class UIToolInvocationPayload<TInput, TOutput>(
    /** @since 0.3.0-beta01 */
    public val input: TInput?,
    /** @since 0.3.0-beta01 */
    public val output: TOutput?,
    /** @since 0.3.0-beta01 */
    public val error: String?,
)

@Poko
/** @since 0.3.0-beta01 */
public class UIToolInvocationMetadata(
    /** @since 0.3.0-beta01 */
    public val preliminary: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val approvalId: String? = null,
    /** @since 0.3.0-beta01 */
    public val signature: String? = null,
)

/** @since 0.3.0-beta01 */
public class UIToolInvocation<TInput, TOutput> constructor(
    /** @since 0.3.0-beta01 */
    public val toolCallId: String,
    /** @since 0.3.0-beta01 */
    public val toolName: String,
    /** @since 0.3.0-beta01 */
    public val state: ToolCallState,
    /** @since 0.3.0-beta01 */
    public val payload: UIToolInvocationPayload<TInput, TOutput>,
    /** @since 0.3.0-beta01 */
    public val metadata: UIToolInvocationMetadata = UIToolInvocationMetadata(),
) {
    /** @since 0.3.0-beta01 */
    public val input: TInput?
        get() = payload.input

    /** @since 0.3.0-beta01 */
    public val output: TOutput?
        get() = payload.output

    /** @since 0.3.0-beta01 */
    public val error: String?
        get() = payload.error

    /** @since 0.3.0-beta01 */
    public val preliminary: Boolean
        get() = metadata.preliminary

    /** @since 0.3.0-beta01 */
    public val approvalId: String?
        get() = metadata.approvalId

    /** @since 0.3.0-beta01 */
    public val signature: String?
        get() = metadata.signature
}

/**
 * Registry of per-tool renderers. The SDK is UI-framework-agnostic — the
 * registry holds renderer functions that take [UIToolInvocation]. An
 * application can supply Compose composables, SwiftUI bridge closures,
 * server-rendered nodes, or any other renderer value.
 *
 * Stays out of `Compose` imports so the SDK remains platform-agnostic.
  * @since 0.3.0-beta01
 */
public class ToolPartHandlerRegistry<TRenderResult> internal constructor(
    private val handlers: Map<String, (UIMessagePart.ToolUI) -> TRenderResult>,
    private val fallback: (UIMessagePart.ToolUI) -> TRenderResult,
) {
    /** @since 0.3.0-beta01 */
    public fun render(part: UIMessagePart.ToolUI): TRenderResult =
        handlers[part.toolName]?.invoke(part) ?: fallback(part)

    @AiSdkDsl
    /** @since 0.3.0-beta01 */
    public class Builder<TRenderResult> {
        internal val handlers: MutableMap<String, (UIMessagePart.ToolUI) -> TRenderResult> = mutableMapOf()

        /**
         * Bind a typed renderer for [tool]. The renderer receives a
         * [UIToolInvocation] with `input` / `output` already deserialized
         * via the tool's own serializers.
         */
        public fun <TInput, TOutput, TContext> register(
            tool: Tool<TInput, TOutput, TContext>,
            render: (UIToolInvocation<TInput, TOutput>) -> TRenderResult,
        ): Builder<TRenderResult> {
            handlers[tool.name] = { part ->
                val typed = UIToolInvocation(
                    toolCallId = part.toolCallId,
                    toolName = part.toolName,
                    state = part.state,
                    payload = UIToolInvocationPayload(
                        input = part.inputAs(tool.inputSerializer),
                        output = part.outputAs(tool.outputSerializer),
                        error = part.error,
                    ),
                    metadata = UIToolInvocationMetadata(
                        preliminary = part.preliminary,
                        approvalId = part.approvalId,
                        signature = part.signature,
                    ),
                )
                render(typed)
            }
            return this
        }

        /** @since 0.3.0-beta01 */
        public fun build(
            fallback: (UIMessagePart.ToolUI) -> TRenderResult,
        ): ToolPartHandlerRegistry<TRenderResult> =
            ToolPartHandlerRegistry(handlers.toMap(), fallback)
    }
}

/** Faux-constructor builder for [ToolPartHandlerRegistry]. */
public fun <TRenderResult> ToolPartHandlerRegistry(
    fallback: (UIMessagePart.ToolUI) -> TRenderResult,
    block: ToolPartHandlerRegistry.Builder<TRenderResult>.() -> Unit,
): ToolPartHandlerRegistry<TRenderResult> {
    val builder = ToolPartHandlerRegistry.Builder<TRenderResult>()
    builder.block()
    return builder.build(fallback)
}
