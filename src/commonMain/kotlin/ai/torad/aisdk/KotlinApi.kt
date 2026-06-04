package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer

/**
 * Kotlin-first call settings shared by text generation entry points.
 *
 * The original v6-shaped functions keep their named parameters for parity.
 * Native Kotlin callers can use this grouped settings object to avoid
 * long nullable argument lists at call sites.
 */
/** Marks AI SDK builder receivers so nested DSL blocks don't leak outer scopes. */
@DslMarker
public annotation class AiSdkDsl

public data class CallSettings(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    // Null = unset. Sentinel defaults (emptyList / AbortSignalNever /
    // ResponseFormat.Text) could not be distinguished from an explicit
    // same-value override during merge, so these are nullable instead (§2.3).
    val stopSequences: List<String>? = null,
    val seed: Int? = null,
    val providerOptions: Map<String, JsonElement> = emptyMap(),
    val abortSignal: AbortSignal? = null,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val responseFormat: ResponseFormat? = null,
)

@AiSdkDsl
public class CallSettingsBuilder internal constructor() {
    public var temperature: Float? = null
    public var topP: Float? = null
    public var topK: Int? = null
    public var maxOutputTokens: Int? = null
    public var seed: Int? = null
    public var providerOptions: Map<String, JsonElement> = emptyMap()
    public var abortSignal: AbortSignal? = null
    public var presencePenalty: Float? = null
    public var frequencyPenalty: Float? = null
    public var responseFormat: ResponseFormat? = null

    private val stopSequences = mutableListOf<String>()

    public fun stopSequence(value: String) {
        stopSequences += value
    }

    public fun stopSequences(values: Iterable<String>) {
        stopSequences += values
    }

    public fun providerOption(name: String, value: JsonElement) {
        providerOptions = providerOptions + (name to value)
    }

    public fun providerOptions(block: ProviderOptionsBuilder.() -> Unit) {
        providerOptions = providerOptions + buildProviderOptions(block)
    }

    internal fun build(): CallSettings = CallSettings(
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxOutputTokens = maxOutputTokens,
        stopSequences = stopSequences.toList().ifEmpty { null },
        seed = seed,
        providerOptions = providerOptions,
        abortSignal = abortSignal,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        responseFormat = responseFormat,
    )
}

public fun callSettings(block: CallSettingsBuilder.() -> Unit): CallSettings =
    CallSettingsBuilder().apply(block).build()

@AiSdkDsl
public class ProviderOptionsBuilder internal constructor() {
    private val values = linkedMapOf<String, JsonElement>()

    public fun put(name: String, value: JsonElement) {
        values[name] = value
    }

    public fun <T> put(name: String, value: T, serializer: KSerializer<T>) {
        values[name] = encodeJsonElement(value, serializer)
    }

    public inline fun <reified T> put(name: String, value: T) {
        put(name, value, serializer())
    }

    public fun provider(name: String, block: JsonObjectBuilder.() -> Unit) {
        values[name] = buildJsonObject(block)
    }

    public fun <T> provider(name: String, value: T, serializer: KSerializer<T>) {
        values[name] = encodeJsonElement(value, serializer)
    }

    public inline fun <reified T> provider(name: String, value: T) {
        provider(name, value, serializer())
    }

    public fun options(values: Map<String, JsonElement>) {
        this.values.putAll(values)
    }

    internal fun build(): Map<String, JsonElement> = values.toMap()
}

public fun buildProviderOptions(block: ProviderOptionsBuilder.() -> Unit): Map<String, JsonElement> =
    ProviderOptionsBuilder().apply(block).build()

public data class TextGenerationRequest(
    val prompt: String? = null,
    val messages: List<ModelMessage> = emptyList(),
    val system: String? = null,
    val settings: CallSettings = CallSettings(),
)

@AiSdkDsl
public class TextGenerationRequestBuilder internal constructor() {
    public var prompt: String? = null
    public var system: String? = null
    public var settings: CallSettings = CallSettings()

    private val messages = mutableListOf<ModelMessage>()

    public fun prompt(value: String) {
        prompt = value
    }

    public fun system(value: String) {
        system = value
    }

    public fun message(message: ModelMessage) {
        messages += message
    }

    public fun messages(values: Iterable<ModelMessage>) {
        messages += values
    }

    public fun settings(value: CallSettings) {
        settings = value
    }

    public fun settings(block: CallSettingsBuilder.() -> Unit) {
        settings = callSettings(block)
    }

