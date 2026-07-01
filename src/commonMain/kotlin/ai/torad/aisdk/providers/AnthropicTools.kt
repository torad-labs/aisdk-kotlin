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

@Poko
/** @since 0.3.0-beta01 */
public class AnthropicTools(
    /** @since 0.3.0-beta01 */
    public val advisor_20260301: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("advisor", "anthropic.advisor_20260301", "Consult an Anthropic advisor model during generation."),
    /** @since 0.3.0-beta01 */
    public val bash_20241022: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("bash", "anthropic.bash_20241022", "Use Anthropic's hosted Bash tool."),
    /** @since 0.3.0-beta01 */
    public val bash_20250124: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("bash", "anthropic.bash_20250124", "Use Anthropic's hosted Bash tool."),
    /** @since 0.3.0-beta01 */
    public val codeExecution_20250522: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("code_execution", "anthropic.code_execution_20250522", "Use Anthropic hosted code execution."),
    /** @since 0.3.0-beta01 */
    public val codeExecution_20250825: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("code_execution", "anthropic.code_execution_20250825", "Use Anthropic hosted code execution."),
    /** @since 0.3.0-beta01 */
    public val codeExecution_20260120: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("code_execution", "anthropic.code_execution_20260120", "Use Anthropic hosted code execution."),
    /** @since 0.3.0-beta01 */
    public val computer_20241022: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("computer", "anthropic.computer_20241022", "Use Anthropic computer control."),
    /** @since 0.3.0-beta01 */
    public val computer_20250124: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("computer", "anthropic.computer_20250124", "Use Anthropic computer control."),
    /** @since 0.3.0-beta01 */
    public val computer_20251124: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("computer", "anthropic.computer_20251124", "Use Anthropic computer control with zoom."),
    /** @since 0.3.0-beta01 */
    public val memory_20250818: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("memory", "anthropic.memory_20250818", "Use Anthropic memory."),
    /** @since 0.3.0-beta01 */
    public val textEditor_20241022: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("str_replace_editor", "anthropic.text_editor_20241022", "Use Anthropic text editor."),
    /** @since 0.3.0-beta01 */
    public val textEditor_20250124: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("str_replace_editor", "anthropic.text_editor_20250124", "Use Anthropic text editor."),
    /** @since 0.3.0-beta01 */
    public val textEditor_20250429: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("str_replace_based_edit_tool", "anthropic.text_editor_20250429", "Use Anthropic text editor."),
    /** @since 0.3.0-beta01 */
    public val textEditor_20250728: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("str_replace_based_edit_tool", "anthropic.text_editor_20250728", "Use Anthropic text editor."),
    /** @since 0.3.0-beta01 */
    public val webFetch_20250910: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("web_fetch", "anthropic.web_fetch_20250910", "Fetch web content through Anthropic."),
    /** @since 0.3.0-beta01 */
    public val webFetch_20260209: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("web_fetch", "anthropic.web_fetch_20260209", "Fetch web content through Anthropic."),
    /** @since 0.3.0-beta01 */
    public val webSearch_20250305: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("web_search", "anthropic.web_search_20250305", "Search the web through Anthropic."),
    /** @since 0.3.0-beta01 */
    public val webSearch_20260209: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("web_search", "anthropic.web_search_20260209", "Search the web through Anthropic."),
    /** @since 0.3.0-beta01 */
    public val toolSearchRegex_20251119: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("tool_search_tool_regex", "anthropic.tool_search_regex_20251119", "Search deferred tools with regex."),
    /** @since 0.3.0-beta01 */
    public val toolSearchBm25_20251119: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("tool_search_tool_bm25", "anthropic.tool_search_bm25_20251119", "Search deferred tools with BM25."),
) {
    internal companion object {
        internal fun anthropicPrepareTools(
            tools: List<LanguageModelTool>,
            choice: ToolChoice,
            options: JsonObject,
            responseFormat: ResponseFormat,
        ): PreparedAnthropicTools {
            if (choice == ToolChoice.None) return PreparedAnthropicTools(null, null, emptyList(), emptySet())
            val warnings = mutableListOf<CallWarning>()
            val betas = linkedSetOf<String>()
            val disableParallel = (options["disableParallelToolUse"] as? JsonPrimitive)?.booleanOrNull
            val toolStreaming = (options["toolStreaming"] as? JsonPrimitive)?.booleanOrNull ?: true
            val prepared = mutableListOf<JsonElement>()

            for (tool in tools) {
                if (tool.providerExecuted) {
                    val mapped = anthropicProviderExecutedTool(tool, betas)
                    if (mapped == null) {
                        warnings += CallWarning("unsupported", "provider-defined tool ${tool.name}")
                    } else {
                        val cacheControl = AnthropicProviderSettings.anthropicCacheControl(tool.providerOptions.toMap())
                            ?: AnthropicProviderSettings.anthropicCacheControl(tool.metadata)
                        prepared += if (cacheControl == null) mapped else JsonObject(mapped + ("cache_control" to cacheControl))
                    }
                } else {
                    prepared += buildJsonObject {
                        put("name", JsonPrimitive(tool.name))
                        put("description", JsonPrimitive(tool.description))
                        put("input_schema", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
                        if (toolStreaming) put("eager_input_streaming", JsonPrimitive(true))
                        tool.strict?.let { put("strict", JsonPrimitive(it)) }
                        val cacheControl = AnthropicProviderSettings.anthropicCacheControl(tool.providerOptions.toMap())
                            ?: AnthropicProviderSettings.anthropicCacheControl(tool.metadata)
                        cacheControl?.let { put("cache_control", it) }
                    }
                    betas += "structured-outputs-2025-11-13"
                }
            }

            if (responseFormat is ResponseFormat.Json && responseFormat.schemaJson != null) {
                // Native output_config handles structured output; no JSON tool fallback needed for the KMP facade.
            }

            val toolChoice = when (choice) {
                ToolChoice.Auto -> if (prepared.isEmpty() && disableParallel != true) null else buildJsonObject {
                    put("type", JsonPrimitive("auto"))
                    disableParallel?.let { put("disable_parallel_tool_use", JsonPrimitive(it)) }
                }
                ToolChoice.Required -> buildJsonObject {
                    put("type", JsonPrimitive("any"))
                    disableParallel?.let { put("disable_parallel_tool_use", JsonPrimitive(it)) }
                }
                is ToolChoice.Specific -> buildJsonObject {
                    put("type", JsonPrimitive("tool"))
                    put("name", JsonPrimitive(choice.toolName))
                    disableParallel?.let { put("disable_parallel_tool_use", JsonPrimitive(it)) }
                }
                ToolChoice.None -> null
            }

            return PreparedAnthropicTools(
                tools = prepared.takeIf { it.isNotEmpty() }?.let(::JsonArray),
                toolChoice = toolChoice,
                warnings = warnings,
                betas = betas,
            )
        }

        internal fun anthropicProviderExecutedTool(tool: LanguageModelTool, betas: MutableSet<String>): JsonObject? {
            val providerToolId = (tool.metadata["providerToolId"] as? JsonPrimitive)?.contentOrNull
                ?: when (tool.name) {
                    "code_execution" -> "anthropic.code_execution_20260120"
                    "bash" -> "anthropic.bash_20250124"
                    "computer" -> "anthropic.computer_20251124"
                    "memory" -> "anthropic.memory_20250818"
                    "web_search" -> "anthropic.web_search_20260209"
                    "web_fetch" -> "anthropic.web_fetch_20260209"
                    "str_replace_editor", "str_replace_based_edit_tool" -> "anthropic.text_editor_20250728"
                    "tool_search_tool_regex" -> "anthropic.tool_search_regex_20251119"
                    "tool_search_tool_bm25" -> "anthropic.tool_search_bm25_20251119"
                    "advisor" -> "anthropic.advisor_20260301"
                    else -> tool.name
                }
            val args = tool.metadata["args"] as? JsonObject ?: JsonObject(emptyMap())
            fun JsonObjectBuilder.putArg(wireName: String, vararg names: String) {
                names.firstNotNullOfOrNull { args[it] }?.let { put(wireName, it) }
            }

            return when (providerToolId) {
                "anthropic.code_execution_20250522" -> {
                    betas += "code-execution-2025-05-22"
                    buildJsonObject { put("type", JsonPrimitive("code_execution_20250522")); put("name", JsonPrimitive("code_execution")) }
                }
                "anthropic.code_execution_20250825" -> {
                    betas += "code-execution-2025-08-25"
                    buildJsonObject { put("type", JsonPrimitive("code_execution_20250825")); put("name", JsonPrimitive("code_execution")) }
                }
                "anthropic.code_execution_20260120" ->
                    buildJsonObject { put("type", JsonPrimitive("code_execution_20260120")); put("name", JsonPrimitive("code_execution")) }
                "anthropic.bash_20241022" -> {
                    betas += "computer-use-2024-10-22"
                    buildJsonObject { put("type", JsonPrimitive("bash_20241022")); put("name", JsonPrimitive("bash")) }
                }
                "anthropic.bash_20250124" -> {
                    betas += "computer-use-2025-01-24"
                    buildJsonObject { put("type", JsonPrimitive("bash_20250124")); put("name", JsonPrimitive("bash")) }
                }
                "anthropic.computer_20241022" -> {
                    betas += "computer-use-2024-10-22"
                    buildJsonObject {
                        put("type", JsonPrimitive("computer_20241022"))
                        put("name", JsonPrimitive("computer"))
                        putArg("display_width_px", "displayWidthPx", "display_width_px")
                        putArg("display_height_px", "displayHeightPx", "display_height_px")
                        putArg("display_number", "displayNumber", "display_number")
                    }
                }
                "anthropic.computer_20250124" -> {
                    betas += "computer-use-2025-01-24"
                    buildJsonObject {
                        put("type", JsonPrimitive("computer_20250124"))
                        put("name", JsonPrimitive("computer"))
                        putArg("display_width_px", "displayWidthPx", "display_width_px")
                        putArg("display_height_px", "displayHeightPx", "display_height_px")
                        putArg("display_number", "displayNumber", "display_number")
                    }
                }
                "anthropic.computer_20251124" -> {
                    betas += "computer-use-2025-11-24"
                    buildJsonObject {
                        put("type", JsonPrimitive("computer_20251124"))
                        put("name", JsonPrimitive("computer"))
                        putArg("display_width_px", "displayWidthPx", "display_width_px")
                        putArg("display_height_px", "displayHeightPx", "display_height_px")
                        putArg("display_number", "displayNumber", "display_number")
                        putArg("enable_zoom", "enableZoom", "enable_zoom")
                    }
                }
                "anthropic.memory_20250818" -> {
                    betas += "context-management-2025-06-27"
                    buildJsonObject { put("type", JsonPrimitive("memory_20250818")); put("name", JsonPrimitive("memory")) }
                }
                "anthropic.text_editor_20241022" -> {
                    betas += "computer-use-2024-10-22"
                    buildJsonObject { put("type", JsonPrimitive("text_editor_20241022")); put("name", JsonPrimitive("str_replace_editor")) }
                }
                "anthropic.text_editor_20250124" -> {
                    betas += "computer-use-2025-01-24"
                    buildJsonObject { put("type", JsonPrimitive("text_editor_20250124")); put("name", JsonPrimitive("str_replace_editor")) }
                }
                "anthropic.text_editor_20250429" -> {
                    betas += "computer-use-2025-01-24"
                    buildJsonObject { put("type", JsonPrimitive("text_editor_20250429")); put("name", JsonPrimitive("str_replace_based_edit_tool")) }
                }
                "anthropic.text_editor_20250728" -> buildJsonObject {
                    put("type", JsonPrimitive("text_editor_20250728"))
                    put("name", JsonPrimitive("str_replace_based_edit_tool"))
                    putArg("max_characters", "maxCharacters", "max_characters")
                }
                "anthropic.web_fetch_20250910" -> {
                    betas += "web-fetch-2025-09-10"
                    buildJsonObject {
                        put("type", JsonPrimitive("web_fetch_20250910"))
                        put("name", JsonPrimitive("web_fetch"))
                        putArg("max_uses", "maxUses", "max_uses")
                        putArg("allowed_domains", "allowedDomains", "allowed_domains")
                        putArg("blocked_domains", "blockedDomains", "blocked_domains")
                        putArg("citations", "citations")
                        putArg("max_content_tokens", "maxContentTokens", "max_content_tokens")
                    }
                }
                "anthropic.web_fetch_20260209" -> {
                    betas += "code-execution-web-tools-2026-02-09"
                    buildJsonObject {
                        put("type", JsonPrimitive("web_fetch_20260209"))
                        put("name", JsonPrimitive("web_fetch"))
                        putArg("max_uses", "maxUses", "max_uses")
                        putArg("allowed_domains", "allowedDomains", "allowed_domains")
                        putArg("blocked_domains", "blockedDomains", "blocked_domains")
                        putArg("citations", "citations")
                        putArg("max_content_tokens", "maxContentTokens", "max_content_tokens")
                    }
                }
                "anthropic.web_search_20250305" -> buildJsonObject {
                    put("type", JsonPrimitive("web_search_20250305"))
                    put("name", JsonPrimitive("web_search"))
                    putArg("max_uses", "maxUses", "max_uses")
                    putArg("allowed_domains", "allowedDomains", "allowed_domains")
                    putArg("blocked_domains", "blockedDomains", "blocked_domains")
                    putArg("user_location", "userLocation", "user_location")
                }
                "anthropic.web_search_20260209" -> {
                    betas += "code-execution-web-tools-2026-02-09"
                    buildJsonObject {
                        put("type", JsonPrimitive("web_search_20260209"))
                        put("name", JsonPrimitive("web_search"))
                        putArg("max_uses", "maxUses", "max_uses")
                        putArg("allowed_domains", "allowedDomains", "allowed_domains")
                        putArg("blocked_domains", "blockedDomains", "blocked_domains")
                        putArg("user_location", "userLocation", "user_location")
                    }
                }
                "anthropic.tool_search_regex_20251119" ->
                    buildJsonObject { put("type", JsonPrimitive("tool_search_tool_regex_20251119")); put("name", JsonPrimitive("tool_search_tool_regex")) }
                "anthropic.tool_search_bm25_20251119" ->
                    buildJsonObject { put("type", JsonPrimitive("tool_search_tool_bm25_20251119")); put("name", JsonPrimitive("tool_search_tool_bm25")) }
                "anthropic.advisor_20260301" -> {
                    betas += "advisor-tool-2026-03-01"
                    buildJsonObject {
                        put("type", JsonPrimitive("advisor_20260301"))
                        put("name", JsonPrimitive("advisor"))
                        putArg("model", "model")
                        putArg("max_uses", "maxUses", "max_uses")
                        putArg("caching", "caching")
                    }
                }
                else -> null
            }
        }

        internal fun anthropicProviderTool(
            name: String,
            id: String,
            description: String,
        ): Tool<JsonElement, JsonElement, Any?> =
            ProviderExecutedTool(
                name = name,
                description = description,
                inputSerializer = JsonElement.serializer(),
                outputSerializer = JsonElement.serializer(),
                metadata = mapOf("providerToolId" to JsonPrimitive(id)),
            )
    }
}


internal data class PreparedAnthropicTools(
    val tools: JsonArray?,
    val toolChoice: JsonElement?,
    val warnings: List<CallWarning>,
    val betas: Set<String>,
)

