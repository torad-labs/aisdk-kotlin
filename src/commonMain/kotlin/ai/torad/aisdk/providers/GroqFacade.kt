package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeSupport.intField
import ai.torad.aisdk.providers.FacadeSupport.nestedIntField
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

public const val GROQ_VERSION: String = "3.0.39"

@Serializable
@Poko
public class GroqProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val baseURL: String = "https://api.groq.com/openai/v1",
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun toCompatible(
        name: String,
        version: String,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings.forFacade(
            name = name,
            version = version,
            baseURL = baseURL,
            apiKey = apiKey,
            headers = headers,
            capabilities = capabilities,
            transformChatRequestBody = ::groqTransformChatBody,
            transformChatResponse = ::groqTransformChatResponse,
            convertUsage = ::groqUsage,
        )

    private fun groqTransformChatBody(body: JsonObject): JsonObject {
        val modelId = (body["model"] as? JsonPrimitive)?.contentOrNull
        val tools = groqTools(JsonAccess.arr(body, "tools"), modelId)
        return buildJsonObject {
            for ((key, value) in body) {
                when (key) {
                    "messages" -> put("messages", groqMessages(value as? JsonArray))
                    // browser_search may have been filtered out — drop the tools key if now empty
                    // (an empty tools array is invalid, same as sending tool_choice without tools).
                    "tools" -> if (tools.isNotEmpty()) put("tools", tools)
                    else -> put(key, value)
                }
            }
        }
    }

    private fun groqMessages(messages: JsonArray?): JsonArray = JsonArray(
        messages.orEmpty().map { message ->
            val obj = message as? JsonObject ?: return@map message
            if ((obj["role"] as? JsonPrimitive)?.contentOrNull != "assistant") return@map obj
            val reasoning = obj["reasoning_content"] ?: return@map obj
            val transformed = obj.toMutableMap()
            transformed.remove("reasoning_content")
            transformed["reasoning"] = reasoning
            JsonObject(transformed)
        },
    )

    private fun groqTools(tools: JsonArray?, modelId: String?): JsonArray = JsonArray(
        tools.orEmpty().mapNotNull { tool ->
            val obj = tool as? JsonObject ?: return@mapNotNull tool
            val function = JsonAccess.obj(obj, "function") ?: return@mapNotNull tool
            val name = (function["name"] as? JsonPrimitive)?.contentOrNull
            if (name == "browserSearch" || name == "browser_search") {
                // Gate the browser_search tool to the models that support it; drop it elsewhere
                // (upstream emits an unsupported warning and omits the tool).
                if (modelId in GROQ_BROWSER_SEARCH_MODELS) {
                    buildJsonObject { put("type", JsonPrimitive("browser_search")) }
                } else {
                    null
                }
            } else {
                tool
            }
        },
    )

    private fun groqTransformChatResponse(body: JsonObject): JsonObject {
        val usage = (JsonAccess.obj(body, "x_groq"))?.get("usage")
        return if (body["usage"] == null && usage != null) {
            JsonObject(body + ("usage" to usage))
        } else {
            body
        }
    }

    private fun groqUsage(value: JsonElement?): Usage {
        val obj = value as? JsonObject ?: return Usage(raw = value)
        val promptTokens = obj.intField("prompt_tokens")
        val completionTokens = obj.intField("completion_tokens")
        val reasoning = obj.nestedIntField("completion_tokens_details", "reasoning_tokens").coerceAtMost(completionTokens)
        return Usage.fromParts(promptTokens, completionTokens, cacheRead = 0, reasoningTokens = reasoning, raw = obj)
    }

    private companion object {
        // Groq supports the browser_search tool only on these models; elsewhere it is dropped.
        private val GROQ_BROWSER_SEARCH_MODELS = setOf("openai/gpt-oss-20b", "openai/gpt-oss-120b")
    }
}

public class GroqProviderSettingsBuilder internal constructor() {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.groq.com/openai/v1"
    private var headers: Map<String, String> = emptyMap()

    public fun apiKey(value: String?) {
        apiKey = value
    }

    public fun baseURL(value: String) {
        baseURL = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    internal fun build(): GroqProviderSettings =
        GroqProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

public fun GroqProviderSettings(
    block: GroqProviderSettingsBuilder.() -> Unit = {},
): GroqProviderSettings =
    GroqProviderSettingsBuilder().apply(block).build()

@Serializable
public data class GroqLanguageModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias GroqProviderOptions = GroqLanguageModelOptions

@Serializable
public data class GroqTranscriptionModelOptions(
    val language: String? = null,
    val prompt: String? = null,
    val temperature: Float? = null,
    val responseFormat: String? = null,
)

public class GroqProvider(
    client: HttpClient,
    settings: GroqProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("groq", GROQ_VERSION, capabilities = ProviderCapabilities(includeUsage = true)),
    )
    override val providerId: String = "groq"
    public val tools: GroqTools = GroqTools()

    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    override fun languageModel(modelId: String): LanguageModel = chat(modelId)
    public fun chat(modelId: String): LanguageModel = compatible.chatModel(modelId)
    public fun transcription(modelId: String): TranscriptionModel = compatible.transcriptionModel(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

public fun Groq(
    client: HttpClient,
    settings: GroqProviderSettings = GroqProviderSettings(),
): GroqProvider = GroqProvider(client, settings)

private val groqBrowserSearchTool: Tool<JsonElement, JsonElement, Any?> = ProviderExecutedTool(
    name = "browserSearch",
    description = "Browser search tool for Groq models.",
    inputSerializer = JsonElement.serializer(),
    outputSerializer = JsonElement.serializer(),
    metadata = mapOf("providerToolId" to JsonPrimitive("groq.browser_search")),
)

@Poko
public class GroqTools(
    public val browserSearch: Tool<JsonElement, JsonElement, Any?> = groqBrowserSearchTool,
)

public val browserSearch: Tool<JsonElement, JsonElement, Any?> = groqBrowserSearchTool
