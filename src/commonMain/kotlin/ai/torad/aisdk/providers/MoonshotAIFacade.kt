package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeSupport.compatibleSettings
import ai.torad.aisdk.providers.FacadeSupport.intField
import ai.torad.aisdk.providers.FacadeSupport.nestedIntField
import ai.torad.aisdk.providers.FacadeSupport.usageFromParts
import ai.torad.aisdk.providers.MoonshotAIWire.toCompatible
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

public const val MOONSHOTAI_VERSION: String = "2.0.23"


@Serializable
public data class MoonshotAIProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.moonshot.ai/v1",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
public data class MoonshotAILanguageModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias MoonshotAIProviderOptions = MoonshotAILanguageModelOptions

public class MoonshotAIProvider(
    client: HttpClient,
    settings: MoonshotAIProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("moonshotai", MOONSHOTAI_VERSION, includeUsage = true),
    )
    override val providerId: String = "moonshotai"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    override fun languageModel(modelId: String): LanguageModel = chatModel(ModelId(modelId))
    public fun chatModel(modelId: ModelId): LanguageModel = compatible.chatModel(modelId.value)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

public fun MoonshotAI(
    client: HttpClient,
    settings: MoonshotAIProviderSettings = MoonshotAIProviderSettings(),
): MoonshotAIProvider = MoonshotAIProvider(client, settings)

internal object MoonshotAIWire {
    fun MoonshotAIProviderSettings.toCompatible(
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
            convertUsage = ::moonshotAIUsage,
        )

    fun moonshotAIUsage(value: JsonElement?): Usage {
        val obj = value as? JsonObject ?: return Usage(raw = value)
        val promptTokens = obj.intField("prompt_tokens")
        val completionTokens = obj.intField("completion_tokens")
        val cacheRead = (
            obj["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: obj.nestedIntField("prompt_tokens_details", "cached_tokens")
            ).coerceAtMost(promptTokens)
        val reasoning = obj.nestedIntField("completion_tokens_details", "reasoning_tokens").coerceAtMost(completionTokens)
        return usageFromParts(promptTokens, completionTokens, cacheRead, reasoning, obj)
    }
}
