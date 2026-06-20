package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public const val VERSION: String = "3.0.67"

public val OPENAI_RESPONSES_SUPPORTED_URLS: Map<String, List<String>> = OPEN_RESPONSES_SUPPORTED_URLS +
    ("application/pdf" to listOf("^https?://.*$"))

public data class OpenAIProviderSettings(
    val baseURL: String = "https://api.openai.com/v1",
    val apiKey: String? = null,
    val organization: String? = null,
    val project: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val name: String = "openai",
    val queryParams: Map<String, String> = emptyMap(),
    val includeUsage: Boolean = false,
)

public interface OpenAIProvider : Provider {
    public val settings: OpenAIProviderSettings
    public val tools: OpenAITools

    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    public fun chat(modelId: String): LanguageModel
    public fun responses(modelId: String): LanguageModel
    public fun completion(modelId: String): LanguageModel
    public fun embedding(modelId: String): EmbeddingModel
    public fun image(modelId: String): ImageModel
    public fun transcription(modelId: String): TranscriptionModel
    public fun speech(modelId: String): SpeechModel

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    public fun textEmbedding(modelId: String): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
    override fun speechModel(modelId: String): SpeechModel = speech(modelId)
}

public fun createOpenAI(
    client: HttpClient,
    settings: OpenAIProviderSettings = OpenAIProviderSettings(),
): OpenAIProvider = DefaultOpenAIProvider(client, settings)

public fun createOpenAIProvider(
    client: HttpClient,
    settings: OpenAIProviderSettings = OpenAIProviderSettings(),
): OpenAIProvider = createOpenAI(client, settings)

public val openai: OpenAIProvider = OpenAIProviderNotConfigured

public class OpenAIProviderNotConfiguredError :
    AiSdkRuntimeException("OpenAI provider is not configured. Use createOpenAI(client, settings).")

private object OpenAIProviderNotConfigured : OpenAIProvider {
    override val settings: OpenAIProviderSettings = OpenAIProviderSettings()
    override val providerId: String = "openai"
    override val tools: OpenAITools = OpenAITools()

    override fun languageModel(modelId: String): LanguageModel = missing()
    override fun chat(modelId: String): LanguageModel = missing()
    override fun responses(modelId: String): LanguageModel = missing()
    override fun completion(modelId: String): LanguageModel = missing()
    override fun embedding(modelId: String): EmbeddingModel = missing()
    override fun image(modelId: String): ImageModel = missing()
    override fun transcription(modelId: String): TranscriptionModel = missing()
    override fun speech(modelId: String): SpeechModel = missing()

    private fun missing(): Nothing = throw OpenAIProviderNotConfiguredError()
}

private class DefaultOpenAIProvider(
    private val client: HttpClient,
    override val settings: OpenAIProviderSettings,
) : OpenAIProvider {
    private val compatible = createOpenAICompatible(client, settings.toCompatibleSettings())

    override val providerId: String = settings.name
    override val tools: OpenAITools = OpenAITools()

    override fun languageModel(modelId: String): LanguageModel = responses(modelId)

    override fun responses(modelId: String): LanguageModel =
        createOpenResponses(
            client,
            OpenResponsesProviderSettings(
                url = settings.responsesUrl(),
                name = settings.name,
                apiKey = settings.apiKey,
                headers = settings.openAIHeaders(),
                userAgentSuffix = "ai-sdk/openai/$VERSION",
                providerOptionsName = "openai",
                supportedUrls = OPENAI_RESPONSES_SUPPORTED_URLS,
                fileIdPrefixes = listOf("file-"),
            ),
        ).responses(modelId)

    override fun chat(modelId: String): LanguageModel =
        compatible.chatModel(modelId)

    override fun completion(modelId: String): LanguageModel =
        compatible.completionModel(modelId)

    override fun embedding(modelId: String): EmbeddingModel =
        compatible.embeddingModel(modelId)

    override fun image(modelId: String): ImageModel =
        OpenAIImageModel(modelId, compatible.imageModel(modelId))

    override fun transcription(modelId: String): TranscriptionModel =
        compatible.transcriptionModel(modelId)

    override fun speech(modelId: String): SpeechModel =
        compatible.speechModel(modelId)
}

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

