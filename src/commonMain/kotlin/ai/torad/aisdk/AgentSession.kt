package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** @since 0.3.0-beta01 */
public enum class AgentSessionStatus {
    Ready,
    Running,
    AwaitingApproval,
    Cancelled,
    Error,
}

/** @since 0.3.0-beta01 */
public data class AgentSessionState<TOutput>(
    val messages: List<ModelMessage> = emptyList(),
    val status: AgentSessionStatus = AgentSessionStatus.Ready,
    val text: String = "",
    val output: TOutput? = null,
    val pendingApprovals: List<PendingApproval> = emptyList(),
    val lastResult: GenerateResult<TOutput>? = null,
    val error: Throwable? = null,
) {
    val isRunning: Boolean
        get() = status == AgentSessionStatus.Running
}

@OptIn(ExperimentalAtomicApi::class)
/** @since 0.3.0-beta01 */
public class AgentSession<TContext, TOutput>(
    private val scope: CoroutineScope,
    private val agent: Agent<TContext, TOutput>,
    initialMessages: List<ModelMessage> = emptyList(),
) {
    private val mutableState = MutableStateFlow(AgentSessionState<TOutput>(messages = initialMessages))

    private val currentJobRef = AtomicReference<Job?>(null)

    // Bundles the last submit's call configuration so a resume (approve/deny) re-runs with the SAME
    // options / abort-signal / streaming-mode. This mirrors upstream Vercel AI SDK v6, where the host
    // re-passes its call settings on every resume generate()/stream(); without it a streamed turn would
    // go dark (non-streaming) the moment it crossed a tool approval. Lifecycle observation is via the
    // agent's `events()` Flow, not a per-call callback, so no hooks are remembered.
    private data class CallState<TContext>(
        val options: TContext?,
        val abortSignal: AbortSignal,
        val streaming: Boolean,
    )

    private data class StreamingTextProjection(
        val value: String,
        val metadata: ProviderMetadata,
    )

    private data class StreamingApprovalProjection(
        val records: List<StreamingApprovalRecord>,
    )

    private data class StreamingApprovalRecord(
        val approval: PendingApproval,
        val metadata: ProviderMetadata,
    )

    private data class StreamingToolResultRecord(
        val result: ContentPart.ToolResult,
        val provisionalDenied: Boolean = false,
    )

    private data class StreamingToolProjection(
        val calls: List<ContentPart.ToolCall>,
        val results: List<ContentPart.ToolResult>,
        val approvals: StreamingApprovalProjection,
    )

    private data class StreamingMessageProjection(
        val text: StreamingTextProjection,
        val extraAssistantParts: List<ContentPart>,
        val tools: StreamingToolProjection,
    )

    private val lastCallRef = AtomicReference<CallState<TContext>>(
        CallState(null, AbortSignalNever, false),
    )

    /** @since 0.3.0-beta01 */
    public val state: StateFlow<AgentSessionState<TOutput>> = mutableState.asStateFlow()

    private fun rememberCall(options: TContext?, abortSignal: AbortSignal, streaming: Boolean) {
        lastCallRef.store(CallState(options, abortSignal, streaming))
    }

    /** @since 0.3.0-beta01 */
    public fun submit(
        prompt: String? = null,
        messages: List<ModelMessage> = state.value.messages,
        options: TContext? = null,
        abortSignal: AbortSignal = AbortSignalNever,
    ): Job {
        currentJobRef.load()?.cancel()
        rememberCall(options, abortSignal, streaming = false)
        val visibleMessages = visibleMessages(messages, prompt)
        mutableState.update {
            it.copy(
                messages = visibleMessages,
                status = AgentSessionStatus.Running,
                error = null,
                pendingApprovals = emptyList(),
                text = "",
                output = null,
                lastResult = null,
            )
        }

        return launchSession(abortSignal) { effectiveAbortSignal, active ->
            val result = agent.generate(
                prompt = prompt,
                messages = messages,
                options = options,
                abortSignal = effectiveAbortSignal,
            ).first()
            if (active()) {
                // An abort that surfaces as a returned (partial) result rather than a
                // thrown CancellationException must still settle Cancelled — mirroring
                // submitStreaming's StreamEvent.Abort handling — not commit it as Ready.
                if (effectiveAbortSignal.isAborted) {
                    mutableState.update { it.copy(status = AgentSessionStatus.Cancelled) }
                } else {
                    mutableState.value = AgentSessionState(
                        messages = result.messages,
                        status = if (result.pendingApprovals.isEmpty()) {
                            AgentSessionStatus.Ready
                        } else {
                            AgentSessionStatus.AwaitingApproval
                        },
                        text = result.text,
                        output = if (result.pendingApprovals.isEmpty()) result.output else null,
                        pendingApprovals = result.pendingApprovals,
                        lastResult = result,
                    )
                }
            }
        }
    }

    /** @since 0.3.0-beta01 */
    public fun submitStreaming(
        prompt: String? = null,
        messages: List<ModelMessage> = state.value.messages,
        options: TContext? = null,
        abortSignal: AbortSignal = AbortSignalNever,
    ): Job {
        currentJobRef.load()?.cancel()
        rememberCall(options, abortSignal, streaming = true)
        val visibleMessages = visibleMessages(messages, prompt)
        mutableState.update {
            it.copy(
                messages = visibleMessages,
                status = AgentSessionStatus.Running,
                error = null,
                pendingApprovals = emptyList(),
                text = "",
                output = null,
                lastResult = null,
            )
        }

        return launchSession(abortSignal) { effectiveAbortSignal, active ->
            val text = StringBuilder()
            var textMetadata: ProviderMetadata = ProviderMetadata.None
            val reasoningBuffers = mutableMapOf<String, StringBuilder>()
            val reasoningMetadata = mutableMapOf<String, ProviderMetadata>()
            val extraAssistantParts = mutableListOf<ContentPart>()
            val toolCalls = mutableListOf<ContentPart.ToolCall>()
            val toolResults = mutableListOf<StreamingToolResultRecord>()
            val pendingApprovalRecords = mutableListOf<StreamingApprovalRecord>()

            fun recordFinalToolResult(result: ContentPart.ToolResult) {
                val provisionalIndex = toolResults.indexOfLast {
                    it.provisionalDenied &&
                        it.result.toolCallId == result.toolCallId &&
                        it.result.toolName == result.toolName
                }
                if (provisionalIndex == -1) {
                    toolResults += StreamingToolResultRecord(result)
                } else {
                    toolResults[provisionalIndex] = StreamingToolResultRecord(result)
                }
            }

            fun render(newStatus: AgentSessionStatus) {
                if (!active()) return
                mutableState.update {
                    it.copy(
                        status = newStatus,
                        text = text.toString(),
                        pendingApprovals = pendingApprovalRecords.map { record -> record.approval },
                        messages = streamingMessages(
                            messages = visibleMessages,
                            projection = StreamingMessageProjection(
                                text = StreamingTextProjection(
                                    value = text.toString(),
                                    metadata = textMetadata,
                                ),
                                extraAssistantParts = extraAssistantParts,
                                tools = StreamingToolProjection(
                                    calls = toolCalls.toList(),
                                    results = toolResults.map { record -> record.result },
                                    approvals = StreamingApprovalProjection(
                                        records = pendingApprovalRecords.toList(),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }

            agent.stream(
                prompt = prompt,
                messages = messages,
                options = options,
                abortSignal = effectiveAbortSignal,
            ).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        text.append(event.text)
                        textMetadata += event.providerMetadata
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.ReasoningStart -> {
                        reasoningBuffers[event.id] = StringBuilder()
                        reasoningMetadata[event.id] = event.providerMetadata
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.ReasoningDelta -> {
                        reasoningBuffers.getOrPut(event.id) { StringBuilder() }.append(event.text)
                        reasoningMetadata[event.id] =
                            (reasoningMetadata[event.id] ?: ProviderMetadata.None) + event.providerMetadata
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.ReasoningEnd -> {
                        val reasoningText = reasoningBuffers.remove(event.id)?.toString().orEmpty()
                        val metadata = (reasoningMetadata.remove(event.id) ?: ProviderMetadata.None) +
                            event.providerMetadata
                        if (reasoningText.isNotEmpty()) {
                            extraAssistantParts += ContentPart.Reasoning(
                                text = reasoningText,
                                providerMetadata = metadata,
                            )
                        }
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.SourcePart -> {
                        extraAssistantParts += ContentPart.Source(
                            sourceType = event.sourceType,
                            sourceId = event.id,
                            url = event.url,
                            title = event.title,
                            providerMetadata = event.providerMetadata,
                            mediaType = event.mediaType,
                        )
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.FilePart -> {
                        extraAssistantParts += ContentPart.File(
                            mediaType = event.mediaType,
                            base64 = event.base64,
                            providerMetadata = event.providerMetadata,
                        )
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.ToolCall -> {
                        toolCalls += ContentPart.ToolCall(
                            toolCallId = event.toolCallId,
                            toolName = event.toolName,
                            input = event.inputJson,
                            providerMetadata = event.providerMetadata,
                        )
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.ToolResult -> {
                        if (!event.preliminary) {
                            recordFinalToolResult(
                                ContentPart.ToolResult(
                                    toolCallId = event.toolCallId,
                                    toolName = event.toolName,
                                    output = event.outputJson,
                                    isError = event.isError,
                                    // Carry the tool's model-facing summary (toModelOutput)
                                    // so a resumed turn doesn't re-feed the full payload.
                                    modelVisible = with(ToolResultOutputs) { event.modelOutput.toJsonElement() },
                                    providerMetadata = event.providerMetadata,
                                ),
                            )
                            render(AgentSessionStatus.Running)
                        }
                    }
                    is StreamEvent.ToolError -> {
                        // Mirror the agent's own log shape: a failed tool is an
                        // error-flagged tool result carrying the message.
                        recordFinalToolResult(
                            ContentPart.ToolResult(
                                toolCallId = event.toolCallId,
                                toolName = event.toolName,
                                output = JsonPrimitive(event.message),
                                isError = true,
                                providerMetadata = event.providerMetadata,
                            ),
                        )
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.ToolOutputDenied -> {
                        val output = ToolResultOutput.ExecutionDenied(event.reason)
                        val outputJson = with(ToolResultOutputs) { output.toJsonElement() }
                        toolResults += StreamingToolResultRecord(
                            result = ContentPart.ToolResult(
                                toolCallId = event.toolCallId,
                                toolName = event.toolName,
                                output = outputJson,
                                isError = true,
                                modelVisible = outputJson,
                                providerMetadata = event.providerMetadata,
                            ),
                            provisionalDenied = true,
                        )
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.ToolApprovalRequest -> {
                        pendingApprovalRecords += StreamingApprovalRecord(
                            approval = PendingApproval(
                                toolCallId = event.toolCallId,
                                toolName = event.toolName,
                                input = event.inputJson,
                                approvalId = event.approvalId,
                                // Carry the HMAC signature (mirrors generate() at ToolLoopAgent.kt:430);
                                // without it a streamed approval can't be re-validated on resume.
                                signature = event.signature,
                            ),
                            metadata = event.providerMetadata,
                        )
                        render(AgentSessionStatus.AwaitingApproval)
                    }
                    is StreamEvent.Finish -> {
                        render(
                            if (pendingApprovalRecords.isEmpty()) {
                                AgentSessionStatus.Ready
                            } else {
                                AgentSessionStatus.AwaitingApproval
                            },
                        )
                    }
                    StreamEvent.Abort -> {
                        if (active()) {
                            mutableState.update { it.copy(status = AgentSessionStatus.Cancelled) }
                        }
                    }
                    is StreamEvent.Error -> {
                        if (active()) {
                            mutableState.update {
                                it.copy(
                                    status = AgentSessionStatus.Error,
                                    // Chain event.cause (matches the generate() path) so the host
                                    // keeps the root exception, not just the message string.
                                    error = UiMessageStreamError(event.message, event.cause),
                                )
                            }
                        }
                    }
                    is StreamEvent.StreamStart,
                    is StreamEvent.ResponseMetadata,
                    is StreamEvent.StepStart,
                    is StreamEvent.TextStart,
                    is StreamEvent.TextEnd,
                    is StreamEvent.Data,
                    is StreamEvent.ToolInputStart,
                    is StreamEvent.ToolInputDelta,
                    is StreamEvent.ToolInputEnd,
                    is StreamEvent.StepFinish,
                    is StreamEvent.Raw,
                    -> Unit
                }
            }
            // Settle if the stream ended without an explicit Finish/Abort/Error.
            if (active() && state.value.status == AgentSessionStatus.Running) {
                render(
                    if (pendingApprovalRecords.isEmpty()) {
                        AgentSessionStatus.Ready
                    } else {
                        AgentSessionStatus.AwaitingApproval
                    },
                )
            }
        }
    }

    /** @since 0.3.0-beta01 */
    public fun approve(
        approval: PendingApproval,
        options: TContext? = null,
        reason: String? = null,
    ): Job = resumeApproval(approval, approved = true, options = options, reason = reason)

    /** @since 0.3.0-beta01 */
    public fun deny(
        approval: PendingApproval,
        options: TContext? = null,
        reason: String? = null,
    ): Job = resumeApproval(approval, approved = false, options = options, reason = reason)

    /** @since 0.3.0-beta01 */
    public fun cancel() {
        currentJobRef.load()?.cancel()
        currentJobRef.store(null)
        mutableState.update { it.copy(status = AgentSessionStatus.Cancelled) }
    }

    /** @since 0.3.0-beta01 */
    public fun reset(messages: List<ModelMessage> = emptyList()) {
        currentJobRef.load()?.cancel()
        currentJobRef.store(null)
        mutableState.value = AgentSessionState(messages = messages)
    }

    private fun resumeApproval(
        approval: PendingApproval,
        approved: Boolean,
        options: TContext?,
        reason: String?,
    ): Job {
        val response = ToolApprovalResponseMessage(
            toolCallId = approval.toolCallId,
            approved = approved,
            reason = reason,
            approvalId = approval.approvalId,
        )
        // Resume with the SAME call config the paused turn ran under (upstream v6 re-passes settings on every
        // resume call): the remembered abort-signal + streaming mode, so a streamed turn keeps streaming across
        // the approval. An explicit [options] still overrides.
        val cs = lastCallRef.load()
        val resumeMessages = state.value.messages + response
        val resumeOptions = options ?: cs.options
        return if (cs.streaming) {
            submitStreaming(
                messages = resumeMessages,
                options = resumeOptions,
                abortSignal = cs.abortSignal,
            )
        } else {
            submit(messages = resumeMessages, options = resumeOptions, abortSignal = cs.abortSignal)
        }
    }

    private fun launchSession(
        abortSignal: AbortSignal,
        block: suspend (effectiveAbortSignal: AbortSignal, active: () -> Boolean) -> Unit,
    ): Job {
        val controller = AbortController()
        val externalRegistration = abortSignal.register { controller.abort() }
        var job: Job? = null
        // The `active` guard stops a job that has been superseded by a newer
        // submit() (which reassigned currentJobRef) from clobbering the new
        // job's state — including from its own cancellation/error handler.
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block(controller.signal) { job === currentJobRef.load() }
            } catch (error: CancellationException) {
                if (job === currentJobRef.load()) {
                    mutableState.update { it.copy(status = AgentSessionStatus.Cancelled) }
                }
                throw error
            } catch (error: Throwable) {
                if (job === currentJobRef.load()) {
                    mutableState.update { it.copy(status = AgentSessionStatus.Error, error = error) }
                }
            }
        }
        job = launched
        launched.invokeOnCompletion {
            externalRegistration.cancel()
            controller.abort()
        }
        // Claim ownership before starting the body so the `active` check inside
        // the coroutine always observes the assignment.
        currentJobRef.store(launched)
        launched.start()
        return launched
    }

    private fun visibleMessages(messages: List<ModelMessage>, prompt: String?): List<ModelMessage> =
        if (prompt == null) messages else messages + UserMessage(prompt)

    private fun streamingMessages(
        messages: List<ModelMessage>,
        projection: StreamingMessageProjection,
    ): List<ModelMessage> = buildList {
        addAll(messages)
        val assistantParts = buildList {
            if (projection.text.value.isNotEmpty()) {
                add(ContentPart.Text(projection.text.value, providerMetadata = projection.text.metadata))
            }
            addAll(projection.extraAssistantParts)
            addAll(projection.tools.calls)
            projection.tools.approvals.records.forEach { record ->
                val approval = record.approval
                add(
                    ContentPart.ToolApprovalRequest(
                        toolCallId = approval.toolCallId,
                        toolName = approval.toolName,
                        input = approval.input,
                        approvalId = approval.approvalId,
                        // Persist the signature into the replayed log so the resume's fail-closed
                        // verifySignature (ToolApprovalCoordinator) can re-validate it.
                        signature = approval.signature,
                        providerMetadata = record.metadata,
                    ),
                )
            }
        }
        if (assistantParts.isNotEmpty()) {
            add(ModelMessage(MessageRole.Assistant, assistantParts))
        }
        // Tool results ride on their own Tool-role message (v6 shape), so a
        // streamed tool round-trip is fully represented in the message log.
        if (projection.tools.results.isNotEmpty()) {
            add(ModelMessage(MessageRole.Tool, projection.tools.results))
        }
    }
}

/** @since 0.3.0-beta01 */
public object AgentSessions {
    public fun <TContext, TOutput> Agent<TContext, TOutput>.session(
        scope: CoroutineScope,
        initialMessages: List<ModelMessage> = emptyList(),
    ): AgentSession<TContext, TOutput> = AgentSession(
        scope = scope,
        agent = this,
        initialMessages = initialMessages,
    )
}
