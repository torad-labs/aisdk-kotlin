package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class AgentSessionStatus {
    Ready,
    Running,
    AwaitingApproval,
    Cancelled,
    Error,
}

data class AgentSessionState<TOutput>(
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

class AgentSession<TContext, TOutput>(
    private val scope: CoroutineScope,
    private val agent: Agent<TContext, TOutput>,
    initialMessages: List<ModelMessage> = emptyList(),
) {
    private val mutableState = MutableStateFlow(AgentSessionState<TOutput>(messages = initialMessages))
    private var currentJob: Job? = null

    val state: StateFlow<AgentSessionState<TOutput>> = mutableState.asStateFlow()

    fun submit(
        prompt: String? = null,
        messages: List<ModelMessage> = state.value.messages,
        options: TContext? = null,
        abortSignal: AbortSignal = AbortSignalNever,
        hooks: AgentCallHooks? = null,
    ): Job {
        currentJob?.cancel()
        val visibleMessages = visibleMessages(messages, prompt)
        mutableState.value = state.value.copy(
            messages = visibleMessages,
            status = AgentSessionStatus.Running,
            error = null,
            pendingApprovals = emptyList(),
            text = "",
            output = null,
            lastResult = null,
        )

        return launchSession(abortSignal) { effectiveAbortSignal ->
            val result = agent.generate(
                prompt = prompt,
                messages = messages,
                options = options,
                abortSignal = effectiveAbortSignal,
                hooks = hooks,
            )
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

    fun submitStreaming(
        prompt: String? = null,
        messages: List<ModelMessage> = state.value.messages,
        options: TContext? = null,
        abortSignal: AbortSignal = AbortSignalNever,
        hooks: AgentCallHooks? = null,
    ): Job {
        currentJob?.cancel()
        val visibleMessages = visibleMessages(messages, prompt)
        mutableState.value = state.value.copy(
            messages = visibleMessages,
            status = AgentSessionStatus.Running,
            error = null,
            pendingApprovals = emptyList(),
            text = "",
            output = null,
            lastResult = null,
        )

        return launchSession(abortSignal) { effectiveAbortSignal ->
            val text = StringBuilder()
            val pendingApprovals = mutableListOf<PendingApproval>()
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
                        mutableState.value = state.value.copy(
                            text = text.toString(),
                            messages = streamingMessages(visibleMessages, text.toString(), pendingApprovals),
                        )
                    }
                    is StreamEvent.ToolApprovalRequest -> {
                        pendingApprovals += PendingApproval(
                            toolCallId = event.toolCallId,
                            toolName = event.toolName,
                            input = event.inputJson,
                            approvalId = event.approvalId,
                        )
                        mutableState.value = state.value.copy(
                            status = AgentSessionStatus.AwaitingApproval,
                            pendingApprovals = pendingApprovals.toList(),
                            messages = streamingMessages(visibleMessages, text.toString(), pendingApprovals),
                        )
                    }
                    is StreamEvent.Finish -> {
                        mutableState.value = state.value.copy(
                            status = if (pendingApprovals.isEmpty()) {
                                AgentSessionStatus.Ready
                            } else {
                                AgentSessionStatus.AwaitingApproval
                            },
                            pendingApprovals = pendingApprovals.toList(),
                            messages = streamingMessages(visibleMessages, text.toString(), pendingApprovals),
                        )
                    }
                    StreamEvent.Abort -> {
                        mutableState.value = state.value.copy(status = AgentSessionStatus.Cancelled)
                    }
                    is StreamEvent.Error -> {
                        mutableState.value = state.value.copy(
                            status = AgentSessionStatus.Error,
                            error = AiSdkException(event.message),
                        )
                    }
                    else -> Unit
                }
            }
            if (state.value.status == AgentSessionStatus.Running) {
                mutableState.value = state.value.copy(
                    status = if (pendingApprovals.isEmpty()) {
                        AgentSessionStatus.Ready
                    } else {
                        AgentSessionStatus.AwaitingApproval
                    },
                    pendingApprovals = pendingApprovals.toList(),
                    messages = streamingMessages(visibleMessages, text.toString(), pendingApprovals),
                )
            }
        }
    }

    fun approve(
        approval: PendingApproval,
        options: TContext? = null,
        reason: String? = null,
    ): Job = resumeApproval(approval, approved = true, options = options, reason = reason)

    fun deny(
        approval: PendingApproval,
        options: TContext? = null,
        reason: String? = null,
    ): Job = resumeApproval(approval, approved = false, options = options, reason = reason)

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        mutableState.value = state.value.copy(status = AgentSessionStatus.Cancelled)
    }

    fun reset(messages: List<ModelMessage> = emptyList()) {
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
        return submit(
            messages = state.value.messages + response,
            options = options,
        )
    }

    private fun launchSession(
        abortSignal: AbortSignal,
        block: suspend (AbortSignal) -> Unit,
    ): Job {
        val controller = AbortController()
        val externalRegistration = abortSignal.register { controller.abort() }
        val job = scope.launch {
            try {
                block(controller.signal)
            } catch (error: CancellationException) {
                mutableState.value = state.value.copy(status = AgentSessionStatus.Cancelled)
                throw error
            } catch (error: Throwable) {
                mutableState.value = state.value.copy(
                    status = AgentSessionStatus.Error,
                    error = error,
                )
            }
        }
        job.invokeOnCompletion {
            externalRegistration.cancel()
            controller.abort()
        }
        currentJob = job
        return job
    }

    private fun visibleMessages(messages: List<ModelMessage>, prompt: String?): List<ModelMessage> =
        if (prompt == null) messages else messages + userMessage(prompt)

    private fun streamingMessages(
        messages: List<ModelMessage>,
        text: String,
        pendingApprovals: List<PendingApproval>,
    ): List<ModelMessage> = buildList {
        addAll(messages)
        val assistantParts = buildList {
            if (text.isNotEmpty()) add(ContentPart.Text(text))
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
    }
}

fun <TContext, TOutput> Agent<TContext, TOutput>.session(
    scope: CoroutineScope,
    initialMessages: List<ModelMessage> = emptyList(),
): AgentSession<TContext, TOutput> = AgentSession(
    scope = scope,
    agent = this,
    initialMessages = initialMessages,
)
