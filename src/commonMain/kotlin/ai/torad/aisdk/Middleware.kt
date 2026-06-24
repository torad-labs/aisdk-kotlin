package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Provider middleware. Per invariants I-9 and R-11, provider differences
 * (Anthropic-specific options, OpenAI-specific options, on-device-only
 * sampling knobs) live in middleware — NEVER in agent or orchestration
 * code. If you find yourself branching on `model.modelId.startsWith("...")`
 * inside an agent, that's a missing middleware.
 *
 * Other middleware uses: logging, telemetry, retry on transient failure,
 * default-provider-options injection, request/response transformation.
 *
 * Mirrors v6's `LanguageModelV3Middleware` shape: each hook receives a
 * [MiddlewareCallContext] exposing both `doGenerate` AND `doStream` so
 * a stream-wrapping middleware can synthesize a stream out of the
 * downstream `generate` result (which is exactly what
 * `ai.torad.aisdk.middleware.simulateStreamingMiddleware` needs and
 * couldn't do under the prior `(params, next)` shape that only exposed
 * the same-direction call).
 */
public interface LanguageModelMiddleware {
    /**
     * Transform the call params before [wrapGenerate]/[wrapStream] run, for the
     * given [operation]. The transformed params flow into both the wrap hooks and
     * the downstream call. This is the params-only seam — a middleware can implement
     * just this (e.g. `ai.torad.aisdk.middleware.defaultSettingsMiddleware`).
     * Default: pass through. Mirrors v6's `transformParams({ type, params, model })`.
     */
    public suspend fun transformParams(
        operation: MiddlewareOperation,
        params: LanguageModelCallParams,
        model: LanguageModel,
    ): LanguageModelCallParams = params

    /** Wrap the one-shot generate call. Default: pass through. */
    public suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult =
        context.doGenerate(context.params)

    /** Wrap the streaming call. Default: pass through. */
    public fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> =
        context.doStream(context.params)

    /** Override the model id the wrapper presents (null = keep the wrapped model's). */
    public fun overrideModelId(model: LanguageModel): String? = null

    /** Override the provider name the wrapper presents (null = keep the wrapped model's). */
    public fun overrideProvider(model: LanguageModel): String? = null

    /** Override the supported-URL patterns the wrapper presents (null = keep the wrapped model's). */
    public fun overrideSupportedUrls(model: LanguageModel): Map<String, List<String>>? = null
}

/** Which operation a [LanguageModelMiddleware.transformParams] call is transforming. */
public enum class MiddlewareOperation { Generate, Stream }

/**
 * What a middleware receives. Contains:
 *
 * - [params]: the in-going call params, exactly as the upstream invoker
 *   constructed them. Middleware may modify and pass a new copy to
 *   [doGenerate] / [doStream].
 * - [model]: the wrapped [LanguageModel] (i.e., this middleware's
 *   enclosing wrapper). Exposed for metadata reads such as
 *   `model.modelId`. **Do NOT call `model.generate(p)` /
 *   `model.stream(p)` from inside a middleware — that re-enters the
 *   chain from the top and infinite-loops. Use [doGenerate] / [doStream]
 *   instead, which targets the rest of the chain past this middleware.**
 * - [doGenerate]: invoke the downstream chain (the next middleware's
 *   `wrapGenerate`, or the underlying model's `generate` if this is the
 *   innermost middleware).
 * - [doStream]: invoke the downstream chain on the streaming axis.
 *
 * Both `doGenerate` and `doStream` reference the SAME downstream depth
 * — so a middleware's `wrapStream` calling `context.doGenerate(...)`
 * skips its own `wrapGenerate` and invokes the rest of the chain's
 * generate path. This is the load-bearing property that lets
 * `simulateStreamingMiddleware` work.
 */
public data class MiddlewareCallContext(
    val params: LanguageModelCallParams,
    val model: LanguageModel,
    val doGenerate: suspend (LanguageModelCallParams) -> LanguageModelResult,
    val doStream: (LanguageModelCallParams) -> Flow<StreamEvent>,
)

/**
 * Compose [middlewares] over [model] into a new [LanguageModel]. Order:
 * the first middleware in the list is the outermost wrapper — `wrapGenerate`
 * runs first on the way in, last on the way out (innermost in the
 * call stack, like Express middleware).
 */
public fun WrapLanguageModel(
    model: LanguageModel,
    middlewares: List<LanguageModelMiddleware>,
): LanguageModel {
    if (middlewares.isEmpty()) return model
    return WrappedLanguageModel(model, middlewares)
}

private class WrappedLanguageModel(
    private val inner: LanguageModel,
    middlewares: List<LanguageModelMiddleware>,
) : LanguageModel {
    // Identity overrides: the first middleware (outermost) that supplies one wins.
    override val modelId: String = middlewares.firstNotNullOfOrNull { it.overrideModelId(inner) } ?: inner.modelId
    override val provider: String = middlewares.firstNotNullOfOrNull { it.overrideProvider(inner) } ?: inner.provider
    override val supportedUrls: Map<String, List<String>> =
        middlewares.firstNotNullOfOrNull { it.overrideSupportedUrls(inner) } ?: inner.supportedUrls

    private val chainGenerate: suspend (LanguageModelCallParams) -> LanguageModelResult
    private val chainStream: (LanguageModelCallParams) -> Flow<StreamEvent>

    init {
        val (gen, stream) = buildChains(middlewares)
        chainGenerate = gen
        chainStream = stream
    }

    // Build both chains bottom-up so each middleware sees `doGenerate`
    // and `doStream` pointing at the rest of the chain past itself
    // (not at this middleware's own wrappers). Each middleware's
    // transformParams runs first (for its axis), then its wrap hook.
    private fun buildChains(
        middlewares: List<LanguageModelMiddleware>,
    ): Pair<suspend (LanguageModelCallParams) -> LanguageModelResult, (LanguageModelCallParams) -> Flow<StreamEvent>> {
        var doGenerate: suspend (LanguageModelCallParams) -> LanguageModelResult = inner::generate
        var doStream: (LanguageModelCallParams) -> Flow<StreamEvent> = inner::stream
        for (mw in middlewares.asReversed()) {
            val downstreamGen = doGenerate
            val downstreamStream = doStream
            val outerGen: suspend (LanguageModelCallParams) -> LanguageModelResult = { p ->
                val tp = mw.transformParams(MiddlewareOperation.Generate, p, this)
                mw.wrapGenerate(
                    MiddlewareCallContext(
                        params = tp,
                        model = this,
                        doGenerate = downstreamGen,
                        doStream = downstreamStream,
                    ),
                )
            }
            val outerStream: (LanguageModelCallParams) -> Flow<StreamEvent> = { p ->
                // transformParams is suspend; run it inside the cold flow the
                // wrapper returns, before delegating (keeps wrapStream non-suspend).
                flow {
                    val tp = mw.transformParams(MiddlewareOperation.Stream, p, this@WrappedLanguageModel)
                    emitAll(
                        mw.wrapStream(
                            MiddlewareCallContext(
                                params = tp,
                                model = this@WrappedLanguageModel,
                                doGenerate = downstreamGen,
                                doStream = downstreamStream,
                            ),
                        ),
                    )
                }
            }
            doGenerate = outerGen
            doStream = outerStream
        }
        return doGenerate to doStream
    }

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        chainGenerate(params)

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        chainStream(params)
}
