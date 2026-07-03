@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

public const val COHERE_VERSION: String = "3.0.36"
public typealias CohereChatModelOptions = CohereLanguageModelOptions
public typealias CohereRerankingOptions = CohereRerankingModelOptions

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class CohereProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.cohere.com/v2",
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun cohereHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String?>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        headers.forEach { (key, value) -> base[key] = value }
        callHeaders.forEach { (key, value) -> base[key] = value }
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/cohere/$COHERE_VERSION")
    }

    internal suspend fun coherePostJson(
        client: HttpClient,
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Post,
            headers = headers,
            body = body,
            requestBodyValues = body,
            errorMessage = this::cohereErrorMessage,
        )

    internal fun cohereOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "cohere") ?: JsonObject(emptyMap())

    internal fun cohereErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Cohere request failed ($statusCode): $detail"
    }
}

/** @since 0.3.0-beta01 */
public class CohereProviderSettingsBuilder {
    private var baseURL: String = "https://api.cohere.com/v2"
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): CohereProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): CohereProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): CohereProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): CohereProviderSettings =
        CohereProviderSettings(
            baseURL = baseURL,
            apiKey = apiKey,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun CohereProviderSettings(
    block: CohereProviderSettingsBuilder.() -> Unit = {},
): CohereProviderSettings =
    CohereProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class CohereLanguageModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val thinking: CohereThinkingOptions? = null,
)

/** @since 0.3.0-beta01 */
public class CohereLanguageModelOptionsBuilder {
    private var thinking: CohereThinkingOptions? = null

    /** @since 0.3.0-beta01 */
    public fun thinking(value: CohereThinkingOptions?): CohereLanguageModelOptionsBuilder {
        thinking = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): CohereLanguageModelOptions =
        CohereLanguageModelOptions(thinking = thinking)
}

/** @since 0.3.0-beta01 */
public fun CohereLanguageModelOptions(
    block: CohereLanguageModelOptionsBuilder.() -> Unit = {},
): CohereLanguageModelOptions =
    CohereLanguageModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class CohereThinkingOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val type: String? = null,
    /** @since 0.3.0-beta01 */
    public val tokenBudget: Int? = null,
)

/** @since 0.3.0-beta01 */
public class CohereThinkingOptionsBuilder {
    private var type: String? = null
    private var tokenBudget: Int? = null

    /** @since 0.3.0-beta01 */
    public fun type(value: String?): CohereThinkingOptionsBuilder {
        type = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun tokenBudget(value: Int?): CohereThinkingOptionsBuilder {
        tokenBudget = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): CohereThinkingOptions =
        CohereThinkingOptions(
            type = type,
            tokenBudget = tokenBudget,
        )
}

/** @since 0.3.0-beta01 */
public fun CohereThinkingOptions(
    block: CohereThinkingOptionsBuilder.() -> Unit = {},
): CohereThinkingOptions =
    CohereThinkingOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class CohereEmbeddingModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val inputType: String? = null,
    /** @since 0.3.0-beta01 */
    public val truncate: String? = null,
    /** @since 0.3.0-beta01 */
    public val outputDimension: Int? = null,
)

/** @since 0.3.0-beta01 */
public class CohereEmbeddingModelOptionsBuilder {
    private var inputType: String? = null
    private var truncate: String? = null
    private var outputDimension: Int? = null

    /** @since 0.3.0-beta01 */
    public fun inputType(value: String?): CohereEmbeddingModelOptionsBuilder {
        inputType = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun truncate(value: String?): CohereEmbeddingModelOptionsBuilder {
        truncate = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun outputDimension(value: Int?): CohereEmbeddingModelOptionsBuilder {
        outputDimension = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): CohereEmbeddingModelOptions =
        CohereEmbeddingModelOptions(
            inputType = inputType,
            truncate = truncate,
            outputDimension = outputDimension,
        )
}

/** @since 0.3.0-beta01 */
public fun CohereEmbeddingModelOptions(
    block: CohereEmbeddingModelOptionsBuilder.() -> Unit = {},
): CohereEmbeddingModelOptions =
    CohereEmbeddingModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class CohereRerankingModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val maxTokensPerDoc: Int? = null,
    /** @since 0.3.0-beta01 */
    public val priority: Int? = null,
)

/** @since 0.3.0-beta01 */
public class CohereRerankingModelOptionsBuilder {
    private var maxTokensPerDoc: Int? = null
    private var priority: Int? = null

    /** @since 0.3.0-beta01 */
    public fun maxTokensPerDoc(value: Int?): CohereRerankingModelOptionsBuilder {
        maxTokensPerDoc = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun priority(value: Int?): CohereRerankingModelOptionsBuilder {
        priority = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): CohereRerankingModelOptions =
        CohereRerankingModelOptions(
            maxTokensPerDoc = maxTokensPerDoc,
            priority = priority,
        )
}

/** @since 0.3.0-beta01 */
public fun CohereRerankingModelOptions(
    block: CohereRerankingModelOptionsBuilder.() -> Unit = {},
): CohereRerankingModelOptions =
    CohereRerankingModelOptionsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class CohereProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: CohereProviderSettings,
) : Provider {
    override val providerId: String = "cohere"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    override fun languageModel(modelId: String): LanguageModel =
        CohereChatLanguageModel(client, settings, modelId)

    /** @since 0.3.0-beta01 */
    public fun embedding(modelId: ModelId): EmbeddingModel =
        CohereEmbeddingModel(client, settings, modelId.value)

    /** @since 0.3.0-beta01 */
    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)

    /** @since 0.3.0-beta01 */
    public fun reranking(modelId: ModelId): RerankingModel =
        CohereRerankingModel(client, settings, modelId.value)

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    override fun rerankingModel(modelId: String): RerankingModel = reranking(ModelId(modelId))

    override fun imageModel(modelId: String): ImageModel =
        throw NoSuchModelError(providerId, "imageModel", modelId)
}

/**
 * PascalCase factory — mirrors the OpenAI(...) reference pattern.
 * @since 0.3.0-beta01
 */
public fun Cohere(
    client: HttpClient,
    settings: CohereProviderSettings = CohereProviderSettings(),
): CohereProvider = CohereProvider(client, settings)
