@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
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

private data class OpenResponsesRequestBuildContext(
    val params: LanguageModelCallParams,
    val stream: Boolean,
    val modelId: String,
    val convertedInput: ConvertedOpenResponsesInput,
    val providerOptions: OpenResponsesOptions?,
    val include: JsonArray?,
    val topLogprobs: Int?,
)

internal fun PreparedOpenResponsesRequest(
    params: LanguageModelCallParams,
    stream: Boolean,
    providerOptionsName: String,
    modelId: String,
    fileIdPrefixes: List<String> = emptyList(),
): PreparedOpenResponsesRequest {
    val warnings = UnsupportedWarnings(params)
    val convertedInput = ConvertedOpenResponsesInput(params.messages, warnings, fileIdPrefixes)
    val providerOptions = OpenResponsesProviderOptions(params.providerOptions, providerOptionsName)
    val topLogprobs = OpenResponsesTopLogprobs(providerOptions?.logprobs)
    val include = OpenResponsesInclude(providerOptions, params.tools, modelId, topLogprobs)
    val context = OpenResponsesRequestBuildContext(
        params = params,
        stream = stream,
        modelId = modelId,
        convertedInput = convertedInput,
        providerOptions = providerOptions,
        include = include,
        topLogprobs = topLogprobs,
    )
    return PreparedOpenResponsesRequest(
        body = OpenResponsesRequestBody(context),
        warnings = warnings,
    )
}

private fun UnsupportedWarnings(params: LanguageModelCallParams): MutableList<CallWarning> = buildList {
    if (params.stopSequences.isNotEmpty()) {
        add(CallWarning("unsupported", "stopSequences are not supported by Open Responses models"))
    }
    params.topK?.let {
        add(CallWarning("unsupported", "topK is not supported by Open Responses models"))
    }
    params.seed?.let {
        add(CallWarning("unsupported", "seed is not supported by Open Responses models"))
    }
}.toMutableList()

private fun OpenResponsesRequestBody(context: OpenResponsesRequestBuildContext): JsonObject =
    buildJsonObject {
        PutCoreRequestFields(this, context)
        PutToolAndTextFields(this, context)
        PutProviderOptionFields(this, context)
    }

private fun PutCoreRequestFields(builder: JsonObjectBuilder, context: OpenResponsesRequestBuildContext) {
    val params = context.params
    val providerOptions = context.providerOptions
    builder.put("model", JsonPrimitive(context.modelId))
    builder.put("input", context.convertedInput.input)
    val instructions = when (providerOptions?.systemMessageMode) {
        "remove" -> providerOptions.instructions
        else -> providerOptions?.instructions ?: context.convertedInput.instructions
    }
    instructions?.let { builder.put("instructions", JsonPrimitive(it)) }
    params.maxOutputTokens?.let { builder.put("max_output_tokens", JsonPrimitive(it)) }
    params.temperature?.let { builder.put("temperature", JsonPrimitive(it)) }
    params.topP?.let { builder.put("top_p", JsonPrimitive(it)) }
    params.presencePenalty?.let { builder.put("presence_penalty", JsonPrimitive(it)) }
    params.frequencyPenalty?.let { builder.put("frequency_penalty", JsonPrimitive(it)) }
    if (context.stream) builder.put("stream", JsonPrimitive(true))
}

private fun PutToolAndTextFields(builder: JsonObjectBuilder, context: OpenResponsesRequestBuildContext) {
    val params = context.params
    val providerOptions = context.providerOptions
    val reasoning = OpenResponsesReasoning(providerOptions)
    if (reasoning.isNotEmpty()) builder.put("reasoning", JsonObject(reasoning))
    if (params.tools.isNotEmpty()) builder.put("tools", JsonArray(params.tools.map(::OpenResponsesToolJson)))
    OpenResponsesToolChoice(params.toolChoice, providerOptions)?.let { builder.put("tool_choice", it) }
    val text = OpenResponsesText(params.responseFormat, providerOptions)
    if (text.isNotEmpty()) builder.put("text", JsonObject(text))
}

