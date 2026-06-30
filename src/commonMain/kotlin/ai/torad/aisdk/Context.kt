package ai.torad.aisdk

/**
 * Context propagated through the agent loop (invariant I-5). The agent
 * gets defaults at construction; per-invocation overrides come from
 * [PrepareCallScope] (runs once before the loop); per-step overrides come
 * from [PrepareStepScope] (runs before every step). Call sites take only
 * `prompt`, `options`, `abortSignal`, `timeout` — never config.
 */

/**
 * Scope for `prepareCall`, run once before the loop starts. Useful for
 * RAG context injection, per-user customization, database lookups that
 * shouldn't repeat per step.
 */
public class PrepareCallScope<TContext>(
    public val options: TContext?,
    public val instructions: String,
    public val model: LanguageModel,
    public val tools: ToolSet<TContext>,
)

/**
 * Returned from `prepareCall`. Any non-null field overrides the agent
 * default for this invocation only.
 *
 * Sampler params (`temperature`, `topP`, `topK`, `maxOutputTokens`,
 * `stopSequences`, `seed`) mirror Vercel AI SDK v6's `CallSettings` —
 * same vocabulary that already exists on [LanguageModelCallParams] and
 * is honored by every provider implementation. `maxRetries` is resolved
 * on the same chain and applied by the agent around each non-streaming
 * model round-trip. Resolution chain inside the agent loop is
 * `StepSettings ?: AgentSettings ?: agent-default ?: provider-default`.
 */
public class AgentSettings<TContext> internal constructor(
    public val instructions: String? = null,
    public val model: LanguageModel? = null,
    public val tools: ToolSet<TContext>? = null,
    public val activeTools: List<String>? = null,
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    public val temperature: Float? = null,
    public val topP: Float? = null,
    public val topK: Int? = null,
    public val maxOutputTokens: Int? = null,
    public val stopSequences: List<String>? = null,
    public val seed: Int? = null,
    /**
     * Penalty for repeating already-emitted tokens (presence) and for
     * tokens proportional to their previous frequency (frequency).
     * Mirrors v6's `CallSettings` (per historical parity gap #3 +
     * Phase 4C #21). Each provider applies them differently; some
     * accept both, some only one, some neither. Null = provider
     * default.
     */
    public val presencePenalty: Float? = null,
    public val frequencyPenalty: Float? = null,
    public val responseFormat: ResponseFormat? = null,
    public val maxRetries: Int? = null,
) {
    init {
        maxRetries?.let { require(it >= 0) { "maxRetries must be >= 0" } }
    }
}

@AiSdkDsl
public class AgentSettingsBuilder<TContext> internal constructor() {
    private var instructions: String? = null
    private var model: LanguageModel? = null
    private var tools: ToolSet<TContext>? = null
    private var activeTools: List<String>? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var temperature: Float? = null
    private var topP: Float? = null
    private var topK: Int? = null
    private var maxOutputTokens: Int? = null
    private var stopSequences: List<String>? = null
    private var seed: Int? = null
    private var presencePenalty: Float? = null
    private var frequencyPenalty: Float? = null
    private var responseFormat: ResponseFormat? = null
    private var maxRetries: Int? = null

    public fun instructions(value: String?) {
        instructions = value
    }

    public fun model(value: LanguageModel?) {
        model = value
    }

    public fun tools(value: ToolSet<TContext>?) {
        tools = value
    }

    public fun activeTools(value: List<String>?) {
        activeTools = value
    }

    public fun providerOptions(value: ProviderOptions) {
        providerOptions = value
    }

    public fun temperature(value: Float?) {
        temperature = value
    }

    public fun topP(value: Float?) {
        topP = value
    }

    public fun topK(value: Int?) {
        topK = value
    }

    public fun maxOutputTokens(value: Int?) {
        maxOutputTokens = value
    }

    public fun stopSequences(value: List<String>?) {
        stopSequences = value
    }

    public fun seed(value: Int?) {
        seed = value
    }

    public fun presencePenalty(value: Float?) {
        presencePenalty = value
    }

    public fun frequencyPenalty(value: Float?) {
        frequencyPenalty = value
    }

    public fun responseFormat(value: ResponseFormat?) {
        responseFormat = value
    }

    public fun maxRetries(value: Int?) {
        maxRetries = value
    }

    internal fun build(): AgentSettings<TContext> =
        AgentSettings(
            instructions = instructions,
            model = model,
            tools = tools,
            activeTools = activeTools,
            providerOptions = providerOptions,
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
        )
}

public fun <TContext> AgentSettings(
    block: AgentSettingsBuilder<TContext>.() -> Unit = {},
): AgentSettings<TContext> =
    AgentSettingsBuilder<TContext>().apply(block).build()

