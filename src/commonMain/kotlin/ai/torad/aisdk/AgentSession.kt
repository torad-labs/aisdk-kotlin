package ai.torad.aisdk

import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public enum class AgentSessionStatus {
    Ready,
    Running,
    AwaitingApproval,
    Cancelled,
    Error,
}

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

public class AgentSession<TContext, TOutput>(
    private val scope: CoroutineScope,
    private val agent: Agent<TContext, TOutput>,
    initialMessages: List<ModelMessage> = emptyList(),
) {
    private val mutableState = MutableStateFlow(AgentSessionState<TOutput>(messages = initialMessages))

    // @Volatile for cross-thread visibility: a launched job reads this to
    // decide whether it is still the active job before writing state.
    @Volatile
    private var currentJob: Job? = null

    // The last submit's call configuration, remembered so a resume (approve/deny) re-runs with the SAME
    // hooks / options / abort-signal / streaming-mode. This mirrors upstream Vercel AI SDK v6, where the host
    // re-passes its call settings on every resume generate()/stream(); without it a streamed, hook-observed
    // turn would go dark (no onStepFinish/onChunk, non-streaming) the moment it crossed a tool approval.
    @Volatile
    private var lastOptions: TContext? = null // var-ok: per-turn call-continuation state (mirrors currentJob)

    @Volatile
    private var lastAbortSignal: AbortSignal = AbortSignalNever // var-ok: per-turn call-continuation state

    @Volatile
    private var lastHooks: AgentCallHooks? = null // var-ok: per-turn call-continuation state

    @Volatile
    private var lastStreaming: Boolean = false // var-ok: per-turn call-continuation state (resume in same mode)

    public val state: StateFlow<AgentSessionState<TOutput>> = mutableState.asStateFlow()

    private fun rememberCall(options: TContext?, abortSignal: AbortSignal, hooks: AgentCallHooks?, streaming: Boolean) {
        lastOptions = options
        lastAbortSignal = abortSignal
        lastHooks = hooks
        lastStreaming = streaming
    }

    public fun submit(
        prompt: String? = null,
        messages: List<ModelMessage> = state.value.messages,
        options: TContext? = null,
        abortSignal: AbortSignal = AbortSignalNever,
        hooks: AgentCallHooks? = null,
    ): Job {
        currentJob?.cancel()
        rememberCall(options, abortSignal, hooks, streaming = false)
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
                hooks = hooks,
            )
            if (active()) {
                mutableState.value = AgentSessionState(
                    messages = result.messages,
                    status = if (result.pendingApprovals.isEmpty()) {
                        AgentSessionStatus.Ready
                    } else {
                        AgentSessionStatus.AwaitingApproval
                    },
                    text = result.text,
                    output = result.output,
                    pendingApprovals = result.pendingApprovals,
                    lastResult = result,
                )
            }
        }
    }

    public fun submitStreaming(
        prompt: String? = null,
        messages: List<ModelMessage> = state.value.messages,
        options: TContext? = null,
        abortSignal: AbortSignal = AbortSignalNever,
        hooks: AgentCallHooks? = null,
    ): Job {
        currentJob?.cancel()
        rememberCall(options, abortSignal, hooks, streaming = true)
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
            // Keyed by toolCallId so a streamed tool's final result replaces
            // its preliminary chunks and order is preserved. These were
            // previously dropped (else -> Unit), truncating the message log.
            val toolCalls = linkedMapOf<String, ContentPart.ToolCall>()
            val toolResults = linkedMapOf<String, ContentPart.ToolResult>()
            val pendingApprovals = mutableListOf<PendingApproval>()

            fun render(newStatus: AgentSessionStatus) {
                if (!active()) return
                mutableState.update {
                    it.copy(
                        status = newStatus,
                        text = text.toString(),
                        pendingApprovals = pendingApprovals.toList(),
                        messages = streamingMessages(
                            messages = visibleMessages,
                            text = text.toString(),
                            toolCalls = toolCalls.values.toList(),
                            toolResults = toolResults.values.toList(),
                            pendingApprovals = pendingApprovals,
                        ),
                    )
                }
            }

            agent.stream(
                prompt = prompt,
                messages = messages,
                options = options,
                abortSignal = effectiveAbortSignal,
                hooks = hooks,
            ).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        text.append(event.text)
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.ToolCall -> {
                        toolCalls[event.toolCallId] = ContentPart.ToolCall(
                            toolCallId = event.toolCallId,
                            toolName = event.toolName,
                            input = event.inputJson,
                            providerMetadata = event.providerMetadata,
                        )
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.ToolResult -> {
                        if (!event.preliminary) {
                            toolResults[event.toolCallId] = ContentPart.ToolResult(
                                toolCallId = event.toolCallId,
                                toolName = event.toolName,
                                output = event.outputJson,
                                isError = event.isError,
                                // Carry the tool's model-facing summary (toModelOutput)
                                // so a resumed turn doesn't re-feed the full payload.
                                modelVisible = event.modelOutput.toJsonElement(),
                                providerMetadata = event.providerMetadata,
                            )
                            render(AgentSessionStatus.Running)
                        }
                    }
                    is StreamEvent.ToolError -> {
                        // Mirror the agent's own log shape: a failed tool is an
                        // error-flagged tool result carrying the message.
                        toolResults[event.toolCallId] = ContentPart.ToolResult(
                            toolCallId = event.toolCallId,
                            toolName = event.toolName,
                            output = JsonPrimitive(event.message),
                            isError = true,
                            providerMetadata = event.providerMetadata,
                        )
                        render(AgentSessionStatus.Running)
                    }
                    is StreamEvent.ToolApprovalRequest -> {
                        pendingApprovals += PendingApproval(
                            toolCallId = event.toolCallId,
                            toolName = event.toolName,
                            input = event.inputJson,
                            approvalId = event.approvalId,
                        )
                        render(AgentSessionStatus.AwaitingApproval)
                    }
                    is StreamEvent.Finish -> {
                        render(
                            if (pendingApprovals.isEmpty()) {
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
                                    error = AiSdkRuntimeException(event.message),
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
            // Settle if the stream ended without an explicit Finish/Abort/Error.
            if (active() && state.value.status == AgentSessionStatus.Running) {
                render(
                    if (pendingApprovals.isEmpty()) {
                        AgentSessionStatus.Ready
                    } else {
                        AgentSessionStatus.AwaitingApproval
                    },
                )
            }
        }
    }

    public fun approve(
        approval: PendingApproval,
        options: TContext? = null,
        reason: String? = null,
    ): Job = resumeApproval(approval, approved = true, options = options, reason = reason)

    public fun deny(
        approval: PendingApproval,
        options: TContext? = null,
        reason: String? = null,
    ): Job = resumeApproval(approval, approved = false, options = options, reason = reason)

    public fun cancel() {
        currentJob?.cancel()
        currentJob = null
        mutableState.update { it.copy(status = AgentSessionStatus.Cancelled) }
    }

    public fun reset(messages: List<ModelMessage> = emptyList()) {
        currentJob?.cancel()
        currentJob = null
        mutableState.value = AgentSessionState(messages = messages)
    }

    private fun resumeApproval(
        approval: PendingApproval,
        approved: Boolean,
        options: TContext?,
        reason: String?,
    ): Job {
        val response = toolApprovalResponseMessage(
            toolCallId = approval.toolCallId,
            approved = approved,
            reason = reason,
            approvalId = approval.approvalId,
        )
        // Resume with the SAME call config the paused turn ran under (upstream v6 re-passes settings on every
        // resume call): the remembered hooks + abort-signal + streaming mode, so a streamed/observed turn keeps
        // streaming and firing its callbacks across the approval. An explicit [options] still overrides.
        val resumeMessages = state.value.messages + response
        val resumeOptions = options ?: lastOptions
        return if (lastStreaming) {
            submitStreaming(
                messages = resumeMessages,
                options = resumeOptions,
                abortSignal = lastAbortSignal,
                hooks = lastHooks,
            )
        } else {
            submit(messages = resumeMessages, options = resumeOptions, abortSignal = lastAbortSignal, hooks = lastHooks)
        }
    }

    private fun launchSession(
        abortSignal: AbortSignal,
        block: suspend (effectiveAbortSignal: AbortSignal, active: () -> Boolean) -> Unit,
    ): Job {
        val controller = AbortController()
        val externalRegistration = abortSignal.register { controller.abort() }
        lateinit var job: Job
        // The `active` guard stops a job that has been superseded by a newer
        // submit() (which reassigned currentJob) from clobbering the new
        // job's state — including from its own cancellation/error handler.
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block(controller.signal) { job === currentJob }
            } catch (error: CancellationException) {
                if (job === currentJob) {
                    mutableState.update { it.copy(status = AgentSessionStatus.Cancelled) }
                }
                throw error
            } catch (error: Throwable) {
                if (job === currentJob) {
                    mutableState.update { it.copy(status = AgentSessionStatus.Error, error = error) }
                }
            }
        }
        job.invokeOnCompletion {
            externalRegistration.cancel()
            controller.abort()
        }
        // Claim ownership before starting the body so the `active` check inside
        // the coroutine always observes the assignment.
        currentJob = job
        job.start()
        return job
    }

    private fun visibleMessages(messages: List<ModelMessage>, prompt: String?): List<ModelMessage> =
        if (prompt == null) messages else messages + userMessage(prompt)

    private fun streamingMessages(
        messages: List<ModelMessage>,
        text: String,
        toolCalls: List<ContentPart.ToolCall>,
        toolResults: List<ContentPart.ToolResult>,
        pendingApprovals: List<PendingApproval>,
    ): List<ModelMessage> = buildList {
        addAll(messages)
        val assistantParts = buildList {
            if (text.isNotEmpty()) add(ContentPart.Text(text))
            addAll(toolCalls)
            pendingApprovals.forEach { approval ->
                add(
                    ContentPart.ToolApprovalRequest(
                        toolCallId = approval.toolCallId,
                        toolName = approval.toolName,
                        input = approval.input,
                        approvalId = approval.approvalId,
                    )
                )
            }
        }
        if (assistantParts.isNotEmpty()) {
            add(ModelMessage(MessageRole.Assistant, assistantParts))
        }
        // Tool results ride on their own Tool-role message (v6 shape), so a
        // streamed tool round-trip is fully represented in the message log.
        if (toolResults.isNotEmpty()) {
            add(ModelMessage(MessageRole.Tool, toolResults))
        }
    }
}

public fun <TContext, TOutput> Agent<TContext, TOutput>.session(
    scope: CoroutineScope,
    initialMessages: List<ModelMessage> = emptyList(),
): AgentSession<TContext, TOutput> = AgentSession(
    scope = scope,
    agent = this,
    initialMessages = initialMessages,
)
