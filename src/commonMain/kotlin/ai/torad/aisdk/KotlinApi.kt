package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer

/** Marks AI SDK builder receivers so nested DSL blocks do not leak outer scopes. */
@DslMarker
public annotation class AiSdkDsl

@Poko
public class CallSettings internal constructor(
    public val temperature: Float? = null,
    public val topP: Float? = null,
    public val topK: Int? = null,
    public val maxOutputTokens: Int? = null,
    public val stopSequences: List<String>? = null,
    public val seed: Int? = null,
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    public val abortSignal: AbortSignal? = null,
    public val presencePenalty: Float? = null,
    public val frequencyPenalty: Float? = null,
    public val responseFormat: ResponseFormat? = null,
    public val maxRetries: Int = 2,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
    }
}

@AiSdkDsl
public class CallSettingsBuilder {
    private var temperature: Float? = null
    private var topP: Float? = null
    private var topK: Int? = null
    private var maxOutputTokens: Int? = null
    private var seed: Int? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var abortSignal: AbortSignal? = null
    private var presencePenalty: Float? = null
    private var frequencyPenalty: Float? = null
    private var responseFormat: ResponseFormat? = null

    private var maxRetries: Int = 2

    private val stopSequences = mutableListOf<String>()

    public fun temperature(value: Float?): CallSettingsBuilder {
        temperature = value
        return this
    }

    public fun topP(value: Float?): CallSettingsBuilder {
        topP = value
        return this
    }

    public fun topK(value: Int?): CallSettingsBuilder {
        topK = value
        return this
    }

    public fun maxOutputTokens(value: Int?): CallSettingsBuilder {
        maxOutputTokens = value
        return this
    }

    public fun seed(value: Int?): CallSettingsBuilder {
        seed = value
        return this
    }

    public fun maxRetries(value: Int): CallSettingsBuilder {
        maxRetries = value
        return this
    }

    public fun providerOptions(value: ProviderOptions): CallSettingsBuilder {
        providerOptions = value
        return this
    }

    public fun abortSignal(value: AbortSignal?): CallSettingsBuilder {
        abortSignal = value
        return this
    }

    public fun presencePenalty(value: Float?): CallSettingsBuilder {
        presencePenalty = value
        return this
    }

    public fun frequencyPenalty(value: Float?): CallSettingsBuilder {
        frequencyPenalty = value
        return this
    }

    public fun responseFormat(value: ResponseFormat?): CallSettingsBuilder {
        responseFormat = value
        return this
    }

    public fun stopSequence(value: String): CallSettingsBuilder {
        stopSequences += value
        return this
    }

    public fun stopSequences(values: Iterable<String>): CallSettingsBuilder {
        stopSequences += values
        return this
    }

    public fun providerOption(name: String, value: JsonElement): CallSettingsBuilder {
        providerOptions = providerOptions + ProviderOptions.Raw(JsonObject(mapOf(name to value)))
        return this
    }

    public fun providerOptions(block: ProviderOptionsBuilder.() -> Unit): CallSettingsBuilder {
        providerOptions = providerOptions + ProviderOptions.Raw(JsonObject(ProviderOptionsDsl.build(block)))
        return this
    }

    public fun build(): CallSettings = CallSettings(
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
        maxRetries = maxRetries,
    )
}

public fun CallSettings(block: CallSettingsBuilder.() -> Unit = {}): CallSettings =
    CallSettingsBuilder().apply(block).build()

@AiSdkDsl
public class ProviderOptionsBuilder internal constructor() {
    private val values = linkedMapOf<String, JsonElement>()

    public fun put(name: String, value: JsonElement) {
        values[name] = value
    }

    public fun <T> put(name: String, value: T, serializer: KSerializer<T>) {
        values[name] = TypedJsonOps.encodeJsonElement(value, serializer)
    }

    public inline fun <reified T> put(name: String, value: T) {
        put(name, value, serializer())
    }

    public fun provider(name: String, block: JsonObjectBuilder.() -> Unit) {
        values[name] = buildJsonObject(block)
    }