public data class OpenAITools(
    val applyPatch: Tool<JsonElement, JsonElement, Any?> = openAIApplyPatch(),
    val codeInterpreter: Tool<JsonElement, JsonElement, Any?> = openAICodeInterpreter(),
    val fileSearch: Tool<JsonElement, JsonElement, Any?> = openAIFileSearch(JsonObject(emptyMap())),
    val imageGeneration: Tool<JsonElement, JsonElement, Any?> = openAIImageGeneration(),
    val localShell: Tool<JsonElement, JsonElement, Any?> = openAILocalShell(),
    val shell: Tool<JsonElement, JsonElement, Any?> = openAIShell(),
    val webSearchPreview: Tool<JsonElement, JsonElement, Any?> = openAIWebSearchPreview(),
    val webSearch: Tool<JsonElement, JsonElement, Any?> = openAIWebSearch(),
    val mcp: Tool<JsonElement, JsonElement, Any?> = openAIMcp(),
    val toolSearch: Tool<JsonElement, JsonElement, Any?> = openAIToolSearch(),
)

public fun openAIApplyPatch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.apply_patch", "Apply structured file patches proposed by the model.", args)

public fun openAICodeInterpreter(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.code_interpreter", "Run Python code in OpenAI's hosted code interpreter.", args)

public fun openAIFileSearch(args: JsonElement): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.file_search", "Search OpenAI vector stores through the Responses API.", args)

public fun openAIImageGeneration(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.image_generation", "Generate images with OpenAI's hosted image tool.", args)

public fun openAILocalShell(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.local_shell", "Request local shell execution through a host integration.", args)

public fun openAIShell(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.shell", "Request controlled shell command execution.", args)

public fun openAIWebSearchPreview(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.web_search_preview", "Search the web with OpenAI's preview web search tool.", args)

public fun openAIWebSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.web_search", "Search the web with OpenAI's web search tool.", args)

public fun openAIMcp(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.mcp", "Call remote MCP tools exposed to OpenAI Responses.", args)

public fun openAIToolSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.tool_search", "Let the model search deferred tools dynamically.", args)

private fun openAIProviderTool(
    id: String,
    description: String,
    args: JsonElement = JsonObject(emptyMap()),
): Tool<JsonElement, JsonElement, Any?> =
    providerExecutedTool(
        name = id.substringAfter("openai."),
        description = description,
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
        metadata = mapOf(
            "providerToolId" to JsonPrimitive(id),
            "providerToolArgs" to args,
        ),
    )

private fun OpenAIProviderSettings.toCompatibleSettings(): OpenAICompatibleProviderSettings {
    val headersWithUserAgent = ProviderHeaders.withUserAgentSuffix(openAIHeaders(), "ai-sdk/openai/$VERSION")
    return OpenAICompatibleProviderSettings(
        name = name,
        baseUrl = baseURL.trimEnd('/'),
        apiKey = apiKey,
        headers = headersWithUserAgent,
        queryParams = queryParams,
        includeUsage = includeUsage,
        supportsStructuredOutputs = true,
    )
}

private fun OpenAIProviderSettings.openAIHeaders(): Map<String, String> {
    val baseHeaders = linkedMapOf<String, String>()
    organization?.takeIf { it.isNotBlank() }?.let { baseHeaders["OpenAI-Organization"] = it }
    project?.takeIf { it.isNotBlank() }?.let { baseHeaders["OpenAI-Project"] = it }
    baseHeaders.putAll(headers)
    return baseHeaders
}

private fun OpenAIProviderSettings.responsesUrl(): String {
    val endpoint = "${baseURL.trimEnd('/')}/responses"
    if (queryParams.isEmpty()) return endpoint
    return endpoint + "?" + queryParams.entries.joinToString("&") { (key, value) ->
        "${UrlOps.encode(key)}=${UrlOps.encode(value)}"
    }
}
