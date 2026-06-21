package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.DeepSeekWire.toCompatible
import ai.torad.aisdk.providers.FacadeSupport.compatibleSettings
import ai.torad.aisdk.providers.FacadeSupport.intField
import ai.torad.aisdk.providers.FacadeSupport.nestedIntField
import ai.torad.aisdk.providers.FacadeSupport.textFromContentParts
import ai.torad.aisdk.providers.FacadeSupport.usageFromParts
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

public const val DEEPSEEK_VERSION: String = "2.0.35"

@Serializable
public data class DeepSeekProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.deepseek.com",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
public data class DeepSeekLanguageModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias DeepSeekChatOptions = DeepSeekLanguageModelOptions
public typealias DeepSeekErrorData = JsonElement

public class DeepSeekProvider(
    client: HttpClient,
    settings: DeepSeekProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("deepseek", DEEPSEEK_VERSION, capabilities = ProviderCapabilities(supportsStructuredOutputs = true)),
    )
    override val providerId: String = "deepseek"

    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    override fun languageModel(modelId: String): LanguageModel = chat(modelId)
    public fun chat(modelId: String): LanguageModel = compatible.chatModel(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

public fun DeepSeek(
    client: HttpClient,
    settings: DeepSeekProviderSettings = DeepSeekProviderSettings(),
): DeepSeekProvider = DeepSeekProvider(client, settings)

internal object DeepSeekWire {
    fun DeepSeekProviderSettings.toCompatible(
        name: String,
        version: String,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OpenAICompatibleProviderSettings =
        compatibleSettings(
            name = name,
            version = version,
            baseURL = baseURL,
            apiKey = apiKey,
            headers = headers,
            capabilities = capabilities,
            transformChatRequestBody = ::deepSeekTransformChatBody,
            convertUsage = ::deepSeekUsage,
        )

    fun deepSeekTransformChatBody(body: JsonObject): JsonObject {
        val responseFormat = body["response_format"] as? JsonObject
        val responseFormatType = responseFormat?.get("type")?.jsonPrimitive?.contentOrNull
        val schema = (responseFormat?.get("json_schema") as? JsonObject)?.get("schema")
        val jsonRequested = responseFormatType == "json_object" || responseFormatType == "json_schema"
        return buildJsonObject {
            for ((key, value) in body) {
                when (key) {
                    "messages" -> put(
                        "messages",
                        deepSeekMessages(
                            messages = value as? JsonArray,
                            jsonRequested = jsonRequested,
                            schema = schema,
                        ),
                    )
                    "response_format" -> if (jsonRequested) {
                        put("response_format", buildJsonObject { put("type", JsonPrimitive("json_object")) })
                    } else {
                        put(key, value)
                    }
                    "seed" -> Unit
                    else -> put(key, value)
                }
            }
        }
    }

    fun deepSeekMessages(
        messages: JsonArray?,
        jsonRequested: Boolean,
        schema: JsonElement?,
    ): JsonArray = JsonArray(
        buildList {
            if (jsonRequested) {
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("system"))
                        put(
                            "content",
                            JsonPrimitive(
                                if (schema == null) {
                                    "Return JSON."
                                } else {
                                    "Return JSON that conforms to the following schema: $schema"
                                },
                            ),
                        )
                    },
                )
            }
            messages.orEmpty().forEach { message ->
                add(deepSeekMessage(message))
            }
        },
    )

    fun deepSeekMessage(message: JsonElement): JsonElement {
        val obj = message as? JsonObject
        val content = obj?.get("content") as? JsonArray
        return if (obj?.get("role")?.jsonPrimitive?.contentOrNull == "user" && content != null) {
            JsonObject(obj + ("content" to JsonPrimitive(textFromContentParts(content))))
        } else {
            message
        }
    }

    fun deepSeekUsage(value: JsonElement?): Usage {
        val obj = value as? JsonObject ?: return Usage(raw = value)
        val promptTokens = obj.intField("prompt_tokens")
        val completionTokens = obj.intField("completion_tokens")
        val cacheRead = obj.intField("prompt_cache_hit_tokens").coerceAtMost(promptTokens)
        val reasoning = obj.nestedIntField("completion_tokens_details", "reasoning_tokens").coerceAtMost(completionTokens)
        return usageFromParts(promptTokens, completionTokens, cacheRead, reasoning, obj)
    }
}
