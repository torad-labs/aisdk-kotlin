package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class BedrockPreparedChatRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
    val usesJsonResponseTool: Boolean,
)

internal data class BedrockPreparedImageRequest(val body: JsonObject, val warnings: List<CallWarning>)

internal data class BedrockConvertedMessages(
    val system: JsonArray,
    val messages: JsonArray,
    val warnings: List<CallWarning>,
)

internal data class BedrockPreparedTools(
    val toolConfig: JsonObject?,
    val warnings: List<CallWarning>,
    val usesJsonResponseTool: Boolean,
)

/**
 * Builds Amazon Bedrock request bodies — Converse chat, embedding `invoke`,
 * image `invoke`, agent `rerank`, and the Mantle OpenAI-shaped chat — together
 * with the prompt/tool/document conversion they depend on.
 */
internal object BedrockRequest {
    /** Default Bedrock output budget added to the thinking budget when maxOutputTokens is unset. */
    private const val DEFAULT_BEDROCK_MAX_TOKENS: Int = 4096

    fun bedrockChatRequestBody(
        modelId: String,
        params: LanguageModelCallParams,
    ): BedrockPreparedChatRequest {
        val warnings = mutableListOf<CallWarning>()
        params.frequencyPenalty?.let { warnings += CallWarning("unsupported", "frequencyPenalty") }
        params.presencePenalty?.let { warnings += CallWarning("unsupported", "presencePenalty") }
        params.seed?.let { warnings += CallWarning("unsupported", "seed") }
        val options = JsonAccess.obj(params.providerOptions.toMap(), "bedrock") ?: JsonObject(emptyMap())
        // Anthropic thinking changes inferenceConfig: the thinking budget is added to maxTokens
        // (Bedrock counts it against the output budget) and Bedrock rejects temperature/topP/topK
        // for thinking-enabled Anthropic calls, so they're stripped (matching upstream).
        val reasoningConfig = JsonAccess.obj(options, "reasoningConfig")
        val isAnthropicThinking = modelId.contains("anthropic") &&
            (reasoningConfig?.get("type") as? JsonPrimitive)?.contentOrNull in setOf("enabled", "adaptive")
        val thinkingBudget = (reasoningConfig?.get("budgetTokens") as? JsonPrimitive)?.intOrNull
        val hasSamplingParams = params.temperature != null || params.topP != null || params.topK != null
        if (isAnthropicThinking && hasSamplingParams) {
            warnings += CallWarning("unsupported", "temperature/topP/topK are not supported with Anthropic thinking")
        }
        val inferenceConfig = buildJsonObject {
            val baseMaxTokens = params.maxOutputTokens
            val resolvedMaxTokens = when {
                !isAnthropicThinking || thinkingBudget == null -> baseMaxTokens
                baseMaxTokens != null -> baseMaxTokens + thinkingBudget
                else -> thinkingBudget + DEFAULT_BEDROCK_MAX_TOKENS
            }
            resolvedMaxTokens?.let { put("maxTokens", JsonPrimitive(it)) }
            if (!isAnthropicThinking) {
                params.temperature?.coerceIn(0f, 1f)?.let { put("temperature", JsonPrimitive(it)) }
                params.topP?.let { put("topP", JsonPrimitive(it)) }
                params.topK?.let { put("topK", JsonPrimitive(it)) }
            }
            if (params.stopSequences.isNotEmpty()) {
                put(
                    "stopSequences",
                    JsonArray(params.stopSequences.map(::JsonPrimitive))
                )
            }
        }
        val converted = bedrockMessages(params.messages)
        warnings += converted.warnings
        val tools = bedrockTools(modelId, params.tools, params.toolChoice, params.responseFormat)
        warnings += tools.warnings
        val bedrockOptions = bedrockAdditionalModelRequestFields(options, modelId, params.responseFormat)
        val serviceTier = (options["serviceTier"] as? JsonPrimitive)?.contentOrNull

        return BedrockPreparedChatRequest(
            body = buildJsonObject {
                if (converted.system.isNotEmpty()) put("system", converted.system)
                put("messages", converted.messages)
                if (bedrockOptions.isNotEmpty()) put("additionalModelRequestFields", bedrockOptions)
                if (modelId.contains("anthropic")) {
                    put("additionalModelResponseFieldPaths", JsonArray(listOf(JsonPrimitive("/delta/stop_sequence"))))
                }
                if (inferenceConfig.isNotEmpty()) put("inferenceConfig", inferenceConfig)
                serviceTier?.let {
                    put("serviceTier", buildJsonObject { put("type", JsonPrimitive(it)) })
                }
                tools.toolConfig?.let { put("toolConfig", it) }
            },
            warnings = warnings,
            usesJsonResponseTool = tools.usesJsonResponseTool,
        )
    }

