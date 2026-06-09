package ai.torad.aisdk

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.KSerializer
import kotlin.coroutines.CoroutineContext

/**
 * Concrete [ToolLoopAgent] for the SDK's own tests. The base is `abstract` — extend it, never instantiate it —
 * so the tests reach the loop machinery through this faithful forwarder (same constructor surface + defaults).
 * `internal` + test-only: it exists solely so the SDK can test its own base without re-opening it to instantiation.
 */
internal class TestToolLoopAgent<TContext, TOutput>(
    model: LanguageModel,
    instructions: String,
    tools: ToolSet<TContext>,
    activeTools: List<String>? = null,
    output: Output<TOutput>? = null,
    stopWhen: StopCondition = stepCountIs(20),
    prepareCall: (suspend PrepareCallScope<TContext>.() -> AgentSettings<TContext>)? = null,
    prepareStep: (suspend PrepareStepScope<TContext>.() -> StepSettings<TContext>)? = null,
    callOptionsSchema: KSerializer<TContext>? = null,
    temperature: Float? = null,
    topP: Float? = null,
    topK: Int? = null,
    maxOutputTokens: Int? = null,
    stopSequences: List<String>? = null,
    seed: Int? = null,
    presencePenalty: Float? = null,
    frequencyPenalty: Float? = null,
    responseFormat: ResponseFormat = ResponseFormat.Text,
    maxParallelToolCalls: Int = Int.MAX_VALUE,
    onStart: (suspend OnStartEvent.() -> Unit)? = null,
    onStepStart: (suspend OnStepStartEvent.() -> Unit)? = null,
    onStepFinish: (suspend OnStepFinishEvent.() -> Unit)? = null,
    onFinish: (suspend OnFinishEvent.() -> Unit)? = null,
    onError: (suspend OnErrorEvent.() -> Unit)? = null,
    onChunk: (suspend OnChunkEvent.() -> Unit)? = null,
    onAbort: (suspend OnAbortEvent.() -> Unit)? = null,
    experimental_onToolCallStart: (suspend OnToolCallStartEvent.() -> Unit)? = null,
    experimental_onToolCallFinish: (suspend OnToolCallFinishEvent.() -> Unit)? = null,
    experimental_repairToolCall: ToolCallRepairFunction<TContext>? = null,
    engineContext: CoroutineContext = Dispatchers.Default,
) : ToolLoopAgent<TContext, TOutput>(
    model = model,
    instructions = instructions,
    tools = tools,
    activeTools = activeTools,
    output = output,
    stopWhen = stopWhen,
    prepareCall = prepareCall,
    prepareStep = prepareStep,
    callOptionsSchema = callOptionsSchema,
    temperature = temperature,
    topP = topP,
    topK = topK,
    maxOutputTokens = maxOutputTokens,
    stopSequences = stopSequences,
    seed = seed,
    presencePenalty = presencePenalty,
    frequencyPenalty = frequencyPenalty,
    responseFormat = responseFormat,
    maxParallelToolCalls = maxParallelToolCalls,
    onStart = onStart,
    onStepStart = onStepStart,
    onStepFinish = onStepFinish,
    onFinish = onFinish,
    onError = onError,
    onChunk = onChunk,
    onAbort = onAbort,
    experimental_onToolCallStart = experimental_onToolCallStart,
    experimental_onToolCallFinish = experimental_onToolCallFinish,
    experimental_repairToolCall = experimental_repairToolCall,
    engineContext = engineContext,
)
