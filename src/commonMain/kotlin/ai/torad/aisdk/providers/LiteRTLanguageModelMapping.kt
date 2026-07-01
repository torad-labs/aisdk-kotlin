@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.Base64Codec
import ai.torad.aisdk.CallWarning
import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.DataUrl
import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.JsonAccess
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.LanguageModelTool
import ai.torad.aisdk.MessageRole
import ai.torad.aisdk.ModelMessage
import ai.torad.aisdk.ProviderMetadata
import ai.torad.aisdk.ProviderOptions
import ai.torad.aisdk.ResponseFormat
import ai.torad.aisdk.ToolChoice
import ai.torad.aisdk.UnsupportedFunctionalityError
import ai.torad.aisdk.Usage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal data class PreparedLiteRTCall(
    val request: LiteRTConversationRequest,
    val warnings: List<CallWarning>,
)

internal class LiteRTCallPreparer(
    private val settings: LiteRTLanguageModelSettings,
) {
    private val messageMapper = LiteRTRequestMessageMapper(settings)

    fun prepare(params: LanguageModelCallParams): PreparedLiteRTCall {
        val warnings = warnings(params).toMutableList()
        val nonSystemMessages = params.messages.filterNot { it.role == MessageRole.System }
        val message = nonSystemMessages.lastOrNull()
            ?: throw UnsupportedFunctionalityError(
                "LiteRT empty prompt",
                "LiteRTLanguageModel requires at least one non-system message.",
            )
        val request = LiteRTConversationRequest {
            systemInstruction(messageMapper.systemInstruction(params.messages))
            initialMessages(nonSystemMessages.dropLast(1).map(messageMapper::message))
            message(messageMapper.message(message))
            tools(tools(params, warnings))
            samplerConfig(sampler(params))
            automaticToolCalling(false)
            channels(settings.channels)
            extraContext(extraContext(params.providerOptions))
            warnings(warnings)
            callParams(params)
        }
        return PreparedLiteRTCall(request, warnings)
    }

    fun warnings(params: LanguageModelCallParams): List<CallWarning> = buildList {
        if (params.maxOutputTokens != null) {
            add(
                CallWarning(
                    "unsupported",
                    "LiteRT-LM does not expose per-call maxOutputTokens; configure EngineConfig.maxNumTokens instead.",
                ),
            )
        }
        if (params.stopSequences.isNotEmpty()) {
            add(CallWarning("unsupported", "LiteRT-LM does not expose per-call stopSequences."))
        }
        if (params.presencePenalty != null) {
            add(CallWarning("unsupported", "LiteRT-LM does not expose presencePenalty."))
        }
        if (params.frequencyPenalty != null) {
            add(CallWarning("unsupported", "LiteRT-LM does not expose frequencyPenalty."))
        }
        if (params.responseFormat !is ResponseFormat.Text) {
            add(CallWarning("unsupported", "LiteRT-LM constrained responseFormat is not supported by this adapter."))
        }
    }

    fun tools(
        params: LanguageModelCallParams,
        warnings: MutableList<CallWarning>,
    ): List<LanguageModelTool> {
        if (params.toolChoice == ToolChoice.None) return emptyList()
        val localTools = params.tools.filterNot { tool ->
            if (tool.providerExecuted) {
                warnings += CallWarning(
                    "unsupported",
                    "LiteRTLanguageModel disables automatic tool calling; " +
                        "providerExecuted tool `${tool.name}` was not advertised.",
                )
                true
            } else {
                false
            }
        }
        return when (val choice = params.toolChoice) {
            ToolChoice.Auto -> localTools
            ToolChoice.None -> emptyList()
            ToolChoice.Required -> {
                warnings += CallWarning(
                    "unsupported",
                    "LiteRT-LM does not expose required tool choice; tools were advertised normally.",
                )
                localTools
            }
            is ToolChoice.Specific -> {
                warnings += CallWarning(
                    "unsupported",
                    "LiteRT-LM does not expose specific tool choice; only `${choice.toolName}` was advertised.",
                )
                localTools.filter { it.name == choice.toolName }
            }
        }
    }

    fun sampler(params: LanguageModelCallParams): LiteRTSamplerConfig? {
        if (!hasSamplerOverride(params) && settings.defaultSamplerConfig == null) {
            return null
        }
        val base = settings.defaultSamplerConfig ?: LiteRTSamplerConfig.Default
        return LiteRTSamplerConfig {
            topK(params.topK ?: base.topK)
            topP(params.topP?.toDouble() ?: base.topP)
            temperature(params.temperature?.toDouble() ?: base.temperature)
            seed(params.seed ?: base.seed)
        }
    }

    fun hasSamplerOverride(params: LanguageModelCallParams): Boolean =
        params.topK != null ||
            params.topP != null ||
            params.temperature != null ||
            params.seed != null

    fun extraContext(providerOptions: ProviderOptions): Map<String, Any?> {
        val options = options(providerOptions) ?: return settings.extraContext
        val extra = settings.extraContext.toMutableMap()
        JsonAccess.obj(options, "extraContext")?.let { obj ->
            for ((key, value) in obj) extra[key] = jsonValue(value)
        }
        options["enableThinking"]?.let { extra["enable_thinking"] = jsonValue(it) }
        options["enable_thinking"]?.let { extra["enable_thinking"] = jsonValue(it) }
        return extra
    }

    fun options(providerOptions: ProviderOptions): JsonObject? {
        val map = providerOptions.toMap()
        return sequenceOf("litert-lm", "litertlm", "litert")
            .firstNotNullOfOrNull { key ->
                when (val value = map[key]) {
                    is JsonObject -> value
                    else -> null
                }
            }
    }

    fun jsonValue(element: JsonElement): Any? =
        when (element) {
            JsonNull -> null
            is JsonPrimitive -> when {
                element.booleanOrNull != null -> element.booleanOrNull
                element.longOrNull != null -> element.longOrNull
                element.doubleOrNull != null -> element.doubleOrNull
                else -> element.contentOrNull ?: element.toString()
            }
            is JsonArray -> element.map(::jsonValue)
            is JsonObject -> element.mapValues { jsonValue(it.value) }
        }
}

