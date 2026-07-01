package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeSupport.intField
import ai.torad.aisdk.providers.FacadeSupport.textFromContentParts
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

public const val PERPLEXITY_VERSION: String = "3.0.33"

@Serializable
@Poko
public class PerplexityProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val baseURL: String = "https://api.perplexity.ai",
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
            transformChatRequestBody = ::perplexityTransformChatBody,
            convertUsage = ::perplexityUsage,
        )

    private fun perplexityTransformChatBody(body: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in body) {
            when (key) {
                "messages" -> put("messages", perplexityMessages(value as? JsonArray))
                "stop", "seed", "tools", "tool_choice" -> Unit
                else -> put(key, value)
            }
        }
    }

    private fun perplexityMessages(messages: JsonArray?): JsonArray = JsonArray(
        messages.orEmpty().mapNotNull { message ->
            val obj = message as? JsonObject ?: return@mapNotNull message
            when ((obj["role"] as? JsonPrimitive)?.contentOrNull) {
                "tool" -> null
                "assistant" -> perplexityAssistantMessage(obj)
                "user" -> perplexityTextMessage(obj)
                else -> obj
            }
        },
    )

    private fun perplexityAssistantMessage(message: JsonObject): JsonObject {
        val transformed = perplexityTextMessage(message).toMutableMap()
        transformed.remove("tool_calls")
        transformed.remove("reasoning_content")
        transformed["content"] = JsonPrimitive((transformed["content"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        return JsonObject(transformed)
    }

    private fun perplexityTextMessage(message: JsonObject): JsonObject {
        val content = JsonAccess.arr(message, "content")
        val textOnly = content?.all {
            ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "text"
        } == true
        return if (content != null && textOnly) {
            JsonObject(message + ("content" to JsonPrimitive(textFromContentParts(content))))
        } else {
            message
        }
    }

    private fun perplexityUsage(value: JsonElement?): Usage {
        val obj = value as? JsonObject ?: return Usage(raw = value)
        val promptTokens = obj.intField("prompt_tokens")
        val completionTokens = obj.intField("completion_tokens")
        val reasoning = obj.intField("reasoning_tokens").coerceAtMost(completionTokens)
        return Usage.fromParts(promptTokens, completionTokens, cacheRead = 0, reasoningTokens = reasoning, raw = obj)
    }
}

public class PerplexityProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.perplexity.ai"
    private var headers: Map<String, String> = emptyMap()

    public fun apiKey(value: String?): PerplexityProviderSettingsBuilder {
        apiKey = value
        return this
    }

    public fun baseURL(value: String): PerplexityProviderSettingsBuilder {
        baseURL = value
        return this
    }

    public fun headers(value: Map<String, String>): PerplexityProviderSettingsBuilder {
        headers = value
        return this
    }

    public fun build(): PerplexityProviderSettings =
        PerplexityProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

public fun PerplexityProviderSettings(
    block: PerplexityProviderSettingsBuilder.() -> Unit = {},
): PerplexityProviderSettings =
    PerplexityProviderSettingsBuilder().apply(block).build()

public class PerplexityProvider(
    client: HttpClient,
    settings: PerplexityProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("perplexity", PERPLEXITY_VERSION, capabilities = ProviderCapabilities(supportsStructuredOutputs = true)),
    )
    override val providerId: String = "perplexity"

    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    override fun languageModel(modelId: String): LanguageModel = compatible.chatModel(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

public fun Perplexity(
    client: HttpClient,
    settings: PerplexityProviderSettings = PerplexityProviderSettings(),
): PerplexityProvider = PerplexityProvider(client, settings)
