package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * Kotlin-first call settings shared by text generation entry points.
 *
 * The original v6-shaped functions keep their named parameters for parity.
 * Native Kotlin callers can use this grouped settings object to avoid
 * long nullable argument lists at call sites.
 */
data class CallSettings(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val seed: Int? = null,
    val providerOptions: Map<String, JsonElement> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val responseFormat: ResponseFormat = ResponseFormat.Text,
)

class CallSettingsBuilder internal constructor() {
    var temperature: Float? = null
    var topP: Float? = null
    var topK: Int? = null
    var maxOutputTokens: Int? = null
    var seed: Int? = null
    var providerOptions: Map<String, JsonElement> = emptyMap()
    var abortSignal: AbortSignal = AbortSignalNever
    var presencePenalty: Float? = null
    var frequencyPenalty: Float? = null
    var responseFormat: ResponseFormat = ResponseFormat.Text

    private val stopSequences = mutableListOf<String>()

    fun stopSequence(value: String) {
        stopSequences += value
    }

    fun stopSequences(values: Iterable<String>) {
        stopSequences += values
    }

    fun providerOption(name: String, value: JsonElement) {
        providerOptions = providerOptions + (name to value)
    }

    internal fun build(): CallSettings = CallSettings(
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxOutputTokens = maxOutputTokens,
        stopSequences = stopSequences.toList(),
        seed = seed,
        providerOptions = providerOptions,
        abortSignal = abortSignal,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        responseFormat = responseFormat,
    )
}

fun callSettings(block: CallSettingsBuilder.() -> Unit): CallSettings =
    CallSettingsBuilder().apply(block).build()

data class TextGenerationRequest(
    val prompt: String? = null,
    val messages: List<ModelMessage> = emptyList(),
    val system: String? = null,
    val settings: CallSettings = CallSettings(),
)

class TextGenerationRequestBuilder internal constructor() {
    var prompt: String? = null
    var system: String? = null
    var settings: CallSettings = CallSettings()

    private val messages = mutableListOf<ModelMessage>()

    fun prompt(value: String) {
        prompt = value
    }

    fun system(value: String) {
        system = value
    }

    fun message(message: ModelMessage) {
        messages += message
    }

    fun messages(values: Iterable<ModelMessage>) {
        messages += values
    }

    fun settings(value: CallSettings) {
        settings = value
    }

    fun settings(block: CallSettingsBuilder.() -> Unit) {
        settings = callSettings(block)
    }

    internal fun build(): TextGenerationRequest = TextGenerationRequest(
        prompt = prompt,
        messages = messages.toList(),
        system = system,
        settings = settings,
    )
}

fun textGenerationRequest(block: TextGenerationRequestBuilder.() -> Unit): TextGenerationRequest =
    TextGenerationRequestBuilder().apply(block).build()

suspend fun generateText(
    model: LanguageModel,
    request: TextGenerationRequest,
): GenerateTextResult<String> = generateText(
    model = model,
    prompt = request.prompt,
    messages = request.messages,
    system = request.system,
    settings = request.settings,
)

suspend fun generateText(
    model: LanguageModel,
    settings: CallSettings = CallSettings(),
    block: TextGenerationRequestBuilder.() -> Unit,
): GenerateTextResult<String> {
    val request = textGenerationRequest(block)
    return generateText(model = model, request = request.copy(settings = settings.merge(request.settings)))
}

suspend fun <TOutput> generateText(
    model: LanguageModel,
    output: Output<TOutput>,
    request: TextGenerationRequest,
): GenerateTextResult<TOutput> = generateText(
    model = model,
    prompt = request.prompt,
    messages = request.messages,
    system = request.system,
    output = output,
    settings = request.settings,
)

suspend fun <TOutput> generateText(
    model: LanguageModel,
    output: Output<TOutput>,
    settings: CallSettings = CallSettings(),
    block: TextGenerationRequestBuilder.() -> Unit,
): GenerateTextResult<TOutput> {
    val request = textGenerationRequest(block)
    return generateText(model = model, output = output, request = request.copy(settings = settings.merge(request.settings)))
}

