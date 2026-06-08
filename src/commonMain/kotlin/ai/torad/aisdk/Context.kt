package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

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
 * is honored by every provider implementation. Resolution chain inside
 * the agent loop is `StepSettings ?: AgentSettings ?: agent-default
 * ?: provider-default`.
 */
public data class AgentSettings<TContext>(
    val instructions: String? = null,
    val model: LanguageModel? = null,
    val tools: ToolSet<TContext>? = null,
    val activeTools: List<String>? = null,
    val providerOptions: Map<String, JsonElement>? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String>? = null,
    val seed: Int? = null,
    /**
     * Penalty for repeating already-emitted tokens (presence) and for
     * tokens proportional to their previous frequency (frequency).
     * Mirrors v6's `CallSettings` (per historical parity gap #3 +
     * Phase 4C #21). Each provider applies them differently; some
     * accept both, some only one, some neither. Null = provider
     * default.
     */
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val responseFormat: ResponseFormat? = null,
)

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
public data class StepSettings<TContext>(
    val model: LanguageModel? = null,
    val activeTools: List<String>? = null,
    val toolChoice: ToolChoice? = null,
    val messages: List<ModelMessage>? = null,
    val system: String? = null,
    val providerOptions: Map<String, JsonElement>? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String>? = null,
    val seed: Int? = null,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val responseFormat: ResponseFormat? = null,
    /**
     * Per-step typed-context override. Mirrors v6's
     * `prepareStep.experimental_context` (per historical parity gap #16).
     * When set, subsequent steps see this value as their
     * `ToolExecutionContext.context` — useful for mid-loop context evolution
     * (e.g., RAG augmentation after a tool result).
     */
    val experimental_context: TContext? = null,
)
