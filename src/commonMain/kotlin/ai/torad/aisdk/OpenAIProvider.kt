package ai.torad.aisdk

import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val VERSION: String = "3.0.67"

val OPENAI_RESPONSES_SUPPORTED_URLS: Map<String, List<String>> = OPEN_RESPONSES_SUPPORTED_URLS +
    ("application/pdf" to listOf("^https?://.*$"))

data class OpenAIProviderSettings(
    val baseURL: String = "https://api.openai.com/v1",
    val apiKey: String? = null,
    val organization: String? = null,
    val project: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val name: String = "openai",
    val queryParams: Map<String, String> = emptyMap(),
    val includeUsage: Boolean = false,
)

interface OpenAIProvider : Provider {
    val settings: OpenAIProviderSettings
    val tools: OpenAITools

    operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    fun chat(modelId: String): LanguageModel
    fun responses(modelId: String): LanguageModel
    fun completion(modelId: String): LanguageModel
    fun embedding(modelId: String): EmbeddingModel
    fun image(modelId: String): ImageModel
    fun transcription(modelId: String): TranscriptionModel
    fun speech(modelId: String): SpeechModel

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    fun textEmbedding(modelId: String): EmbeddingModel = embedding(modelId)
    fun textEmbeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
    override fun speechModel(modelId: String): SpeechModel = speech(modelId)
}

fun createOpenAI(
    client: HttpClient,
    settings: OpenAIProviderSettings = OpenAIProviderSettings(),
): OpenAIProvider = DefaultOpenAIProvider(client, settings)

fun createOpenAIProvider(
    client: HttpClient,
    settings: OpenAIProviderSettings = OpenAIProviderSettings(),
): OpenAIProvider = createOpenAI(client, settings)

val openai: OpenAIProvider = OpenAIProviderNotConfigured

class OpenAIProviderNotConfiguredError :
    AiSdkException("OpenAI provider is not configured. Use createOpenAI(client, settings).")

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
        compatible.imageModel(modelId)

    override fun transcription(modelId: String): TranscriptionModel =
        compatible.transcriptionModel(modelId)

    override fun speech(modelId: String): SpeechModel =
        compatible.speechModel(modelId)
}

data class OpenAITools(
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

fun openAIApplyPatch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.apply_patch", "Apply structured file patches proposed by the model.", args)

fun openAICodeInterpreter(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.code_interpreter", "Run Python code in OpenAI's hosted code interpreter.", args)

fun openAIFileSearch(args: JsonElement): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.file_search", "Search OpenAI vector stores through the Responses API.", args)

fun openAIImageGeneration(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.image_generation", "Generate images with OpenAI's hosted image tool.", args)

fun openAILocalShell(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.local_shell", "Request local shell execution through a host integration.", args)

fun openAIShell(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.shell", "Request controlled shell command execution.", args)

fun openAIWebSearchPreview(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.web_search_preview", "Search the web with OpenAI's preview web search tool.", args)

fun openAIWebSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.web_search", "Search the web with OpenAI's web search tool.", args)

fun openAIMcp(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    openAIProviderTool("openai.mcp", "Call remote MCP tools exposed to OpenAI Responses.", args)

fun openAIToolSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
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
    val headersWithUserAgent = withUserAgentSuffix(openAIHeaders(), "ai-sdk/openai/$VERSION")
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
        "${urlEncode(key)}=${urlEncode(value)}"
    }
}

