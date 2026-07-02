@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)
@file:Suppress("FunctionNaming")

package ai.torad.aisdk.providers

import ai.torad.aisdk.CallWarning
import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.IdGenerator
import ai.torad.aisdk.LanguageModel
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.LanguageModelStreamResult
import ai.torad.aisdk.LanguageModelTool
import ai.torad.aisdk.ProviderMetadata
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.Usage
import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public typealias LiteRTProviderOptions = JsonObject

/** @since 0.3.0-beta01 */
public enum class LiteRTStreamTextMode {
    Delta,
    Cumulative,
}

/** @since 0.3.0-beta01 */
public enum class LiteRTMessageRole {
    System,
    User,
    Model,
    Tool,
}

private const val LITERT_CUMULATIVE_RECOVERY_TYPE = "non-prefix-cumulative-snapshot"
private const val LITERT_CUMULATIVE_RECOVERY_MESSAGE =
    "LiteRT cumulative stream snapshot rewrote previously emitted text; " +
        "emitted only an append-only best-effort suffix."

/** @since 0.3.0-beta01 */
public class LiteRTBytes(bytes: ByteArray) {
    private val value: ByteArray = bytes.copyOf()

    /** @since 0.3.0-beta01 */
    public fun toByteArray(): ByteArray = value.copyOf()
}

@Poko
/** @since 0.3.0-beta01 */
public class LiteRTSamplerConfig internal constructor(
    /** @since 0.3.0-beta01 */
    public val topK: Int,
    /** @since 0.3.0-beta01 */
    public val topP: Double,
    /** @since 0.3.0-beta01 */
    public val temperature: Double,
    /** @since 0.3.0-beta01 */
    public val seed: Int = 0,
) {
    public companion object {
        /** @since 0.3.0-beta01 */
        public val Default: LiteRTSamplerConfig = LiteRTSamplerConfig(
            topK = 40,
            topP = 0.95,
            temperature = 1.0,
        )
    }
}

/** @since 0.3.0-beta01 */
public class LiteRTSamplerConfigBuilder {
    private var topK: Int? = null
    private var topP: Double? = null
    private var temperature: Double? = null
    private var seed: Int = 0

