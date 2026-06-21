package ai.torad.aisdk.ui

import ai.torad.aisdk.AiSdkDsl
import ai.torad.aisdk.Tool

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
 */
public class UIToolInvocation<TInput, TOutput>(
    public val toolCallId: String,
    public val toolName: String,
    public val state: ToolCallState,
    public val input: TInput?,
    public val output: TOutput?,
    public val error: String?,
)

/**
 * Registry of per-tool renderers. The SDK is UI-framework-agnostic — the
 * registry holds renderer functions that take [UIToolInvocation]. An
 * application can supply Compose composables, SwiftUI bridge closures,
 * server-rendered nodes, or any other renderer value.
 *
 * Stays out of `Compose` imports so the SDK remains platform-agnostic.
 */
public class ToolPartHandlerRegistry<TRenderResult> internal constructor(
    private val handlers: Map<String, (UIMessagePart.ToolUI) -> TRenderResult>,
    private val fallback: (UIMessagePart.ToolUI) -> TRenderResult,
) {
    public fun render(part: UIMessagePart.ToolUI): TRenderResult =
        handlers[part.toolName]?.invoke(part) ?: fallback(part)

    @AiSdkDsl
    public class Builder<TRenderResult> internal constructor() {
        internal val handlers: MutableMap<String, (UIMessagePart.ToolUI) -> TRenderResult> = mutableMapOf()

        /**
         * Bind a typed renderer for [tool]. The renderer receives a
         * [UIToolInvocation] with `input` / `output` already deserialized
         * via the tool's own serializers.
         */
        public fun <TInput, TOutput, TContext> register(
            tool: Tool<TInput, TOutput, TContext>,
            render: (UIToolInvocation<TInput, TOutput>) -> TRenderResult,
        ) {
            handlers[tool.name] = { part ->
                val typed = UIToolInvocation(
                    toolCallId = part.toolCallId,
                    toolName = part.toolName,
                    state = part.state,
                    input = TypedJsonOps.inputAs(part, tool.inputSerializer),
                    output = TypedJsonOps.outputAs(part, tool.outputSerializer),
                    error = part.error,
                )
                render(typed)
            }
        }

        internal fun build(
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
