package ai.torad.aisdk

import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement

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
    val applyPatch: Tool<JsonElement, JsonElement, Any?> = openAIProviderTool(
        id = "openai.apply_patch",
        description = "Apply structured file patches proposed by the model.",
    ),
    val codeInterpreter: Tool<JsonElement, JsonElement, Any?> = openAIProviderTool(
        id = "openai.code_interpreter",
        description = "Run Python code in OpenAI's hosted code interpreter.",
    ),
    val fileSearch: Tool<JsonElement, JsonElement, Any?> = openAIProviderTool(
        id = "openai.file_search",
        description = "Search OpenAI vector stores through the Responses API.",
    ),
    val imageGeneration: Tool<JsonElement, JsonElement, Any?> = openAIProviderTool(
        id = "openai.image_generation",
        description = "Generate images with OpenAI's hosted image tool.",
    ),
    val localShell: Tool<JsonElement, JsonElement, Any?> = openAIProviderTool(
        id = "openai.local_shell",
        description = "Request local shell execution through a host integration.",
    ),
    val shell: Tool<JsonElement, JsonElement, Any?> = openAIProviderTool(
        id = "openai.shell",
        description = "Request controlled shell command execution.",
    ),
    val webSearchPreview: Tool<JsonElement, JsonElement, Any?> = openAIProviderTool(
        id = "openai.web_search_preview",
        description = "Search the web with OpenAI's preview web search tool.",
    ),
    val webSearch: Tool<JsonElement, JsonElement, Any?> = openAIProviderTool(
        id = "openai.web_search",
        description = "Search the web with OpenAI's web search tool.",
    ),
    val mcp: Tool<JsonElement, JsonElement, Any?> = openAIProviderTool(
        id = "openai.mcp",
        description = "Call remote MCP tools exposed to OpenAI Responses.",
    ),
    val toolSearch: Tool<JsonElement, JsonElement, Any?> = openAIProviderTool(
        id = "openai.tool_search",
        description = "Let the model search deferred tools dynamically.",
    ),
)

private fun openAIProviderTool(
    id: String,
    description: String,
): Tool<JsonElement, JsonElement, Any?> =
    providerExecutedTool(
        name = id.substringAfter("openai."),
        description = description,
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
        metadata = mapOf("providerToolId" to kotlinx.serialization.json.JsonPrimitive(id)),
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
        "${openAIProviderUrlEncode(key)}=${openAIProviderUrlEncode(value)}"
    }
}

private fun openAIProviderUrlEncode(value: String): String =
    buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            if (char.isLetterOrDigit() || char in setOf('-', '_', '.', '~')) {
                append(char)
            } else {
                append('%')
                append(unsigned.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