private fun PutProviderOptionFields(builder: JsonObjectBuilder, context: OpenResponsesRequestBuildContext) {
    val providerOptions = context.providerOptions
    PutStringOption(builder, "conversation", providerOptions?.conversation)
    PutIntOption(builder, "max_tool_calls", providerOptions?.maxToolCalls)
    PutJsonOption(builder, "metadata", providerOptions?.metadata)
    PutBooleanOption(builder, "parallel_tool_calls", providerOptions?.parallelToolCalls)
    PutStringOption(builder, "previous_response_id", providerOptions?.previousResponseId)
    PutStringOption(builder, "prompt_cache_key", providerOptions?.promptCacheKey)
    PutStringOption(builder, "prompt_cache_retention", providerOptions?.promptCacheRetention)
    PutStringOption(builder, "safety_identifier", providerOptions?.safetyIdentifier)
    PutStringOption(builder, "service_tier", providerOptions?.serviceTier)
    PutBooleanOption(builder, "store", providerOptions?.store)
    PutStringOption(builder, "truncation", providerOptions?.truncation)
    PutStringOption(builder, "user", providerOptions?.user)
    PutJsonOption(builder, "include", context.include)
    PutIntOption(builder, "top_logprobs", context.topLogprobs)
}

private fun PutStringOption(builder: JsonObjectBuilder, name: String, value: String?) {
    if (value != null) builder.put(name, JsonPrimitive(value))
}

private fun PutIntOption(builder: JsonObjectBuilder, name: String, value: Int?) {
    if (value != null) builder.put(name, JsonPrimitive(value))
}

private fun PutBooleanOption(builder: JsonObjectBuilder, name: String, value: Boolean?) {
    if (value != null) builder.put(name, JsonPrimitive(value))
}

private fun PutJsonOption(builder: JsonObjectBuilder, name: String, value: JsonElement?) {
    if (value != null) builder.put(name, value)
}

private fun OpenResponsesProviderOptions(
    providerOptions: ProviderOptions,
    providerOptionsName: String,
): OpenResponsesOptions? {
    val poMap = providerOptions.toMap()
    val element = poMap[providerOptionsName] ?: poMap["open-responses"] ?: return null
    // Surface a malformed options block instead of swallowing it to null — getOrNull() here
    // silently dropped EVERY user option (instructions, reasoningEffort, store, …) on a single
    // wrong-typed field, with no error and no clue why the request behaved as if unconfigured.
    return try {
        WireDecoder.decode(
            OpenResponsesOptions.serializer(),
            element,
            provider = "Open Responses",
            operation = "provider options",
            path = "$.providerOptions.$providerOptionsName",
        )
    } catch (e: WireDecodeException) {
        throw InvalidArgumentError(
            "providerOptions.$providerOptionsName",
            "could not decode OpenResponses provider options: ${e.message ?: "<no message>"}",
            e,
        )
    }
}

private fun OpenResponsesReasoning(options: OpenResponsesOptions?): Map<String, JsonElement> = buildMap {
    options?.reasoningEffort?.let { put("effort", JsonPrimitive(it)) }
    options?.reasoningSummary?.let { put("summary", JsonPrimitive(it)) }
}

private fun OpenResponsesToolJson(tool: LanguageModelTool): JsonObject =
    if (tool.providerExecuted) OpenResponsesProviderToolJson(tool) else OpenResponsesFunctionToolJson(tool)

private fun OpenResponsesFunctionToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("function"))
    put("name", JsonPrimitive(tool.name))
    put("description", JsonPrimitive(tool.description))
    put("parameters", openResponsesJson.parseToJsonElement(tool.parametersSchemaJson))
    tool.strict?.let { put("strict", JsonPrimitive(it)) }
}