    internal fun build(): TextGenerationRequest = TextGenerationRequest(
        prompt = prompt,
        messages = messages.toList(),
        system = system,
        settings = settings,
    )
}

public fun textGenerationRequest(block: TextGenerationRequestBuilder.() -> Unit): TextGenerationRequest =
    TextGenerationRequestBuilder().apply(block).build()

public suspend fun generateText(
    model: LanguageModel,
    request: TextGenerationRequest,
): GenerateTextResult<String> = generateText(
    model = model,
    prompt = request.prompt,
    messages = request.messages,
    system = request.system,
    settings = request.settings,
)

public suspend fun generateText(
    model: LanguageModel,
    settings: CallSettings = CallSettings(),
    block: TextGenerationRequestBuilder.() -> Unit,
): GenerateTextResult<String> {
    val request = textGenerationRequest(block)
    return generateText(model = model, request = request.copy(settings = settings.merge(request.settings)))
}

public suspend fun <TOutput> generateText(
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

public suspend fun <TOutput> generateText(
    model: LanguageModel,
    output: Output<TOutput>,
    settings: CallSettings = CallSettings(),
    block: TextGenerationRequestBuilder.() -> Unit,
): GenerateTextResult<TOutput> {
    val request = textGenerationRequest(block)
    return generateText(model = model, output = output, request = request.copy(settings = settings.merge(request.settings)))
}

public suspend fun generateText(
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
    stopSequences = settings.stopSequences ?: emptyList(),
    seed = settings.seed,
    providerOptions = settings.providerOptions,
    abortSignal = settings.abortSignal ?: AbortSignalNever,
    presencePenalty = settings.presencePenalty,
    frequencyPenalty = settings.frequencyPenalty,
    responseFormat = settings.responseFormat ?: ResponseFormat.Text,
)

public suspend fun <TOutput> generateText(
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
    stopSequences = settings.stopSequences ?: emptyList(),
    seed = settings.seed,
    providerOptions = settings.providerOptions,
    abortSignal = settings.abortSignal ?: AbortSignalNever,
    presencePenalty = settings.presencePenalty,
    frequencyPenalty = settings.frequencyPenalty,
    responseFormat = settings.responseFormat ?: ResponseFormat.Text,
)

public fun streamText(
    model: LanguageModel,
    request: TextGenerationRequest,
): Flow<StreamEvent> = streamTextResult(model = model, request = request).fullStream

public fun streamText(
    model: LanguageModel,
    settings: CallSettings = CallSettings(),
    block: TextGenerationRequestBuilder.() -> Unit,
): Flow<StreamEvent> {
    val request = textGenerationRequest(block)
    return streamText(model = model, request = request.copy(settings = settings.merge(request.settings)))
}

public fun streamTextResult(
    model: LanguageModel,
    request: TextGenerationRequest,
): StreamTextResult = streamTextResult(
    model = model,
    prompt = request.prompt,
    messages = request.messages,
    system = request.system,
    settings = request.settings,
)

public fun streamTextResult(
    model: LanguageModel,
    settings: CallSettings = CallSettings(),
    block: TextGenerationRequestBuilder.() -> Unit,
): StreamTextResult {
    val request = textGenerationRequest(block)
    return streamTextResult(model = model, request = request.copy(settings = settings.merge(request.settings)))
}

public fun streamTextResult(
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
    stopSequences = settings.stopSequences ?: emptyList(),
    seed = settings.seed,
    providerOptions = settings.providerOptions,
    abortSignal = settings.abortSignal ?: AbortSignalNever,
    presencePenalty = settings.presencePenalty,
    frequencyPenalty = settings.frequencyPenalty,
    responseFormat = settings.responseFormat ?: ResponseFormat.Text,
)

private fun CallSettings.merge(other: CallSettings): CallSettings = copy(
    temperature = other.temperature ?: temperature,
    topP = other.topP ?: topP,
    topK = other.topK ?: topK,
    maxOutputTokens = other.maxOutputTokens ?: maxOutputTokens,
    stopSequences = other.stopSequences ?: stopSequences,
    seed = other.seed ?: seed,
    providerOptions = providerOptions + other.providerOptions,
    abortSignal = other.abortSignal ?: abortSignal,
    presencePenalty = other.presencePenalty ?: presencePenalty,
    frequencyPenalty = other.frequencyPenalty ?: frequencyPenalty,
    responseFormat = other.responseFormat ?: responseFormat,
)
