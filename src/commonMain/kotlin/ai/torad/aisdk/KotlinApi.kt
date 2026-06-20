package ai.torad.aisdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer

/** Marks AI SDK builder receivers so nested DSL blocks do not leak outer scopes. */
@DslMarker
public annotation class AiSdkDsl

public data class CallSettings(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
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

public data class TextGenerationRequest public constructor(
    val prompt: String? = null,
    val messages: List<ModelMessage> = emptyList(),
    val system: String? = null,
    val settings: CallSettings = CallSettings(),
) {
    public constructor(
        input: Input,
        system: String? = null,
        settings: CallSettings = CallSettings(),
    ) : this(
        prompt = input.prompt,
        messages = input.messages,
        system = system,
        settings = settings,
    )

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

    public sealed interface Input {
        public val prompt: String?
        public val messages: List<ModelMessage>

        public data class PromptText(
            public val text: String,
        ) : Input {
            override val prompt: String get() = text
            override val messages: List<ModelMessage> get() = emptyList()
        }

        public data class MessageHistory(
            public val history: NonEmptyMessages,
        ) : Input {
            override val prompt: String? get() = null
            override val messages: List<ModelMessage> get() = history.values
        }

        public data class MessageHistoryWithPrompt(
            public val history: NonEmptyMessages,
            override val prompt: String,
        ) : Input {
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