private fun OpenResponsesProviderToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
    val args = OpenResponsesProviderToolArgs(tool)
    when (val type = OpenResponsesProviderToolType(tool)) {
        "file_search" -> {
            put("type", JsonPrimitive(type))
            PutOpenResponsesField("vector_store_ids", args["vectorStoreIds"] ?: args["vector_store_ids"])
            PutOpenResponsesField("max_num_results", args["maxNumResults"] ?: args["max_num_results"])
            OpenResponsesRankingOptions(args)?.let { put("ranking_options", it) }
            PutOpenResponsesField("filters", args["filters"])
        }
        "web_search", "web_search_preview" -> {
            put("type", JsonPrimitive(type))
            PutOpenResponsesField(
                "external_web_access",
                args["externalWebAccess"] ?: args["external_web_access"]
            )
            PutOpenResponsesField("filters", OpenResponsesWebSearchFilters(args["filters"]))
            PutOpenResponsesField(
                "search_context_size",
                args["searchContextSize"] ?: args["search_context_size"]
            )
            PutOpenResponsesField("user_location", args["userLocation"] ?: args["user_location"])
        }
        "code_interpreter" -> {
            put("type", JsonPrimitive(type))
            put("container", OpenResponsesCodeInterpreterContainer(args["container"]))
        }
        "image_generation" -> {
            put("type", JsonPrimitive(type))
            PutOpenResponsesField("background", args["background"])
            PutOpenResponsesField("input_fidelity", args["inputFidelity"] ?: args["input_fidelity"])
            PutOpenResponsesField(
                "input_image_mask",
                OpenResponsesInputImageMask(args["inputImageMask"] ?: args["input_image_mask"])
            )
            PutOpenResponsesField("model", args["model"])
            PutOpenResponsesField("moderation", args["moderation"])
            PutOpenResponsesField("partial_images", args["partialImages"] ?: args["partial_images"])
            PutOpenResponsesField("quality", args["quality"])
            PutOpenResponsesField("output_compression", args["outputCompression"] ?: args["output_compression"])
            PutOpenResponsesField("output_format", args["outputFormat"] ?: args["output_format"])
            PutOpenResponsesField("size", args["size"])
        }
        "mcp" -> {
            put("type", JsonPrimitive(type))
            PutOpenResponsesField("server_label", args["serverLabel"] ?: args["server_label"])
            PutOpenResponsesField(
                "allowed_tools",
                OpenResponsesAllowedMcpTools(args["allowedTools"] ?: args["allowed_tools"])
            )
            PutOpenResponsesField("authorization", args["authorization"])
            PutOpenResponsesField("connector_id", args["connectorId"] ?: args["connector_id"])
            PutOpenResponsesField("headers", args["headers"])
            PutOpenResponsesField(
                "require_approval",
                OpenResponsesRequireApproval(args["requireApproval"] ?: args["require_approval"])
            )
            PutOpenResponsesField("server_description", args["serverDescription"] ?: args["server_description"])
            PutOpenResponsesField("server_url", args["serverUrl"] ?: args["server_url"])
        }
        "shell" -> {
            put("type", JsonPrimitive(type))
            PutOpenResponsesField("environment", OpenResponsesShellEnvironment(args["environment"]))
        }
        "tool_search" -> {
            put("type", JsonPrimitive(type))
            PutOpenResponsesField("execution", args["execution"])
            PutOpenResponsesField("description", args["description"])
            PutOpenResponsesField("parameters", args["parameters"])
        }
        "custom" -> {
            put("type", JsonPrimitive(type))
            PutOpenResponsesField("name", args["name"] ?: JsonPrimitive(tool.name))
            PutOpenResponsesField("description", args["description"] ?: JsonPrimitive(tool.description))
            PutOpenResponsesField("format", args["format"])
        }
        else -> put("type", JsonPrimitive(type))
    }
}

