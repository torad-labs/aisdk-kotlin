package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeSupport.intField
import ai.torad.aisdk.providers.FacadeSupport.nestedIntField
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

public const val MOONSHOTAI_VERSION: String = "2.0.23"


@Serializable
@Poko
public class MoonshotAIProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val baseURL: String = "https://api.moonshot.ai/v1",
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
            convertUsage = ::moonshotAIUsage,
        )

    private fun moonshotAIUsage(value: JsonElement?): Usage {
        val obj = value as? JsonObject ?: return Usage(raw = value)
        val promptTokens = obj.intField("prompt_tokens")
        val completionTokens = obj.intField("completion_tokens")
        val cacheRead = (
            (obj["cached_tokens"] as? JsonPrimitive)?.intOrNull
                ?: obj.nestedIntField("prompt_tokens_details", "cached_tokens")
            ).coerceAtMost(promptTokens)
        val reasoning = obj.nestedIntField("completion_tokens_details", "reasoning_tokens").coerceAtMost(completionTokens)
        return Usage.fromParts(promptTokens, completionTokens, cacheRead, reasoning, obj)
    }
}

public class MoonshotAIProviderSettingsBuilder internal constructor() {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.moonshot.ai/v1"
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

    internal fun build(): MoonshotAIProviderSettings =
        MoonshotAIProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

public fun MoonshotAIProviderSettings(
    block: MoonshotAIProviderSettingsBuilder.() -> Unit = {},
): MoonshotAIProviderSettings =
    MoonshotAIProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
public class MoonshotAILanguageModelOptions internal constructor(
    public val raw: Map<String, JsonElement> = emptyMap(),
)

public class MoonshotAILanguageModelOptionsBuilder internal constructor() {
    private var raw: Map<String, JsonElement> = emptyMap()

    public fun raw(value: Map<String, JsonElement>) {
        raw = value
    }

    internal fun build(): MoonshotAILanguageModelOptions =
        MoonshotAILanguageModelOptions(raw = raw)
}

public fun MoonshotAILanguageModelOptions(
    block: MoonshotAILanguageModelOptionsBuilder.() -> Unit = {},
): MoonshotAILanguageModelOptions =
    MoonshotAILanguageModelOptionsBuilder().apply(block).build()

public typealias MoonshotAIProviderOptions = MoonshotAILanguageModelOptions

public class MoonshotAIProvider(
    client: HttpClient,
    settings: MoonshotAIProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("moonshotai", MOONSHOTAI_VERSION, capabilities = ProviderCapabilities(includeUsage = true)),
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