/**
 * Scope for `prepareStep`, run before every step in the loop. Used for:
 * model routing per step (cheap model for step 1 + 2, expensive for step
 * 3), tool gating per step (only the search tool is callable on step 1),
 * dynamic system prompts, message compression.
 */
public class PrepareStepScope<TContext>(
    public val stepNumber: Int,
    public val model: LanguageModel,
    public val steps: List<StepResult>,
    public val messages: List<ModelMessage>,
    public val context: TContext?,
)

/**
 * Returned from `prepareStep`. Any non-null field overrides the agent
 * default / `prepareCall` value for this step only.
 *
 * Sampler params here override the per-invocation values from
 * [AgentSettings] for one step — same vocabulary as v6's
 * `tool-loop-agent-settings.ts`'s `prepareStep` return. Useful when
 * (say) step 1 is a deterministic plan (`temperature = 0`) and the
 * following synthesis steps are warmer.
 */
public class StepSettings<TContext> internal constructor(
    public val model: LanguageModel? = null,
    public val activeTools: List<String>? = null,
    public val toolChoice: ToolChoice? = null,
    public val messages: List<ModelMessage>? = null,
    public val system: String? = null,
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    public val temperature: Float? = null,
    public val topP: Float? = null,
    public val topK: Int? = null,
    public val maxOutputTokens: Int? = null,
    public val stopSequences: List<String>? = null,
    public val seed: Int? = null,
    public val presencePenalty: Float? = null,
    public val frequencyPenalty: Float? = null,
    public val responseFormat: ResponseFormat? = null,
    public val maxRetries: Int? = null,
    /**
     * Per-step typed-context override. Mirrors v6's
     * `prepareStep.experimental_context` (per historical parity gap #16).
     * When set, subsequent steps see this value as their
     * `ToolExecutionContext.context` — useful for mid-loop context evolution
     * (e.g., RAG augmentation after a tool result).
     */
    public val experimental_context: TContext? = null,
) {
    init {
        maxRetries?.let { require(it >= 0) { "maxRetries must be >= 0" } }
    }
}

@AiSdkDsl
public class StepSettingsBuilder<TContext> internal constructor() {
    private var model: LanguageModel? = null
    private var activeTools: List<String>? = null
    private var toolChoice: ToolChoice? = null
    private var messages: List<ModelMessage>? = null
    private var system: String? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var temperature: Float? = null
    private var topP: Float? = null
    private var topK: Int? = null
    private var maxOutputTokens: Int? = null
    private var stopSequences: List<String>? = null
    private var seed: Int? = null
    private var presencePenalty: Float? = null
    private var frequencyPenalty: Float? = null
    private var responseFormat: ResponseFormat? = null
    private var maxRetries: Int? = null
    private var experimental_context: TContext? = null

    public fun model(value: LanguageModel?) {
        model = value
    }

    public fun activeTools(value: List<String>?) {
        activeTools = value
    }

    public fun toolChoice(value: ToolChoice?) {
        toolChoice = value
    }

    public fun messages(value: List<ModelMessage>?) {
        messages = value
    }

    public fun system(value: String?) {
        system = value
    }

    public fun providerOptions(value: ProviderOptions) {
        providerOptions = value
    }

    public fun temperature(value: Float?) {
        temperature = value
    }

    public fun topP(value: Float?) {
        topP = value
    }

    public fun topK(value: Int?) {
        topK = value
    }

    public fun maxOutputTokens(value: Int?) {
        maxOutputTokens = value
    }

    public fun stopSequences(value: List<String>?) {
        stopSequences = value
    }

    public fun seed(value: Int?) {
        seed = value
    }

    public fun presencePenalty(value: Float?) {
        presencePenalty = value
    }

    public fun frequencyPenalty(value: Float?) {
        frequencyPenalty = value
    }

    public fun responseFormat(value: ResponseFormat?) {
        responseFormat = value
    }

    public fun maxRetries(value: Int?) {
        maxRetries = value
    }

    public fun experimental_context(value: TContext?) {
        experimental_context = value
    }

    internal fun build(): StepSettings<TContext> =
        StepSettings(
            model = model,
            activeTools = activeTools,
            toolChoice = toolChoice,
            messages = messages,
            system = system,
            providerOptions = providerOptions,
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
            experimental_context = experimental_context,
        )
}

public fun <TContext> StepSettings(
    block: StepSettingsBuilder<TContext>.() -> Unit = {},
): StepSettings<TContext> =
    StepSettingsBuilder<TContext>().apply(block).build()
