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
 * @since 0.3.0-beta01
 */
public class PrepareCallScope<TContext>(
    /** @since 0.3.0-beta01 */
    public val options: TContext?,
    /** @since 0.3.0-beta01 */
    public val instructions: String,
    /** @since 0.3.0-beta01 */
    public val model: LanguageModel,
    /** @since 0.3.0-beta01 */
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
 * @since 0.3.0-beta01
 */
public class AgentSettings<TContext> internal constructor(
    /** @since 0.3.0-beta01 */
    public val instructions: String? = null,
    /** @since 0.3.0-beta01 */
    public val model: LanguageModel? = null,
    /** @since 0.3.0-beta01 */
    public val tools: ToolSet<TContext>? = null,
    /** @since 0.3.0-beta01 */
    public val activeTools: List<String>? = null,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val temperature: Float? = null,
    /** @since 0.3.0-beta01 */
    public val topP: Float? = null,
    /** @since 0.3.0-beta01 */
    public val topK: Int? = null,
    /** @since 0.3.0-beta01 */
    public val maxOutputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val stopSequences: List<String>? = null,
    /** @since 0.3.0-beta01 */
    public val seed: Int? = null,
    /**
     * Penalty for repeating already-emitted tokens (presence) and for
     * tokens proportional to their previous frequency (frequency).
     * Mirrors v6's `CallSettings` (per historical parity gap #3 +
     * Phase 4C #21). Each provider applies them differently; some
     * accept both, some only one, some neither. Null = provider
     * default.
     * @since 0.3.0-beta01
     */
    public val presencePenalty: Float? = null,
    /** @since 0.3.0-beta01 */
    public val frequencyPenalty: Float? = null,
    /** @since 0.3.0-beta01 */
    public val responseFormat: ResponseFormat? = null,
    /** @since 0.3.0-beta01 */
    public val maxRetries: Int? = null,
) {
    init {
        maxRetries?.let { require(it >= 0) { "maxRetries must be >= 0" } }
    }
}

@AiSdkDsl
/** @since 0.3.0-beta01 */
public class AgentSettingsBuilder<TContext> {
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

    /** @since 0.3.0-beta01 */
    public fun instructions(value: String?): AgentSettingsBuilder<TContext> {
        instructions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun model(value: LanguageModel?): AgentSettingsBuilder<TContext> {
        model = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun tools(value: ToolSet<TContext>?): AgentSettingsBuilder<TContext> {
        tools = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun activeTools(value: List<String>?): AgentSettingsBuilder<TContext> {
        activeTools = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): AgentSettingsBuilder<TContext> {
        providerOptions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun temperature(value: Float?): AgentSettingsBuilder<TContext> {
        temperature = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topP(value: Float?): AgentSettingsBuilder<TContext> {
        topP = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topK(value: Int?): AgentSettingsBuilder<TContext> {
        topK = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxOutputTokens(value: Int?): AgentSettingsBuilder<TContext> {
        maxOutputTokens = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun stopSequences(value: List<String>?): AgentSettingsBuilder<TContext> {
        stopSequences = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun seed(value: Int?): AgentSettingsBuilder<TContext> {
        seed = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun presencePenalty(value: Float?): AgentSettingsBuilder<TContext> {
        presencePenalty = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun frequencyPenalty(value: Float?): AgentSettingsBuilder<TContext> {
        frequencyPenalty = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun responseFormat(value: ResponseFormat?): AgentSettingsBuilder<TContext> {
        responseFormat = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxRetries(value: Int?): AgentSettingsBuilder<TContext> {
        maxRetries = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AgentSettings<TContext> =
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
 * @since 0.3.0-beta01
 */
public class PrepareStepScope<TContext>(
    /** @since 0.3.0-beta01 */
    public val stepNumber: Int,
    /** @since 0.3.0-beta01 */
    public val model: LanguageModel,
    /** @since 0.3.0-beta01 */
    public val steps: List<StepResult>,
    /** @since 0.3.0-beta01 */
    public val messages: List<ModelMessage>,
    /** @since 0.3.0-beta01 */
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
 * @since 0.3.0-beta01
 */
public class StepSettings<TContext> internal constructor(
    /** @since 0.3.0-beta01 */
    public val model: LanguageModel? = null,
    /** @since 0.3.0-beta01 */
    public val activeTools: List<String>? = null,
    /** @since 0.3.0-beta01 */
    public val toolChoice: ToolChoice? = null,
    /** @since 0.3.0-beta01 */
    public val messages: List<ModelMessage>? = null,
    /** @since 0.3.0-beta01 */
    public val system: String? = null,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val temperature: Float? = null,
    /** @since 0.3.0-beta01 */
    public val topP: Float? = null,
    /** @since 0.3.0-beta01 */
    public val topK: Int? = null,
    /** @since 0.3.0-beta01 */
    public val maxOutputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val stopSequences: List<String>? = null,
    /** @since 0.3.0-beta01 */
    public val seed: Int? = null,
    /** @since 0.3.0-beta01 */
    public val presencePenalty: Float? = null,
    /** @since 0.3.0-beta01 */
    public val frequencyPenalty: Float? = null,
    /** @since 0.3.0-beta01 */
    public val responseFormat: ResponseFormat? = null,
    /** @since 0.3.0-beta01 */
    public val maxRetries: Int? = null,
    /**
     * Per-step typed-context override. Mirrors v6's
     * `prepareStep.experimental_context` (per historical parity gap #16).
     * When set, subsequent steps see this value as their
     * `ToolExecutionContext.context` — useful for mid-loop context evolution
     * (e.g., RAG augmentation after a tool result).
     * @since 0.3.0-beta01
     */
    public val experimental_context: TContext? = null,
) {
    init {
        maxRetries?.let { require(it >= 0) { "maxRetries must be >= 0" } }
    }
}

@AiSdkDsl
/** @since 0.3.0-beta01 */
public class StepSettingsBuilder<TContext> {
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

    /** @since 0.3.0-beta01 */
    public fun model(value: LanguageModel?): StepSettingsBuilder<TContext> {
        model = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun activeTools(value: List<String>?): StepSettingsBuilder<TContext> {
        activeTools = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun toolChoice(value: ToolChoice?): StepSettingsBuilder<TContext> {
        toolChoice = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun messages(value: List<ModelMessage>?): StepSettingsBuilder<TContext> {
        messages = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun system(value: String?): StepSettingsBuilder<TContext> {
        system = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): StepSettingsBuilder<TContext> {
        providerOptions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun temperature(value: Float?): StepSettingsBuilder<TContext> {
        temperature = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topP(value: Float?): StepSettingsBuilder<TContext> {
        topP = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topK(value: Int?): StepSettingsBuilder<TContext> {
        topK = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxOutputTokens(value: Int?): StepSettingsBuilder<TContext> {
        maxOutputTokens = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun stopSequences(value: List<String>?): StepSettingsBuilder<TContext> {
        stopSequences = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun seed(value: Int?): StepSettingsBuilder<TContext> {
        seed = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun presencePenalty(value: Float?): StepSettingsBuilder<TContext> {
        presencePenalty = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun frequencyPenalty(value: Float?): StepSettingsBuilder<TContext> {
        frequencyPenalty = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun responseFormat(value: ResponseFormat?): StepSettingsBuilder<TContext> {
        responseFormat = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxRetries(value: Int?): StepSettingsBuilder<TContext> {
        maxRetries = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun experimental_context(value: TContext?): StepSettingsBuilder<TContext> {
        experimental_context = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): StepSettings<TContext> =
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