internal class LiteRTRequestMessageMapper(
    private val settings: LiteRTLanguageModelSettings,
) {
    fun systemInstruction(messages: List<ModelMessage>): List<LiteRTContent> =
        messages
            .filter { it.role == MessageRole.System }
            .flatMap { contents(it, MessageRole.System) }

    fun message(message: ModelMessage): LiteRTMessage =
        LiteRTMessage(
            role = when (message.role) {
                MessageRole.System -> LiteRTMessageRole.System
                MessageRole.User -> LiteRTMessageRole.User
                MessageRole.Assistant -> LiteRTMessageRole.Model
                MessageRole.Tool -> LiteRTMessageRole.Tool
            },
            content = contents(message, message.role),
            toolCalls = message.content.filterIsInstance<ContentPart.ToolCall>().map {
                LiteRTToolCall(
                    name = it.toolName,
                    arguments = it.input,
                    id = it.toolCallId,
                    providerMetadata = it.providerMetadata,
                )
            },
            channels = channels(message),
        )

    fun contents(message: ModelMessage, role: MessageRole): List<LiteRTContent> =
        message.content.mapNotNull { part ->
            when (part) {
                is ContentPart.Text -> LiteRTContent.Text(part.text)
                is ContentPart.Image -> media(part.mediaType, part.base64, part.url, image = true)
                is ContentPart.File -> file(part)
                is ContentPart.ToolResult ->
                    if (role == MessageRole.Tool) {
                        LiteRTContent.ToolResponse(part.toolName, part.modelVisible)
                    } else {
                        null
                    }
                is ContentPart.Reasoning,
                is ContentPart.ToolCall,
                is ContentPart.ToolApprovalRequest,
                is ContentPart.ToolApprovalResponse,
                is ContentPart.Source,
                is ContentPart.Raw,
                -> null
            }
        }

    fun channels(message: ModelMessage): Map<String, String> {
        if (message.role != MessageRole.Assistant) return emptyMap()
        val reasoning = message.content
            .filterIsInstance<ContentPart.Reasoning>()
            .joinToString("") { it.text }
        return if (reasoning.isEmpty()) {
            emptyMap()
        } else {
            mapOf(settings.assistantReasoningChannelName to reasoning)
        }
    }

    fun file(part: ContentPart.File): LiteRTContent =
        when {
            part.mediaType.startsWith("image/") -> media(part.mediaType, part.base64, part.url, image = true)
            part.mediaType.startsWith("audio/") -> media(part.mediaType, part.base64, part.url, image = false)
            part.mediaType == "text/plain" && part.base64.isNotEmpty() ->
                LiteRTContent.Text(Base64Codec.decode(part.base64).decodeToString())
            else -> throw UnsupportedFunctionalityError(
                "LiteRT file media",
                "LiteRTLanguageModel only supports image/*, audio/*, and base64 text/plain file parts.",
            )
        }

    fun media(
        mediaType: String,
        base64: String,
        url: String?,
        image: Boolean,
    ): LiteRTContent {
        val bytes = when {
            base64.isNotEmpty() -> LiteRTBytes(Base64Codec.decode(base64))
            url?.startsWith("data:") == true -> LiteRTBytes(Base64Codec.decode(DataUrl.parse(url).base64))
            else -> null
        }
        if (bytes != null) {
            return if (image) {
                LiteRTContent.ImageBytes(bytes, mediaType)
            } else {
                LiteRTContent.AudioBytes(bytes, mediaType)
            }
        }
        val path = url?.takeIf { !it.contains("://") && !it.startsWith("data:") }
            ?: throw UnsupportedFunctionalityError(
                "LiteRT remote media URL",
                "LiteRTLanguageModel requires inline base64 data or an absolute local file path for media.",
            )
        return if (image) {
            LiteRTContent.ImageFile(path, mediaType)
        } else {
            LiteRTContent.AudioFile(path, mediaType)
        }
    }
}

