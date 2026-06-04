package ai.torad.aisdk

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Default [Agent] implementation — the canonical v6 ToolLoopAgent.
 *
 * Runs the model, executes tool calls, accumulates the message list,
 * checks [stopWhen] after each step, surfaces lifecycle events. Manages
 * the loop end-to-end (invariant I-7) — no manual loop in user code.
 *
 * Per the v6 source comment in `packages/ai/src/agent/tool-loop-agent.ts`,
 * the loop continues until:
 *   - A finish reason other than `tool-calls` is returned, OR
 *   - A tool that is invoked does not have an execute function, OR
 *   - **A tool call needs approval**, OR
 *   - A stop condition is met (default `stepCountIs(20)`).
 *
 * ## Approval flow (v6 RPC semantics)
 *
 * When a tool's `needsApproval(input, context)` returns true:
 *   1. Emit [StreamEvent.ToolApprovalRequest] in the stream.
 *   2. Append [ContentPart.ToolApprovalRequest] to the assistant message.
 *   3. **End the loop** — return control to the host with
 *      `GenerateResult.pendingApprovals` populated.
 *   4. Host calls [generate] again with
 *      `messages = result.messages + toolApprovalResponseMessage(...)`.
 *   5. Loop resumes; approved tools execute; denied tools are skipped
 *      and the denial reason flows back to the model as a tool result.
 *
 * Generation isn't kept "in flight" while the user decides — host can
 * serialize, persist, transport, then resume.
 */
