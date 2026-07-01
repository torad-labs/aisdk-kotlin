package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public const val VERSION: String = "3.0.67"

/** @since 0.3.0-beta01 */
public val OPENAI_RESPONSES_SUPPORTED_URLS: Map<String, List<String>> = OPEN_RESPONSES_SUPPORTED_URLS +
    ("application/pdf" to listOf("^https?://.*$"))

@Poko
/** @since 0.3.0-beta01 */
public class OpenAIProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.openai.com/v1",
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val organization: String? = null,
    /** @since 0.3.0-beta01 */
    public val project: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val name: String = "openai",
    /** @since 0.3.0-beta01 */
    public val queryParams: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val includeUsage: Boolean = false,
) {
    internal fun toCompatibleSettings(): OpenAICompatibleProviderSettings {
        val headersWithUserAgent = ProviderHeaders.withUserAgentSuffix(openAIHeaders(), "ai-sdk/openai/$VERSION")
        return OpenAICompatibleProviderSettings {
            name(name)
            baseUrl(baseURL.trimEnd('/'))
            apiKey(apiKey)
            headers(headersWithUserAgent)
            // UA already embedded in headersWithUserAgent — null the default suffix to avoid
            // commonHeaders() appending "ai-sdk/openai-compatible-kotlin" a second time.
            userAgentSuffix(null)
            queryParams(queryParams)
            includeUsage(includeUsage)
            supportsStructuredOutputs(true)
        }
    }

    internal fun openAIHeaders(): Map<String, String> {
        val baseHeaders = linkedMapOf<String, String>()
        organization?.takeIf { it.isNotBlank() }?.let { baseHeaders["OpenAI-Organization"] = it }
        project?.takeIf { it.isNotBlank() }?.let { baseHeaders["OpenAI-Project"] = it }
        baseHeaders.putAll(headers)
        return baseHeaders
    }

    internal fun responsesUrl(): String {
        val endpoint = "${baseURL.trimEnd('/')}/responses"
        if (queryParams.isEmpty()) return endpoint
        return endpoint + "?" + queryParams.entries.joinToString("&") { (key, value) ->
            "${UrlOps.encode(key)}=${UrlOps.encode(value)}"
        }
    }
}

