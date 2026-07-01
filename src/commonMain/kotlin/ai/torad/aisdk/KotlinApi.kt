package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.time.Duration

/** Marks AI SDK builder receivers so nested DSL blocks do not leak outer scopes. */
@DslMarker
public annotation class AiSdkDsl

@Poko
/** @since 0.3.0-beta01 */
public class CallSettings internal constructor(
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
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal? = null,
    /** @since 0.3.0-beta01 */
    public val presencePenalty: Float? = null,
    /** @since 0.3.0-beta01 */
    public val frequencyPenalty: Float? = null,
    /** @since 0.3.0-beta01 */
    public val responseFormat: ResponseFormat? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    private val timeoutBox: Any? = null,
    /** @since 0.3.0-beta01 */
    public val maxRetries: Int = 2,
) {
    /**
     * Optional total timeout for a single high-level model call. For streaming calls this bounds
     * the full collection lifetime, not the idle gap between individual events.
     * @since 0.3.0-beta01
     */
    public val timeout: Duration?
        get() = timeoutBox as Duration?

    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(timeout == null || timeout?.isPositive() == true) {
            "timeout must be positive when set"
        }
    }
}

@AiSdkDsl
/** @since 0.3.0-beta01 */
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
    private var timeout: Duration? = null

    private var maxRetries: Int = 2

    private val stopSequences = mutableListOf<String>()
    private val headers = linkedMapOf<String, String>()

    /** @since 0.3.0-beta01 */
    public fun temperature(value: Float?): CallSettingsBuilder {
        temperature = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topP(value: Float?): CallSettingsBuilder {
        topP = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topK(value: Int?): CallSettingsBuilder {
        topK = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxOutputTokens(value: Int?): CallSettingsBuilder {
        maxOutputTokens = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun seed(value: Int?): CallSettingsBuilder {
        seed = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxRetries(value: Int): CallSettingsBuilder {
        maxRetries = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): CallSettingsBuilder {
        providerOptions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal?): CallSettingsBuilder {
        abortSignal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun presencePenalty(value: Float?): CallSettingsBuilder {
        presencePenalty = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun frequencyPenalty(value: Float?): CallSettingsBuilder {
        frequencyPenalty = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun responseFormat(value: ResponseFormat?): CallSettingsBuilder {
        responseFormat = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): CallSettingsBuilder {
        headers.clear()
        headers.putAll(value)
        return this
    }

    /**
     * Set a total timeout for one high-level call. For streaming calls this bounds the whole
     * stream collection, not the time between chunks.
     * @since 0.3.0-beta01
     */
    public fun timeout(value: Duration?): CallSettingsBuilder {
        timeout = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun stopSequence(value: String): CallSettingsBuilder {
        stopSequences += value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun stopSequences(values: Iterable<String>): CallSettingsBuilder {
        stopSequences += values
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOption(name: String, value: JsonElement): CallSettingsBuilder {
        providerOptions = providerOptions + ProviderOptions.Raw(JsonObject(mapOf(name to value)))
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(block: ProviderOptionsBuilder.() -> Unit): CallSettingsBuilder {
        providerOptions = providerOptions + ProviderOptions.Raw(JsonObject(ProviderOptionsDsl.build(block)))
        return this
    }

    /** @since 0.3.0-beta01 */
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
        headers = headers.toMap(),
        timeoutBox = timeout as Any?,
        maxRetries = maxRetries,
    )
}

/** @since 0.3.0-beta01 */
public fun CallSettings(block: CallSettingsBuilder.() -> Unit = {}): CallSettings =
    CallSettingsBuilder().apply(block).build()

@AiSdkDsl
/** @since 0.3.0-beta01 */
public class ProviderOptionsBuilder {
    private val values = linkedMapOf<String, JsonElement>()

    /** @since 0.3.0-beta01 */
    public fun put(name: String, value: JsonElement): ProviderOptionsBuilder {
        values[name] = value
        return this
    }

    public fun <T> put(name: String, value: T, serializer: KSerializer<T>): ProviderOptionsBuilder {
        values[name] = TypedJsonOps.encodeJsonElement(value, serializer)
        return this
    }

    public inline fun <reified T> put(name: String, value: T): ProviderOptionsBuilder {
        put(name, value, serializer())
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun provider(name: String, block: JsonObjectBuilder.() -> Unit): ProviderOptionsBuilder {
        values[name] = buildJsonObject(block)
        return this
    }

    public fun <T> provider(name: String, value: T, serializer: KSerializer<T>): ProviderOptionsBuilder {
        values[name] = TypedJsonOps.encodeJsonElement(value, serializer)
        return this
    }

    public inline fun <reified T> provider(name: String, value: T): ProviderOptionsBuilder {
        provider(name, value, serializer())
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun options(values: Map<String, JsonElement>): ProviderOptionsBuilder {
        this.values.putAll(values)
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): Map<String, JsonElement> = values.toMap()
}

/** @since 0.3.0-beta01 */
public object ProviderOptionsDsl {
    /** @since 0.3.0-beta01 */
    public fun build(block: ProviderOptionsBuilder.() -> Unit): Map<String, JsonElement> =
        ProviderOptionsBuilder().apply(block).build()
}

@Poko
/** @since 0.3.0-beta01 */
public class TextGenerationRequest internal constructor(
    /** @since 0.3.0-beta01 */
    public val prompt: String? = null,
    /** @since 0.3.0-beta01 */
    public val messages: List<ModelMessage> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val system: String? = null,
    /** @since 0.3.0-beta01 */
    public val settings: CallSettings = CallSettings(),
) {
    public companion object {
        /** @since 0.3.0-beta01 */
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

    /** @since 0.3.0-beta01 */
    public val input: Input
        get() = Input.from(prompt = prompt, messages = messages)

    /** @since 0.3.0-beta01 */
    public class NonEmptyMessages private constructor(
        /** @since 0.3.0-beta01 */
        public val values: List<ModelMessage>,
    ) {
        public companion object {
            /** @since 0.3.0-beta01 */
            public fun of(first: ModelMessage, vararg rest: ModelMessage): NonEmptyMessages =
                NonEmptyMessages(listOf(first) + rest)

            /** @since 0.3.0-beta01 */
            public fun from(messages: Iterable<ModelMessage>): NonEmptyMessages {
                val values = messages.toList()
                require(values.isNotEmpty()) { "messages must contain at least one message" }
                return NonEmptyMessages(values)
            }
        }
    }

    /** @since 0.3.0-beta01 */
    public sealed class Input {
        /** @since 0.3.0-beta01 */
        public abstract val prompt: String?
        /** @since 0.3.0-beta01 */
        public abstract val messages: List<ModelMessage>

        /** @since 0.3.0-beta01 */
        public data class PromptText(
            /** @since 0.3.0-beta01 */
            public val text: String,
        ) : Input() {
            override val prompt: String get() = text
            override val messages: List<ModelMessage> get() = emptyList()
        }

        /** @since 0.3.0-beta01 */
        public data class MessageHistory(
            /** @since 0.3.0-beta01 */
            public val history: NonEmptyMessages,
        ) : Input() {
            override val prompt: String? get() = null
            override val messages: List<ModelMessage> get() = history.values
        }

        /** @since 0.3.0-beta01 */
        public data class MessageHistoryWithPrompt(
            /** @since 0.3.0-beta01 */
            public val history: NonEmptyMessages,
            override val prompt: String,
        ) : Input() {
            override val messages: List<ModelMessage> get() = history.values
        }

        public companion object {
            /** @since 0.3.0-beta01 */
            public fun prompt(text: String): Input = PromptText(text)

            /** @since 0.3.0-beta01 */
            public fun messages(first: ModelMessage, vararg rest: ModelMessage): Input =
                MessageHistory(NonEmptyMessages.of(first, *rest))

            /** @since 0.3.0-beta01 */
            public fun messages(history: NonEmptyMessages): Input = MessageHistory(history)

            /** @since 0.3.0-beta01 */
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
/** @since 0.3.0-beta01 */
public class TextGenerationRequestBuilder {
    private var prompt: String? = null
    private var system: String? = null
    private var settings: CallSettings = CallSettings()

    private val messages = mutableListOf<ModelMessage>()

    /** @since 0.3.0-beta01 */
    public fun prompt(value: String): TextGenerationRequestBuilder {
        prompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun system(value: String): TextGenerationRequestBuilder {
        system = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun message(message: ModelMessage): TextGenerationRequestBuilder {
        messages += message
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun messages(values: Iterable<ModelMessage>): TextGenerationRequestBuilder {
        messages += values
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun settings(value: CallSettings): TextGenerationRequestBuilder {
        settings = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun settings(block: CallSettingsBuilder.() -> Unit): TextGenerationRequestBuilder {
        settings = CallSettings(block)
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): TextGenerationRequest = TextGenerationRequest(
        prompt = prompt,
        messages = messages.toList(),
        system = system,
        settings = settings,
    )
}

/** @since 0.3.0-beta01 */
public fun TextGenerationRequest(block: TextGenerationRequestBuilder.() -> Unit = {}): TextGenerationRequest =
    TextGenerationRequestBuilder().apply(block).build()