internal class LiteRTResponseMapper(
    private val settings: LiteRTLanguageModelSettings,
) {
    fun languageModelResult(
        message: LiteRTMessage,
        warnings: List<CallWarning>,
    ): LanguageModelResult {
        val text = text(message)
        val toolCalls = message.toolCalls.map(::contentToolCall)
        val reasoning = reasoningContent(message)
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = if (toolCalls.isEmpty()) FinishReason.Stop else FinishReason.ToolCalls,
            usage = Usage(),
            providerMetadata = providerMetadata(message),
            content = buildList {
                if (text.isNotEmpty()) add(ContentPart.Text(text))
                addAll(reasoning)
                addAll(toolCalls)
            },
            warnings = warnings,
        )
    }

    fun text(message: LiteRTMessage): String =
        message.content.filterIsInstance<LiteRTContent.Text>().joinToString("") { it.text }

    fun reasoningContent(message: LiteRTMessage): List<ContentPart.Reasoning> =
        message.channels
            .filterKeys { it in settings.reasoningChannelNames }
            .values
            .filter { it.isNotEmpty() }
            .map { ContentPart.Reasoning(it, providerMetadata(message)) }

    fun contentToolCall(call: LiteRTToolCall): ContentPart.ToolCall =
        ContentPart.ToolCall(
            toolCallId = call.id ?: settings.toolCallIdGenerator(),
            toolName = call.name,
            input = call.arguments,
            providerMetadata = call.providerMetadata,
        )

    fun providerMetadata(message: LiteRTMessage): ProviderMetadata {
        val channelMetadata = if (message.channels.isEmpty()) {
            ProviderMetadata.None
        } else {
            ProviderMetadata.Raw(
                JsonObject(
                    mapOf(
                        "litert" to buildJsonObject {
                            put("channels", JsonObject(message.channels.mapValues { JsonPrimitive(it.value) }))
                        },
                    ),
                ),
            )
        }
        return message.providerMetadata + channelMetadata
    }
}