    /** @since 0.3.0-beta01 */
    public fun topK(value: Int): LiteRTSamplerConfigBuilder {
        topK = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topP(value: Double): LiteRTSamplerConfigBuilder {
        topP = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun temperature(value: Double): LiteRTSamplerConfigBuilder {
        temperature = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun seed(value: Int): LiteRTSamplerConfigBuilder {
        seed = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LiteRTSamplerConfig =
        LiteRTSamplerConfig(
            topK = topK ?: LiteRTSamplerConfig.Default.topK,
            topP = topP ?: LiteRTSamplerConfig.Default.topP,
            temperature = temperature ?: LiteRTSamplerConfig.Default.temperature,
            seed = seed,
        )
}

/** @since 0.3.0-beta01 */
public fun LiteRTSamplerConfig(
    block: LiteRTSamplerConfigBuilder.() -> Unit = {},
): LiteRTSamplerConfig =
    LiteRTSamplerConfigBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class LiteRTChannel internal constructor(
    /** @since 0.3.0-beta01 */
    public val channelName: String,
    /** @since 0.3.0-beta01 */
    public val start: String,
    /** @since 0.3.0-beta01 */
    public val end: String,
)

/** @since 0.3.0-beta01 */
public class LiteRTChannelBuilder {
    private var channelName: String? = null
    private var start: String? = null
    private var end: String? = null

    /** @since 0.3.0-beta01 */
    public fun channelName(value: String): LiteRTChannelBuilder {
        channelName = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun start(value: String): LiteRTChannelBuilder {
        start = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun end(value: String): LiteRTChannelBuilder {
        end = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LiteRTChannel =
        LiteRTChannel(
            channelName = requireNotNull(channelName) { "LiteRTChannel.channelName is required" },
            start = requireNotNull(start) { "LiteRTChannel.start is required" },
            end = requireNotNull(end) { "LiteRTChannel.end is required" },
        )
}

/** @since 0.3.0-beta01 */
public fun LiteRTChannel(
    block: LiteRTChannelBuilder.() -> Unit,
): LiteRTChannel =
    LiteRTChannelBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public sealed class LiteRTContent {
    @Poko
    /** @since 0.3.0-beta01 */
    public class Text internal constructor(
        /** @since 0.3.0-beta01 */
        public val text: String,
    ) : LiteRTContent() {
        /** @since 0.3.0-beta01 */
        public companion object {
            /** @since 0.3.0-beta01 */
            public operator fun invoke(text: String): Text = Text(text)

            /** @since 0.3.0-beta01 */
            public operator fun invoke(block: Builder.() -> Unit): Text =
                Builder().apply(block).build()
        }

        /** @since 0.3.0-beta01 */
        public class Builder {
            private var text: String? = null

            /** @since 0.3.0-beta01 */
            public fun text(value: String): Builder {
                text = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun build(): Text =
                Text(requireNotNull(text) { "LiteRTContent.Text.text is required" })
        }
    }

    @Poko
    /** @since 0.3.0-beta01 */
    public class ImageBytes internal constructor(
        /** @since 0.3.0-beta01 */
        public val bytes: LiteRTBytes,
        /** @since 0.3.0-beta01 */
        public val mediaType: String? = null,
    ) : LiteRTContent() {
        /** @since 0.3.0-beta01 */
        public companion object {
            /** @since 0.3.0-beta01 */
            public operator fun invoke(bytes: LiteRTBytes, mediaType: String? = null): ImageBytes =
                ImageBytes(bytes, mediaType)

            /** @since 0.3.0-beta01 */
            public operator fun invoke(block: Builder.() -> Unit): ImageBytes =
                Builder().apply(block).build()
        }

        /** @since 0.3.0-beta01 */
        public class Builder {
            private var bytes: LiteRTBytes? = null
            private var mediaType: String? = null

            /** @since 0.3.0-beta01 */
            public fun bytes(value: LiteRTBytes): Builder {
                bytes = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun mediaType(value: String?): Builder {
                mediaType = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun build(): ImageBytes =
                ImageBytes(
                    bytes = requireNotNull(bytes) { "LiteRTContent.ImageBytes.bytes is required" },
                    mediaType = mediaType,
                )
        }
    }

    @Poko
    /** @since 0.3.0-beta01 */
    public class ImageFile internal constructor(
        /** @since 0.3.0-beta01 */
        public val absolutePath: String,
        /** @since 0.3.0-beta01 */
        public val mediaType: String? = null,
    ) : LiteRTContent() {
        /** @since 0.3.0-beta01 */
        public companion object {
            /** @since 0.3.0-beta01 */
            public operator fun invoke(absolutePath: String, mediaType: String? = null): ImageFile =
                ImageFile(absolutePath, mediaType)

            /** @since 0.3.0-beta01 */
            public operator fun invoke(block: Builder.() -> Unit): ImageFile =
                Builder().apply(block).build()
        }

        /** @since 0.3.0-beta01 */
        public class Builder {
            private var absolutePath: String? = null
            private var mediaType: String? = null

            /** @since 0.3.0-beta01 */
            public fun absolutePath(value: String): Builder {
                absolutePath = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun mediaType(value: String?): Builder {
                mediaType = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun build(): ImageFile =
                ImageFile(
                    absolutePath = requireNotNull(absolutePath) { "LiteRTContent.ImageFile.absolutePath is required" },
                    mediaType = mediaType,
                )
        }
    }

    @Poko
    /** @since 0.3.0-beta01 */
    public class AudioBytes internal constructor(
        /** @since 0.3.0-beta01 */
        public val bytes: LiteRTBytes,
        /** @since 0.3.0-beta01 */
        public val mediaType: String? = null,
    ) : LiteRTContent() {
        /** @since 0.3.0-beta01 */
        public companion object {
            /** @since 0.3.0-beta01 */
            public operator fun invoke(bytes: LiteRTBytes, mediaType: String? = null): AudioBytes =
                AudioBytes(bytes, mediaType)

            /** @since 0.3.0-beta01 */
            public operator fun invoke(block: Builder.() -> Unit): AudioBytes =
                Builder().apply(block).build()
        }

        /** @since 0.3.0-beta01 */
        public class Builder {
            private var bytes: LiteRTBytes? = null
            private var mediaType: String? = null

            /** @since 0.3.0-beta01 */
            public fun bytes(value: LiteRTBytes): Builder {
                bytes = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun mediaType(value: String?): Builder {
                mediaType = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun build(): AudioBytes =
                AudioBytes(
                    bytes = requireNotNull(bytes) { "LiteRTContent.AudioBytes.bytes is required" },
                    mediaType = mediaType,
                )
        }
    }

    @Poko
    /** @since 0.3.0-beta01 */
    public class AudioFile internal constructor(
        /** @since 0.3.0-beta01 */
        public val absolutePath: String,
        /** @since 0.3.0-beta01 */
        public val mediaType: String? = null,
    ) : LiteRTContent() {
        /** @since 0.3.0-beta01 */
        public companion object {
            /** @since 0.3.0-beta01 */
            public operator fun invoke(absolutePath: String, mediaType: String? = null): AudioFile =
                AudioFile(absolutePath, mediaType)

            /** @since 0.3.0-beta01 */
            public operator fun invoke(block: Builder.() -> Unit): AudioFile =
                Builder().apply(block).build()
        }

        /** @since 0.3.0-beta01 */
        public class Builder {
            private var absolutePath: String? = null
            private var mediaType: String? = null

            /** @since 0.3.0-beta01 */
            public fun absolutePath(value: String): Builder {
                absolutePath = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun mediaType(value: String?): Builder {
                mediaType = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun build(): AudioFile =
                AudioFile(
                    absolutePath = requireNotNull(absolutePath) { "LiteRTContent.AudioFile.absolutePath is required" },
                    mediaType = mediaType,
                )
        }
    }

    /**
     * Tool response content sent back to LiteRT-LM.
     *
     * LiteRT's bridge correlates tool responses by [name] only; this type does
     * not carry a tool-call id. Adapters that allow multiple simultaneous calls
     * to the same tool name must preserve/disambiguate that correlation outside
     * this content value.
     * @since 0.3.0-beta01
     */
    @Poko
    public class ToolResponse internal constructor(
        /** @since 0.3.0-beta01 */
        public val name: String,
        /** @since 0.3.0-beta01 */
        public val response: JsonElement,
    ) : LiteRTContent() {
        /** @since 0.3.0-beta01 */
        public companion object {
            /** @since 0.3.0-beta01 */
            public operator fun invoke(name: String, response: JsonElement): ToolResponse =
                ToolResponse(name, response)

            /** @since 0.3.0-beta01 */
            public operator fun invoke(block: Builder.() -> Unit): ToolResponse =
                Builder().apply(block).build()
        }

        /** @since 0.3.0-beta01 */
        public class Builder {
            private var name: String? = null
            private var response: JsonElement? = null

            /** @since 0.3.0-beta01 */
            public fun name(value: String): Builder {
                name = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun response(value: JsonElement): Builder {
                response = value
                return this
            }

            /** @since 0.3.0-beta01 */
            public fun build(): ToolResponse =
                ToolResponse(
                    name = requireNotNull(name) { "LiteRTContent.ToolResponse.name is required" },
                    response = requireNotNull(response) { "LiteRTContent.ToolResponse.response is required" },
                )
        }
    }
}

@Poko
/** @since 0.3.0-beta01 */
public class LiteRTToolCall internal constructor(
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val arguments: JsonElement = JsonObject(emptyMap()),
    /** @since 0.3.0-beta01 */
    public val id: String? = null,
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

/** @since 0.3.0-beta01 */
public class LiteRTToolCallBuilder {
    private var name: String? = null
    private var arguments: JsonElement = JsonObject(emptyMap())
    private var id: String? = null
    private var providerMetadata: ProviderMetadata = ProviderMetadata.None

    /** @since 0.3.0-beta01 */
    public fun name(value: String): LiteRTToolCallBuilder {
        name = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun arguments(value: JsonElement): LiteRTToolCallBuilder {
        arguments = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun id(value: String?): LiteRTToolCallBuilder {
        id = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerMetadata(value: ProviderMetadata): LiteRTToolCallBuilder {
        providerMetadata = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LiteRTToolCall =
        LiteRTToolCall(
            name = requireNotNull(name) { "LiteRTToolCall.name is required" },
            arguments = arguments,
            id = id,
            providerMetadata = providerMetadata,
        )
}

/** @since 0.3.0-beta01 */
public fun LiteRTToolCall(
    block: LiteRTToolCallBuilder.() -> Unit,
): LiteRTToolCall =
    LiteRTToolCallBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class LiteRTMessage internal constructor(
    /** @since 0.3.0-beta01 */
    public val role: LiteRTMessageRole,
    /** @since 0.3.0-beta01 */
    public val content: List<LiteRTContent> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val toolCalls: List<LiteRTToolCall> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val channels: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

/** @since 0.3.0-beta01 */
public class LiteRTMessageBuilder {
    private var role: LiteRTMessageRole? = null
    private var content: List<LiteRTContent> = emptyList()
    private var toolCalls: List<LiteRTToolCall> = emptyList()
    private var channels: Map<String, String> = emptyMap()
    private var providerMetadata: ProviderMetadata = ProviderMetadata.None

    /** @since 0.3.0-beta01 */
    public fun role(value: LiteRTMessageRole): LiteRTMessageBuilder {
        role = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun content(value: List<LiteRTContent>): LiteRTMessageBuilder {
        content = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun toolCalls(value: List<LiteRTToolCall>): LiteRTMessageBuilder {
        toolCalls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun channels(value: Map<String, String>): LiteRTMessageBuilder {
        channels = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerMetadata(value: ProviderMetadata): LiteRTMessageBuilder {
        providerMetadata = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LiteRTMessage =
        LiteRTMessage(
            role = requireNotNull(role) { "LiteRTMessage.role is required" },
            content = content,
            toolCalls = toolCalls,
            channels = channels,
            providerMetadata = providerMetadata,
        )
}

/** @since 0.3.0-beta01 */
public fun LiteRTMessage(
    block: LiteRTMessageBuilder.() -> Unit,
): LiteRTMessage =
    LiteRTMessageBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
@Suppress("LongParameterList")
public class LiteRTConversationRequest internal constructor(
    /** @since 0.3.0-beta01 */
    public val systemInstruction: List<LiteRTContent> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val initialMessages: List<LiteRTMessage> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val message: LiteRTMessage,
    /** @since 0.3.0-beta01 */
    public val tools: List<LanguageModelTool> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val samplerConfig: LiteRTSamplerConfig? = null,
    /** @since 0.3.0-beta01 */
    public val automaticToolCalling: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val channels: List<LiteRTChannel>? = null,
    /** @since 0.3.0-beta01 */
    public val extraContext: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val callParams: LanguageModelCallParams,
)

/** @since 0.3.0-beta01 */
public class LiteRTConversationRequestBuilder {
    private var systemInstruction: List<LiteRTContent> = emptyList()
    private var initialMessages: List<LiteRTMessage> = emptyList()
    private var message: LiteRTMessage? = null
    private var tools: List<LanguageModelTool> = emptyList()
    private var samplerConfig: LiteRTSamplerConfig? = null
    private var automaticToolCalling: Boolean = false
    private var channels: List<LiteRTChannel>? = null
    private var extraContext: Map<String, JsonElement> = emptyMap()
    private var warnings: List<CallWarning> = emptyList()
    private var callParams: LanguageModelCallParams? = null

    /** @since 0.3.0-beta01 */
    public fun systemInstruction(value: List<LiteRTContent>): LiteRTConversationRequestBuilder {
        systemInstruction = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun initialMessages(value: List<LiteRTMessage>): LiteRTConversationRequestBuilder {
        initialMessages = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun message(value: LiteRTMessage): LiteRTConversationRequestBuilder {
        message = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun tools(value: List<LanguageModelTool>): LiteRTConversationRequestBuilder {
        tools = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun samplerConfig(value: LiteRTSamplerConfig?): LiteRTConversationRequestBuilder {
        samplerConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun automaticToolCalling(value: Boolean): LiteRTConversationRequestBuilder {
        automaticToolCalling = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun channels(value: List<LiteRTChannel>?): LiteRTConversationRequestBuilder {
        channels = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun extraContext(value: Map<String, JsonElement>): LiteRTConversationRequestBuilder {
        extraContext = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun warnings(value: List<CallWarning>): LiteRTConversationRequestBuilder {
        warnings = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun callParams(value: LanguageModelCallParams): LiteRTConversationRequestBuilder {
        callParams = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LiteRTConversationRequest =
        LiteRTConversationRequest(
            systemInstruction = systemInstruction,
            initialMessages = initialMessages,
            message = requireNotNull(message) { "LiteRTConversationRequest.message is required" },
            tools = tools,
            samplerConfig = samplerConfig,
            automaticToolCalling = automaticToolCalling,
            channels = channels,
            extraContext = extraContext,
            warnings = warnings,
            callParams = requireNotNull(callParams) { "LiteRTConversationRequest.callParams is required" },
        )
}

/** @since 0.3.0-beta01 */
public fun LiteRTConversationRequest(
    block: LiteRTConversationRequestBuilder.() -> Unit = {},
): LiteRTConversationRequest =
    LiteRTConversationRequestBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public fun interface LiteRTConversationFactory {
    public suspend fun create(request: LiteRTConversationRequest): LiteRTConversation
}

/** @since 0.3.0-beta01 */
public interface LiteRTConversation {
    /** @since 0.3.0-beta01 */
    public suspend fun send(message: LiteRTMessage, extraContext: Map<String, JsonElement> = emptyMap()): LiteRTMessage

    /** @since 0.3.0-beta01 */
    public fun stream(message: LiteRTMessage, extraContext: Map<String, JsonElement> = emptyMap()): Flow<LiteRTMessage>

    /**
     * Cancel in-flight generation. The default is a no-op for simple engines;
     * implementations that can abort on-device work must override this so
     * [LanguageModelCallParams.abortSignal] stops generation promptly.
     * @since 0.3.0-beta01
     */
    public fun cancel(): Unit = Unit

    /**
     * Release engine resources owned by this conversation. The default is a
     * no-op for stateless engines; implementations that allocate sessions,
     * handles, or native resources must override it.
     * @since 0.3.0-beta01
     */
    public fun close(): Unit = Unit
}

/** @since 0.3.0-beta01 */
@Suppress("LongParameterList")
public class LiteRTLanguageModelSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val provider: String = "litert-lm",
    /** @since 0.3.0-beta01 */
    public val supportedUrls: Map<String, List<String>> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val defaultSamplerConfig: LiteRTSamplerConfig? = null,
    /** @since 0.3.0-beta01 */
    public val streamTextMode: LiteRTStreamTextMode = LiteRTStreamTextMode.Delta,
    /** @since 0.3.0-beta01 */
    public val reasoningChannelNames: Set<String> = setOf("thinking", "reasoning"),
    /** @since 0.3.0-beta01 */
    public val assistantReasoningChannelName: String = "thinking",
    /** @since 0.3.0-beta01 */
    public val channels: List<LiteRTChannel>? = null,
    /** @since 0.3.0-beta01 */
    public val extraContext: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val toolCallIdGenerator: () -> String = { IdGenerator.generate("call") },
)

/** @since 0.3.0-beta01 */
public class LiteRTLanguageModelSettingsBuilder {
    private var provider: String = "litert-lm"
    private var supportedUrls: Map<String, List<String>> = emptyMap()
    private var defaultSamplerConfig: LiteRTSamplerConfig? = null
    private var streamTextMode: LiteRTStreamTextMode = LiteRTStreamTextMode.Delta
    private var reasoningChannelNames: Set<String> = setOf("thinking", "reasoning")
    private var assistantReasoningChannelName: String = "thinking"
    private var channels: List<LiteRTChannel>? = null
    private var extraContext: Map<String, JsonElement> = emptyMap()
    private var toolCallIdGenerator: () -> String = { IdGenerator.generate("call") }

    /** @since 0.3.0-beta01 */
    public fun provider(value: String): LiteRTLanguageModelSettingsBuilder {
        provider = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun supportedUrls(value: Map<String, List<String>>): LiteRTLanguageModelSettingsBuilder {
        supportedUrls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun defaultSamplerConfig(value: LiteRTSamplerConfig?): LiteRTLanguageModelSettingsBuilder {
        defaultSamplerConfig = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun streamTextMode(value: LiteRTStreamTextMode): LiteRTLanguageModelSettingsBuilder {
        streamTextMode = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun reasoningChannelNames(value: Set<String>): LiteRTLanguageModelSettingsBuilder {
        reasoningChannelNames = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun assistantReasoningChannelName(value: String): LiteRTLanguageModelSettingsBuilder {
        assistantReasoningChannelName = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun channels(value: List<LiteRTChannel>?): LiteRTLanguageModelSettingsBuilder {
        channels = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun extraContext(value: Map<String, JsonElement>): LiteRTLanguageModelSettingsBuilder {
        extraContext = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun toolCallIdGenerator(value: () -> String): LiteRTLanguageModelSettingsBuilder {
        toolCallIdGenerator = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LiteRTLanguageModelSettings =
        LiteRTLanguageModelSettings(
            provider = provider,
            supportedUrls = supportedUrls,
            defaultSamplerConfig = defaultSamplerConfig,
            streamTextMode = streamTextMode,
            reasoningChannelNames = reasoningChannelNames,
            assistantReasoningChannelName = assistantReasoningChannelName,
            channels = channels,
            extraContext = extraContext,
            toolCallIdGenerator = toolCallIdGenerator,
        )
}

/** @since 0.3.0-beta01 */
public fun LiteRTLanguageModelSettings(
    block: LiteRTLanguageModelSettingsBuilder.() -> Unit = {},
): LiteRTLanguageModelSettings =
    LiteRTLanguageModelSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class LiteRTLanguageModel(
    override val modelId: String,
    private val conversationFactory: LiteRTConversationFactory,
    private val settings: LiteRTLanguageModelSettings = LiteRTLanguageModelSettings(block = {}),
) : LanguageModel {
    private val callPreparer = LiteRTCallPreparer(settings)
    private val responseMapper = LiteRTResponseMapper(settings)

    override val provider: String = settings.provider

    override val supportedUrls: Map<String, List<String>> = settings.supportedUrls

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = callPreparer.prepare(params)
        val conversation = conversationFactory.create(prepared.request)
        val abortRegistration = params.abortSignal.register { conversation.cancel() }
        return try {
            params.abortSignal.throwIfAborted()
            val response = conversation.send(prepared.request.message, prepared.request.extraContext)
            params.abortSignal.throwIfAborted()
            responseMapper.languageModelResult(response, prepared.warnings)
        } finally {
            abortRegistration.cancel()
            conversation.close()
        }
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = callPreparer.prepare(params)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val conversation = conversationFactory.create(prepared.request)
        val abortRegistration = params.abortSignal.register { conversation.cancel() }
        val state = LiteRTStreamState(
            provider = settings.provider,
            textMode = settings.streamTextMode,
            reasoningChannelNames = settings.reasoningChannelNames,
            idGenerator = settings.toolCallIdGenerator,
        )
        try {
            params.abortSignal.throwIfAborted()
            conversation.stream(prepared.request.message, prepared.request.extraContext).collect { message ->
                params.abortSignal.throwIfAborted()
                state.accept(message, this)
            }
            state.finish(this)
        } finally {
            abortRegistration.cancel()
            conversation.close()
        }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        LanguageModelStreamResult(stream = stream(params))
}

private class LiteRTStreamState(
    private val provider: String,
    private val textMode: LiteRTStreamTextMode,
    private val reasoningChannelNames: Set<String>,
    private val idGenerator: () -> String,
) {
    private val textId: String = "text"
    private val reasoningIds: MutableMap<String, String> = mutableMapOf()
    private val cumulativeReasoning: MutableMap<String, String> = mutableMapOf()
    private var cumulativeText: String = ""
    private var textOpen: Boolean = false
    private val openReasoning: MutableSet<String> = mutableSetOf()
    private val emittedToolCalls: MutableSet<LiteRTStreamToolCallKey> = mutableSetOf()
    private var hasToolCalls: Boolean = false
    private val textRecoveryMetadata: ProviderMetadata = ProviderMetadata.ofPairs(
        provider to JsonObject(
            mapOf(
                "cumulativeRecovery" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive(LITERT_CUMULATIVE_RECOVERY_TYPE),
                        "block" to JsonPrimitive("text"),
                        "message" to JsonPrimitive(LITERT_CUMULATIVE_RECOVERY_MESSAGE),
                    ),
                ),
            ),
        ),
    )
    private val reasoningRecoveryMetadata: ProviderMetadata = ProviderMetadata.ofPairs(
        provider to JsonObject(
            mapOf(
                "cumulativeRecovery" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive(LITERT_CUMULATIVE_RECOVERY_TYPE),
                        "block" to JsonPrimitive("reasoning"),
                        "message" to JsonPrimitive(LITERT_CUMULATIVE_RECOVERY_MESSAGE),
                    ),
                ),
            ),
        ),
    )

    suspend fun accept(message: LiteRTMessage, out: FlowCollector<StreamEvent>) {
        val textParts = message.content.filterIsInstance<LiteRTContent.Text>()
        if (textParts.isNotEmpty()) {
            val text = textParts.joinToString("") { it.text }
            val textDelta = delta(cumulativeText, text)
            if (textDelta.text.isNotEmpty()) {
                if (!textOpen) {
                    out.emit(StreamEvent.TextStart(textId, message.providerMetadata))
                    textOpen = true
                }
                val metadata = if (textDelta.recovered) {
                    message.providerMetadata + textRecoveryMetadata
                } else {
                    message.providerMetadata
                }
                out.emit(StreamEvent.TextDelta(textId, textDelta.text, metadata))
            }
            cumulativeText = textDelta.nextBaseline
        }
        for ((channel, value) in message.channels.filterKeys { it in reasoningChannelNames }) {
            val prior = cumulativeReasoning[channel].orEmpty()
            val delta = delta(prior, value)
            if (delta.text.isNotEmpty()) {
                val id = reasoningIds.getOrPut(channel) { "reasoning-${reasoningIds.size + 1}" }
                if (channel !in openReasoning) {
                    out.emit(StreamEvent.ReasoningStart(id, message.providerMetadata))
                    openReasoning += channel
                }
                val metadata = if (delta.recovered) {
                    message.providerMetadata + reasoningRecoveryMetadata
                } else {
                    message.providerMetadata
                }
                out.emit(StreamEvent.ReasoningDelta(id, delta.text, metadata))
            }
            cumulativeReasoning[channel] = delta.nextBaseline
        }
        for (call in message.toolCalls) {
            val key = LiteRTStreamToolCallKey(id = call.id, name = call.name, arguments = call.arguments)
            if (!emittedToolCalls.add(key)) continue
            hasToolCalls = true
            val toolCallId = call.id ?: idGenerator()
            out.emit(StreamEvent.ToolInputStart(toolCallId, call.name, call.providerMetadata))
            out.emit(StreamEvent.ToolInputDelta(toolCallId, call.arguments.toString(), call.providerMetadata))
            out.emit(StreamEvent.ToolInputEnd(toolCallId, call.providerMetadata))
            out.emit(StreamEvent.ToolCall(toolCallId, call.name, call.arguments, call.providerMetadata))
        }
    }

    suspend fun finish(out: FlowCollector<StreamEvent>) {
        if (textOpen) {
            out.emit(StreamEvent.TextEnd(textId))
        }
        for ((channel, id) in reasoningIds) {
            if (channel in openReasoning) {
                out.emit(StreamEvent.ReasoningEnd(id))
            }
        }
        out.emit(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = if (hasToolCalls) FinishReason.ToolCalls else FinishReason.Stop,
                usage = Usage(),
            ),
        )
    }

    private fun delta(prior: String, next: String): LiteRTCumulativeDelta =
        when {
            textMode != LiteRTStreamTextMode.Cumulative -> LiteRTCumulativeDelta(
                text = next,
                nextBaseline = prior + next,
            )
            next.startsWith(prior) -> LiteRTCumulativeDelta(
                text = next.removePrefix(prior),
                nextBaseline = next,
            )
            else -> {
                // Stream events are append-only, so LiteRT cumulative rewrites can only surface as
                // a marked best-effort suffix; the rewritten snapshot still becomes the baseline.
                LiteRTCumulativeDelta(
                    text = bestEffortSuffix(prior, next),
                    nextBaseline = next,
                    recovered = true,
                )
            }
        }

    private fun bestEffortSuffix(prior: String, next: String): String =
        suffixAfterSubsequence(prior, next)
            ?: suffixAfterCoveredPrefix(prior, next)

    private fun suffixAfterSubsequence(prior: String, next: String): String? {
        var priorIndex = 0
        for (nextIndex in next.indices) {
            if (priorIndex < prior.length && next[nextIndex] == prior[priorIndex]) {
                priorIndex += 1
                if (priorIndex == prior.length) return next.substring(nextIndex + 1)
            }
        }
        return null
    }

    private fun suffixAfterCoveredPrefix(prior: String, next: String): String {
        var nextIndex = 0
        for (char in prior) {
            if (nextIndex < next.length && char == next[nextIndex]) nextIndex += 1
        }
        return if (nextIndex == 0) "" else next.substring(nextIndex)
    }
}

private data class LiteRTCumulativeDelta(
    val text: String,
    val nextBaseline: String,
    val recovered: Boolean = false,
)

private data class LiteRTStreamToolCallKey(
    val id: String?,
    val name: String,
    val arguments: JsonElement,
)