suspend fun generateText(
    model: LanguageModel,
    prompt: String? = null,
    messages: List<ModelMessage> = emptyList(),
    system: String? = null,
    settings: CallSettings,
): GenerateTextResult<String> = generateText(
    model = model,
    prompt = prompt,
    messages = messages,
    system = system,
    temperature = settings.temperature,
    topP = settings.topP,
    topK = settings.topK,
    maxOutputTokens = settings.maxOutputTokens,
    stopSequences = settings.stopSequences,
    seed = settings.seed,
    providerOptions = settings.providerOptions,
    abortSignal = settings.abortSignal,
    presencePenalty = settings.presencePenalty,
    frequencyPenalty = settings.frequencyPenalty,
    responseFormat = settings.responseFormat,
)

suspend fun <TOutput> generateText(
    model: LanguageModel,
    prompt: String? = null,
    messages: List<ModelMessage> = emptyList(),
    system: String? = null,
    output: Output<TOutput>,
    settings: CallSettings,
): GenerateTextResult<TOutput> = generateText(
    model = model,
    prompt = prompt,
    messages = messages,
    system = system,
    output = output,
    temperature = settings.temperature,
    topP = settings.topP,
    topK = settings.topK,
    maxOutputTokens = settings.maxOutputTokens,
    stopSequences = settings.stopSequences,
    seed = settings.seed,
    providerOptions = settings.providerOptions,
    abortSignal = settings.abortSignal,
    presencePenalty = settings.presencePenalty,
    frequencyPenalty = settings.frequencyPenalty,
    responseFormat = settings.responseFormat,
)

fun streamText(
    model: LanguageModel,
    request: TextGenerationRequest,
): Flow<StreamEvent> = streamTextResult(model = model, request = request).fullStream

fun streamText(
    model: LanguageModel,
    settings: CallSettings = CallSettings(),
    block: TextGenerationRequestBuilder.() -> Unit,
): Flow<StreamEvent> {
    val request = textGenerationRequest(block)
    return streamText(model = model, request = request.copy(settings = settings.merge(request.settings)))
}

fun streamTextResult(
    model: LanguageModel,
    request: TextGenerationRequest,
): StreamTextResult = streamTextResult(
    model = model,
    prompt = request.prompt,
    messages = request.messages,
    system = request.system,
    settings = request.settings,
)

fun streamTextResult(
    model: LanguageModel,
    settings: CallSettings = CallSettings(),
    block: TextGenerationRequestBuilder.() -> Unit,
): StreamTextResult {
    val request = textGenerationRequest(block)
    return streamTextResult(model = model, request = request.copy(settings = settings.merge(request.settings)))
}

fun streamTextResult(
    model: LanguageModel,
    prompt: String? = null,
    messages: List<ModelMessage> = emptyList(),
    system: String? = null,
    settings: CallSettings,
): StreamTextResult = streamTextResult(
    model = model,
    prompt = prompt,
    messages = messages,
    system = system,
    temperature = settings.temperature,
    topP = settings.topP,
    topK = settings.topK,
    maxOutputTokens = settings.maxOutputTokens,
    stopSequences = settings.stopSequences,
    seed = settings.seed,
    providerOptions = settings.providerOptions,
    abortSignal = settings.abortSignal,
    presencePenalty = settings.presencePenalty,
    frequencyPenalty = settings.frequencyPenalty,
    responseFormat = settings.responseFormat,
)

private fun CallSettings.merge(other: CallSettings): CallSettings = copy(
    temperature = other.temperature ?: temperature,
    topP = other.topP ?: topP,
    topK = other.topK ?: topK,
    maxOutputTokens = other.maxOutputTokens ?: maxOutputTokens,
    stopSequences = other.stopSequences.ifEmpty { stopSequences },
    seed = other.seed ?: seed,
    providerOptions = providerOptions + other.providerOptions,
    abortSignal = if (other.abortSignal === AbortSignalNever) abortSignal else other.abortSignal,
    presencePenalty = other.presencePenalty ?: presencePenalty,
    frequencyPenalty = other.frequencyPenalty ?: frequencyPenalty,
    responseFormat = if (other.responseFormat == ResponseFormat.Text) responseFormat else other.responseFormat,
)
