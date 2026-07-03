@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.jvm.JvmOverloads

/**
 * High-level text generation facade for a single [LanguageModel].
 *
 * `TextGenerator` turns [GenerationInput] plus optional [Output] decoding into
 * cold Flow-based calls. Use [generate] for one-shot responses and collect the
 * returned flow with `.first()`. Use [stream] or [streamResult] when the caller
 * wants incremental [StreamEvent] values or stream metadata.
 *
 * Streaming has no default deadline. Set [CallConfig.timeout] or configure the
 * underlying HTTP/on-device engine socket timeout so a silent SSE or local
 * engine stream cannot wait forever.
 * @since 0.3.0-beta01
 */
public class TextGenerator @JvmOverloads constructor(
    private val model: LanguageModel,
    private val config: CallConfig = CallConfig(),
) {
    /**
     * Cold flow: no model call starts until collected. Each collection reruns
     * the generation from the beginning, so provider calls may incur cost or
     * billing again. One-shot callers should collect exactly one value,
     * usually with `.first()`.
     * @since 0.3.0-beta01
     */
    public fun generate(input: GenerationInput): Flow<GenerateTextResult<String>> = flow {
        emit(doGenerate(input, null) { it })
    }

    /**
     * Cold flow: no model call starts until collected. Each collection reruns
     * the generation from the beginning, including structured-output decoding,
     * so provider calls may incur cost or billing again. One-shot callers
     * should collect exactly one value, usually with `.first()`.
     * @since 0.3.0-beta01
     */
    public fun <T> generate(input: GenerationInput, output: Output<T>): Flow<GenerateTextResult<T>> = flow {
        emit(doGenerate(input, output, output::decode))
    }

    /**
     * Starts a text stream when collected.
     *
     * The returned flow is cold. Each collection opens a fresh provider stream
     * and can incur cost again. Streaming has no default deadline; set
     * [CallConfig.timeout] on this generator or configure the provider engine's
     * socket/read timeout.
     * @since 0.3.0-beta01
     */
    public fun stream(input: GenerationInput): Flow<StreamEvent> =
        buildParams(input, null).let { params ->
            StreamOpenRetry.wrap(config.maxRetries) {
                model.stream(params)
            }
        }.let { CallTimeout.flow(it, config.timeout) }

    /**
     * Creates a replayable stream result with request/response metadata.
     *
     * The result is cold until one of its flows is collected. Each abandoned
     * pre-terminal run can be restarted by a later collector. Streaming has no
     * default deadline; set [CallConfig.timeout] here or configure the
     * underlying engine/client timeout.
     * @since 0.3.0-beta01
     */
    public fun streamResult(input: GenerationInput): StreamTextResult {
        val params = buildParams(input, null)
        val result = model.streamResult(params)
        return StreamTextResult(
            sourceStream = CallTimeout.flow(
                StreamOpenRetry.wrap(config.maxRetries) {
                    result.stream
                },
                config.timeout,
            ),
            request = result.request,
            initialResponse = result.response,
        )
    }

    private suspend fun <T> doGenerate(
        input: GenerationInput,
        output: Output<T>?,
        decode: (String) -> T,
    ): GenerateTextResult<T> {
        val params = buildParams(input, output)
        val raw = CallTimeout.run(config.timeout) {
            RetryPolicy {
                maxRetries(config.maxRetries)
            }.execute {
                model.generate(params)
            }
        }
        return ResultConstruction.generateTextResult(
            output = decode(raw.text),
            text = raw.text,
            toolCalls = raw.toolCalls,
            finishReason = raw.finishReason,
            usage = raw.usage,
            content = raw.content,
            reasoning = raw.content.filterIsInstance<ContentPart.Reasoning>(),
            files = raw.content.filterIsInstance<ContentPart.File>(),
            sources = raw.content.filterIsInstance<ContentPart.Source>(),
            totalUsage = raw.usage,
            warnings = raw.warnings,
            request = raw.request,
            response = raw.response,
            providerMetadata = raw.providerMetadata,
            rawFinishReason = raw.rawFinishReason,
        )
    }

    private fun buildParams(input: GenerationInput, output: Output<*>?): LanguageModelCallParams =
        LanguageModelCallParams {
            messages(input.toMessages(null))
            temperature(config.temperature)
            topP(config.topP)
            topK(config.topK)
            maxOutputTokens(config.maxOutputTokens)
            stopSequences(config.stopSequences)
            seed(config.seed)
            providerOptions(config.providerOptions)
            abortSignal(config.abortSignal)
            presencePenalty(config.presencePenalty)
            frequencyPenalty(config.frequencyPenalty)
            headers(config.headers)
            responseFormat(
                output?.let { o ->
                    if (config.responseFormat == ResponseFormat.Text) o.toResponseFormat() else config.responseFormat
                } ?: config.responseFormat
            )
        }
}