/** @since 0.3.0-beta01 */
public class OpenAIProviderSettingsBuilder {
    private var baseURL: String = "https://api.openai.com/v1"
    private var apiKey: String? = null
    private var organization: String? = null
    private var project: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var name: String = "openai"
    private var queryParams: Map<String, String> = emptyMap()
    private var includeUsage: Boolean = false

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): OpenAIProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): OpenAIProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun organization(value: String?): OpenAIProviderSettingsBuilder {
        organization = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun project(value: String?): OpenAIProviderSettingsBuilder {
        project = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): OpenAIProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun name(value: String): OpenAIProviderSettingsBuilder {
        name = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun queryParams(value: Map<String, String>): OpenAIProviderSettingsBuilder {
        queryParams = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun includeUsage(value: Boolean): OpenAIProviderSettingsBuilder {
        includeUsage = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): OpenAIProviderSettings =
        OpenAIProviderSettings(
            baseURL = baseURL,
            apiKey = apiKey,
            organization = organization,
            project = project,
            headers = headers,
            name = name,
            queryParams = queryParams,
            includeUsage = includeUsage,
        )
}

/** @since 0.3.0-beta01 */
public fun OpenAIProviderSettings(
    block: OpenAIProviderSettingsBuilder.() -> Unit = {},
): OpenAIProviderSettings =
    OpenAIProviderSettingsBuilder().apply(block).build()

public typealias OpenAISettings = OpenAIProviderSettings

/** @since 0.3.0-beta01 */
public class OpenAIProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: OpenAIProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(client, settings.toCompatibleSettings())

    override val providerId: String = settings.name
    /** @since 0.3.0-beta01 */
    public val tools: OpenAITools = OpenAITools()

    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)

    override fun languageModel(modelId: String): LanguageModel = responses(modelId)

    /** @since 0.3.0-beta01 */
    public fun chat(modelId: String): LanguageModel =
        compatible.chatModel(modelId)

    /** @since 0.3.0-beta01 */
    public fun responses(modelId: String): LanguageModel =
        OpenResponses(
            client,
            OpenResponsesProviderSettings {
                url(settings.responsesUrl())
                name(settings.name)
                apiKey(settings.apiKey)
                headers(settings.openAIHeaders())
                userAgentSuffix("ai-sdk/openai/$VERSION")
                providerOptionsName("openai")
                supportedUrls(OPENAI_RESPONSES_SUPPORTED_URLS)
                fileIdPrefixes(listOf("file-"))
            },
        ).responses(modelId)

    /** @since 0.3.0-beta01 */
    public fun completion(modelId: String): LanguageModel =
        compatible.completionModel(modelId)

    /** @since 0.3.0-beta01 */
    public fun embedding(modelId: String): EmbeddingModel =
        compatible.embeddingModel(modelId)

    /** @since 0.3.0-beta01 */
    public fun image(modelId: String): ImageModel =
        OpenAIImageModel(modelId, compatible.imageModel(modelId))

    /** @since 0.3.0-beta01 */
    public fun transcription(modelId: String): TranscriptionModel =
        compatible.transcriptionModel(modelId)

    /** @since 0.3.0-beta01 */
    public fun speech(modelId: String): SpeechModel =
        compatible.speechModel(modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    /** @since 0.3.0-beta01 */
    public fun textEmbedding(modelId: String): EmbeddingModel = embedding(modelId)
    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
    override fun speechModel(modelId: String): SpeechModel = speech(modelId)
}

/**
 * PascalCase factory — the reference pattern that Layer-8 providers will follow.
 * @since 0.3.0-beta01
 */
public fun OpenAI(
    client: HttpClient,
    settings: OpenAISettings = OpenAISettings(),
): OpenAIProvider = OpenAIProvider(client, settings)


private class OpenAIImageModel(
    override val modelId: String,
    private val delegate: ImageModel,
) : ImageModel by delegate {
    override val maxImagesPerCall: Int = when (modelId) {
        "dall-e-3" -> OPENAI_SINGLE_IMAGE_PER_CALL
        "dall-e-2",
        "gpt-image-1",
        "gpt-image-1-mini",
        "gpt-image-1.5",
        "gpt-image-2",
        "chatgpt-image-latest",
        -> OPENAI_MULTI_IMAGE_PER_CALL
        else -> OPENAI_SINGLE_IMAGE_PER_CALL
    }
}

private const val OPENAI_SINGLE_IMAGE_PER_CALL: Int = 1
private const val OPENAI_MULTI_IMAGE_PER_CALL: Int = 10

@Poko
/** @since 0.3.0-beta01 */
public class OpenAITools(
    /** @since 0.3.0-beta01 */
    public val applyPatch: Tool<JsonElement, JsonElement, Any?> = OpenAIApplyPatch(),
    /** @since 0.3.0-beta01 */
    public val codeInterpreter: Tool<JsonElement, JsonElement, Any?> = OpenAICodeInterpreter(),
    /** @since 0.3.0-beta01 */
    public val fileSearch: Tool<JsonElement, JsonElement, Any?> = OpenAIFileSearch(JsonObject(emptyMap())),
    /** @since 0.3.0-beta01 */
    public val imageGeneration: Tool<JsonElement, JsonElement, Any?> = OpenAIImageGeneration(),
    /** @since 0.3.0-beta01 */
    public val localShell: Tool<JsonElement, JsonElement, Any?> = OpenAILocalShell(),
    /** @since 0.3.0-beta01 */
    public val shell: Tool<JsonElement, JsonElement, Any?> = OpenAIShell(),
    /** @since 0.3.0-beta01 */
    public val webSearchPreview: Tool<JsonElement, JsonElement, Any?> = OpenAIWebSearchPreview(),
    /** @since 0.3.0-beta01 */
    public val webSearch: Tool<JsonElement, JsonElement, Any?> = OpenAIWebSearch(),
    /** @since 0.3.0-beta01 */
    public val mcp: Tool<JsonElement, JsonElement, Any?> = OpenAIMcp(),
    /** @since 0.3.0-beta01 */
    public val toolSearch: Tool<JsonElement, JsonElement, Any?> = OpenAIToolSearch(),
) {
    internal companion object {
        internal fun providerTool(
            id: String,
            description: String,
            args: JsonElement = JsonObject(emptyMap()),
        ): Tool<JsonElement, JsonElement, Any?> =
            ProviderExecutedTool(
                name = id.substringAfter("openai."),
                description = description,
                inputSerializer = JsonElement.serializer(),
                outputSerializer = JsonElement.serializer(),
                metadata = mapOf(
                    "providerToolId" to JsonPrimitive(id),
                    "providerToolArgs" to args,
                ),
            )
    }
}

/** @since 0.3.0-beta01 */
public fun OpenAIApplyPatch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.apply_patch", "Apply structured file patches proposed by the model.", args)

/** @since 0.3.0-beta01 */
public fun OpenAICodeInterpreter(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.code_interpreter", "Run Python code in OpenAI's hosted code interpreter.", args)

/** @since 0.3.0-beta01 */
public fun OpenAIFileSearch(args: JsonElement): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.file_search", "Search OpenAI vector stores through the Responses API.", args)

/** @since 0.3.0-beta01 */
public fun OpenAIImageGeneration(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.image_generation", "Generate images with OpenAI's hosted image tool.", args)

/** @since 0.3.0-beta01 */
public fun OpenAILocalShell(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.local_shell", "Request local shell execution through a host integration.", args)

/** @since 0.3.0-beta01 */
public fun OpenAIShell(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.shell", "Request controlled shell command execution.", args)

/** @since 0.3.0-beta01 */
public fun OpenAIWebSearchPreview(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.web_search_preview", "Search the web with OpenAI's preview web search tool.", args)

/** @since 0.3.0-beta01 */
public fun OpenAIWebSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.web_search", "Search the web with OpenAI's web search tool.", args)

/** @since 0.3.0-beta01 */
public fun OpenAIMcp(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.mcp", "Call remote MCP tools exposed to OpenAI Responses.", args)

/** @since 0.3.0-beta01 */
public fun OpenAIToolSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.tool_search", "Let the model search deferred tools dynamically.", args)
