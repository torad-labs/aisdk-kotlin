package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public const val VERSION: String = "3.0.67"

public val OPENAI_RESPONSES_SUPPORTED_URLS: Map<String, List<String>> = OPEN_RESPONSES_SUPPORTED_URLS +
    ("application/pdf" to listOf("^https?://.*$"))

@Poko
public class OpenAIProviderSettings internal constructor(
    public val baseURL: String = "https://api.openai.com/v1",
    public val apiKey: String? = null,
    public val organization: String? = null,
    public val project: String? = null,
    public val headers: Map<String, String> = emptyMap(),
    public val name: String = "openai",
    public val queryParams: Map<String, String> = emptyMap(),
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

public class OpenAIProviderSettingsBuilder internal constructor() {
    private var baseURL: String = "https://api.openai.com/v1"
    private var apiKey: String? = null
    private var organization: String? = null
    private var project: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var name: String = "openai"
    private var queryParams: Map<String, String> = emptyMap()
    private var includeUsage: Boolean = false

    public fun baseURL(value: String) {
        baseURL = value
    }

    public fun apiKey(value: String?) {
        apiKey = value
    }

    public fun organization(value: String?) {
        organization = value
    }

    public fun project(value: String?) {
        project = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    public fun name(value: String) {
        name = value
    }

    public fun queryParams(value: Map<String, String>) {
        queryParams = value
    }

    public fun includeUsage(value: Boolean) {
        includeUsage = value
    }

    internal fun build(): OpenAIProviderSettings =
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

public fun OpenAIProviderSettings(
    block: OpenAIProviderSettingsBuilder.() -> Unit = {},
): OpenAIProviderSettings =
    OpenAIProviderSettingsBuilder().apply(block).build()

public typealias OpenAISettings = OpenAIProviderSettings

public class OpenAIProvider(
    private val client: HttpClient,
    public val settings: OpenAIProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(client, settings.toCompatibleSettings())

    override val providerId: String = settings.name
    public val tools: OpenAITools = OpenAITools()

    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)

    override fun languageModel(modelId: String): LanguageModel = responses(modelId)

    public fun chat(modelId: String): LanguageModel =
        compatible.chatModel(modelId)

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

    public fun completion(modelId: String): LanguageModel =
        compatible.completionModel(modelId)

    public fun embedding(modelId: String): EmbeddingModel =
        compatible.embeddingModel(modelId)

    public fun image(modelId: String): ImageModel =
        OpenAIImageModel(modelId, compatible.imageModel(modelId))

    public fun transcription(modelId: String): TranscriptionModel =
        compatible.transcriptionModel(modelId)

    public fun speech(modelId: String): SpeechModel =
        compatible.speechModel(modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    public fun textEmbedding(modelId: String): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
    override fun speechModel(modelId: String): SpeechModel = speech(modelId)
}

/** PascalCase factory — the reference pattern that Layer-8 providers will follow. */
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
public class OpenAITools(
    public val applyPatch: Tool<JsonElement, JsonElement, Any?> = OpenAIApplyPatch(),
    public val codeInterpreter: Tool<JsonElement, JsonElement, Any?> = OpenAICodeInterpreter(),
    public val fileSearch: Tool<JsonElement, JsonElement, Any?> = OpenAIFileSearch(JsonObject(emptyMap())),
    public val imageGeneration: Tool<JsonElement, JsonElement, Any?> = OpenAIImageGeneration(),
    public val localShell: Tool<JsonElement, JsonElement, Any?> = OpenAILocalShell(),
    public val shell: Tool<JsonElement, JsonElement, Any?> = OpenAIShell(),
    public val webSearchPreview: Tool<JsonElement, JsonElement, Any?> = OpenAIWebSearchPreview(),
    public val webSearch: Tool<JsonElement, JsonElement, Any?> = OpenAIWebSearch(),
    public val mcp: Tool<JsonElement, JsonElement, Any?> = OpenAIMcp(),
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

public fun OpenAIApplyPatch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.apply_patch", "Apply structured file patches proposed by the model.", args)

public fun OpenAICodeInterpreter(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.code_interpreter", "Run Python code in OpenAI's hosted code interpreter.", args)

public fun OpenAIFileSearch(args: JsonElement): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.file_search", "Search OpenAI vector stores through the Responses API.", args)

public fun OpenAIImageGeneration(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.image_generation", "Generate images with OpenAI's hosted image tool.", args)

public fun OpenAILocalShell(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.local_shell", "Request local shell execution through a host integration.", args)

public fun OpenAIShell(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.shell", "Request controlled shell command execution.", args)

public fun OpenAIWebSearchPreview(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.web_search_preview", "Search the web with OpenAI's preview web search tool.", args)

public fun OpenAIWebSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.web_search", "Search the web with OpenAI's web search tool.", args)

public fun OpenAIMcp(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.mcp", "Call remote MCP tools exposed to OpenAI Responses.", args)

public fun OpenAIToolSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    OpenAITools.providerTool("openai.tool_search", "Let the model search deferred tools dynamically.", args)