private fun OpenResponsesToolChoice(
    choice: ToolChoice,
    options: OpenResponsesOptions?,
): JsonElement? {
    options?.allowedTools?.takeIf { it.toolNames.isNotEmpty() }?.let { allowed ->
        return buildJsonObject {
            put("type", JsonPrimitive("allowed_tools"))
            put("mode", JsonPrimitive(allowed.mode ?: "auto"))
            put(
                "tools",
                JsonArray(
                    allowed.toolNames.map { name ->
                        buildJsonObject {
                            put("type", JsonPrimitive("function"))
                            put("name", JsonPrimitive(name))
                        }
                    },
                ),
            )
        }
    }
    return when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Specific -> buildJsonObject {
            val providerToolType = OpenResponsesProviderToolTypeOrNull(choice.toolName)
            if (providerToolType != null) {
                put("type", JsonPrimitive(providerToolType))
            } else {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(choice.toolName))
            }
        }
    }
}

private fun OpenResponsesText(
    format: ResponseFormat,
    options: OpenResponsesOptions?,
): Map<String, JsonElement> = buildMap {
    OpenResponsesTextFormat(format, options?.strictJsonSchema ?: true)?.let { put("format", it) }
    options?.textVerbosity?.let { put("verbosity", JsonPrimitive(it)) }
}

private fun OpenResponsesTextFormat(format: ResponseFormat, strict: Boolean): JsonElement? = when (format) {
    ResponseFormat.Text -> null
    // No schema → plain json_object mode. A json_schema format with no `schema` key is malformed.
    is ResponseFormat.Json -> if (format.schemaJson == null) {
        buildJsonObject { put("type", JsonPrimitive("json_object")) }
    } else {
        buildJsonObject {
            put("type", JsonPrimitive("json_schema"))
            put("name", JsonPrimitive(format.schemaName ?: "response"))
            format.schemaDescription?.let { put("description", JsonPrimitive(it)) }
            put("schema", format.schemaJson)
            put("strict", JsonPrimitive(strict))
        }
    }
}

private fun OpenResponsesTopLogprobs(logprobs: JsonElement?): Int? {
    val primitive = (logprobs as? JsonPrimitive) ?: return null
    primitive.booleanOrNull?.let { return if (it) OPEN_RESPONSES_TOP_LOGPROBS_MAX else null }
    return primitive.intOrNull?.coerceIn(1, OPEN_RESPONSES_TOP_LOGPROBS_MAX)
}

private fun OpenResponsesInclude(
    options: OpenResponsesOptions?,
    tools: List<LanguageModelTool>,
    modelId: String,
    topLogprobs: Int?,
): JsonArray? {
    val include = linkedSetOf<String>()
    options?.include.orEmpty().forEach { include += it }
    if (topLogprobs != null) include += "message.output_text.logprobs"
    if (tools.any { it.providerExecuted && it.name in setOf("web_search", "web_search_preview") }) {
        include += "web_search_call.action.sources"
    }
    if (tools.any { it.providerExecuted && it.name == "code_interpreter" }) {
        include += "code_interpreter_call.outputs"
    }
    if (options?.store == false && IsOpenResponsesReasoningModel(modelId, options)) {
        include += "reasoning.encrypted_content"
    }
    return include.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) }
}

private fun JsonObjectBuilder.PutOpenResponsesField(name: String, value: JsonElement?) {
    if (value != null && value !is JsonNull) put(name, value)
}

private fun OpenResponsesProviderToolArgs(tool: LanguageModelTool): JsonObject =
    (JsonAccess.obj(tool.metadata, "providerToolArgs"))
        ?: (JsonAccess.obj(tool.metadata, "providerOptions"))
        ?: JsonObject(emptyMap())

private fun OpenResponsesProviderToolType(tool: LanguageModelTool): String {
    val providerToolId = (tool.metadata["providerToolId"] as? JsonPrimitive)?.contentOrNull
    return providerToolId?.removePrefix("openai.") ?: OpenResponsesProviderToolTypeOrNull(tool.name) ?: "custom"
}

