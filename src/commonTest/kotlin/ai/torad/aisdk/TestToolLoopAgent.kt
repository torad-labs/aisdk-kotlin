package ai.torad.aisdk

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.KSerializer
import kotlin.coroutines.CoroutineContext

/**
 * Concrete [ToolLoopAgent] for the SDK's own tests. The base is `abstract` — extend it, never instantiate it —
 * so the tests reach the loop machinery through this faithful forwarder (same constructor surface + defaults).
 * `internal` + test-only: it exists solely so the SDK can test its own base without re-opening it to instantiation.
 */
@Suppress("LongParameterList")
internal class TestToolLoopAgent<TContext, TOutput>(
    model: LanguageModel,
    instructions: String,
    tools: ToolSet<TContext>,
    activeTools: List<String>? = null,
    output: Output<TOutput>? = null,
    stopWhen: StopCondition = StepCountIs(20),
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
    maxRetries: Int = 2,
    maxParallelToolCalls: Int = ToolExecutionPolicy.DEFAULT_MAX_PARALLEL_TOOL_CALLS,
    toolExecutionPolicy: ToolExecutionPolicy = ToolExecutionPolicy {
        maxParallelToolCalls(maxParallelToolCalls)
    },
    experimental_repairToolCall: ToolCallRepairFunction<TContext>? = null,
    experimental_toolApprovalSecret: ByteArray? = null,
    telemetry: TelemetrySettings? = null,
    logger: Logger = NoopLogger,
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
    maxRetries = maxRetries,
    maxParallelToolCalls = maxParallelToolCalls,
    toolExecutionPolicy = toolExecutionPolicy,
    experimental_repairToolCall = experimental_repairToolCall,
    experimental_toolApprovalSecret = experimental_toolApprovalSecret,
    telemetry = telemetry,
    logger = logger,
    engineContext = engineContext,
)
