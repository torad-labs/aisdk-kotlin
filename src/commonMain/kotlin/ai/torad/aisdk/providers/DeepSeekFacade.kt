package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeSupport.intField
import ai.torad.aisdk.providers.FacadeSupport.nestedIntField
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

public const val DEEPSEEK_VERSION: String = "2.0.35"

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class DeepSeekProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.deepseek.com",
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
            transformChatRequestBody = ::deepSeekTransformChatBody,
            convertUsage = ::deepSeekUsage,
        )

    private fun deepSeekTransformChatBody(body: JsonObject): JsonObject {
        val responseFormat = JsonAccess.obj(body, "response_format")
        val responseFormatType = (responseFormat?.get("type") as? JsonPrimitive)?.contentOrNull
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

    private fun deepSeekMessages(
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

    private fun deepSeekMessage(message: JsonElement): JsonElement {
        val obj = message as? JsonObject
        val content = obj?.get("content") as? JsonArray
        return if ((obj?.get("role") as? JsonPrimitive)?.contentOrNull == "user" && content != null) {
            JsonObject(obj + ("content" to JsonPrimitive(textFromContentParts(content))))
        } else {
            message
        }
    }

    private fun deepSeekUsage(value: JsonElement?): Usage {
        val obj = value as? JsonObject ?: return Usage(raw = value)
        val promptTokens = obj.intField("prompt_tokens")
        val completionTokens = obj.intField("completion_tokens")
        val cacheRead = obj.intField("prompt_cache_hit_tokens").coerceAtMost(promptTokens)
        val reasoning = obj.nestedIntField(
            "completion_tokens_details",
            "reasoning_tokens"
        ).coerceAtMost(completionTokens)
        return Usage.fromParts(promptTokens, completionTokens, cacheRead, reasoning, obj)
    }
}

/** @since 0.3.0-beta01 */
public class DeepSeekProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.deepseek.com"
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): DeepSeekProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): DeepSeekProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): DeepSeekProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): DeepSeekProviderSettings =
        DeepSeekProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun DeepSeekProviderSettings(
    block: DeepSeekProviderSettingsBuilder.() -> Unit = {},
): DeepSeekProviderSettings =
    DeepSeekProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class DeepSeekLanguageModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val raw: Map<String, JsonElement> = emptyMap(),
)

/** @since 0.3.0-beta01 */
public class DeepSeekLanguageModelOptionsBuilder {
    private var raw: Map<String, JsonElement> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun raw(value: Map<String, JsonElement>): DeepSeekLanguageModelOptionsBuilder {
        raw = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): DeepSeekLanguageModelOptions =
        DeepSeekLanguageModelOptions(raw = raw)
}

/** @since 0.3.0-beta01 */
public fun DeepSeekLanguageModelOptions(
    block: DeepSeekLanguageModelOptionsBuilder.() -> Unit = {},
): DeepSeekLanguageModelOptions =
    DeepSeekLanguageModelOptionsBuilder().apply(block).build()

public typealias DeepSeekChatOptions = DeepSeekLanguageModelOptions

/** @since 0.3.0-beta01 */
public class DeepSeekProvider(
    client: HttpClient,
    settings: DeepSeekProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible(
            "deepseek",
            DEEPSEEK_VERSION,
            capabilities = ProviderCapabilities(supportsStructuredOutputs = true)
        ),
    )
    override val providerId: String = "deepseek"

    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    override fun languageModel(modelId: String): LanguageModel = chat(modelId)

    /** @since 0.3.0-beta01 */
    public fun chat(modelId: String): LanguageModel = compatible.chatModel(modelId)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(
        modelId: String
    ): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun embeddingModel(
        modelId: String
    ): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** @since 0.3.0-beta01 */
public fun DeepSeek(
    client: HttpClient,
    settings: DeepSeekProviderSettings = DeepSeekProviderSettings(),
): DeepSeekProvider = DeepSeekProvider(client, settings)