    public fun <T> provider(name: String, value: T, serializer: KSerializer<T>) {
        values[name] = TypedJsonOps.encodeJsonElement(value, serializer)
    }

    public inline fun <reified T> provider(name: String, value: T) {
        provider(name, value, serializer())
    }

    public fun options(values: Map<String, JsonElement>) {
        this.values.putAll(values)
    }

    internal fun build(): Map<String, JsonElement> = values.toMap()
}

public object ProviderOptionsDsl {
    public fun build(block: ProviderOptionsBuilder.() -> Unit): Map<String, JsonElement> =
        ProviderOptionsBuilder().apply(block).build()
}

@Poko
public class TextGenerationRequest internal constructor(
    public val prompt: String? = null,
    public val messages: List<ModelMessage> = emptyList(),
    public val system: String? = null,
    public val settings: CallSettings = CallSettings(),
) {
    public companion object {
        public fun of(
            input: Input,
            system: String? = null,
            settings: CallSettings = CallSettings(),
        ): TextGenerationRequest = TextGenerationRequest(
            prompt = input.prompt,
            messages = input.messages,
            system = system,
            settings = settings,
        )
    }

    public val input: Input
        get() = Input.from(prompt = prompt, messages = messages)

    public class NonEmptyMessages private constructor(
        public val values: List<ModelMessage>,
    ) {
        public companion object {
            public fun of(first: ModelMessage, vararg rest: ModelMessage): NonEmptyMessages =
                NonEmptyMessages(listOf(first) + rest)

            public fun from(messages: Iterable<ModelMessage>): NonEmptyMessages {
                val values = messages.toList()
                require(values.isNotEmpty()) { "messages must contain at least one message" }
                return NonEmptyMessages(values)
            }
        }
    }

    public sealed class Input {
        public abstract val prompt: String?
        public abstract val messages: List<ModelMessage>

        public data class PromptText(
            public val text: String,
        ) : Input() {
            override val prompt: String get() = text
            override val messages: List<ModelMessage> get() = emptyList()
        }

        public data class MessageHistory(
            public val history: NonEmptyMessages,
        ) : Input() {
            override val prompt: String? get() = null
            override val messages: List<ModelMessage> get() = history.values
        }

        public data class MessageHistoryWithPrompt(
            public val history: NonEmptyMessages,
            override val prompt: String,
        ) : Input() {
            override val messages: List<ModelMessage> get() = history.values
        }

        public companion object {
            public fun prompt(text: String): Input = PromptText(text)

            public fun messages(first: ModelMessage, vararg rest: ModelMessage): Input =
                MessageHistory(NonEmptyMessages.of(first, *rest))

            public fun messages(history: NonEmptyMessages): Input = MessageHistory(history)

            public fun messagesWithPrompt(
                history: NonEmptyMessages,
                prompt: String,
            ): Input = MessageHistoryWithPrompt(history = history, prompt = prompt)

            internal fun from(
                prompt: String?,
                messages: List<ModelMessage>,
            ): Input =
                when {
                    prompt != null && messages.isNotEmpty() ->
                        MessageHistoryWithPrompt(NonEmptyMessages.from(messages), prompt)
                    prompt != null -> PromptText(prompt)
                    messages.isNotEmpty() -> MessageHistory(NonEmptyMessages.from(messages))
                    else -> throw IllegalArgumentException(
                        "TextGenerationRequest requires prompt text or non-empty messages"
                    )
                }
        }
    }
}

@AiSdkDsl
public class TextGenerationRequestBuilder internal constructor() {
    private var prompt: String? = null
    private var system: String? = null
    private var settings: CallSettings = CallSettings()

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
        settings = CallSettings(block)
    }

    internal fun build(): TextGenerationRequest = TextGenerationRequest(
        prompt = prompt,
        messages = messages.toList(),
        system = system,
        settings = settings,
    )
}

public fun TextGenerationRequest(block: TextGenerationRequestBuilder.() -> Unit = {}): TextGenerationRequest =
    TextGenerationRequestBuilder().apply(block).build()
