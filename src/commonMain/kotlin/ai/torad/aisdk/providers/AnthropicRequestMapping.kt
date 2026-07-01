@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal data class PreparedAnthropicRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
    val betas: Set<String>,
) {
    internal companion object {
        internal fun anthropicRequestBody(
            settings: AnthropicProviderSettings,
            modelId: String,
            params: LanguageModelCallParams,
            stream: Boolean,
        ): PreparedAnthropicRequest {
            val warnings = mutableListOf<CallWarning>()
            if (params.frequencyPenalty != null) warnings += CallWarning("unsupported", "frequencyPenalty")
            if (params.presencePenalty != null) warnings += CallWarning("unsupported", "presencePenalty")
            if (params.seed != null) warnings += CallWarning("unsupported", "seed")

            val options = settings.anthropicOptions(params.providerOptions)
            val betas = linkedSetOf<String>()
            JsonAccess.arr(options, "anthropicBeta")?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(betas::add) }
            val sendReasoning = (options["sendReasoning"] as? JsonPrimitive)?.booleanOrNull ?: true
            val prompt = AnthropicPrompt.anthropicPrompt(params.messages, sendReasoning)
            betas += prompt.betas

            val thinking = JsonAccess.obj(options, "thinking")
            val thinkingType = (thinking?.get("type") as? JsonPrimitive)?.contentOrNull
            val isThinking = thinkingType == "enabled" || thinkingType == "adaptive"
            val rawThinkingBudget = (thinking?.get("budgetTokens") as? JsonPrimitive)?.intOrNull
            val modelMaxOutputTokens = AnthropicProviderSettings.anthropicMaxOutputTokensOrNull(modelId)
            val maxTokensBase = params.maxOutputTokens ?: AnthropicProviderSettings.anthropicMaxOutputTokensForModel(modelId)
            // `thinkingBudget` is the effective budget (defaulting to 1024 only when thinking is
            // explicitly enabled and the caller omitted it); `maxTokens` folds it into the base.
            val thinkingBudget: Int?
            val computedMaxTokens: Int
            if (isThinking && thinkingType == "enabled") {
                val budget = rawThinkingBudget ?: run {
                    warnings += CallWarning("compatibility", "thinking budget is required when thinking is enabled. using default budget of 1024 tokens.")
                    1024
                }
                thinkingBudget = budget
                computedMaxTokens = maxTokensBase + budget
            } else {
                thinkingBudget = rawThinkingBudget
                computedMaxTokens = maxTokensBase
            }
            val maxTokens = if (modelMaxOutputTokens != null && computedMaxTokens > modelMaxOutputTokens) {
                if (params.maxOutputTokens != null) {
                    warnings += CallWarning(
                        type = "unsupported",
                        message = "maxOutputTokens",
                        details = JsonPrimitive(
                            "$computedMaxTokens (maxOutputTokens + thinkingBudget) is greater than " +
                                "$modelId $modelMaxOutputTokens max output tokens. " +
                                "The max output tokens have been limited to $modelMaxOutputTokens.",
                        ),
                    )
                }
                modelMaxOutputTokens
            } else {
                computedMaxTokens
            }

            val temperature = params.temperature?.coerceIn(0f, 1f)?.also {
                if (params.temperature != it) warnings += CallWarning("unsupported", "temperature")
            }
            val rejectsSamplingParameters =
                AnthropicProviderSettings.anthropicRejectsSamplingParameterModelFragments.any { modelId.contains(it) }
            val samplingTemperature = if (rejectsSamplingParameters) {
                if (temperature != null) {
                    warnings += CallWarning(
                        type = "unsupported",
                        message = "temperature",
                        details = JsonPrimitive("temperature is not supported by $modelId and will be ignored"),
                    )
                }
                null
            } else {
                temperature
            }
            val samplingTopK = if (rejectsSamplingParameters) {
                if (params.topK != null) {
                    warnings += CallWarning(
                        type = "unsupported",
                        message = "topK",
                        details = JsonPrimitive("topK is not supported by $modelId and will be ignored"),
                    )
                }
                null
            } else {
                params.topK
            }
            val samplingTopP = if (rejectsSamplingParameters) {
                if (params.topP != null) {
                    warnings += CallWarning(
                        type = "unsupported",
                        message = "topP",
                        details = JsonPrimitive("topP is not supported by $modelId and will be ignored"),
                    )
                }
                null
            } else {
                params.topP
            }
            val topP = if (isThinking) {
                if (samplingTopP != null) warnings += CallWarning("unsupported", "topP")
                null
            } else if (samplingTemperature != null && samplingTopP != null && modelId.startsWith("claude-")) {
                warnings += CallWarning("unsupported", "topP")
                null
            } else {
                samplingTopP
            }
            val topK = if (isThinking) {
                if (samplingTopK != null) warnings += CallWarning("unsupported", "topK")
                null
            } else {
                samplingTopK
            }
            val finalTemperature = if (isThinking) {
                if (samplingTemperature != null) warnings += CallWarning("unsupported", "temperature")
                null
            } else {
                samplingTemperature
            }

            val preparedTools = AnthropicTools.anthropicPrepareTools(params.tools, params.toolChoice, options, params.responseFormat)
            warnings += preparedTools.warnings
            betas += preparedTools.betas
            val outputConfig = anthropicOutputConfig(options, params.responseFormat)
            if (params.responseFormat is ResponseFormat.Json && params.responseFormat.schemaJson != null) {
                betas += "structured-outputs-2025-11-13"
            }
            if (options["taskBudget"] != null) betas += "task-budgets-2026-03-13"
            if ((options["speed"] as? JsonPrimitive)?.contentOrNull == "fast") betas += "fast-mode-2026-02-01"

            return PreparedAnthropicRequest(
                body = buildJsonObject {
                    put("model", JsonPrimitive(modelId))
                    put("max_tokens", JsonPrimitive(maxTokens))
                    finalTemperature?.let { put("temperature", JsonPrimitive(it)) }
                    topK?.let { put("top_k", JsonPrimitive(it)) }
                    topP?.let { put("top_p", JsonPrimitive(it)) }
                    if (params.stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(params.stopSequences.map(::JsonPrimitive)))
                    if (isThinking) {
                        put(
                            "thinking",
                            buildJsonObject {
                                put("type", JsonPrimitive(thinkingType))
                                thinkingBudget?.let { put("budget_tokens", JsonPrimitive(it)) }
                                thinking["display"]?.let { put("display", it) }
                            },
                        )
                    }
                    outputConfig?.let { put("output_config", it) }
                    options["speed"]?.let { put("speed", it) }
                    options["inferenceGeo"]?.let { put("inference_geo", it) }
                    options["cacheControl"]?.let { put("cache_control", it) }
                    anthropicMetadata(options)?.let { put("metadata", it) }
                    anthropicMcpServers(options)?.let {
                        put("mcp_servers", it)
                        betas += "mcp-client-2025-04-04"
                    }
                    AnthropicRequestJson.container(options)?.let { container ->
                        put("container", container)
                        if (container is JsonObject && container["skills"] != null) {
                            betas += setOf("code-execution-2025-08-25", "skills-2025-10-02", "files-api-2025-04-14")
                        }
                    }
                    options["contextManagement"]?.let {
                        put("context_management", camelToSnakeJson(it))
                        betas += "context-management-2025-06-27"
                    }
                    prompt.system?.let { put("system", it) }
                    put("messages", prompt.messages)
                    preparedTools.tools?.let { put("tools", it) }
                    preparedTools.toolChoice?.let { put("tool_choice", it) }
                    if (stream) put("stream", JsonPrimitive(true))
                },
                warnings = warnings,
                betas = betas,
            )
        }

        private fun anthropicOutputConfig(options: JsonObject, responseFormat: ResponseFormat): JsonObject? {
            val fields = linkedMapOf<String, JsonElement>()
            options["effort"]?.let { fields["effort"] = it }
            options["taskBudget"]?.let { fields["task_budget"] = camelToSnakeJson(it) }
            if (responseFormat is ResponseFormat.Json && responseFormat.schemaJson != null) {
                fields["format"] = buildJsonObject {
                    put("type", JsonPrimitive("json_schema"))
                    put("schema", responseFormat.schemaJson)
                }
            }
            return fields.takeIf { it.isNotEmpty() }?.let(::JsonObject)
        }

        private fun anthropicMetadata(options: JsonObject): JsonObject? {
            val metadata = JsonAccess.obj(options, "metadata") ?: return null
            val userId = metadata["userId"] ?: return null
            return buildJsonObject { put("user_id", userId) }
        }

        private fun anthropicMcpServers(options: JsonObject): JsonArray? {
            val servers = JsonAccess.arr(options, "mcpServers") ?: return null
            if (servers.isEmpty()) return null
            return JsonArray(servers.mapNotNull { server ->
                val obj = server as? JsonObject ?: return@mapNotNull null
                buildJsonObject {
                    put("type", obj["type"] ?: JsonPrimitive("url"))
                    put("name", obj["name"] ?: JsonPrimitive(""))
                    put("url", obj["url"] ?: JsonPrimitive(""))
                    obj["authorizationToken"]?.let { put("authorization_token", it) }
                    JsonAccess.obj(obj, "toolConfiguration")?.let { put("tool_configuration", camelToSnakeJson(it)) }
                }
            })
        }

        internal fun camelToSnakeJson(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapKeys { camelToSnake(it.key) }.mapValues { camelToSnakeJson(it.value) })
            is JsonArray -> JsonArray(element.map(::camelToSnakeJson))
            else -> element
        }

        internal fun camelToSnake(value: String): String =
            value.replace(Regex("([a-z0-9])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }
    }
}

