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
/** @since 0.3.0-beta01 */
public class GroqProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.groq.com/openai/v1",
    /** @since 0.3.0-beta01 */
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
        val reasoning = obj.nestedIntField(
            "completion_tokens_details",
            "reasoning_tokens"
        ).coerceAtMost(completionTokens)
        return Usage.fromParts(promptTokens, completionTokens, cacheRead = 0, reasoningTokens = reasoning, raw = obj)
    }

    private companion object {
        // Groq supports the browser_search tool only on these models; elsewhere it is dropped.
        private val GROQ_BROWSER_SEARCH_MODELS = setOf("openai/gpt-oss-20b", "openai/gpt-oss-120b")
    }
}

/** @since 0.3.0-beta01 */
public class GroqProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.groq.com/openai/v1"
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): GroqProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): GroqProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): GroqProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): GroqProviderSettings =
        GroqProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun GroqProviderSettings(
    block: GroqProviderSettingsBuilder.() -> Unit = {},
): GroqProviderSettings =
    GroqProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class GroqLanguageModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val raw: Map<String, JsonElement> = emptyMap(),
)

/** @since 0.3.0-beta01 */
public class GroqLanguageModelOptionsBuilder {
    private var raw: Map<String, JsonElement> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun raw(value: Map<String, JsonElement>): GroqLanguageModelOptionsBuilder {
        raw = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): GroqLanguageModelOptions =
        GroqLanguageModelOptions(raw = raw)
}

/** @since 0.3.0-beta01 */
public fun GroqLanguageModelOptions(
    block: GroqLanguageModelOptionsBuilder.() -> Unit = {},
): GroqLanguageModelOptions =
    GroqLanguageModelOptionsBuilder().apply(block).build()

public typealias GroqProviderOptions = GroqLanguageModelOptions

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class GroqTranscriptionModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val language: String? = null,
    /** @since 0.3.0-beta01 */
    public val prompt: String? = null,
    /** @since 0.3.0-beta01 */
    public val temperature: Float? = null,
    /** @since 0.3.0-beta01 */
    public val responseFormat: String? = null,
)

/** @since 0.3.0-beta01 */
public class GroqTranscriptionModelOptionsBuilder {
    private var language: String? = null
    private var prompt: String? = null
    private var temperature: Float? = null
    private var responseFormat: String? = null

    /** @since 0.3.0-beta01 */
    public fun language(value: String?): GroqTranscriptionModelOptionsBuilder {
        language = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun prompt(value: String?): GroqTranscriptionModelOptionsBuilder {
        prompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun temperature(value: Float?): GroqTranscriptionModelOptionsBuilder {
        temperature = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun responseFormat(value: String?): GroqTranscriptionModelOptionsBuilder {
        responseFormat = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): GroqTranscriptionModelOptions =
        GroqTranscriptionModelOptions(
            language = language,
            prompt = prompt,
            temperature = temperature,
            responseFormat = responseFormat,
        )
}

/** @since 0.3.0-beta01 */
public fun GroqTranscriptionModelOptions(
    block: GroqTranscriptionModelOptionsBuilder.() -> Unit = {},
): GroqTranscriptionModelOptions =
    GroqTranscriptionModelOptionsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class GroqProvider(
    client: HttpClient,
    settings: GroqProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("groq", GROQ_VERSION, capabilities = ProviderCapabilities(includeUsage = true)),
    )
    override val providerId: String = "groq"

    /** @since 0.3.0-beta01 */
    public val tools: GroqTools = GroqTools()

    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    override fun languageModel(modelId: String): LanguageModel = chat(modelId)

    /** @since 0.3.0-beta01 */
    public fun chat(modelId: String): LanguageModel = compatible.chatModel(modelId)

    /** @since 0.3.0-beta01 */
    public fun transcription(modelId: String): TranscriptionModel = compatible.transcriptionModel(modelId)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(
        providerId,
        "embeddingModel",
        modelId
    )
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(
        providerId,
        "embeddingModel",
        modelId
    )
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** @since 0.3.0-beta01 */
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
/** @since 0.3.0-beta01 */
public class GroqTools(
    /** @since 0.3.0-beta01 */
    public val browserSearch: Tool<JsonElement, JsonElement, Any?> = groqBrowserSearchTool,
)

/** @since 0.3.0-beta01 */
public val browserSearch: Tool<JsonElement, JsonElement, Any?> = groqBrowserSearchTool