    private fun bedrockMessages(messages: List<ModelMessage>): BedrockConvertedMessages {
        val system = mutableListOf<JsonElement>()
        val converted = mutableListOf<JsonElement>()
        val warnings = mutableListOf<CallWarning>()
        for (message in messages) {
            when (message.role) {
                MessageRole.System -> message.content.forEach { part ->
                    if (part is ContentPart.Text) {
                        system += buildJsonObject {
                            put("text", JsonPrimitive(part.text))
                            bedrockCachePoint(part.providerMetadata.toMap())?.let { put("cachePoint", it) }
                        }
                    }
                }
                MessageRole.User -> converted += buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonArray(message.content.mapNotNull { bedrockUserPart(it) }))
                }
                MessageRole.Assistant -> converted += buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonArray(message.content.mapNotNull { bedrockAssistantPart(it) }))
                }
                MessageRole.Tool -> converted += buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonArray(message.content.mapNotNull { bedrockToolResultPart(it) }))
                }
            }
        }
        return BedrockConvertedMessages(JsonArray(system), JsonArray(converted), warnings)
    }

    private fun bedrockUserPart(part: ContentPart): JsonElement? = when (part) {
        is ContentPart.Text -> buildJsonObject {
            put("text", JsonPrimitive(part.text))
            bedrockCachePoint(part.providerMetadata.toMap())?.let { put("cachePoint", it) }
        }
        is ContentPart.Image -> buildJsonObject {
            put(
                "image",
                buildJsonObject {
                    put("format", JsonPrimitive(bedrockImageFormat(part.mediaType)))
                    put("source", buildJsonObject { put("bytes", JsonPrimitive(part.base64)) })
                },
            )
        }
        is ContentPart.File -> buildJsonObject {
            if (part.mediaType.startsWith("image/")) {
                put(
                    "image",
                    buildJsonObject {
                        put("format", JsonPrimitive(bedrockImageFormat(part.mediaType)))
                        put("source", buildJsonObject { put("bytes", JsonPrimitive(part.base64)) })
                    },
                )
            } else {
                put(
                    "document",
                    buildJsonObject {
                        put("format", JsonPrimitive(bedrockDocumentFormat(part.mediaType)))
                        put("name", JsonPrimitive(part.filename?.substringBeforeLast('.') ?: "document"))
                        put("source", buildJsonObject { put("bytes", JsonPrimitive(part.base64)) })
                        if (bedrockCitationsEnabled(part.providerMetadata.toMap())) {
                            put("citations", buildJsonObject { put("enabled", JsonPrimitive(true)) })
                        }
                    },
                )
            }
        }
        is ContentPart.Reasoning -> null
        is ContentPart.ToolCall -> null
        is ContentPart.ToolResult -> null
        is ContentPart.ToolApprovalRequest -> null
        is ContentPart.ToolApprovalResponse -> null
        is ContentPart.Source -> null
        is ContentPart.Raw -> null
    }

    private fun bedrockAssistantPart(part: ContentPart): JsonElement? = when (part) {
        is ContentPart.Text -> buildJsonObject { put("text", JsonPrimitive(part.text)) }
        is ContentPart.Reasoning -> buildJsonObject {
            put(
                "reasoningContent",
                buildJsonObject {
                    put(
                        "reasoningText",
                        buildJsonObject {
                            put("text", JsonPrimitive(part.text))
                            JsonAccess.obj(part.providerMetadata.toMap(), "bedrock")
                                ?.get("signature")
                                ?.let { put("signature", it) }
                        },
                    )
                },
            )
        }
        is ContentPart.ToolCall -> buildJsonObject {
            put(
                "toolUse",
                buildJsonObject {
                    put("toolUseId", JsonPrimitive(part.toolCallId))
                    put("name", JsonPrimitive(part.toolName))
                    put("input", part.input)
                },
            )
        }
        is ContentPart.Image -> null
        is ContentPart.File -> null
        is ContentPart.ToolResult -> null
        is ContentPart.ToolApprovalRequest -> null
        is ContentPart.ToolApprovalResponse -> null
        is ContentPart.Source -> null
        is ContentPart.Raw -> null
    }

    private fun bedrockToolResultPart(part: ContentPart): JsonElement? = when (part) {
        // Bedrock's toolResult has NO `status` field, and content blocks may only be
        // text/image/document — never `{json}` (a non-string output is JSON-stringified into a
        // text block, matching upstream).
        is ContentPart.ToolResult -> buildJsonObject {
            put(
                "toolResult",
                buildJsonObject {
                    put("toolUseId", JsonPrimitive(part.toolCallId))
                    put("content", JsonArray(listOf(bedrockToolResultContent(part.modelVisible))))
                },
            )
        }
        is ContentPart.ToolApprovalResponse -> buildJsonObject {
            put(
                "toolResult",
                buildJsonObject {
                    put("toolUseId", JsonPrimitive(part.toolCallId))
                    val text = part.reason ?: "Tool execution ${if (part.approved) "approved" else "denied"}."
                    put("content", JsonArray(listOf(buildJsonObject { put("text", JsonPrimitive(text)) })))
                },
            )
        }
        is ContentPart.Text -> null
        is ContentPart.Reasoning -> null
        is ContentPart.ToolCall -> null
        is ContentPart.ToolApprovalRequest -> null
        is ContentPart.Source -> null
        is ContentPart.File -> null
        is ContentPart.Image -> null
        is ContentPart.Raw -> null
    }

    private fun bedrockToolResultContent(output: JsonElement): JsonObject =
        buildJsonObject { put("text", JsonPrimitive(bedrockToolResultText(output))) }

    // Bedrock tool_result here is a single text block (it rejects a `json` block; image blocks would
    // need a multi-block array — tracked follow-up). Decode the wire wrapper and extract the real
    // content instead of stringifying it, so a Text/structured output isn't sent as a raw JSON blob.
    private fun bedrockToolResultText(output: JsonElement): String =
        when (val o = ToolResultOutputs.toolResultOutputFromWire(output)) {
            is ToolResultOutput.Text -> o.text
            is ToolResultOutput.Error -> o.message
            is ToolResultOutput.ExecutionDenied -> o.reason ?: "Tool execution denied."
            is ToolResultOutput.Json -> o.json.toString()
            is ToolResultOutput.ErrorJson -> o.json.toString()
            is ToolResultOutput.Content -> o.value.joinToString("") { item ->
                (
                    (item as? JsonObject)?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
                        ?.get("text") as? JsonPrimitive
                    )?.contentOrNull.orEmpty()
            }
        }

    private fun bedrockTools(
        modelId: String,
        tools: List<LanguageModelTool>,
        choice: ToolChoice,
        responseFormat: ResponseFormat,
    ): BedrockPreparedTools {
        val warnings = mutableListOf<CallWarning>()
        if (choice == ToolChoice.None) return BedrockPreparedTools(null, warnings, false)
        val preparedTools = mutableListOf<LanguageModelTool>()
        preparedTools += when (choice) {
            is ToolChoice.Specific -> tools.filter { it.name == choice.toolName }
            is ToolChoice.Auto -> tools
            is ToolChoice.Required -> tools
            is ToolChoice.None -> tools
        }
        val usesJsonResponseTool = responseFormat is ResponseFormat.Json && responseFormat.schemaJson != null
        if (usesJsonResponseTool) {
            preparedTools += LanguageModelTool("json", "Respond with a JSON object.", responseFormat.schemaJson.toString())
        }
        val bedrockTools = preparedTools.mapNotNull { tool ->
            if (tool.providerExecuted && !modelId.contains("anthropic")) {
                warnings += CallWarning("unsupported", "tool ${tool.name}")
                null
            } else {
                buildJsonObject {
                    put(
                        "toolSpec",
                        buildJsonObject {
                            put("name", JsonPrimitive(tool.name))
                            if (tool.description.isNotBlank()) put("description", JsonPrimitive(tool.description))
                            put(
                                "inputSchema",
                                buildJsonObject { put("json", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson)) }
                            )
                        },
                    )
                }
            }
        }
        if (bedrockTools.isEmpty()) return BedrockPreparedTools(null, warnings, usesJsonResponseTool)
        val toolChoice = when (choice) {
            ToolChoice.Auto -> buildJsonObject { put("auto", buildJsonObject { }) }
            ToolChoice.Required -> buildJsonObject { put("any", buildJsonObject { }) }
            is ToolChoice.Specific -> buildJsonObject {
                put(
                    "tool",
                    buildJsonObject { put("name", JsonPrimitive(choice.toolName)) }
                )
            }
            ToolChoice.None -> null
        }
        return BedrockPreparedTools(
            toolConfig = buildJsonObject {
                put("tools", JsonArray(bedrockTools))
                toolChoice?.let { put("toolChoice", it) }
            },
            warnings = warnings,
            usesJsonResponseTool = usesJsonResponseTool,
        )
    }

    private fun bedrockAdditionalModelRequestFields(
        options: JsonObject,
        modelId: String,
        responseFormat: ResponseFormat,
    ): JsonObject {
        val additional = JsonAccess.obj(options, "additionalModelRequestFields")?.toMutableMap() ?: mutableMapOf()
        val reasoningConfig = JsonAccess.obj(options, "reasoningConfig")
        val reasoningType = (reasoningConfig?.get("type") as? JsonPrimitive)?.contentOrNull
        val maxReasoningEffort = (reasoningConfig?.get("maxReasoningEffort") as? JsonPrimitive)?.contentOrNull
        if (modelId.contains("anthropic") && reasoningType in setOf("enabled", "adaptive")) {
            additional["thinking"] = buildJsonObject {
                put("type", JsonPrimitive(reasoningType))
                reasoningConfig?.get("budgetTokens")?.let { put("budget_tokens", it) }
                reasoningConfig?.get("display")?.let { put("display", it) }
            }
        }
        if (maxReasoningEffort != null) {
            if (modelId.contains("anthropic")) {
                val existing = JsonAccess.obj(additional, "output_config") ?: JsonObject(emptyMap())
                additional["output_config"] = JsonObject(existing + ("effort" to JsonPrimitive(maxReasoningEffort)))
            } else if (modelId.startsWith("openai.")) {
                additional["reasoning_effort"] = JsonPrimitive(maxReasoningEffort)
            } else {
                additional["reasoningConfig"] = buildJsonObject {
                    reasoningType?.let { put("type", JsonPrimitive(it)) }
                    reasoningConfig["budgetTokens"]?.let { put("budgetTokens", it) }
                    put("maxReasoningEffort", JsonPrimitive(maxReasoningEffort))
                }
            }
        }
        if (modelId.contains("anthropic") && responseFormat is ResponseFormat.Json && responseFormat.schemaJson != null && reasoningType in setOf("enabled", "adaptive")) {
            val existing = JsonAccess.obj(additional, "output_config") ?: JsonObject(emptyMap())
            additional["output_config"] = JsonObject(
                existing + (
                    "format" to buildJsonObject {
                        put("type", JsonPrimitive("json_schema"))
                        put("schema", responseFormat.schemaJson)
                    }
                    ),
            )
        }
        (JsonAccess.arr(options, "anthropicBeta"))?.let { additional["anthropic_beta"] = it }
        return JsonObject(additional)
    }

    fun bedrockEmbeddingBody(modelId: String, value: String, options: JsonObject): JsonObject =
        when {
            modelId.startsWith("amazon.nova-") && modelId.contains("embed") -> buildJsonObject {
                put("taskType", JsonPrimitive("SINGLE_EMBEDDING"))
                put(
                    "singleEmbeddingParams",
                    buildJsonObject {
                        put("embeddingPurpose", options["embeddingPurpose"] ?: JsonPrimitive("GENERIC_INDEX"))
                        put("embeddingDimension", options["embeddingDimension"] ?: JsonPrimitive(1024))
                        put(
                            "text",
                            buildJsonObject {
                                put("truncationMode", options["truncate"] ?: JsonPrimitive("END"))
                                put("value", JsonPrimitive(value))
                            },
                        )
                    },
                )
            }
            modelId.startsWith("cohere.embed-") -> buildJsonObject {
                put("input_type", options["inputType"] ?: JsonPrimitive("search_query"))
                put("texts", JsonArray(listOf(JsonPrimitive(value))))
                options["truncate"]?.let { put("truncate", it) }
                options["outputDimension"]?.let { put("output_dimension", it) }
            }
            else -> buildJsonObject {
                put("inputText", JsonPrimitive(value))
                options["dimensions"]?.let { put("dimensions", it) }
                options["normalize"]?.let { put("normalize", it) }
            }
        }

    fun bedrockImageBody(params: ImageGenerationParams): BedrockPreparedImageRequest {
        val warnings = mutableListOf<CallWarning>()
        if (params.aspectRatio != null) warnings += CallWarning("unsupported", "aspectRatio")
        val options = JsonAccess.obj(params.providerOptions.toMap(), "bedrock") ?: JsonObject(emptyMap())
        val sizeParts = params.size?.split("x")?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val imageGenerationConfig = buildJsonObject {
            sizeParts.getOrNull(0)?.let { put("width", JsonPrimitive(it)) }
            sizeParts.getOrNull(1)?.let { put("height", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            put("numberOfImages", JsonPrimitive(params.n))
            options["quality"]?.let { put("quality", it) }
            options["cfgScale"]?.let { put("cfgScale", it) }
        }
        val body = if (params.files.isNotEmpty()) {
            val taskType = (options["taskType"] as? JsonPrimitive)?.contentOrNull
                ?: if (params.mask != null || options["maskPrompt"] != null) "INPAINTING" else "IMAGE_VARIATION"
            when (taskType) {
                "INPAINTING" -> buildJsonObject {
                    put("taskType", JsonPrimitive("INPAINTING"))
                    put(
                        "inPaintingParams",
                        buildJsonObject {
                            put("image", JsonPrimitive(bedrockImageFileBase64(params.files.first())))
                            if (params.prompt.isNotBlank()) put("text", JsonPrimitive(params.prompt))
                            options["negativeText"]?.let { put("negativeText", it) }
                            params.mask?.let { put("maskImage", JsonPrimitive(bedrockImageFileBase64(it))) }
                            options["maskPrompt"]?.let { put("maskPrompt", it) }
                        },
                    )
                    put("imageGenerationConfig", imageGenerationConfig)
                }
                "OUTPAINTING" -> buildJsonObject {
                    put("taskType", JsonPrimitive("OUTPAINTING"))
                    put(
                        "outPaintingParams",
                        buildJsonObject {
                            put("image", JsonPrimitive(bedrockImageFileBase64(params.files.first())))
                            if (params.prompt.isNotBlank()) put("text", JsonPrimitive(params.prompt))
                            options["negativeText"]?.let { put("negativeText", it) }
                            options["outPaintingMode"]?.let { put("outPaintingMode", it) }
                            params.mask?.let { put("maskImage", JsonPrimitive(bedrockImageFileBase64(it))) }
                            options["maskPrompt"]?.let { put("maskPrompt", it) }
                        },
                    )
                    put("imageGenerationConfig", imageGenerationConfig)
                }
                "BACKGROUND_REMOVAL" -> buildJsonObject {
                    put("taskType", JsonPrimitive("BACKGROUND_REMOVAL"))
                    put(
                        "backgroundRemovalParams",
                        buildJsonObject { put("image", JsonPrimitive(bedrockImageFileBase64(params.files.first()))) }
                    )
                }
                else -> buildJsonObject {
                    put("taskType", JsonPrimitive("IMAGE_VARIATION"))
                    put(
                        "imageVariationParams",
                        buildJsonObject {
                            put("images", JsonArray(params.files.map { JsonPrimitive(bedrockImageFileBase64(it)) }))
                            if (params.prompt.isNotBlank()) put("text", JsonPrimitive(params.prompt))
                            options["negativeText"]?.let { put("negativeText", it) }
                            options["similarityStrength"]?.let { put("similarityStrength", it) }
                        },
                    )
                    put("imageGenerationConfig", imageGenerationConfig)
                }
            }
        } else {
            buildJsonObject {
                put("taskType", JsonPrimitive("TEXT_IMAGE"))
                put(
                    "textToImageParams",
                    buildJsonObject {
                        put("text", JsonPrimitive(params.prompt))
                        options["negativeText"]?.let { put("negativeText", it) }
                        options["style"]?.let { put("style", it) }
                    },
                )
                put("imageGenerationConfig", imageGenerationConfig)
            }
        }
        return BedrockPreparedImageRequest(body, warnings)
    }

    fun bedrockRerankBody(
        region: String,
        modelId: String,
        params: RerankingParams,
        options: JsonObject,
    ): JsonObject = buildJsonObject {
        options["nextToken"]?.let { put("nextToken", it) }
        put(
            "queries",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", JsonPrimitive("TEXT"))
                        put("textQuery", buildJsonObject { put("text", JsonPrimitive(params.query)) })
                    },
                ),
            ),
        )
        put(
            "rerankingConfiguration",
            buildJsonObject {
                put("type", JsonPrimitive("BEDROCK_RERANKING_MODEL"))
                put(
                    "bedrockRerankingConfiguration",
                    buildJsonObject {
                        put(
                            "modelConfiguration",
                            buildJsonObject {
                                put("modelArn", JsonPrimitive("arn:aws:bedrock:$region::foundation-model/$modelId"))
                                options["additionalModelRequestFields"]?.let { put("additionalModelRequestFields", it) }
                            },
                        )
                        params.topN?.let { put("numberOfResults", JsonPrimitive(it)) }
                    },
                )
            },
        )
        put(
            "sources",
            JsonArray(
                params.documents.map { document ->
                    buildJsonObject {
                        put("type", JsonPrimitive("INLINE"))
                        put(
                            "inlineDocumentSource",
                            buildJsonObject {
                                put("type", JsonPrimitive("TEXT"))
                                put("textDocument", buildJsonObject { put("text", JsonPrimitive(document)) })
                            },
                        )
                    }
                },
            ),
        )
    }

    fun bedrockMantleMessage(message: ModelMessage): JsonObject = buildJsonObject {
        put(
            "role",
            JsonPrimitive(
                when (message.role) {
                    MessageRole.System -> "system"
                    MessageRole.User -> "user"
                    MessageRole.Assistant -> "assistant"
                    MessageRole.Tool -> "tool"
                },
            ),
        )
        put("content", JsonPrimitive(message.content.joinToString("") { if (it is ContentPart.Text) it.text else "" }))
    }

    fun bedrockMantleTool(tool: LanguageModelTool): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put(
            "function",
            buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("description", JsonPrimitive(tool.description))
                put("parameters", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
            },
        )
    }

    private fun bedrockImageFileBase64(file: ImageGenerationFile): String =
        file.base64 ?: throw UnsupportedFunctionalityError("url-based images", "URL-based images are not supported for Amazon Bedrock image editing. Provide base64 data directly.")

    private fun bedrockImageFormat(mediaType: String): String =
        mediaType.substringAfter("image/", "png").substringBefore("+").substringBefore(";")

    private fun bedrockDocumentFormat(mediaType: String): String = when (mediaType) {
        "application/pdf" -> "pdf"
        "text/csv" -> "csv"
        "text/html" -> "html"
        "text/plain" -> "txt"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/msword" -> "doc"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.ms-excel" -> "xls"
        else -> mediaType.substringAfterLast('/').substringAfterLast('.')
    }

    private fun bedrockCachePoint(metadata: Map<String, JsonElement>?): JsonElement? =
        (metadata?.get("bedrock") as? JsonObject)?.get("cachePoint")

    private fun bedrockCitationsEnabled(metadata: Map<String, JsonElement>?): Boolean =
        (
            ((metadata?.get("bedrock") as? JsonObject)?.get("citations") as? JsonObject)
                ?.get("enabled") as? JsonPrimitive
            )?.contentOrNull == "true"
}
