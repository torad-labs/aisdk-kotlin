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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

public typealias LiteRTProviderOptions = JsonObject

public enum class LiteRTStreamTextMode {
    Delta,
    Cumulative,
}

public enum class LiteRTMessageRole {
    System,
    User,
    Model,
    Tool,
}

public class LiteRTBytes(bytes: ByteArray) {
    private val value: ByteArray = bytes.copyOf()

    public fun toByteArray(): ByteArray = value.copyOf()
}

public data class LiteRTSamplerConfig(
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val seed: Int = 0,
) {
    public companion object {
        public val Default: LiteRTSamplerConfig = LiteRTSamplerConfig(
            topK = 40,
            topP = 0.95,
            temperature = 1.0,
        )
    }
}

public data class LiteRTChannel(
    val channelName: String,
    val start: String,
    val end: String,
)

public sealed class LiteRTContent {
    public data class Text(val text: String) : LiteRTContent()
    public data class ImageBytes(val bytes: LiteRTBytes, val mediaType: String? = null) : LiteRTContent()
    public data class ImageFile(val absolutePath: String, val mediaType: String? = null) : LiteRTContent()
    public data class AudioBytes(val bytes: LiteRTBytes, val mediaType: String? = null) : LiteRTContent()
    public data class AudioFile(val absolutePath: String, val mediaType: String? = null) : LiteRTContent()
    public data class ToolResponse(val name: String, val response: JsonElement) : LiteRTContent()
}

public data class LiteRTToolCall(
    val name: String,
    val arguments: JsonElement = JsonObject(emptyMap()),
    val id: String? = null,
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

public data class LiteRTMessage(
    val role: LiteRTMessageRole,
    val content: List<LiteRTContent> = emptyList(),
    val toolCalls: List<LiteRTToolCall> = emptyList(),
    val channels: Map<String, String> = emptyMap(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

public data class LiteRTConversationRequest(
    val systemInstruction: List<LiteRTContent> = emptyList(),
    val initialMessages: List<LiteRTMessage> = emptyList(),
    val message: LiteRTMessage,
    val tools: List<LanguageModelTool> = emptyList(),
    val samplerConfig: LiteRTSamplerConfig? = null,
    val automaticToolCalling: Boolean = false,
    val channels: List<LiteRTChannel>? = null,
    val extraContext: Map<String, Any?> = emptyMap(),
    val warnings: List<CallWarning> = emptyList(),
    val callParams: LanguageModelCallParams,
)

public fun interface LiteRTConversationFactory {
    public suspend fun create(request: LiteRTConversationRequest): LiteRTConversation
}

public interface LiteRTConversation {
    public suspend fun send(message: LiteRTMessage, extraContext: Map<String, Any?> = emptyMap()): LiteRTMessage

    public fun stream(message: LiteRTMessage, extraContext: Map<String, Any?> = emptyMap()): Flow<LiteRTMessage>

    public fun cancel(): Unit = Unit

    public fun close(): Unit = Unit
}

public data class LiteRTLanguageModelSettings(
    val provider: String = "litert-lm",
    val supportedUrls: Map<String, List<String>> = emptyMap(),
    val defaultSamplerConfig: LiteRTSamplerConfig? = null,
    val streamTextMode: LiteRTStreamTextMode = LiteRTStreamTextMode.Delta,
    val reasoningChannelNames: Set<String> = setOf("thinking", "reasoning"),
    val assistantReasoningChannelName: String = "thinking",
    val channels: List<LiteRTChannel>? = null,
    val extraContext: Map<String, Any?> = emptyMap(),
    val toolCallIdGenerator: () -> String = { IdGenerator.generate("call") },
)

public class LiteRTLanguageModel(
    override val modelId: String,
    private val conversationFactory: LiteRTConversationFactory,
    private val settings: LiteRTLanguageModelSettings = LiteRTLanguageModelSettings(),
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
