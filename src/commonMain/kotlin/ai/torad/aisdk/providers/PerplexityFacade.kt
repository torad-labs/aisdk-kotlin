package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeSupport.compatibleSettings
import ai.torad.aisdk.providers.FacadeSupport.intField
import ai.torad.aisdk.providers.FacadeSupport.textFromContentParts
import ai.torad.aisdk.providers.FacadeSupport.usageFromParts
import ai.torad.aisdk.providers.PerplexityWire.toCompatible
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

public const val PERPLEXITY_VERSION: String = "3.0.33"

@Serializable
public data class PerplexityProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.perplexity.ai",
    val headers: Map<String, String> = emptyMap(),
)

public class PerplexityProvider(
    client: HttpClient,
    settings: PerplexityProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("perplexity", PERPLEXITY_VERSION, supportsStructuredOutputs = true),
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

internal object PerplexityWire {
    fun PerplexityProviderSettings.toCompatible(
        name: String,
        version: String,
        includeUsage: Boolean = false,
        supportsStructuredOutputs: Boolean = false,
    ): OpenAICompatibleProviderSettings =
        compatibleSettings(
            name = name,
            version = version,
            baseURL = baseURL,
            apiKey = apiKey,
            headers = headers,
            includeUsage = includeUsage,
            supportsStructuredOutputs = supportsStructuredOutputs,
            transformChatRequestBody = ::perplexityTransformChatBody,
            convertUsage = ::perplexityUsage,
        )

    fun perplexityTransformChatBody(body: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in body) {
            when (key) {
                "messages" -> put("messages", perplexityMessages(value as? JsonArray))
                "stop", "seed", "tools", "tool_choice" -> Unit
                else -> put(key, value)
            }
        }
    }

    fun perplexityMessages(messages: JsonArray?): JsonArray = JsonArray(
        messages.orEmpty().mapNotNull { message ->
            val obj = message as? JsonObject ?: return@mapNotNull message
            when (obj["role"]?.jsonPrimitive?.contentOrNull) {
                "tool" -> null
                "assistant" -> perplexityAssistantMessage(obj)
                "user" -> perplexityTextMessage(obj)
                else -> obj
            }
        },
    )

    fun perplexityAssistantMessage(message: JsonObject): JsonObject {
        val transformed = perplexityTextMessage(message).toMutableMap()
        transformed.remove("tool_calls")
        transformed.remove("reasoning_content")
        transformed["content"] = JsonPrimitive((transformed["content"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        return JsonObject(transformed)
    }

    fun perplexityTextMessage(message: JsonObject): JsonObject {
        val content = message["content"] as? JsonArray
        val textOnly = content?.all {
            (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text"
        } == true
        return if (content != null && textOnly) {
            JsonObject(message + ("content" to JsonPrimitive(textFromContentParts(content))))
        } else {
            message
        }
    }

    fun perplexityUsage(value: JsonElement?): Usage {
        val obj = value as? JsonObject ?: return Usage(raw = value)
        val promptTokens = obj.intField("prompt_tokens")
        val completionTokens = obj.intField("completion_tokens")
        val reasoning = obj.intField("reasoning_tokens").coerceAtMost(completionTokens)
        return usageFromParts(promptTokens, completionTokens, cacheRead = 0, reasoningTokens = reasoning, raw = obj)
    }
}
