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
 */
@Poko
public class UIToolInvocationPayload<TInput, TOutput>(
    public val input: TInput?,
    public val output: TOutput?,
    public val error: String?,
)

@Poko
public class UIToolInvocationMetadata(
    public val preliminary: Boolean = false,
    public val approvalId: String? = null,
    public val signature: String? = null,
)

public class UIToolInvocation<TInput, TOutput> constructor(
    public val toolCallId: String,
    public val toolName: String,
    public val state: ToolCallState,
    public val payload: UIToolInvocationPayload<TInput, TOutput>,
    public val metadata: UIToolInvocationMetadata = UIToolInvocationMetadata(),
) {
    public val input: TInput?
        get() = payload.input

    public val output: TOutput?
        get() = payload.output

    public val error: String?
        get() = payload.error

    public val preliminary: Boolean
        get() = metadata.preliminary

    public val approvalId: String?
        get() = metadata.approvalId

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