private fun OpenResponsesProviderToolTypeOrNull(toolName: String): String? = when (toolName) {
    "apply_patch",
    "code_interpreter",
    "file_search",
    "image_generation",
    "local_shell",
    "mcp",
    "shell",
    "tool_search",
    "web_search",
    "web_search_preview",
    -> toolName
    else -> null
}

private fun OpenResponsesRankingOptions(args: JsonObject): JsonObject? {
    val ranking = JsonAccess.obj(args, "ranking") ?: return null
    val mapped = buildJsonObject {
        PutOpenResponsesField("ranker", ranking["ranker"])
        PutOpenResponsesField("score_threshold", ranking["scoreThreshold"] ?: ranking["score_threshold"])
    }
    return mapped.takeIf { it.isNotEmpty() }
}

private fun OpenResponsesWebSearchFilters(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    val allowedDomains = obj["allowedDomains"] ?: obj["allowed_domains"]
    return if (allowedDomains == null) value else buildJsonObject { put("allowed_domains", allowedDomains) }
}

private fun OpenResponsesCodeInterpreterContainer(value: JsonElement?): JsonElement =
    when (value) {
        null, JsonNull -> buildJsonObject { put("type", JsonPrimitive("auto")) }
        is JsonPrimitive -> value
        is JsonObject -> buildJsonObject {
            put("type", JsonPrimitive("auto"))
            PutOpenResponsesField("file_ids", value["fileIds"] ?: value["file_ids"])
        }
        else -> value
    }

private fun OpenResponsesInputImageMask(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    return buildJsonObject {
        PutOpenResponsesField("file_id", obj["fileId"] ?: obj["file_id"])
        PutOpenResponsesField("image_url", obj["imageUrl"] ?: obj["image_url"])
    }.takeIf { it.isNotEmpty() }
}

private fun OpenResponsesAllowedMcpTools(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    return buildJsonObject {
        PutOpenResponsesField("read_only", obj["readOnly"] ?: obj["read_only"])
        PutOpenResponsesField("tool_names", obj["toolNames"] ?: obj["tool_names"])
    }.takeIf { it.isNotEmpty() }
}

private fun OpenResponsesRequireApproval(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    val never = JsonAccess.obj(obj, "never") ?: return value
    return buildJsonObject {
        put(
            "never",
            buildJsonObject {
                PutOpenResponsesField("tool_names", never["toolNames"] ?: never["tool_names"])
            },
        )
    }
}

private fun OpenResponsesShellEnvironment(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
        "containerReference" -> buildJsonObject {
            put("type", JsonPrimitive("container_reference"))
            PutOpenResponsesField("container_id", obj["containerId"] ?: obj["container_id"])
        }
        "containerAuto" -> buildJsonObject {
            put("type", JsonPrimitive("container_auto"))
            PutOpenResponsesField("file_ids", obj["fileIds"] ?: obj["file_ids"])
            PutOpenResponsesField("memory_limit", obj["memoryLimit"] ?: obj["memory_limit"])
            PutOpenResponsesField("network_policy", obj["networkPolicy"] ?: obj["network_policy"])
            PutOpenResponsesField("skills", obj["skills"])
        }
        else -> value
    }
}

private fun IsOpenResponsesReasoningModel(modelId: String, options: OpenResponsesOptions?): Boolean =
    options?.forceReasoning == true ||
        modelId == "o1" ||
        modelId.startsWith("o1-") ||
        modelId == "o3" ||
        modelId.startsWith("o3-") ||
        modelId == "o3-mini" ||
        modelId.startsWith("o3-mini-") ||
        modelId == "o4-mini" ||
        modelId.startsWith("o4-mini-") ||
        modelId == "gpt-5" ||
        modelId.startsWith("gpt-5-") ||
        modelId.startsWith("gpt-5.") ||
        modelId.startsWith("gpt-5_")

internal data class PreparedOpenResponsesRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)
