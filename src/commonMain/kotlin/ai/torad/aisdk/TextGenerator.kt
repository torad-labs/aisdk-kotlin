@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.jvm.JvmOverloads

/** @since 0.3.0-beta01 */
public class TextGenerator @JvmOverloads constructor(
    private val model: LanguageModel,
    private val config: CallConfig = CallConfig(),
) {
    /** @since 0.3.0-beta01 */
    public fun generate(input: GenerationInput): Flow<GenerateTextResult<String>> = flow {
        emit(doGenerate(input, null) { it })
    }

    public fun <T> generate(input: GenerationInput, output: Output<T>): Flow<GenerateTextResult<T>> = flow {
        emit(doGenerate(input, output, output::decode))
    }

    /** @since 0.3.0-beta01 */
    public fun stream(input: GenerationInput): Flow<StreamEvent> =
        buildParams(input, null).let { params ->
            StreamOpenRetry.wrap(config.maxRetries) {
                model.stream(params)
            }
        }.let { CallTimeout.flow(it, config.timeout) }

    /** @since 0.3.0-beta01 */
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
        return GenerateTextResult(
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
            responseFormat(output?.let { o ->
                if (config.responseFormat == ResponseFormat.Text) o.toResponseFormat() else config.responseFormat
            } ?: config.responseFormat)
        }
}