open class ToolLoopAgent<TContext, TOutput>(
    val model: LanguageModel,
    val instructions: String,
    override val tools: ToolSet<TContext>,
    val activeTools: List<String>? = null,
    val output: Output<TOutput>? = null,
    val stopWhen: StopCondition = stepCountIs(20),
    val prepareCall: (suspend PrepareCallScope<TContext>.() -> AgentSettings<TContext>)? = null,
    val prepareStep: (suspend PrepareStepScope<TContext>.() -> StepSettings<TContext>)? = null,
    val callOptionsSchema: KSerializer<TContext>? = null,
    // Sampler-param defaults set at agent construction. Mirror v6's
    // `CallSettings` (tool-loop-agent-settings.ts:145-194). Resolution
    // chain inside the loop is `StepSettings ?: AgentSettings ?: these
    // constructor defaults ?: null` (null = provider's own default).
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String>? = null,
    val seed: Int? = null,
    /** v6 `CallSettings.presencePenalty` agent-default. */
    val presencePenalty: Float? = null,
    /** v6 `CallSettings.frequencyPenalty` agent-default. */
    val frequencyPenalty: Float? = null,
    /** Wire-level response constraint for providers that support it. */
    val responseFormat: ResponseFormat = ResponseFormat.Text,
    val onStart: (suspend OnStartEvent.() -> Unit)? = null,
    val onStepStart: (suspend OnStepStartEvent.() -> Unit)? = null,
    val onStepFinish: (suspend OnStepFinishEvent.() -> Unit)? = null,
    val onFinish: (suspend OnFinishEvent.() -> Unit)? = null,
    val onError: (suspend OnErrorEvent.() -> Unit)? = null,
    val onChunk: (suspend OnChunkEvent.() -> Unit)? = null,
    val experimental_onToolCallStart: (suspend OnToolCallStartEvent.() -> Unit)? = null,
    val experimental_onToolCallFinish: (suspend OnToolCallFinishEvent.() -> Unit)? = null,
    /**
     * Self-healing callback fired when a tool call's arguments fail to
     * decode. Return a corrected call to retry, or null to surface
     * `StreamEvent.ToolError`. See [ToolCallRepairFunction].
     */
    val experimental_repairToolCall: ToolCallRepairFunction<TContext>? = null,
    /**
     * Coroutine context for the engine-surface scope (the long-lived
     * StateFlow-driven surface). Defaults to [Dispatchers.Default]; inject a
     * test dispatcher or a host-controlled one for deterministic scheduling.
     * The per-call generate()/stream() API is unaffected.
     */
    engineContext: CoroutineContext = Dispatchers.Default,
) : Agent<TContext, TOutput> {

    // ─── ENGINE-SHAPE STATE-HOLDER SURFACE ───────────────────────────────
    //
    // Layered ON TOP of the per-call generate/stream API. Same agent,
    // same tools, same hooks. This surface is what a long-lived host
    // (Repository, ViewModel-shaped runtime) talks to so it can treat
    // the agent like a stateful service rather than a per-request HTTP
    // client. State + action types live in ToolLoopAgentEngine.kt.
    //
    // Per-call API stays available for tests and stateless hosts; the
    // two surfaces don't share mutable state.

    private val mutableEngineState = MutableStateFlow(ToolLoopAgentState())

    /**
     * Port-level engine state machine (isStreaming, currentToolCalls,
     * pendingApprovals, totalSteps, etc.). Distinct from the app-level
     * [host-specific orchestration state] which
     * is a host-specific reactive lifecycle surface layered on top.
     * Renamed from `state` so subclasses can declare their own `state`
     * member without member-hiding clashes.
     */
    val engineState: StateFlow<ToolLoopAgentState> = mutableEngineState.asStateFlow()

    private val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + engineContext)
    private var currentEngineJob: Job? = null
    private var currentEngineContext: TContext? = null

    /**
     * Cancel the engine scope and any in-flight engine job. Call when a
     * long-lived host (ViewModel / Repository) that drove the engine surface
     * is disposed. The per-call generate()/stream() API needs no close().
     */
    fun close() {
        currentEngineJob?.cancel()
        currentEngineJob = null
        engineScope.cancel()
    }

    /**
     * Drive the engine. Each action either advances [engineState] or
     * aborts the in-flight stream. Idempotent — calling
     * [ToolLoopAgentAction.Cancel] when nothing is streaming is a no-op.
     *
     * Renamed from `onAction` to disambiguate from
     * [host-specific orchestration action handler]
     * which takes a host-specific host action type. The port-level engine
     * action dispatch lives here; subclasses layer their own onAction
     * with different action types on top.
     */
    fun dispatchEngineAction(action: ToolLoopAgentAction<TContext>) {
        when (action) {
            is ToolLoopAgentAction.UserSubmitPrompt<TContext> ->
                submitPrompt(action.text, action.context)
            is ToolLoopAgentAction.ApproveToolCall ->
                resumeWithApproval(action.toolCallId, approved = true, reason = null)
            is ToolLoopAgentAction.DenyToolCall ->
                resumeWithApproval(action.toolCallId, approved = false, reason = action.reason)
            ToolLoopAgentAction.Cancel -> {
                currentEngineJob?.cancel()
                mutableEngineState.update { it.copy(isStreaming = false) }
            }
            ToolLoopAgentAction.Reset -> {
                currentEngineJob?.cancel()
                currentEngineContext = null
                mutableEngineState.value = ToolLoopAgentState()
            }
        }
    }

    private fun submitPrompt(text: String, context: TContext?) {
        currentEngineJob?.cancel()
        currentEngineContext = context
        val priorMessages = mutableEngineState.value.messages
        mutableEngineState.update {
            it.copy(
                messages = priorMessages + userMessage(text),
                streamingAssistantText = "",
                currentToolCalls = emptyList(),
                pendingApprovals = emptyList(),
                isStreaming = true,
                error = null,
            )
        }
        currentEngineJob = engineScope.launch {
            runEngineLoop(prompt = text, priorMessages = priorMessages, context = context)
        }
    }

    private fun resumeWithApproval(toolCallId: String, approved: Boolean, reason: String?) {
        currentEngineJob?.cancel()
        val priorMessages = mutableEngineState.value.messages
        val approvalResponse = ModelMessage(
            role = MessageRole.Tool,
            content = listOf(ContentPart.ToolApprovalResponse(toolCallId, approved, reason)),
        )
        val updatedMessages = priorMessages + approvalResponse
        mutableEngineState.update {
            it.copy(
                messages = updatedMessages,
                streamingAssistantText = "",
                currentToolCalls = emptyList(),
                pendingApprovals = emptyList(),
                isStreaming = true,
                error = null,
            )
        }
        currentEngineJob = engineScope.launch {
            runEngineLoop(prompt = null, priorMessages = updatedMessages, context = currentEngineContext)
        }
    }

    private suspend fun runEngineLoop(
        prompt: String?,
        priorMessages: List<ModelMessage>,
        context: TContext?,
    ) {
        val engineHooks = AgentCallHooks(
            onChunk = { event ->
                val streamEvent = event.event
                if (streamEvent is StreamEvent.TextDelta) {
                    mutableEngineState.update { current ->
                        current.copy(streamingAssistantText = current.streamingAssistantText + streamEvent.text)
                    }
                }
            },
            onStepFinish = { _ ->
                mutableEngineState.update {
                    it.copy(
                        streamingAssistantText = "",
                        currentToolCalls = emptyList(),
                        totalSteps = it.totalSteps + 1,
                    )
                }
            },
            onFinish = { event ->
                mutableEngineState.update {
                    it.copy(
                        messages = event.messages,
                        streamingAssistantText = "",
                        currentToolCalls = emptyList(),
                        pendingApprovals = event.pendingApprovals,
                        isStreaming = false,
                    )
                }
            },
        )
        try {
            stream(
                prompt = prompt,
                messages = priorMessages,
                options = context,
                hooks = engineHooks,
            ).collect { event ->
                when (event) {
                    is StreamEvent.ToolCall -> mutableEngineState.update { current ->
                        current.copy(
                            currentToolCalls = current.currentToolCalls +
                                ContentPart.ToolCall(event.toolCallId, event.toolName, event.inputJson),
                        )
                    }
                    is StreamEvent.Error -> mutableEngineState.update {
                        it.copy(isStreaming = false, error = event.message)
                    }
                    else -> Unit
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            mutableEngineState.update { it.copy(isStreaming = false, error = t.message ?: "agent failed") }
        }
    }
    // ──────────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    override suspend fun generate(
        prompt: String?,
        messages: List<ModelMessage>,
        options: TContext?,
        abortSignal: AbortSignal,
        hooks: AgentCallHooks?,
    ): GenerateResult<TOutput> {
        val accumulator = StringBuilder()
        val collectedSteps = mutableListOf<StepResult>()
        val collectedApprovals = mutableListOf<PendingApproval>()
        var collectedMessages: List<ModelMessage> = emptyList()
        var finishReason = FinishReason.Other
        var totalUsage = Usage()
        val captureCollector = StreamCapture { event ->
            when (event) {
                is StreamEvent.TextDelta -> accumulator.append(event.text)
                is StreamEvent.StepFinish -> {
                    finishReason = event.finishReason
                    totalUsage += event.usage
                }
                is StreamEvent.Finish -> {
                    finishReason = event.finishReason
                    totalUsage = event.usage
                }
                is StreamEvent.ToolApprovalRequest -> {
                    collectedApprovals.add(
                        PendingApproval(
                            toolCallId = event.toolCallId,
                            toolName = event.toolName,
                            input = event.inputJson,
                            approvalId = event.approvalId,
                        ),
                    )
                }
                is StreamEvent.Error -> throw AiSdkException(event.message, event.cause)
                else -> Unit
            }
        }
        val finalMessagesRef = MessageHolder()
        val stepsRef = StepsHolder(collectedSteps)
        streamInternal(prompt, messages, options, abortSignal, hooks, finalMessagesRef, stepsRef)
            .collect { event ->
                captureCollector.consume(event)
            }
        collectedMessages = finalMessagesRef.value
        val text = accumulator.toString()
        val typed = output?.decode(text) ?: (text as TOutput)
        return GenerateResult(
            output = typed,
            text = text,
            steps = collectedSteps.toList(),
            finishReason = finishReason,
            usage = totalUsage,
            pendingApprovals = collectedApprovals.toList(),
            messages = collectedMessages,
        )
    }

    override fun stream(
        prompt: String?,
        messages: List<ModelMessage>,
        options: TContext?,
        abortSignal: AbortSignal,
        hooks: AgentCallHooks?,
    ): Flow<StreamEvent> = streamInternal(prompt, messages, options, abortSignal, hooks, null, null)

    private fun streamInternal(
        prompt: String?,
        priorMessages: List<ModelMessage>,
        options: TContext?,
        abortSignal: AbortSignal,
        hooks: AgentCallHooks?,
        finalMessagesRef: MessageHolder?,
        stepsCapture: StepsHolder?,
    ): Flow<StreamEvent> = flow {
        require(prompt != null || priorMessages.isNotEmpty()) {
            "Agent.generate/stream: must provide either `prompt` or `messages`"
        }
        val validatedOptions = validateCallOptions(options)
        emit(StreamEvent.StreamStart())
        runHook(0) {
            onStart?.invoke(OnStartEvent(prompt, priorMessages, validatedOptions))
            hooks?.onStart?.invoke(OnStartEvent(prompt, priorMessages, validatedOptions))
        }

        // prepareCall — once per invocation.
        val resolvedSettings: AgentSettings<TContext> = prepareCall?.let { hook ->
            try {
                PrepareCallScope(validatedOptions, instructions, model, tools).hook()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emitError(t, 0, OnErrorEvent.ErrorSource.PrepareCall, hooks)
                emit(StreamEvent.Error(t.message ?: "prepareCall failed", cause = t))
                finalMessagesRef?.value = priorMessages
                return@flow
            }
        } ?: AgentSettings()

        val resolvedInstructions = resolvedSettings.instructions ?: instructions
        val resolvedModel = resolvedSettings.model ?: model
        val resolvedTools = resolvedSettings.tools ?: tools

        val isResumption = priorMessages.isNotEmpty() && hasSystemMessage(priorMessages)
        val messages = if (isResumption) {
            priorMessages.toMutableList().apply {
                if (prompt != null) add(userMessage(prompt))
            }
        } else {
            mutableListOf<ModelMessage>().apply {
                add(systemMessage(resolvedInstructions))
                addAll(priorMessages)
                if (prompt != null) add(userMessage(prompt))
            }
        }

        // Apply any pending tool-approval responses BEFORE the next model call.
        val resumeOutcome = applyToolApprovalResponses(this, messages, resolvedTools, options, abortSignal, hooks)
        if (resumeOutcome != null) {
            // We executed approved tools (and/or recorded denials). Continue the loop normally.
        }

        val completedSteps = mutableListOf<StepResult>()
        val toolCallsAllSteps = mutableListOf<ContentPart.ToolCall>()
        // Tool-result summarization is now carried inline on each
        // ContentPart.ToolResult via its `modelVisible` field. The LLM
        // provider reads `modelVisible` directly when formatting the
        // prompt, and persistence reads `output` for rich rehydration.
        // No per-stream substitution map is needed because the message
        // list itself is the source of truth across both single-stream
        // step iterations AND multi-stream resumption.
        var totalUsage = Usage()
        var stepNumber = 0
        var lastFinishReason = FinishReason.Other
        var pendingApprovalEmitted = false
        // gap #16: running typed context. A prepareStep may override it via
        // StepSettings.experimental_context to evolve context mid-loop (e.g.
        // RAG augmentation after a tool result); this step's tool execution
        // and every subsequent step then see the new value.
        var activeContext: TContext? = validatedOptions

        loopFinished@ while (true) {
            stepNumber += 1
            abortSignal.throwIfAborted()
            emit(StreamEvent.StepStart(stepNumber))
            runHook(stepNumber) {
                onStepStart?.invoke(OnStepStartEvent(stepNumber, messages.toList()))
                hooks?.onStepStart?.invoke(OnStepStartEvent(stepNumber, messages.toList()))
            }

            val stepSettings: StepSettings<TContext> = prepareStep?.let { hook ->
                try {
                    PrepareStepScope(stepNumber, resolvedModel, completedSteps.toList(), messages.toList(), activeContext).hook()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    emitError(t, stepNumber, OnErrorEvent.ErrorSource.PrepareStep, hooks)
                    emit(StreamEvent.Error(t.message ?: "prepareStep failed", cause = t))
                    finalMessagesRef?.value = messages.toList()
                    return@flow
                }
            } ?: StepSettings<TContext>()
            // gap #16: a returned experimental_context overrides the running
            // context — for this step's tool execution and every later step.
            stepSettings.experimental_context?.let { activeContext = it }

            val stepModel = stepSettings.model ?: resolvedModel
            val stepMessages = stepSettings.messages ?: messages.toList()
            val stepActiveTools = stepSettings.activeTools ?: resolvedSettings.activeTools ?: activeTools
            val stepTools = stepActiveTools?.let { active ->
                val activeNames = active.toSet()
                ToolSet<TContext>(resolvedTools.byName.filterKeys { it in activeNames })
            } ?: resolvedTools
            val stepToolChoice = stepSettings.toolChoice ?: ToolChoice.Auto
            val stepProviderOptions =
                (resolvedSettings.providerOptions ?: emptyMap()) + (stepSettings.providerOptions ?: emptyMap())
            val stepSystem = stepSettings.system

            val effectiveMessages = if (stepSystem != null) {
                listOf(systemMessage(stepSystem)) + stepMessages.dropWhile { it.role == MessageRole.System }
            } else stepMessages

            val callParams = LanguageModelCallParams(
                messages = effectiveMessages,
                tools = stepTools.descriptors,
                toolChoice = stepToolChoice,
                temperature = stepSettings.temperature
                    ?: resolvedSettings.temperature ?: temperature,
                topP = stepSettings.topP ?: resolvedSettings.topP ?: topP,
                topK = stepSettings.topK ?: resolvedSettings.topK ?: topK,
                maxOutputTokens = stepSettings.maxOutputTokens
                    ?: resolvedSettings.maxOutputTokens ?: maxOutputTokens,
                stopSequences = resolveStopSequences(stepSettings, resolvedSettings),
                seed = stepSettings.seed ?: resolvedSettings.seed ?: seed,
                providerOptions = stepProviderOptions,
                abortSignal = abortSignal,
                presencePenalty = stepSettings.presencePenalty
                    ?: resolvedSettings.presencePenalty ?: presencePenalty,
                frequencyPenalty = stepSettings.frequencyPenalty
                    ?: resolvedSettings.frequencyPenalty ?: frequencyPenalty,
                responseFormat = stepSettings.responseFormat
                    ?: resolvedSettings.responseFormat
                    ?: if (responseFormat == ResponseFormat.Text && output != null) {
                        output.toResponseFormat()
                    } else responseFormat,
            )

            val stepText = StringBuilder()
            val stepReasoning = StringBuilder()
            val stepToolCalls = mutableListOf<ContentPart.ToolCall>()
            val stepToolResults = mutableListOf<ContentPart.ToolResult>()
            val stepApprovalRequests = mutableListOf<ContentPart.ToolApprovalRequest>()
            var stepFinishReason = FinishReason.Stop
            var stepUsage = Usage()
            // gap #18: a streaming tool-input id -> toolName, so ToolInputDelta
            // (which carries only the streaming id) can route to the right
            // tool's onInputDelta hook.
            val toolInputNames = mutableMapOf<String, String>()

            try {
                stepModel.stream(callParams).collect { event ->
                    abortSignal.throwIfAborted()
                    runHook(stepNumber) {
                        onChunk?.invoke(OnChunkEvent(event, stepNumber))
                        hooks?.onChunk?.invoke(OnChunkEvent(event, stepNumber))
                    }
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            stepText.append(event.text)
                            emit(event)
                        }
                        is StreamEvent.ReasoningDelta -> {
                            stepReasoning.append(event.text)
                            emit(event)
                        }
                        is StreamEvent.ToolInputStart -> {
                            // gap #18: model committed to a tool — fire the
                            // pre-warm hook (UI spinner, cache priming). Guarded
                            // so a hook failure can't abort the inference stream.
                            toolInputNames[event.id] = event.toolName
                            val onStart = stepTools.find(event.toolName)?.onInputStart
                            runHook(stepNumber) { onStart?.invoke(event.id) }
                            emit(event)
                        }
                        is StreamEvent.ToolInputDelta -> {
                            // gap #18: raw-character pre-warm as input streams in.
                            val onDelta = toolInputNames[event.id]
                                ?.let { stepTools.find(it)?.onInputDelta }
                            runHook(stepNumber) { onDelta?.invoke(event.id, event.delta) }
                            emit(event)
                        }
                        is StreamEvent.ToolCall -> {
                            val call = ContentPart.ToolCall(event.toolCallId, event.toolName, event.inputJson)
                            stepToolCalls.add(call)
                            toolCallsAllSteps.add(call)
                            emit(event)
                        }
                        is StreamEvent.StepFinish -> {
                            stepFinishReason = event.finishReason
                            stepUsage = event.usage
                            // Don't forward — we emit our own StepFinish below post-tool-execution.
                        }
                        is StreamEvent.Finish -> {
                            stepFinishReason = event.finishReason
                            stepUsage = event.usage
                        }
                        is StreamEvent.Error -> {
                            emit(event)
                            throw TerminalModelStreamError()
                        }
                        else -> emit(event)
                    }
                }
            } catch (_: TerminalModelStreamError) {
                finalMessagesRef?.value = messages.toList()
                return@flow
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emitError(t, stepNumber, OnErrorEvent.ErrorSource.Model, hooks)
                emit(StreamEvent.Error(t.message ?: "model failed", cause = t))
                finalMessagesRef?.value = messages.toList()
                return@flow
            }

            // Build assistant content: text + reasoning + tool calls.
            val assistantParts: MutableList<ContentPart> = mutableListOf()
            if (stepText.isNotEmpty()) assistantParts.add(ContentPart.Text(stepText.toString()))
            if (stepReasoning.isNotEmpty()) assistantParts.add(ContentPart.Reasoning(stepReasoning.toString()))
            assistantParts.addAll(stepToolCalls)

            // Process tool calls — for each, check if approval needed first.
            val toolsRequiringApproval = mutableListOf<ContentPart.ToolCall>()
            val toolsToExecute = mutableListOf<ContentPart.ToolCall>()
            for (call in stepToolCalls) {
                val toolDef = stepTools.find(call.toolName)
                if (toolDef == null) {
                    val err = AgentError.NoSuchTool(call.toolName, stepTools.names())
                    emitToolError(this, call.toolCallId, call.toolName, err, messages)
                    continue
                }
                if (toolDef.providerExecuted) {
                    continue
                }
                val needsApproval = try {
                    callNeedsApproval(toolDef, call, activeContext, messages.toList())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    emitToolError(
                        this,
                        call.toolCallId,
                        call.toolName,
                        t as? AgentError ?: AgentError.ToolExecution(call.toolName, call.toolCallId, t),
                        messages,
                    )
                    continue
                }
                if (needsApproval) {
                    toolsRequiringApproval.add(call)
                } else {
                    toolsToExecute.add(call)
                }
            }
            val hasLocalToolWork = toolsRequiringApproval.isNotEmpty() || toolsToExecute.isNotEmpty()

            // Emit approval requests + add to assistant content. Loop ends after this step.
            for (call in toolsRequiringApproval) {
                emit(StreamEvent.ToolApprovalRequest(call.toolCallId, call.toolName, call.input))
                val request = ContentPart.ToolApprovalRequest(call.toolCallId, call.toolName, call.input)
                assistantParts.add(request)
                stepApprovalRequests.add(request)
                pendingApprovalEmitted = true
            }

            if (assistantParts.isNotEmpty()) {
                messages.add(ModelMessage(MessageRole.Assistant, assistantParts.toList()))
            }

            // Execute non-approval-gated tools.
            for (call in toolsToExecute) {
                val toolDef = stepTools.find(call.toolName)
                    ?: continue // already handled above

                runHook(stepNumber) {
                    val event = OnToolCallStartEvent(call.toolCallId, call.toolName, call.input, stepNumber, messages.toList())
                    experimental_onToolCallStart?.invoke(event)
                }

                val result = executeTool(
                    out = this,
                    toolDef = toolDef,
                    call = call,
                    options = activeContext,
                    abortSignal = abortSignal,
                    stepNumber = stepNumber,
                    messages = messages.toList(),
                    hooks = hooks,
                )

                runHook(stepNumber) {
                    val event = OnToolCallFinishEvent(
                        toolCallId = call.toolCallId,
                        toolName = call.toolName,
                        outputJson = (result as? ToolExecutionResult.Success)?.outputJson,
                        errorMessage = (result as? ToolExecutionResult.Failure)?.error?.message,
                        stepNumber = stepNumber,
                    )
                    experimental_onToolCallFinish?.invoke(event)
                }

                applyToolResult(this, call, result, messages, stepToolResults)
            }

            val effectiveFinishReason = if (toolsRequiringApproval.isNotEmpty()) {
                FinishReason.ToolApprovalRequested
            } else stepFinishReason

            val step = StepResult(
                stepNumber = stepNumber,
                text = stepText.toString(),
                reasoning = stepReasoning.toString(),
                toolCalls = stepToolCalls.toList(),
                toolResults = stepToolResults.toList(),
                toolApprovalRequests = stepApprovalRequests.toList(),
                finishReason = effectiveFinishReason,
                usage = stepUsage,
            )
            completedSteps.add(step)
            stepsCapture?.steps?.add(step)
            totalUsage += stepUsage
            lastFinishReason = effectiveFinishReason

            runHook(stepNumber) {
                onStepFinish?.invoke(OnStepFinishEvent(stepNumber, step))
                hooks?.onStepFinish?.invoke(OnStepFinishEvent(stepNumber, step))
            }
            emit(StreamEvent.StepFinish(stepNumber, effectiveFinishReason, stepUsage))

            // If approval was emitted this step, the loop ends — host resumes.
            if (toolsRequiringApproval.isNotEmpty()) break@loopFinished

            val loopState = LoopState(
                stepNumber = stepNumber,
                totalSteps = stepNumber,
                lastFinishReason = effectiveFinishReason,
                toolCallsThisStep = stepToolCalls.toList(),
                toolCallsAllSteps = toolCallsAllSteps.toList(),
                steps = completedSteps.toList(),
            )
            if (stopWhen.shouldStop(loopState)) break@loopFinished

            // Natural termination: any no-tool finish is terminal. `ToolCalls`
            // only continues when actual tool calls were parsed and processed.
            if (!hasLocalToolWork && effectiveFinishReason != FinishReason.ToolApprovalRequested) {
                break@loopFinished
            }
        }

        emit(StreamEvent.Finish(stepNumber, lastFinishReason, totalUsage))
        finalMessagesRef?.value = messages.toList()

        val pendingApprovals = if (pendingApprovalEmitted) {
            messages.lastOrNull { it.role == MessageRole.Assistant }
                ?.content
                ?.filterIsInstance<ContentPart.ToolApprovalRequest>()
                ?.map {
                    PendingApproval(
                        toolCallId = it.toolCallId,
                        toolName = it.toolName,
                        input = it.input,
                        approvalId = it.approvalId,
                    )
                }
                ?: emptyList()
        } else emptyList()

        runHook(stepNumber) {
            val finishEvent = OnFinishEvent(
                null,
                stepNumber,
                totalUsage,
                pendingApprovals,
                messages.toList(),
                // gap #36: surface the post-prepareStep-override context the
                // loop tracked (activeContext), not a hardcoded null.
                experimental_context = activeContext,
            )
            onFinish?.invoke(finishEvent)
            hooks?.onFinish?.invoke(finishEvent)
        }
    }

    /**
     * If the most recent tool message contains [ContentPart.ToolApprovalResponse]
     * parts, execute (or deny) the matching tool calls from the prior
     * assistant message before the next model call.
     */
    private suspend fun applyToolApprovalResponses(
        out: FlowCollector<StreamEvent>,
        messages: MutableList<ModelMessage>,
        tools: ToolSet<TContext>,
        options: TContext?,
        abortSignal: AbortSignal,
        hooks: AgentCallHooks?,
    ): Unit? {
        val lastToolMsg = messages.lastOrNull { it.role == MessageRole.Tool } ?: return null
        val approvals = lastToolMsg.content.filterIsInstance<ContentPart.ToolApprovalResponse>()
        if (approvals.isEmpty()) return null

        val priorAssistantMsg = messages.findLast { it.role == MessageRole.Assistant } ?: return null
        val priorToolCalls = priorAssistantMsg.content.filterIsInstance<ContentPart.ToolCall>()
        if (priorToolCalls.isEmpty()) return null

        for (approval in approvals) {
            val matchingCall = priorToolCalls.firstOrNull { it.toolCallId == approval.toolCallId }
                ?: continue
            if (!approval.approved) {
                val denialMsg = approval.reason ?: "user denied tool execution"
                applyDeniedToolApproval(
                    out = out,
                    call = matchingCall,
                    approvalId = approval.approvalId ?: matchingCall.toolCallId,
                    reason = denialMsg,
                    messages = messages,
                )
                continue
            }
            val toolDef = tools.find(matchingCall.toolName) ?: continue
            val approvedResult = executeTool(
                out = out,
                toolDef = toolDef,
                call = matchingCall,
                options = options,
                abortSignal = abortSignal,
                stepNumber = 0,
                messages = messages.toList(),
                hooks = hooks,
            )
            when (approvedResult) {
                is ToolExecutionResult.Success -> applyApprovedToolSuccess(
                    call = matchingCall,
                    result = approvedResult,
                    messages = messages,
                    out = out,
                )
                is ToolExecutionResult.Failure ->
                    emitToolError(out, matchingCall.toolCallId, matchingCall.toolName, approvedResult.error, messages)
            }
        }
        return Unit
    }

    private suspend fun callNeedsApproval(
        toolDef: Tool<*, *, TContext>,
        call: ContentPart.ToolCall,
        options: TContext?,
        messages: List<ModelMessage>,
    ): Boolean {
        val gate = toolDef.needsApproval ?: return false

        @Suppress("UNCHECKED_CAST")
        val approver = gate as suspend (input: Any?, opts: ToolPredicateOptions<TContext>) -> Boolean
        val typedInput = try {
            decodeToolInput(toolDef, call.input)
        } catch (ce: CancellationException) {
            throw ce
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            throw AgentError.InvalidToolInput(call.toolName, call.input.toString(), t)
        }
        val predicateOptions = ToolPredicateOptions(
            toolCallId = call.toolCallId,
            messages = messages,
            experimental_context = options,
        )
        return try {
            approver(typedInput, predicateOptions)
        } catch (ce: CancellationException) {
            throw ce
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            throw AgentError.ToolExecution(call.toolName, call.toolCallId, t)
        }
    }

    /**
     * Execute one tool call. Tool executors return `Flow<TOutput>`
     * (per v6 `tool.ts:68-71`) so a tool can emit preliminary
     * progress snapshots before its final value. This function
     * implements the v6 semantics: **last emission = final**.
     *
     * Implementation is one-step lookahead — hold the most recent
     * emission, and when a new one arrives, emit the previous as
     * `StreamEvent.ToolResult(preliminary = true)`. Once the Flow
     * completes, the last buffered value becomes the
     * `ToolExecutionResult.Success` that the caller passes to
     * [applyToolResult] for the final stream event + model message
     * append. An empty Flow is treated as failure ("tool emitted no
     * values").
     *
     * Preliminary emissions never touch `messages` or the step's
     * `stepToolResults` list — they're UI-only progress signals.
     */
    @Suppress("LongParameterList")
    private suspend fun executeTool(
        out: FlowCollector<StreamEvent>,
        toolDef: Tool<*, *, TContext>,
        call: ContentPart.ToolCall,
        options: TContext?,
        abortSignal: AbortSignal,
        stepNumber: Int,
        messages: List<ModelMessage>,
        hooks: AgentCallHooks?,
    ): ToolExecutionResult {
        return try {
            // Resolve (toolDef, decoded input). On first attempt: decode
            // the call's args against `toolDef`. On decode failure with
            // `experimental_repairToolCall` set: invoke the repair fn,
            // re-resolve the tool (a repaired call may re-route to a
            // different toolName), and retry decode ONCE. No recursive
            // repair — keeps the loop bounded.
            val (resolvedTool, resolvedInput) = resolveToolInput(toolDef, call, messages)

            @Suppress("UNCHECKED_CAST")
            val typedTool = resolvedTool as Tool<Any?, Any?, TContext>
            val input = resolvedInput
            val ctx = ToolExecutionContext(
                context = options,
                abortSignal = abortSignal,
                stepNumber = stepNumber,
                messages = messages,
                toolCallId = call.toolCallId,
                writer = FlowToolStreamWriter(out), // gap #21: write-back into the stream
            )
            // gap #18: typed input parsed — fire onInputAvailable just before
            // the executor runs (UI pre-warm). Guarded so a hook failure does
            // not fail the tool call itself.
            runHook(stepNumber) { typedTool.onInputAvailable?.invoke(call.toolCallId, input) }
            val captured = collectFinalToolOutput(out, typedTool, ctx, call, input)
            if (!captured.hasOutput) {
                return toolFailure(call, IllegalStateException("tool emitted no values"))
            }
            val lastOutput = captured.value
            val outputJson = encodeToolOutput(typedTool, lastOutput)
            val predicateOptions = ToolPredicateOptions(
                toolCallId = call.toolCallId,
                messages = messages,
                experimental_context = options,
            )
            val output = toolResultOutputFromJson(outputJson)
            val modelOutput = typedTool.toModelOutput?.invoke(lastOutput, predicateOptions) ?: output
            ToolExecutionResult.Success(
                outputJson = outputJson,
                output = output,
                modelOutput = modelOutput,
                modelVisible = modelOutput.toJsonElement(),
            )
        } catch (ce: CancellationException) {
            // Cancellation MUST propagate, never get persisted as a tool result
            // — otherwise the agent loop continues with a turn that says "the
            // tool returned cancellation" and the conversation goes off-rails.
            throw ce
        } catch (t: Throwable) {
            emitError(t, stepNumber, OnErrorEvent.ErrorSource.Tool, hooks)
            // resolveToolInput throws typed AgentError; a raw executor
            // throw becomes ToolExecution (see toolFailure).
            toolFailure(call, t)
        }
    }

    /**
     * Drain [tool]'s executor Flow with one-step lookahead: each
     * non-final emission is surfaced as a preliminary
     * [StreamEvent.ToolResult], and the value held when the Flow
     * completes is the final output. Extracted from [executeTool] so
     * that body stays under the LongMethod limit; the capture holder
     * preserves the empty-flow path (caller maps `!hasOutput` to a tool
     * failure without an extra `onError`).
     */
    private suspend fun collectFinalToolOutput(
        out: FlowCollector<StreamEvent>,
        tool: Tool<Any?, Any?, TContext>,
        ctx: ToolExecutionContext<TContext>,
        call: ContentPart.ToolCall,
        input: Any?,
    ): ToolOutputCapture {
        var lastOutput: Any? = null
        var hasOutput = false
        tool.executor.invoke(ctx, input).collect { output ->
            if (hasOutput) {
                val outputJson = encodeToolOutput(tool, lastOutput)
                val output = toolResultOutputFromJson(outputJson)
                val predicateOptions = ToolPredicateOptions(
                    toolCallId = call.toolCallId,
                    messages = ctx.messages,
                    experimental_context = ctx.context,
                )
                val modelOutput = tool.toModelOutput?.invoke(lastOutput, predicateOptions) ?: output
                out.emit(
                    StreamEvent.ToolResult(
                        toolCallId = call.toolCallId,
                        toolName = call.toolName,
                        outputJson = outputJson,
                        output = output,
                        modelOutput = modelOutput,
                        isError = modelOutput.isToolResultError(),
                        preliminary = true,
                    ),
                )
            }
            lastOutput = output
            hasOutput = true
        }
        return ToolOutputCapture(hasOutput, lastOutput)
    }

    /**
     * Apply a tool-execution [result] to the agent's bookkeeping: emit
     * the appropriate stream event, append to [stepToolResults] / the
     * conversation [messages], and (on Success) carry the tool's
     * `modelVisible` summary alongside the full `output` so subsequent
     * turns send the short version to the LLM while persistence and the
     * UI converter still see the rich payload. Extracted out of the
     * main step body so the loop reads as orchestration only — the
     * Success/Failure branching is a named operation, not inline logic
     * inside a 200-line `while`.
     */
    private suspend fun applyToolResult(
        out: FlowCollector<StreamEvent>,
        call: ContentPart.ToolCall,
        result: ToolExecutionResult,
        messages: MutableList<ModelMessage>,
        stepToolResults: MutableList<ContentPart.ToolResult>,
    ) {
        when (result) {
            is ToolExecutionResult.Success -> {
                out.emit(
                    StreamEvent.ToolResult(
                        toolCallId = call.toolCallId,
                        toolName = call.toolName,
                        outputJson = result.outputJson,
                        output = result.output,
                        modelOutput = result.modelOutput,
                        isError = result.isError,
                    ),
                )
                val toolPart = ContentPart.ToolResult(
                    toolCallId = call.toolCallId,
                    toolName = call.toolName,
                    output = result.outputJson,
                    isError = result.isError,
                    modelVisible = result.modelVisible,
                )
                stepToolResults.add(toolPart)
                messages.add(ModelMessage(MessageRole.Tool, listOf(toolPart)))
            }
            is ToolExecutionResult.Failure ->
                emitToolError(out, call.toolCallId, call.toolName, result.error, messages)
        }
    }

    /**
     * Approval-resume mirror of [applyToolResult] for the success branch.
     * Same modelVisible handling so tools that combine an approval gate
     * with `toModelOutput` summarization still avoid the KV-cache blow-up
     * on the resumed turn.
     */
    private suspend fun applyApprovedToolSuccess(
        call: ContentPart.ToolCall,
        result: ToolExecutionResult.Success,
        messages: MutableList<ModelMessage>,
        out: FlowCollector<StreamEvent>,
    ) {
        out.emit(
            StreamEvent.ToolResult(
                toolCallId = call.toolCallId,
                toolName = call.toolName,
                outputJson = result.outputJson,
                output = result.output,
                modelOutput = result.modelOutput,
                isError = result.isError,
            ),
        )
        val toolPart = ContentPart.ToolResult(
            toolCallId = call.toolCallId,
            toolName = call.toolName,
            output = result.outputJson,
            isError = result.isError,
            modelVisible = result.modelVisible,
        )
        messages.add(ModelMessage(MessageRole.Tool, listOf(toolPart)))
    }

    /**
     * Approval denials are an expected host decision, not a tool failure.
     * They still need a tool-role result in the durable message log so a
     * resumed conversation can explain that the tool was intentionally
     * skipped without retriggering the approval path.
     */
    private suspend fun applyDeniedToolApproval(
        out: FlowCollector<StreamEvent>,
        call: ContentPart.ToolCall,
        approvalId: String,
        reason: String,
        messages: MutableList<ModelMessage>,
    ) {
        val output = ToolResultOutput.ExecutionDenied(reason)
        val outputJson = output.toJsonElement()
        out.emit(
            StreamEvent.ToolOutputDenied(
                toolCallId = call.toolCallId,
                toolName = call.toolName,
                approvalId = approvalId,
                reason = reason,
            ),
        )
        out.emit(
            StreamEvent.ToolResult(
                toolCallId = call.toolCallId,
                toolName = call.toolName,
                outputJson = outputJson,
                output = output,
                modelOutput = output,
                isError = true,
            ),
        )
        val toolPart = ContentPart.ToolResult(
            toolCallId = call.toolCallId,
            toolName = call.toolName,
            output = outputJson,
            isError = true,
            modelVisible = outputJson,
        )
        messages.add(ModelMessage(MessageRole.Tool, listOf(toolPart)))
    }

    @Suppress("UNCHECKED_CAST")
    /**
     * Decode `call.input` to a typed value. On failure, invoke
     * [experimental_repairToolCall] once (if set); if the repair
     * returns a corrected call, re-resolve the tool (the corrected
     * call may target a different toolName) and retry the decode.
     * If the repair returns null or the retry also fails, the
     * original exception propagates and the caller turns it into
     * [ToolExecutionResult.Failure].
     */
    private suspend fun resolveToolInput(
        toolDef: Tool<*, *, TContext>,
        call: ContentPart.ToolCall,
        messages: List<ModelMessage>,
    ): Pair<Tool<*, *, TContext>, Any?> {
        try {
            return toolDef to decodeToolInput(toolDef, call.input)
        } catch (ce: CancellationException) {
            throw ce
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            tryRepairToolInput(toolDef, call, e, messages)?.let { return it }
            // Repair absent or exhausted — surface a typed decode failure.
            throw if (experimental_repairToolCall != null) {
                AgentError.ToolCallRepairFailed(call.toolName, originalError = e, repairError = null)
            } else {
                AgentError.InvalidToolInput(call.toolName, call.input.toString(), e)
            }
        }
    }

    /** Single-attempt repair pass — null if no repair fn, repair gave
     *  up, the rerouted tool isn't in the set, or the repaired input
     *  still doesn't decode. Caller throws the original exception when
     *  this returns null. */
    private suspend fun tryRepairToolInput(
        originalToolDef: Tool<*, *, TContext>,
        call: ContentPart.ToolCall,
        originalError: Throwable,
        messages: List<ModelMessage>,
    ): Pair<Tool<*, *, TContext>, Any?>? {
        val repair = experimental_repairToolCall ?: return null
        val corrected = try {
            repair.invoke(call, originalError, messages, tools)
        } catch (ce: CancellationException) {
            throw ce
        } catch (@Suppress("TooGenericExceptionCaught") repairError: Throwable) {
            // The repair fn ITSELF threw (e.g. the model re-prompt failed) —
            // route to the documented ToolCallRepairFailed with repairError
            // populated, not the generic ToolExecution path executeTool's
            // outer catch would otherwise produce.
            throw AgentError.ToolCallRepairFailed(call.toolName, originalError = originalError, repairError = repairError)
        } ?: return null
        val toolDef = if (corrected.toolName != call.toolName) {
            tools.find(corrected.toolName)
        } else {
            originalToolDef
        }
        return toolDef?.let { def ->
            runCatching { def to decodeToolInput(def, corrected.input) }.getOrNull()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeToolInput(tool: Tool<*, *, *>, input: JsonElement): Any? {
        val ser = tool.inputSerializer as KSerializer<Any?>
        return WireDecoder.decode(ser, input, provider = "tool", operation = "${tool.name} input")
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeToolOutput(tool: Tool<*, *, *>, output: Any?): JsonElement {
        val ser = tool.outputSerializer as KSerializer<Any?>
        return aiSdkOutputJson.encodeToJsonElement(ser, output)
    }

    private fun validateCallOptions(options: TContext?): TContext? {
        val serializer = callOptionsSchema ?: return options
        if (options == null) return null
        return try {
            aiSdkOutputJson.decodeFromJsonElement(serializer, aiSdkOutputJson.encodeToJsonElement(serializer, options))
        } catch (error: Exception) {
            throw AgentError.InvalidCallOptions(error)
        }
    }

    private suspend fun emitError(
        t: Throwable,
        stepNumber: Int,
        source: OnErrorEvent.ErrorSource,
        hooks: AgentCallHooks?,
    ) {
        val event = OnErrorEvent(t, stepNumber, source)
        runCatching { onError?.invoke(event) }
        runCatching { hooks?.onError?.invoke(event) }
    }

    /** Single source for the 3 tool-error sites: emit a typed
     *  [StreamEvent.ToolError] (display text derived from [error]) AND
     *  append the matching tool message so the model sees the failure. */
    private suspend fun emitToolError(
        out: FlowCollector<StreamEvent>,
        toolCallId: String,
        toolName: String,
        error: AgentError,
        messages: MutableList<ModelMessage>,
    ) {
        val msg = error.message ?: "tool failed"
        out.emit(StreamEvent.ToolError(toolCallId, toolName, msg, error = error))
        messages.add(toolMessage(toolCallId, toolName, JsonPrimitive(msg)))
    }

    /** Wrap a tool-execution throwable as a typed Failure: an AgentError
     *  thrown by resolveToolInput passes through; anything else becomes
     *  [AgentError.ToolExecution]. */
    private fun toolFailure(call: ContentPart.ToolCall, t: Throwable): ToolExecutionResult.Failure =
        ToolExecutionResult.Failure(
            t as? AgentError ?: AgentError.ToolExecution(call.toolName, call.toolCallId, t),
        )

    private suspend fun runHook(stepNumber: Int, block: suspend () -> Unit) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            runCatching { onError?.invoke(OnErrorEvent(t, stepNumber, OnErrorEvent.ErrorSource.Hook)) }
        }
    }

    private fun hasSystemMessage(messages: List<ModelMessage>): Boolean =
        messages.any { it.role == MessageRole.System }

    private sealed interface ToolExecutionResult {
        /**
         * [outputJson] is the full typed payload that drives the UI's
         * per-tool renderer pipeline. [modelVisible] is what the agent
         * feeds back to the model in its `toolMessage(...)` — by
         * default the same as [outputJson], but tools can override via
         * `toModelOutput` to send the model a token-cheap summary
         * (e.g. "lineup: 2127 artists across 9 stages") while the UI
         * still gets the full thing. Without this split, rich tools
         * blow the model's context window every call.
         */
        data class Success(
            val outputJson: JsonElement,
            val output: ToolResultOutput = toolResultOutputFromJson(outputJson),
            val modelOutput: ToolResultOutput = output,
            val modelVisible: JsonElement = outputJson,
        ) : ToolExecutionResult {
            val isError: Boolean = modelOutput.isToolResultError()
        }
        data class Failure(val error: AgentError) : ToolExecutionResult
    }

    private class MessageHolder(var value: List<ModelMessage> = emptyList())
    private class StepsHolder(val steps: MutableList<StepResult>)

    /**
     * Tiny adapter that captures stream events into a side-effecting
     * collector while leaving the upstream Flow uncollected from the
     * caller's perspective. Used by [generate] to drain its own stream
     * and tally aggregates.
     */
    private class StreamCapture(private val onEach: suspend (StreamEvent) -> Unit) {
        suspend fun consume(event: StreamEvent) = onEach(event)
    }

    /** Result of draining a tool executor Flow — see [collectFinalToolOutput]. */
    private data class ToolOutputCapture(val hasOutput: Boolean, val value: Any?)

    /**
     * Resolve `stopSequences` along the `Step ?: Agent ?: agent-default`
     * chain, defaulting to an empty list. Extracted from the call-params
     * construction so the four-level null-walk doesn't pile up inline
     * (detekt's `Wrapping` rule was rejecting the inline version).
     */
    private fun resolveStopSequences(
        step: StepSettings<TContext>,
        agent: AgentSettings<TContext>,
    ): List<String> = step.stopSequences ?: agent.stopSequences ?: stopSequences ?: emptyList()
}

/**
 * [ToolStreamWriter] bound to the agent loop's output [FlowCollector].
 * Supplied to every [ToolExecutionContext] so a tool executor can write
 * custom events into the same stream the loop emits on (gap #21). Safe
 * because tool execution runs sequentially inside the loop's `flow { }`
 * collector — there's no concurrent emission to race with.
 */
private class FlowToolStreamWriter(
    private val out: FlowCollector<StreamEvent>,
) : ToolStreamWriter {
    override suspend fun write(event: StreamEvent) = out.emit(event)
    override suspend fun writeData(value: JsonElement) = out.emit(StreamEvent.Raw(value))
}

private class TerminalModelStreamError : RuntimeException()
