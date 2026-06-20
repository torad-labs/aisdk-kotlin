package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Abstract base [Agent] implementation — the canonical v6 ToolLoopAgent. **Extend it; do not instantiate it.**
 * A concrete agent subclasses this (e.g. `class MyAgent(...) : ToolLoopAgent<C, O>(...)`) so the agent's identity,
 * tools, lifecycle overrides, and DI live in one named type — never a bare `ToolLoopAgent(...)` held as a field.
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
public abstract class ToolLoopAgent<TContext, TOutput>(
    public val model: LanguageModel,
    public val instructions: String,
    override val tools: ToolSet<TContext>,
    public val activeTools: List<String>? = null,
    public val output: Output<TOutput>? = null,
    public val stopWhen: StopCondition = stepCountIs(20),
    public val prepareCall: (suspend PrepareCallScope<TContext>.() -> AgentSettings<TContext>)? = null,
    public val prepareStep: (suspend PrepareStepScope<TContext>.() -> StepSettings<TContext>)? = null,
    public val callOptionsSchema: KSerializer<TContext>? = null,
    // Sampler-param defaults set at agent construction. Mirror v6's
    // `CallSettings` (tool-loop-agent-settings.ts:145-194). Resolution
    // chain inside the loop is `StepSettings ?: AgentSettings ?: these
    // constructor defaults ?: null` (null = provider's own default).
    public val temperature: Float? = null,
    public val topP: Float? = null,
    public val topK: Int? = null,
    public val maxOutputTokens: Int? = null,
    public val stopSequences: List<String>? = null,
    public val seed: Int? = null,
    /** v6 `CallSettings.presencePenalty` agent-default. */
    public val presencePenalty: Float? = null,
    /** v6 `CallSettings.frequencyPenalty` agent-default. */
    public val frequencyPenalty: Float? = null,
    /** Wire-level response constraint for providers that support it. */
    public val responseFormat: ResponseFormat = ResponseFormat.Text,
    /**
     * Max tool calls executed concurrently within one step. Default unbounded — all of a
     * step's tool calls run at once (latency = slowest tool, not the sum). Results are
     * still applied to the message log in deterministic call order.
     */
    public val maxParallelToolCalls: Int = Int.MAX_VALUE,
    public val onStart: (suspend OnStartEvent.() -> Unit)? = null,
    public val onStepStart: (suspend OnStepStartEvent.() -> Unit)? = null,
    public val onStepFinish: (suspend OnStepFinishEvent.() -> Unit)? = null,
    public val onFinish: (suspend OnFinishEvent.() -> Unit)? = null,
    public val onError: (suspend OnErrorEvent.() -> Unit)? = null,
    public val onChunk: (suspend OnChunkEvent.() -> Unit)? = null,
    public val onAbort: (suspend OnAbortEvent.() -> Unit)? = null,
    public val experimental_onToolCallStart: (suspend OnToolCallStartEvent.() -> Unit)? = null,
    public val experimental_onToolCallFinish: (suspend OnToolCallFinishEvent.() -> Unit)? = null,
    /**
     * Self-healing callback fired when a tool call's arguments fail to
     * decode. Return a corrected call to retry, or null to surface
     * `StreamEvent.ToolError`. See [ToolCallRepairFunction].
     */
    public val experimental_repairToolCall: ToolCallRepairFunction<TContext>? = null,
    /**
     * Secret for HMAC-signing tool-approval requests (upstream v6.0.202
     * `experimental_toolApprovalSecret`). When set, every approval request the
     * loop issues carries a signature over `(approvalId, toolCallId, toolName,
     * input)`, and a replayed approval is re-validated FAIL-CLOSED before the
     * tool executes: missing/invalid signature throws
     * [AgentError.InvalidToolApprovalSignature], the input is re-validated
     * against the tool's schema, and a tool that no longer requires approval
     * is denied rather than run — so a client cannot forge, re-target, or
     * input-swap an approval. Experimental upstream (can break in patches).
     */
    public val experimental_toolApprovalSecret: ByteArray? = null,
    /**
     * Telemetry for this agent's invocations (upstream v7 `telemetry`).
     * [Telemetry] integrations registered globally via [registerTelemetry]
     * observe every generate/stream call automatically; this setting adds
     * call metadata ([TelemetrySettings.functionId]) or per-agent
     * [TelemetrySettings.integrations] — which, when non-empty, REPLACE the
     * global registrations for this agent's calls (upstream per-call
     * semantics). Telemetry observes — an integration throw never alters
     * loop behavior.
     */
    public val telemetry: TelemetrySettings? = null,
    /**
     * Port-side log sink for non-fatal warnings (see [Logger]). The loop warns here
     * when a [Telemetry] integration throws and the event is dropped — the swallow
     * contract keeps telemetry from altering the loop, and the warn keeps a broken
     * integration DISCOVERABLE instead of perfectly silent.
     */
    public val logger: Logger = NoopLogger,
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
    public val engineState: StateFlow<ToolLoopAgentState> = mutableEngineState.asStateFlow()

    private val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + engineContext)

    // @Volatile for cross-thread visibility: these are written from host-thread
    // entry points (dispatchEngineAction/submitPrompt/resumeWithApproval/close) and
    // read from engine jobs running on a multi-threaded dispatcher (Dispatchers.Default,
    // i.e. Native). A plain var here is a data race the single-threaded test scheduler
    // hides. currentEngineJob also backs the supersession guard (see runEngineLoop).
    @Volatile
    private var currentEngineJob: Job? = null

    @Volatile
    private var currentEngineContext: TContext? = null

    // TOOL-004: tracks the most recent activeContext the loop was running with,
    // including any override applied by prepareStep via StepSettings.experimental_context.
    // Updated by runEngineLoop before the stream starts and after each prepareStep override,
    // so resumeWithApproval picks up the live context rather than the stale submitPrompt one.
    @Volatile
    private var currentActiveContext: TContext? = null

    /**
     * Cancel the engine scope and any in-flight engine job. Call when a
     * long-lived host (ViewModel / Repository) that drove the engine surface
     * is disposed. The per-call generate()/stream() API needs no close().
     */
    public fun close() {
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
    public fun dispatchEngineAction(action: ToolLoopAgentAction<TContext>) {
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
        // Lazy-start so currentEngineJob is published BEFORE the loop body runs (build →
        // assign → start). The body's supersession guard (runEngineLoop) compares its own
        // job to this field; an eager launch could begin writing state before the
        // assignment lands, making the new job's own early writes fail the guard.
        val job = engineScope.launch(start = CoroutineStart.LAZY) {
            runEngineLoop(prompt = text, priorMessages = priorMessages, context = context)
        }
        currentEngineJob = job
        job.start()
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
        // Build → assign → start (see submitPrompt) so the guard sees this job published.
        val job = engineScope.launch(start = CoroutineStart.LAZY) {
            // TOOL-004: use currentActiveContext (updated by prepareStep overrides)
            // rather than currentEngineContext (only set at submitPrompt time).
            runEngineLoop(prompt = null, priorMessages = updatedMessages, context = currentActiveContext)
        }
        currentEngineJob = job
        job.start()
    }

    private suspend fun runEngineLoop(
        prompt: String?,
        priorMessages: List<ModelMessage>,
        context: TContext?,
    ) {
        // This loop's own job. Every state write below is gated on it still being the
        // current engine job, so a superseded-but-cooperatively-unwinding old loop can't
        // clobber a newer submit's state (the stale-write race AgentSession's active()
        // guard prevents). currentEngineJob was published before start() (see callers).
        val ownJob = coroutineContext[Job]
        // TOOL-004: seed the active context so resumeWithApproval uses at least the
        // caller-supplied context when no prepareStep override has fired yet.
        // FLAG: per-step updates (when stepSettings.experimental_context overrides activeContext
        // inside streamInternal) are not yet reflected here — that requires threading a
        // callback into streamInternal. The seed covers the common case; per-step context
        // evolution on resume is a follow-up.
        currentActiveContext = context
        val engineHooks = AgentCallHooks(
            onChunk = { event ->
                val streamEvent = event.event
                if (streamEvent is StreamEvent.TextDelta) {
                    updateEngineStateIfCurrent(ownJob) { current ->
                        current.copy(streamingAssistantText = current.streamingAssistantText + streamEvent.text)
                    }
                }
            },
            onStepFinish = { _ ->
                updateEngineStateIfCurrent(ownJob) {
                    it.copy(
                        streamingAssistantText = "",
                        currentToolCalls = emptyList(),
                        totalSteps = it.totalSteps + 1,
                    )
                }
            },
            onFinish = { event ->
                updateEngineStateIfCurrent(ownJob) {
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
                    is StreamEvent.ToolCall -> updateEngineStateIfCurrent(ownJob) { current ->
                        current.copy(
                            currentToolCalls = current.currentToolCalls +
                                ContentPart.ToolCall(event.toolCallId, event.toolName, event.inputJson),
                        )
                    }
                    is StreamEvent.Error -> updateEngineStateIfCurrent(ownJob) {
                        it.copy(isStreaming = false, error = event.message)
                    }
                    else -> Unit
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            updateEngineStateIfCurrent(ownJob) { it.copy(isStreaming = false, error = t.message ?: "agent failed") }
        }
    }

    /**
     * Apply a state transition only if [ownerJob] is still the current engine job.
     * Gates every [runEngineLoop] write so a superseded job (cancelled by a newer
     * submit/resume but still unwinding on a multi-threaded dispatcher) cannot
     * overwrite the new job's state. Mirrors [AgentSession]'s `active()` guard.
     *
     * The check→write is not atomic against a concurrent [close] that nulls
     * currentEngineJob between the two: such a write lands on an agent that is
     * already disposing (engineScope is being cancelled) and on a StateFlow no
     * live host is observing, so it is benign — a tighter lock would buy nothing.
     */
    private inline fun updateEngineStateIfCurrent(
        ownerJob: Job?,
        block: (ToolLoopAgentState) -> ToolLoopAgentState,
    ) {
        if (currentEngineJob !== ownerJob) return
        mutableEngineState.update(block)
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
                            signature = event.signature,
                        ),
                    )
                }
                is StreamEvent.Error -> throw AiSdkRuntimeException(event.message, event.cause)
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
        val steps = collectedSteps.toList()
        return GenerateResult(
            output = decodeFinalOutput(output, text, finishReason),
            text = text,
            steps = steps,
            finishReason = finishReason,
            // upstream parity: usage = final step's usage, totalUsage = sum across steps.
            usage = steps.lastOrNull()?.usage ?: totalUsage,
            totalUsage = totalUsage,
            pendingApprovals = collectedApprovals.toList(),
            messages = collectedMessages,
        )
    }

    /**
     * Resolve the final typed output. Matching upstream, structured output is only
     * parsed when the model actually stopped (finishReason == stop); ending on
     * length / step-cap / non-stop yields no parseable object, surfaced as
     * [NoOutputGeneratedError] rather than a confusing decode error.
     */
    private fun decodeFinalOutput(output: Output<TOutput>?, text: String, finishReason: FinishReason): TOutput =
        if (output == null) {
            @Suppress("UNCHECKED_CAST")
            (text as TOutput)
        } else if (finishReason == FinishReason.Stop) {
            output.decode(text)
        } else {
            throw NoOutputGeneratedError("No object generated: the model finished with `$finishReason`, not `stop`.")
        }

    override fun stream(
        prompt: String?,
        messages: List<ModelMessage>,
        options: TContext?,
        abortSignal: AbortSignal,
        hooks: AgentCallHooks?,
    ): Flow<StreamEvent> = streamInternal(prompt, messages, options, abortSignal, hooks, null, null)

    // SwallowedException: the AbortError catch intentionally consumes the abort — it is a
    // terminal user signal surfaced as StreamEvent.Abort, not an error to propagate.
    @Suppress("SwallowedException")
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
        // v7 telemetry: resolve the effective integration once per invocation and
        // stamp every event of this call with one TelemetryCall envelope.
        val feed = resolveTelemetry(telemetry, logger)?.let { tele ->
            TelemetryFeed(
                tele = tele,
                call = TelemetryCall(
                    callId = generateId("call"),
                    agentId = id,
                    agentVersion = version,
                    modelId = model.modelId,
                    functionId = telemetry?.functionId,
                ),
            )
        }
        emit(StreamEvent.StreamStart())
        // One immutable event shared by both hook invocations AND telemetry — the identical snapshot.
        val startEvent = OnStartEvent(prompt, priorMessages, validatedOptions)
        runHook(0, feed) {
            onStart?.invoke(startEvent)
            hooks?.onStart?.invoke(startEvent)
        }
        fireTelemetry(feed) { onAgentStart(it, startEvent) }

        // prepareCall — once per invocation.
        val resolvedSettings: AgentSettings<TContext> = prepareCall?.let { hook ->
            try {
                PrepareCallScope(validatedOptions, instructions, model, tools).hook()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emitError(t, 0, OnErrorEvent.ErrorSource.PrepareCall, hooks, feed)
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
        val resumeOutcome =
            applyToolApprovalResponses(this, messages, resolvedTools, options, abortSignal, hooks, feed)
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
        var lastRawFinishReason: String? = null
        var pendingApprovalEmitted = false
        // gap #16: running typed context. A prepareStep may override it via
        // StepSettings.experimental_context to evolve context mid-loop (e.g.
        // RAG augmentation after a tool result); this step's tool execution
        // and every subsequent step then see the new value.
        var activeContext: TContext? = validatedOptions

        // Fire the onAbort hooks + persist the messages collected so far. The caller emits
        // StreamEvent.Abort and returns; this only does the side effects (it isn't the
        // FlowCollector, so it can't emit itself).
        suspend fun fireAbort() {
            val abortEvent = OnAbortEvent(completedSteps.toList())
            runHook(stepNumber, feed) {
                onAbort?.invoke(abortEvent)
                hooks?.onAbort?.invoke(abortEvent)
            }
            fireTelemetry(feed) { onAbort(it, abortEvent) }
            finalMessagesRef?.value = messages.toList()
        }

        loopFinished@ while (true) {
            stepNumber += 1
            // Poll abort at each step boundary: emit a terminal Abort event + fire onAbort,
            // then end the stream cleanly (was: throwIfAborted unwinding with no Abort event).
            if (abortSignal.isAborted) {
                emit(StreamEvent.Abort)
                fireAbort()
                return@flow
            }
            emit(StreamEvent.StepStart(stepNumber))
            val stepStartEvent = OnStepStartEvent(stepNumber, messages.toList())
            runHook(stepNumber, feed) {
                onStepStart?.invoke(stepStartEvent)
                hooks?.onStepStart?.invoke(stepStartEvent)
            }
            fireTelemetry(feed) { onStepStart(it, stepStartEvent) }

            val stepSettings: StepSettings<TContext> = prepareStep?.let { hook ->
                try {
                    PrepareStepScope(stepNumber, resolvedModel, completedSteps.toList(), messages.toList(), activeContext).hook()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    emitError(t, stepNumber, OnErrorEvent.ErrorSource.PrepareStep, hooks, feed)
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
            } else {
                stepMessages
            }

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
                    } else {
                        responseFormat
                    },
            )

            val stepText = StringBuilder()
            val stepReasoning = StringBuilder()
            val stepToolCalls = mutableListOf<ContentPart.ToolCall>()
            val stepToolResults = mutableListOf<ContentPart.ToolResult>()
            val stepApprovalRequests = mutableListOf<ContentPart.ToolApprovalRequest>()
            var stepFinishReason = FinishReason.Stop
            var stepUsage = Usage()
            // Per-step metadata, captured from the stream and surfaced on StepResult
            // (parity with upstream — the loop previously dropped all four).
            var stepWarnings: List<CallWarning> = emptyList()
            var stepProviderMetadata: Map<String, JsonElement> = emptyMap()
            var stepResponse = LanguageModelResponseMetadata()
            var stepRawFinishReason: String? = null
            // gap #18: a streaming tool-input id -> toolName, so ToolInputDelta
            // (which carries only the streaming id) can route to the right
            // tool's onInputDelta hook.
            val toolInputNames = mutableMapOf<String, String>()

            // Close this step's model-call telemetry bracket. Called on EVERY exit from the
            // model stream — clean completion, provider stream error, abort, transport throw —
            // so a span-pairing integration never leaks an open model-call span. A local fun
            // (the fireAbort precedent) so the step locals are captured, not re-passed 4x.
            suspend fun closeModelCall(finishReason: FinishReason, rawFinishReason: String?) {
                fireTelemetry(feed) {
                    onModelCallFinish(
                        it,
                        TelemetryModelCallResultEvent(
                            stepNumber = stepNumber,
                            modelId = stepModel.modelId,
                            finishReason = finishReason,
                            usage = stepUsage,
                            response = stepResponse,
                            rawFinishReason = rawFinishReason,
                        ),
                    )
                }
            }

            fireTelemetry(feed) {
                onModelCallStart(it, TelemetryModelCallEvent(stepNumber, stepModel.modelId, callParams))
            }
            try {
                stepModel.stream(callParams).collect { event ->
                    abortSignal.throwIfAborted()
                    runHook(stepNumber, feed) {
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
                            val startedTool = stepTools.find(event.toolName)
                            runHook(stepNumber, feed) { startedTool?.onInputStart(event.id) }
                            emit(event)
                        }
                        is StreamEvent.ToolInputDelta -> {
                            // gap #18: raw-character pre-warm as input streams in.
                            val deltaTool = toolInputNames[event.id]?.let { stepTools.find(it) }
                            runHook(stepNumber, feed) { deltaTool?.onInputDelta(event.id, event.delta) }
                            emit(event)
                        }
                        is StreamEvent.ToolCall -> {
                            // gap #18: input stream for this id is complete; remove so a
                            // reused id in a later ToolInputStart doesn't misroute deltas.
                            toolInputNames.remove(event.toolCallId)
                            val call = ContentPart.ToolCall(event.toolCallId, event.toolName, event.inputJson)
                            stepToolCalls.add(call)
                            toolCallsAllSteps.add(call)
                            emit(event)
                        }
                        is StreamEvent.StepFinish -> {
                            stepFinishReason = event.finishReason
                            stepUsage = event.usage
                            event.providerMetadata?.let { stepProviderMetadata = it }
                            // Don't forward — we emit our own StepFinish below post-tool-execution.
                        }
                        is StreamEvent.Finish -> {
                            stepFinishReason = event.finishReason
                            stepUsage = event.usage
                            lastRawFinishReason = event.rawFinishReason
                            stepRawFinishReason = event.rawFinishReason
                            event.providerMetadata?.let { stepProviderMetadata = it }
                        }
                        is StreamEvent.StreamStart -> {
                            stepWarnings = event.warnings
                            emit(event)
                        }
                        is StreamEvent.ResponseMetadata -> {
                            stepResponse = LanguageModelResponseMetadata(
                                id = event.id,
                                timestampMillis = event.timestampMillis,
                                modelId = event.modelId,
                                headers = event.headers,
                                body = event.body,
                            )
                            emit(event)
                        }
                        is StreamEvent.Error -> {
                            emit(event)
                            throw TerminalModelStreamError()
                        }
                        else -> emit(event)
                    }
                }
            } catch (_: TerminalModelStreamError) {
                // TOOL-002: preserve any assistant content (text + complete tool calls)
                // accumulated before the stream error so the message history isn't
                // silently truncated on resumption.
                val partialParts: MutableList<ContentPart> = mutableListOf()
                if (stepText.isNotEmpty()) partialParts.add(ContentPart.Text(stepText.toString()))
                if (stepReasoning.isNotEmpty()) partialParts.add(ContentPart.Reasoning(stepReasoning.toString()))
                partialParts.addAll(stepToolCalls)
                if (partialParts.isNotEmpty()) {
                    messages.add(ModelMessage(MessageRole.Assistant, partialParts.toList()))
                }
                // Close the model-call telemetry bracket — a span-pairing integration must
                // never leak an open span because the provider errored mid-stream.
                closeModelCall(FinishReason.Error, stepRawFinishReason)
                finalMessagesRef?.value = messages.toList()
                return@flow
            } catch (abort: AbortError) {
                // A user-initiated abort (abortSignal.throwIfAborted), caught BEFORE the
                // generic CancellationException clause: surface it as a terminal Abort event
                // + onAbort and end cleanly. The model-call bracket closes FIRST (the inner
                // span), then fireAbort feeds the outer onAbort.
                closeModelCall(FinishReason.Other, "aborted")
                emit(StreamEvent.Abort)
                fireAbort()
                return@flow
            } catch (ce: CancellationException) {
                // A genuine coroutine cancellation must re-throw untouched (Tier-0 coroutines).
                throw ce
            } catch (t: Throwable) {
                emitError(t, stepNumber, OnErrorEvent.ErrorSource.Model, hooks, feed)
                emit(StreamEvent.Error(t.message ?: "model failed", cause = t))
                closeModelCall(FinishReason.Error, stepRawFinishReason)
                finalMessagesRef?.value = messages.toList()
                return@flow
            }
            closeModelCall(stepFinishReason, stepRawFinishReason)

            // Build assistant content: text + reasoning + tool calls.
            val assistantParts: MutableList<ContentPart> = mutableListOf()
            if (stepText.isNotEmpty()) assistantParts.add(ContentPart.Text(stepText.toString()))
            if (stepReasoning.isNotEmpty()) assistantParts.add(ContentPart.Reasoning(stepReasoning.toString()))
            assistantParts.addAll(stepToolCalls)

            // Process tool calls — resolve (decode + a single repair attempt) ONCE per call,
            // then decide approval on the RESOLVED input. Resolving up front (instead of
            // decoding inside callNeedsApproval and again inside executeTool) means
            // experimental_repairToolCall now reaches EVERY tool — factory- or subclass-built —
            // and removes the prior double-decode. The resolved (tool, input) is carried to the
            // executor via resolvedForExecution so the repair attempt is not repeated.
            val toolsRequiringApproval = mutableListOf<ContentPart.ToolCall>()
            val toolsToExecute = mutableListOf<ContentPart.ToolCall>()
            val resolvedForExecution = mutableMapOf<String, Pair<Tool<*, *, TContext>, Any?>>()
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
                val (resolvedTool, resolvedInput, wasRepaired) = try {
                    resolveCall(toolDef, call, messages.toList())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    // Decode/repair failure. Fire onError + telemetry here so these failures stay on
                    // the error-reporting surface — before input resolution was hoisted ahead of the
                    // approval gate, a non-gated tool's decode failure happened inside executeTool,
                    // which reported it; categorizing it here must report it too.
                    emitError(t, stepNumber, OnErrorEvent.ErrorSource.Tool, hooks, feed)
                    emitToolError(
                        this,
                        call.toolCallId,
                        call.toolName,
                        t as? AgentError ?: AgentError.ToolExecution(call.toolName, call.toolCallId, t),
                        messages,
                    )
                    continue
                }
                val needsApproval = try {
                    callNeedsApproval(resolvedTool, call, resolvedInput, activeContext, messages.toList())
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
                    if (wasRepaired) {
                        // A gated tool whose input only decoded after repair: reject rather than
                        // request approval over a rewritten call. Approval requests + the resume
                        // re-validation are keyed to the model's ORIGINAL input (and its signature),
                        // so approve-over-repaired could not be honored on resume. Matches the
                        // pre-resolve "gated tool + malformed input = error" behavior.
                        emitToolError(
                            this,
                            call.toolCallId,
                            call.toolName,
                            AgentError.InvalidToolInput(
                                call.toolName,
                                call.input.toString(),
                                IllegalStateException(
                                    "approval-gated tool received input that only decoded after repair; " +
                                        "gated tool calls are not auto-repaired",
                                ),
                            ),
                            messages,
                        )
                    } else {
                        toolsRequiringApproval.add(call)
                    }
                } else {
                    resolvedForExecution[call.toolCallId] = resolvedTool to resolvedInput
                    toolsToExecute.add(call)
                }
            }
            val hasLocalToolWork = toolsRequiringApproval.isNotEmpty() || toolsToExecute.isNotEmpty()

            // Emit approval requests + add to assistant content. Loop ends after this step.
            // With an approval secret configured, each request is signed at issuance over its
            // EFFECTIVE approval id (explicit approvalId ?: toolCallId — here the default).
            for (call in toolsRequiringApproval) {
                val signature = maybeSignToolApproval(
                    secret = experimental_toolApprovalSecret,
                    approvalId = call.toolCallId,
                    toolCallId = call.toolCallId,
                    toolName = call.toolName,
                    input = call.input,
                )
                emit(
                    StreamEvent.ToolApprovalRequest(
                        call.toolCallId,
                        call.toolName,
                        call.input,
                        signature = signature,
                    ),
                )
                val request = ContentPart.ToolApprovalRequest(
                    call.toolCallId,
                    call.toolName,
                    call.input,
                    signature = signature,
                )
                assistantParts.add(request)
                stepApprovalRequests.add(request)
                pendingApprovalEmitted = true
            }

            if (assistantParts.isNotEmpty()) {
                messages.add(ModelMessage(MessageRole.Assistant, assistantParts.toList()))
            }

            // Execute non-approval-gated tools concurrently (bounded by
            // maxParallelToolCalls). Child coroutines send progress to a parent-owned
            // channel; only this collector coroutine performs real Flow emits. Final
            // durable tool results are applied incrementally in original call order.
            // All tools see the SAME prompt snapshot (they were requested in one assistant
            // turn) — more correct than the prior serial leak of one tool's result into the
            // next tool's messages view.
            val promptSnapshot = messages.toList()
            val permits = Semaphore(maxParallelToolCalls.coerceAtLeast(1))
            val parentOut: FlowCollector<StreamEvent> = this
            coroutineScope {
                val signals = Channel<ParallelToolSignal>(Channel.BUFFERED)
                toolsToExecute.forEachIndexed { index, call ->
                    launch {
                        val toolDef = stepTools.find(call.toolName)
                        if (toolDef == null) {
                            signals.send(
                                ParallelToolSignal.Completed(OrderedToolCompletion.Skipped(index)),
                            )
                            return@launch
                        }
                        permits.withPermit {
                            val progressOut = ChannelToolEventCollector(signals)
                            runHook(stepNumber, feed) {
                                val startEvent = OnToolCallStartEvent(
                                    call.toolCallId,
                                    call.toolName,
                                    call.input,
                                    stepNumber,
                                    promptSnapshot,
                                )
                                experimental_onToolCallStart?.invoke(startEvent)
                                hooks?.experimental_onToolCallStart?.invoke(startEvent)
                            }
                            val result = executeTool(
                                out = progressOut,
                                toolDef = toolDef,
                                call = call,
                                options = activeContext,
                                abortSignal = abortSignal,
                                stepNumber = stepNumber,
                                messages = promptSnapshot,
                                hooks = hooks,
                                feed = feed,
                                // Reuse the (tool, input) already resolved + repaired during the
                                // approval-categorization pass — don't decode/repair a second time.
                                preResolved = resolvedForExecution[call.toolCallId],
                            )
                            runHook(stepNumber, feed) {
                                val finishEvent = OnToolCallFinishEvent(
                                    toolCallId = call.toolCallId,
                                    toolName = call.toolName,
                                    outcome = when (result) {
                                        is ToolExecutionResult.Success ->
                                            OnToolCallFinishEvent.Outcome.Success(result.outputJson)
                                        is ToolExecutionResult.Failure ->
                                            OnToolCallFinishEvent.Outcome.Failure(
                                                result.error.message ?: "tool failed",
                                            )
                                    },
                                    stepNumber = stepNumber,
                                )
                                experimental_onToolCallFinish?.invoke(finishEvent)
                                hooks?.experimental_onToolCallFinish?.invoke(finishEvent)
                            }
                            signals.send(
                                ParallelToolSignal.Completed(
                                    OrderedToolCompletion.Executed(index, call, result),
                                ),
                            )
                        }
                    }
                }

                val completions = mutableMapOf<Int, OrderedToolCompletion>()
                var completedChildren = 0
                var nextToApply = 0
                while (completedChildren < toolsToExecute.size) {
                    when (val signal = signals.receive()) {
                        is ParallelToolSignal.Progress -> parentOut.emit(signal.event)
                        is ParallelToolSignal.Completed -> {
                            completedChildren += 1
                            completions[signal.completion.index] = signal.completion
                            while (true) {
                                val completion = completions.remove(nextToApply) ?: break
                                when (completion) {
                                    is OrderedToolCompletion.Executed ->
                                        applyToolResult(
                                            parentOut,
                                            completion.call,
                                            completion.result,
                                            messages,
                                            stepToolResults,
                                        )
                                    is OrderedToolCompletion.Skipped -> Unit
                                }
                                nextToApply += 1
                            }
                        }
                    }
                }
            }

            val effectiveFinishReason = if (toolsRequiringApproval.isNotEmpty()) {
                FinishReason.ToolApprovalRequested
            } else {
                stepFinishReason
            }

            val step = StepResult(
                stepNumber = stepNumber,
                text = stepText.toString(),
                reasoning = stepReasoning.toString(),
                toolCalls = stepToolCalls.toList(),
                toolResults = stepToolResults.toList(),
                toolApprovalRequests = stepApprovalRequests.toList(),
                finishReason = effectiveFinishReason,
                usage = stepUsage,
                warnings = stepWarnings,
                request = LanguageModelRequestMetadata(),
                response = stepResponse,
                providerMetadata = stepProviderMetadata,
                rawFinishReason = stepRawFinishReason,
                model = stepModel.modelId,
                experimentalContext = activeContext,
            )
            completedSteps.add(step)
            stepsCapture?.steps?.add(step)
            totalUsage += stepUsage
            lastFinishReason = effectiveFinishReason

            val stepFinishEvent = OnStepFinishEvent(stepNumber, step)
            runHook(stepNumber, feed) {
                onStepFinish?.invoke(stepFinishEvent)
                hooks?.onStepFinish?.invoke(stepFinishEvent)
            }
            fireTelemetry(feed) { onStepFinish(it, stepFinishEvent) }
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

        emit(StreamEvent.Finish(stepNumber, lastFinishReason, totalUsage, rawFinishReason = lastRawFinishReason))
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
                        signature = it.signature,
                    )
                }
                ?: emptyList()
        } else {
            emptyList()
        }

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
        runHook(stepNumber, feed) {
            onFinish?.invoke(finishEvent)
            hooks?.onFinish?.invoke(finishEvent)
        }
        fireTelemetry(feed) { onAgentFinish(it, finishEvent) }
    }

    /**
     * If the most recent tool message contains [ContentPart.ToolApprovalResponse]
     * parts, execute (or deny) the matching tool calls from the prior
     * assistant message before the next model call.
     */
    @Suppress("LongParameterList")
    private suspend fun applyToolApprovalResponses(
        out: FlowCollector<StreamEvent>,
        messages: MutableList<ModelMessage>,
        tools: ToolSet<TContext>,
        options: TContext?,
        abortSignal: AbortSignal,
        hooks: AgentCallHooks?,
        feed: TelemetryFeed? = null,
    ): Unit? {
        val lastToolMsg = messages.lastOrNull { it.role == MessageRole.Tool } ?: return null
        val approvals = lastToolMsg.content.filterIsInstance<ContentPart.ToolApprovalResponse>()
        if (approvals.isEmpty()) return null

        val priorAssistantMsg = messages.findLast { it.role == MessageRole.Assistant } ?: return null
        val priorToolCalls = priorAssistantMsg.content.filterIsInstance<ContentPart.ToolCall>()
        if (priorToolCalls.isEmpty()) return null

        // Correlate response.approvalId -> request.toolCallId (the approval response may
        // reference the request by a distinct approvalId), matching upstream.
        val approvalRequests = priorAssistantMsg.content.filterIsInstance<ContentPart.ToolApprovalRequest>()
        val approvalIdToCallId = approvalRequests
            .mapNotNull { req -> req.approvalId?.let { it to req.toolCallId } }
            .toMap()
        // The issued request per EFFECTIVE approval id — carries the signature the
        // v6.0.202 fail-closed re-validation checks before an approved tool executes.
        val requestsByApprovalId = approvalRequests.associateBy { it.approvalId ?: it.toolCallId }
        // Idempotency: a call already answered by a tool result must NOT re-execute.
        val alreadyResolved = resolvedToolCallIds(messages)

        // TOOL-003: index calls by toolCallId so duplicate approvals for the same id
        // are each matched deterministically to a distinct call occurrence (preserving
        // order), rather than silently mapping every approval to the first match.
        val remainingCalls = priorToolCalls.toMutableList()
        for (approval in approvals) {
            // Resolve the target call via approvalId correlation, falling back to toolCallId.
            val targetCallId = approval.approvalId?.let { approvalIdToCallId[it] } ?: approval.toolCallId
            if (targetCallId in alreadyResolved) continue // already executed/denied — skip
            val callIndex = remainingCalls.indexOfFirst { it.toolCallId == targetCallId }
            if (callIndex == -1) continue
            val matchingCall = remainingCalls.removeAt(callIndex)
            alreadyResolved += matchingCall.toolCallId
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
            // v6.0.202 fail-closed re-validation of the client-replayed approval, in upstream
            // order: HMAC signature (with a configured secret), input schema, then a fresh
            // needsApproval resolution. A tool that vanished from the surface or no longer
            // requires approval could never have been issued this request — denied, not run.
            val effectiveApprovalKey = approval.approvalId ?: matchingCall.toolCallId
            verifyReplayedApprovalSignature(
                request = requestsByApprovalId[effectiveApprovalKey],
                approvalKey = effectiveApprovalKey,
                call = matchingCall,
            )
            val toolDef = tools.find(matchingCall.toolName)
            val decodedInput: Any? = if (toolDef != null) revalidateApprovedInput(toolDef, matchingCall) else null
            val stillNeedsApproval = toolDef != null &&
                callNeedsApproval(toolDef, matchingCall, decodedInput, options, messages.toList())
            if (toolDef == null || !stillNeedsApproval) {
                applyDeniedToolApproval(
                    out = out,
                    call = matchingCall,
                    approvalId = effectiveApprovalKey,
                    reason = approval.reason
                        ?: "Tool \"${matchingCall.toolName}\" does not require approval",
                    messages = messages,
                )
                continue
            }
            val approvedResult = executeTool(
                out = out,
                toolDef = toolDef,
                call = matchingCall,
                options = options,
                abortSignal = abortSignal,
                stepNumber = 0,
                messages = messages.toList(),
                hooks = hooks,
                feed = feed,
                // Approval only ever fires for cleanly-decoded input — reuse it (no re-decode/repair).
                preResolved = toolDef to decodedInput,
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

    /**
     * Fail-closed HMAC check for one replayed approval (v6.0.202): with no configured
     * secret this is a no-op; with one, a missing or invalid signature throws
     * [AgentError.InvalidToolApprovalSignature] — the tool never executes. Verified over
     * the ASSISTANT tool-call's input (what would execute), not the response's claim.
     */
    private fun verifyReplayedApprovalSignature(
        request: ContentPart.ToolApprovalRequest?,
        approvalKey: String,
        call: ContentPart.ToolCall,
    ) {
        val secret = experimental_toolApprovalSecret ?: return
        val signature = request?.signature
            ?: throw AgentError.InvalidToolApprovalSignature(approvalKey, call.toolCallId, "missing signature")
        val valid = verifyToolApprovalSignature(
            secret = secret,
            signature = signature,
            approvalId = approvalKey,
            toolCallId = call.toolCallId,
            toolName = call.toolName,
            input = call.input,
        )
        if (!valid) {
            throw AgentError.InvalidToolApprovalSignature(approvalKey, call.toolCallId, "invalid signature")
        }
    }

    /** Fail-closed schema re-validation of a replayed approval's input (v6.0.202): the
     *  client supplied this history, so the input is re-decoded against the tool's schema
     *  BEFORE execution; a mismatch throws [AgentError.InvalidToolInput]. */
    private fun revalidateApprovedInput(toolDef: Tool<*, *, TContext>, call: ContentPart.ToolCall): Any? {
        return try {
            decodeToolInput(toolDef, call.input)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw t as? AgentError ?: AgentError.InvalidToolInput(call.toolName, call.input.toString(), t)
        }
    }

    /** Tool-call ids already answered by a tool result anywhere in the message log. */
    private fun resolvedToolCallIds(messages: List<ModelMessage>): MutableSet<String> =
        messages.asSequence()
            .filter { it.role == MessageRole.Tool }
            .flatMap { it.content.asSequence() }
            .filterIsInstance<ContentPart.ToolResult>()
            .map { it.toolCallId }
            .toMutableSet()

    /**
     * Evaluate a tool's approval gate on its ALREADY-resolved (decoded/repaired)
     * input. Decode + repair happen once up front (see [resolveCall]); this only
     * runs the predicate, so it is never the place a malformed input is rejected
     * and never bypasses [ToolCallRepairFunction].
     */
    private suspend fun callNeedsApproval(
        toolDef: Tool<*, *, TContext>,
        call: ContentPart.ToolCall,
        typedInput: Any?,
        options: TContext?,
        messages: List<ModelMessage>,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        val gated = toolDef as Tool<Any?, Any?, TContext>
        val predicateOptions = ToolPredicateOptions(
            toolCallId = call.toolCallId,
            messages = messages,
            experimental_context = options,
        )
        return try {
            gated.needsApproval(typedInput, predicateOptions)
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
        feed: TelemetryFeed? = null,
        // Pre-resolved (tool, input) from the approval-categorization pass, so the
        // step-loop path doesn't decode/repair twice. Null on the resume path, where
        // executeTool resolves the approved call itself.
        preResolved: Pair<Tool<*, *, TContext>, Any?>? = null,
    ): ToolExecutionResult {
        // Telemetry brackets the execution HERE (not at the dispatch site) so
        // both routes — the step loop AND the approval-resume path — emit.
        fireTelemetry(feed) {
            onToolCallStart(it, OnToolCallStartEvent(call.toolCallId, call.toolName, call.input, stepNumber, messages))
        }
        val result = try {
            // Resolve (toolDef, decoded input). On first attempt: decode
            // the call's args against `toolDef`. On decode failure with
            // `experimental_repairToolCall` set: invoke the repair fn,
            // re-resolve the tool (a repaired call may re-route to a
            // different toolName), and retry decode ONCE. No recursive
            // repair — keeps the loop bounded.
            val (resolvedTool, resolvedInput) = preResolved ?: resolveToolInput(toolDef, call, messages)

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
            runHook(stepNumber, feed) { typedTool.onInputAvailable(call.toolCallId, input) }
            val captured = collectFinalToolOutput(out, typedTool, ctx, call, input)
            if (!captured.hasOutput) {
                toolFailure(call, IllegalStateException("tool emitted no values"))
            } else {
                val lastOutput = captured.value
                val outputJson = encodeToolOutput(typedTool, lastOutput)
                val predicateOptions = ToolPredicateOptions(
                    toolCallId = call.toolCallId,
                    messages = messages,
                    experimental_context = options,
                )
                val output = toolResultOutputFromJson(outputJson)
                val modelOutput = typedTool.toModelOutput(lastOutput, predicateOptions) ?: output
                ToolExecutionResult.Success(
                    outputJson = outputJson,
                    output = output,
                    modelOutput = modelOutput,
                    modelVisible = modelOutput.toJsonElement(),
                )
            }
        } catch (ce: CancellationException) {
            // Cancellation MUST propagate, never get persisted as a tool result
            // — otherwise the agent loop continues with a turn that says "the
            // tool returned cancellation" and the conversation goes off-rails.
            throw ce
        } catch (t: Throwable) {
            emitError(t, stepNumber, OnErrorEvent.ErrorSource.Tool, hooks, feed)
            // resolveToolInput throws typed AgentError; a raw executor
            // throw becomes ToolExecution (see toolFailure).
            toolFailure(call, t)
        }
        fireTelemetry(feed) {
            onToolCallFinish(
                it,
                OnToolCallFinishEvent(
                    toolCallId = call.toolCallId,
                    toolName = call.toolName,
                    outcome = when (result) {
                        is ToolExecutionResult.Success ->
                            OnToolCallFinishEvent.Outcome.Success(result.outputJson)
                        is ToolExecutionResult.Failure ->
                            OnToolCallFinishEvent.Outcome.Failure(
                                result.error.message ?: "tool failed",
                            )
                    },
                    stepNumber = stepNumber,
                ),
            )
        }
        return result
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
        tool.streamExecutor(ctx, input).collect { output ->
            if (hasOutput) {
                val outputJson = encodeToolOutput(tool, lastOutput)
                val output = toolResultOutputFromJson(outputJson)
                val predicateOptions = ToolPredicateOptions(
                    toolCallId = call.toolCallId,
                    messages = ctx.messages,
                    experimental_context = ctx.context,
                )
                val modelOutput = tool.toModelOutput(lastOutput, predicateOptions) ?: output
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
        val (tool, input, _) = resolveCall(toolDef, call, messages)
        return tool to input
    }

    /**
     * Resolve a call's input: a plain decode, then — on failure — a SINGLE repair
     * attempt via [experimental_repairToolCall]. Returns the resolved (tool, typed
     * input) plus whether repair was needed; throws a typed [AgentError]
     * ([AgentError.ToolCallRepairFailed] / [AgentError.InvalidToolInput]) when the
     * input cannot be decoded even after repair. A repaired call may re-route to a
     * different tool, so the returned tool is not necessarily [toolDef]. This is the
     * ONE place a tool call is decoded/repaired — both the approval-categorization
     * pass and [executeTool] consume its result, so repair runs at most once per call.
     */
    private suspend fun resolveCall(
        toolDef: Tool<*, *, TContext>,
        call: ContentPart.ToolCall,
        messages: List<ModelMessage>,
    ): Triple<Tool<*, *, TContext>, Any?, Boolean> {
        val plainError: Throwable = try {
            return Triple(toolDef, decodeToolInput(toolDef, call.input), false)
        } catch (ce: CancellationException) {
            throw ce
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            e
        }
        tryRepairToolInput(toolDef, call, plainError, messages)?.let { (repairedTool, repairedInput) ->
            return Triple(repairedTool, repairedInput, true)
        }
        // Repair absent or exhausted — surface a typed decode failure.
        throw if (experimental_repairToolCall != null) {
            AgentError.ToolCallRepairFailed(call.toolName, originalError = plainError, repairError = null)
        } else {
            AgentError.InvalidToolInput(call.toolName, call.input.toString(), plainError)
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
        feed: TelemetryFeed? = null,
    ) {
        val event = OnErrorEvent(t, stepNumber, source)
        try { onError?.invoke(event) } catch (ce: CancellationException) { throw ce } catch (_: Throwable) {}
        try { hooks?.onError?.invoke(event) } catch (ce: CancellationException) { throw ce } catch (_: Throwable) {}
        fireTelemetry(feed) { onError(it, event) }
    }

    /** One invocation's telemetry handle: the resolved integration + the call's correlation envelope. */
    private class TelemetryFeed(
        val tele: Telemetry,
        val call: TelemetryCall,
    )

    /**
     * Deliver one telemetry event, guarded: telemetry OBSERVES — an
     * integration throw is swallowed so it can never alter loop behavior
     * (CancellationException still propagates). No-op when no integration
     * is registered ([feed] null — the zero-overhead path).
     */
    private suspend fun fireTelemetry(
        feed: TelemetryFeed?,
        block: suspend Telemetry.(TelemetryCall) -> Unit,
    ) {
        if (feed == null) return
        try {
            feed.tele.block(feed.call)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Telemetry failures never reach the loop — but they leave a tell.
            logger.warn("telemetry integration '${feed.tele.name}' threw — event dropped", t)
        }
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

    /** One guarded lifecycle-hook body — run via [runHook], which reports failures instead of throwing. */
    private fun interface HookBody {
        public suspend fun run()
    }

    private suspend fun runHook(
        stepNumber: Int,
        feed: TelemetryFeed? = null,
        block: HookBody,
    ) {
        try {
            block.run()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val event = OnErrorEvent(t, stepNumber, OnErrorEvent.ErrorSource.Hook)
            try {
                onError?.invoke(event)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // reporting hook's own failure is best-effort
            }
            fireTelemetry(feed) { onError(it, event) }
        }
    }

    private fun hasSystemMessage(messages: List<ModelMessage>): Boolean =
        messages.any { it.role == MessageRole.System }

    private sealed interface ParallelToolSignal {
        class Progress(val event: StreamEvent) : ParallelToolSignal
        class Completed(val completion: OrderedToolCompletion) : ParallelToolSignal
    }

    private sealed interface OrderedToolCompletion {
        val index: Int

        class Executed(
            override val index: Int,
            val call: ContentPart.ToolCall,
            val result: ToolExecutionResult,
        ) : OrderedToolCompletion

        class Skipped(override val index: Int) : OrderedToolCompletion
    }

    private class ChannelToolEventCollector(
        private val signals: Channel<ParallelToolSignal>,
    ) : FlowCollector<StreamEvent> {
        override suspend fun emit(value: StreamEvent) {
            signals.send(ParallelToolSignal.Progress(value))
        }
    }

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
