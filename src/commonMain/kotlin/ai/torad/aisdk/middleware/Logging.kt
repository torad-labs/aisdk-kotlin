@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package ai.torad.aisdk.middleware

import ai.torad.aisdk.AiSdkDefaultRedactor
import ai.torad.aisdk.LanguageModelMiddleware
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.Logger
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.Redactor
import ai.torad.aisdk.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** @since 0.3.0-beta01 */
public class LoggingOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val recordInputs: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val recordOutputs: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val allowRawValues: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val redactor: Redactor = AiSdkDefaultRedactor,
)

/** @since 0.3.0-beta01 */
public class LoggingOptionsBuilder {
    private var recordInputs: Boolean = false
    private var recordOutputs: Boolean = false
    private var allowRawValues: Boolean = false
    private var redactor: Redactor = AiSdkDefaultRedactor

    /** @since 0.3.0-beta01 */
    public fun recordInputs(value: Boolean): LoggingOptionsBuilder {
        recordInputs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun recordOutputs(value: Boolean): LoggingOptionsBuilder {
        recordOutputs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun allowRawValues(value: Boolean): LoggingOptionsBuilder {
        allowRawValues = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun redactor(value: Redactor): LoggingOptionsBuilder {
        redactor = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LoggingOptions =
        LoggingOptions(
            recordInputs = recordInputs,
            recordOutputs = recordOutputs,
            allowRawValues = allowRawValues,
            redactor = redactor,
        )
}

/** @since 0.3.0-beta01 */
public fun LoggingOptions(
    block: LoggingOptionsBuilder.() -> Unit = {},
): LoggingOptions =
    LoggingOptionsBuilder().apply(block).build()

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
/** @since 0.3.0-beta01 */
public fun LoggingMiddleware(
    logger: Logger,
    tag: String = "agent",
): LanguageModelMiddleware = LoggingMiddleware(
    logger = logger,
    options = LoggingOptions {},
    tag = tag,
)

/** @since 0.3.0-beta01 */
public fun LoggingMiddleware(
    logger: Logger,
    options: LoggingOptions,
    tag: String = "agent",
): LanguageModelMiddleware = object : LanguageModelMiddleware {

    override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult {
        val result = context.doGenerate(context.params)
        logger.debug("[$tag] generate finishReason=${result.finishReason} toolCalls=${result.toolCalls.size}")
        return result
    }

    override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> =
        context.doStream(context.params).onEach { event ->
            logStreamEvent(
                logger = logger,
                tag = tag,
                options = options,
                toolSchemaBytes = context.params.tools.associate { tool ->
                    tool.name to tool.parametersSchemaJson.encodeToByteArray().size
                },
                event = event,
            )
        }

    /** Per-event routing for [LoggingMiddleware]'s stream path. */
    @Suppress("LongMethod")
    private fun logStreamEvent(
        logger: Logger,
        tag: String,
        options: LoggingOptions,
        toolSchemaBytes: Map<String, Int>,
        event: StreamEvent,
    ) {
        when (event) {
            is StreamEvent.ToolInputStart ->
                logger.debug("[$tag] tool-open id=${event.id} name=${event.toolName}")
            is StreamEvent.ToolCall ->
                logger.debug(
                    buildString {
                        append("[$tag] tool-call id=${event.toolCallId} name=${event.toolName}")
                        append(" schemaBytes=${toolSchemaBytes[event.toolName] ?: 0}")
                        append(" argsBytes=${event.inputJson.toString().encodeToByteArray().size}")
                        if (options.recordInputs) {
                            append(" args=")
                            append(
                                if (options.allowRawValues) {
                                    event.inputJson
                                } else {
                                    options.redactor.redactJson(event.inputJson)
                                },
                            )
                        }
                    },
                )
            is StreamEvent.ToolResult ->
                logger.debug(
                    buildString {
                        append("[$tag] tool-result id=${event.toolCallId} name=${event.toolName} ")
                        append("bytes=${event.outputJson.toString().encodeToByteArray().size}")
                        if (options.recordOutputs) {
                            append(" output=")
                            append(
                                if (options.allowRawValues) {
                                    event.outputJson
                                } else {
                                    options.redactor.redactJson(event.outputJson)
                                },
                            )
                        }
                    },
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
            is StreamEvent.Data,
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