internal data class AnthropicPrompt(
    val system: JsonArray?,
    val messages: JsonArray,
    val betas: Set<String>,
) {
    internal companion object {
        internal fun anthropicPrompt(
            messages: List<ModelMessage>,
            sendReasoning: Boolean,
        ): AnthropicPrompt {
            val system = mutableListOf<JsonElement>()
            val apiMessages = mutableListOf<JsonElement>()
            val betas = linkedSetOf<String>()

            for (message in messages) {
                when (message.role) {
                    MessageRole.System -> system += message.content.mapNotNull { part ->
                        (part as? ContentPart.Text)?.let {
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(it.text))
                                AnthropicProviderSettings.anthropicCacheControl(it.providerMetadata.toMap())?.let { cache -> put("cache_control", cache) }
                            }
                        }
                    }
                    MessageRole.User -> apiMessages += buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonArray(message.content.mapNotNull { anthropicUserPart(it, betas) }))
                    }
                    MessageRole.Assistant -> {
                        // Anthropic rejects trailing whitespace in a pre-filled assistant turn, so
                        // trim the LAST text part of the LAST message (the pre-fill), per upstream.
                        val isLastMessage = message === messages.last()
                        val lastTextIndex =
                            if (isLastMessage) message.content.indexOfLast { it is ContentPart.Text } else -1
                        val content = message.content.mapIndexedNotNull { index, part ->
                            anthropicAssistantPart(part, sendReasoning, currentIndex = index, lastTextIndex = lastTextIndex)
                        }
                        if (content.isNotEmpty()) {
                            apiMessages += buildJsonObject {
                                put("role", JsonPrimitive("assistant"))
                                put("content", JsonArray(content))
                            }
                        }
                    }
                    MessageRole.Tool -> {
                        val content = message.content.filterIsInstance<ContentPart.ToolResult>().map { result ->
                            buildJsonObject {
                                put("type", JsonPrimitive("tool_result"))
                                put("tool_use_id", JsonPrimitive(result.toolCallId))
                                put("content", anthropicToolResultContent(result))
                                if (result.isError) put("is_error", JsonPrimitive(true))
                                AnthropicProviderSettings.anthropicCacheControl(result.providerMetadata.toMap())?.let { put("cache_control", it) }
                            }
                        }
                        if (content.isNotEmpty()) {
                            apiMessages += buildJsonObject {
                                put("role", JsonPrimitive("user"))
                                put("content", JsonArray(content))
                            }
                        }
                    }
                }
            }

            return AnthropicPrompt(
                system = system.takeIf { it.isNotEmpty() }?.let(::JsonArray),
                messages = JsonArray(apiMessages),
                betas = betas,
            )
        }

        private fun anthropicUserPart(part: ContentPart, betas: MutableSet<String>): JsonElement? = when (part) {
            is ContentPart.Text -> buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(part.text))
                AnthropicProviderSettings.anthropicCacheControl(part.providerMetadata.toMap())?.let { put("cache_control", it) }
            }
            is ContentPart.Image -> buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("source", anthropicMediaSource(part.url, part.mediaType, part.base64))
                AnthropicProviderSettings.anthropicCacheControl(part.providerMetadata.toMap())?.let { put("cache_control", it) }
            }
            is ContentPart.File -> when {
                part.mediaType.startsWith("image/") -> buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("source", anthropicMediaSource(part.url, part.mediaType, part.base64))
                    AnthropicProviderSettings.anthropicCacheControl(part.providerMetadata.toMap())?.let { put("cache_control", it) }
                }
                part.mediaType == "application/pdf" -> {
                    betas += "pdfs-2024-09-25"
                    buildJsonObject {
                        put("type", JsonPrimitive("document"))
                        put("source", anthropicMediaSource(part.url, "application/pdf", part.base64))
                        part.filename?.let { put("title", JsonPrimitive(it)) }
                        AnthropicProviderSettings.anthropicFileOptions(part.providerMetadata.toMap())?.let { putJsonObjectFields(it) }
                        AnthropicProviderSettings.anthropicCacheControl(part.providerMetadata.toMap())?.let { put("cache_control", it) }
                    }
                }
                part.mediaType == "text/plain" -> buildJsonObject {
                    put("type", JsonPrimitive("document"))
                    put("source", buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("media_type", JsonPrimitive("text/plain"))
                        put("data", JsonPrimitive(Base64Codec.decode(part.base64).decodeToString()))
                    })
                    part.filename?.let { put("title", JsonPrimitive(it)) }
                    AnthropicProviderSettings.anthropicFileOptions(part.providerMetadata.toMap())?.let { putJsonObjectFields(it) }
                    AnthropicProviderSettings.anthropicCacheControl(part.providerMetadata.toMap())?.let { put("cache_control", it) }
                }
                else -> throw UnsupportedFunctionalityError("file media type ${part.mediaType}", "Unsupported Anthropic file media type: ${part.mediaType}")
            }
            is ContentPart.Reasoning,
            is ContentPart.ToolCall,
            is ContentPart.ToolResult,
            is ContentPart.ToolApprovalRequest,
            is ContentPart.ToolApprovalResponse,
            is ContentPart.Source,
            is ContentPart.Raw,
            -> null
        }

        /**
         * Anthropic media source: a remote [url] is sent as a `url` source (Anthropic
         * fetches it); otherwise the inline [base64] bytes are sent. Closes the gap where
         * a ContentPart carrying only a `url` was previously serialized with empty data.
         */
        private fun anthropicMediaSource(url: String?, mediaType: String, base64: String): JsonObject = buildJsonObject {
            if (url != null) {
                put("type", JsonPrimitive("url"))
                put("url", JsonPrimitive(url))
            } else {
                put("type", JsonPrimitive("base64"))
                put("media_type", JsonPrimitive(if (mediaType == "image/*") "image/jpeg" else mediaType))
                put("data", JsonPrimitive(base64))
            }
        }

        private fun anthropicAssistantPart(
            part: ContentPart,
            sendReasoning: Boolean,
            currentIndex: Int = -1,
            lastTextIndex: Int = -1,
        ): JsonElement? = when (part) {
            is ContentPart.Text -> buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(if (currentIndex == lastTextIndex && lastTextIndex >= 0) part.text.trim() else part.text))
                AnthropicProviderSettings.anthropicCacheControl(part.providerMetadata.toMap())?.let { put("cache_control", it) }
            }
            is ContentPart.Reasoning -> when {
                !sendReasoning -> null
                else -> buildJsonObject {
                    val metadata = JsonAccess.obj(part.providerMetadata.toMap(), "anthropic")
                    put("type", JsonPrimitive("thinking"))
                    put("thinking", JsonPrimitive(part.text))
                    metadata?.get("signature")?.let { put("signature", it) }
                }
            }
            is ContentPart.ToolCall -> buildJsonObject {
                put("type", JsonPrimitive("tool_use"))
                put("id", JsonPrimitive(part.toolCallId))
                put("name", JsonPrimitive(part.toolName))
                put("input", part.input)
                AnthropicProviderSettings.anthropicCacheControl(part.providerMetadata.toMap())?.let { put("cache_control", it) }
            }
            is ContentPart.ToolResult,
            is ContentPart.ToolApprovalRequest,
            is ContentPart.ToolApprovalResponse,
            is ContentPart.Source,
            is ContentPart.File,
            is ContentPart.Image,
            is ContentPart.Raw,
            -> null
        }

        /**
         * Decode [ContentPart.ToolResult.modelVisible] off the wire and render it as an Anthropic
         * `tool_result.content` value. Text/json/error/denied collapse to a string; `Content`
         * (the MCP shape) maps to the native content-block array, preserving image blocks. The old
         * implementation `toString()`'d the raw element, which dropped MCP content/images and leaked
         * the error/denial wrapper objects into the prompt — mirrors OpenResponsesProvider's
         * `openResponsesToolOutput`.
         */
        private fun anthropicToolResultContent(result: ContentPart.ToolResult): JsonElement =
            when (val output = ToolResultOutputs.toolResultOutputFromWire(result.modelVisible)) {
                is ToolResultOutput.Text -> JsonPrimitive(output.text)
                is ToolResultOutput.Error -> JsonPrimitive(output.message)
                is ToolResultOutput.ExecutionDenied -> JsonPrimitive(output.reason ?: "Tool execution denied.")
                is ToolResultOutput.Json -> JsonPrimitive(output.json.toString())
                is ToolResultOutput.ErrorJson -> JsonPrimitive(output.json.toString())
                is ToolResultOutput.Content -> JsonArray(output.value.mapNotNull(::anthropicToolResultContentBlock))
            }

        /** Map one MCP/`Content` item to an Anthropic `tool_result` content block. Anthropic
         *  tool_result content supports text + image blocks only; other item types are skipped. */
        private fun anthropicToolResultContentBlock(item: JsonElement): JsonObject? {
            val obj = item as? JsonObject
            return when ((obj?.get("type") as? JsonPrimitive)?.contentOrNull) {
                "text" -> buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", obj["text"] ?: JsonPrimitive(""))
                }
                "image-data" -> buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put(
                        "source",
                        buildJsonObject {
                            put("type", JsonPrimitive("base64"))
                            put("media_type", obj["mediaType"] ?: JsonPrimitive("application/octet-stream"))
                            put("data", obj["data"] ?: JsonPrimitive(""))
                        },
                    )
                }
                "image-url" -> buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put(
                        "source",
                        buildJsonObject {
                            put("type", JsonPrimitive("url"))
                            put("url", obj["url"] ?: JsonPrimitive(""))
                        },
                    )
                }
                else -> null
            }
        }

        private fun JsonObjectBuilder.putJsonObjectFields(fields: JsonObject) {
            fields.forEach { (key, value) -> if (value !is JsonNull) put(key, value) }
        }
    }
}

