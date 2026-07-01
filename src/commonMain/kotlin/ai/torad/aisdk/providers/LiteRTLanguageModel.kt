@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

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
            topK = requireNotNull(topK) { "LiteRTSamplerConfig.topK is required" },
            topP = requireNotNull(topP) { "LiteRTSamplerConfig.topP is required" },
            temperature = requireNotNull(temperature) { "LiteRTSamplerConfig.temperature is required" },
            seed = seed,
        )
}

/** @since 0.3.0-beta01 */
public fun LiteRTSamplerConfig(
    block: LiteRTSamplerConfigBuilder.() -> Unit = {},
): LiteRTSamplerConfig =
    LiteRTSamplerConfigBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public data class LiteRTChannel(
    val channelName: String,
    val start: String,
    val end: String,
)

/** @since 0.3.0-beta01 */
public sealed class LiteRTContent {
    /** @since 0.3.0-beta01 */
    public data class Text(val text: String) : LiteRTContent()
    /** @since 0.3.0-beta01 */
    public data class ImageBytes(val bytes: LiteRTBytes, val mediaType: String? = null) : LiteRTContent()
    /** @since 0.3.0-beta01 */
    public data class ImageFile(val absolutePath: String, val mediaType: String? = null) : LiteRTContent()
    /** @since 0.3.0-beta01 */
    public data class AudioBytes(val bytes: LiteRTBytes, val mediaType: String? = null) : LiteRTContent()
    /** @since 0.3.0-beta01 */
    public data class AudioFile(val absolutePath: String, val mediaType: String? = null) : LiteRTContent()
    /** @since 0.3.0-beta01 */
    public data class ToolResponse(val name: String, val response: JsonElement) : LiteRTContent()
}

/** @since 0.3.0-beta01 */
public data class LiteRTToolCall(
    val name: String,
    val arguments: JsonElement = JsonObject(emptyMap()),
    val id: String? = null,
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

/** @since 0.3.0-beta01 */
public data class LiteRTMessage(
    val role: LiteRTMessageRole,
    val content: List<LiteRTContent> = emptyList(),
    val toolCalls: List<LiteRTToolCall> = emptyList(),
    val channels: Map<String, String> = emptyMap(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

/** @since 0.3.0-beta01 */
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
    public val extraContext: Map<String, Any?> = emptyMap(),
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
    private var extraContext: Map<String, Any?> = emptyMap()
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
    public fun extraContext(value: Map<String, Any?>): LiteRTConversationRequestBuilder {
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
    public suspend fun send(message: LiteRTMessage, extraContext: Map<String, Any?> = emptyMap()): LiteRTMessage

    /** @since 0.3.0-beta01 */
    public fun stream(message: LiteRTMessage, extraContext: Map<String, Any?> = emptyMap()): Flow<LiteRTMessage>

    /** @since 0.3.0-beta01 */
    public fun cancel(): Unit = Unit

    /** @since 0.3.0-beta01 */
    public fun close(): Unit = Unit
}

/** @since 0.3.0-beta01 */
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
    public val extraContext: Map<String, Any?> = emptyMap(),
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
    private var extraContext: Map<String, Any?> = emptyMap()
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
    public fun extraContext(value: Map<String, Any?>): LiteRTLanguageModelSettingsBuilder {
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
    private var hasToolCalls: Boolean = false

    suspend fun accept(message: LiteRTMessage, out: FlowCollector<StreamEvent>) {
        val text = message.content.filterIsInstance<LiteRTContent.Text>().joinToString("") { it.text }
        val textDelta = delta(cumulativeText, text)
        if (textDelta.isNotEmpty()) {
            if (!textOpen) {
                out.emit(StreamEvent.TextStart(textId, message.providerMetadata))
                textOpen = true
            }
            out.emit(StreamEvent.TextDelta(textId, textDelta, message.providerMetadata))
            cumulativeText = if (textMode == LiteRTStreamTextMode.Cumulative) text else cumulativeText + textDelta
        }
        for ((channel, value) in message.channels.filterKeys { it in reasoningChannelNames }) {
            val prior = cumulativeReasoning[channel].orEmpty()
            val delta = delta(prior, value)
            if (delta.isEmpty()) continue
            val id = reasoningIds.getOrPut(channel) { "reasoning-${reasoningIds.size + 1}" }
            if (channel !in openReasoning) {
                out.emit(StreamEvent.ReasoningStart(id, message.providerMetadata))
                openReasoning += channel
            }
            out.emit(StreamEvent.ReasoningDelta(id, delta, message.providerMetadata))
            cumulativeReasoning[channel] = if (textMode == LiteRTStreamTextMode.Cumulative) value else prior + delta
        }
        for (call in message.toolCalls) {
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

    private fun delta(prior: String, next: String): String =
        if (textMode == LiteRTStreamTextMode.Cumulative && next.startsWith(prior)) {
            next.removePrefix(prior)
        } else {
            next
        }
}
