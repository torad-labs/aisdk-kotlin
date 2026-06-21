package ai.torad.aisdk.middleware

import ai.torad.aisdk.LanguageModelMiddleware
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.Logger
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * Routes a model invocation's tool-call boundary + error events through
 * an injected [Logger] — the port-side, provider-agnostic home for
 * observability.
 *
 * Logging belongs in middleware, not in the provider (which stays focused
 * on engine ↔ SDK translation) nor the agent loop. This is the canonical
 * consumer of the [Logger] primitive: a host injects its platform
 * [Logger] (Logcat / Crashlytics / os_log adapter) and wraps its model
 * with `wrapLanguageModel(model, listOf(loggingMiddleware(logger)))`.
 *
 * Routing:
 *  - [StreamEvent.ToolError] → [Logger.warn], passing the `@Transient`
 *    typed [StreamEvent.ToolError.error] as the throwable when present.
 *  - [StreamEvent.Error] → [Logger.warn].
 *  - tool-open / tool-call / tool-result → [Logger.debug] so production
 *    hosts can gate verbosity.
 *  - Routine `TextDelta` / `ReasoningDelta` tokens are never logged —
 *    that would flood the sink.
 *
 * `wrapGenerate` logs a one-line completion summary; the streaming path
 * (the chat surface) carries the per-event detail.
 */
public fun LoggingMiddleware(
    logger: Logger,
    tag: String = "agent",
): LanguageModelMiddleware = object : LanguageModelMiddleware {

    override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult {
        val result = context.doGenerate(context.params)
        logger.debug("[$tag] generate finishReason=${result.finishReason} toolCalls=${result.toolCalls.size}")
        return result
    }

    override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> =
        context.doStream(context.params).onEach { event -> LoggingWire.logStreamEvent(logger, tag, event) }
}

/** Stream-event routing helpers for [LoggingMiddleware]. */
internal object LoggingWire {
    /** Per-event routing for [LoggingMiddleware]'s stream path. */
    fun logStreamEvent(logger: Logger, tag: String, event: StreamEvent) {
        when (event) {
            is StreamEvent.ToolInputStart ->
                logger.debug("[$tag] tool-open id=${event.id} name=${event.toolName}")
            is StreamEvent.ToolCall ->
                logger.debug("[$tag] tool-call id=${event.toolCallId} name=${event.toolName} args=${event.inputJson}")
            is StreamEvent.ToolResult ->
                logger.debug(
                    "[$tag] tool-result id=${event.toolCallId} name=${event.toolName} " +
                        "bytes=${event.outputJson.toString().length}",
                )
            is StreamEvent.ToolError ->
                logger.warn(
                    "[$tag] tool-error id=${event.toolCallId} name=${event.toolName} reason=${event.message}",
                    event.error,
                )
            is StreamEvent.Error ->
                // Forward the typed cause (like the ToolError branch forwards event.error) so a
                // Crashlytics/Sentry-style Logger sink can group + symbolicate the terminal error —
                // the one failure a developer most needs a stack trace for.
                logger.warn("[$tag] stream-error ${event.message}", event.cause)
            is StreamEvent.StreamStart,
            is StreamEvent.ResponseMetadata,
            is StreamEvent.StepStart,
            is StreamEvent.TextStart,
            is StreamEvent.TextDelta,
            is StreamEvent.TextEnd,
            is StreamEvent.ReasoningStart,
            is StreamEvent.ReasoningDelta,
            is StreamEvent.ReasoningEnd,
            is StreamEvent.SourcePart,
            is StreamEvent.FilePart,
            is StreamEvent.ToolInputDelta,
            is StreamEvent.ToolInputEnd,
            is StreamEvent.ToolApprovalRequest,
            is StreamEvent.ToolOutputDenied,
            is StreamEvent.StepFinish,
            is StreamEvent.Finish,
            StreamEvent.Abort,
            is StreamEvent.Raw,
            -> Unit // text / reasoning / lifecycle events are noise here.
        }
    }
}
