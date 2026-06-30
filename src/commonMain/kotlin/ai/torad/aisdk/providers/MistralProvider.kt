@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

public const val MISTRAL_VERSION: String = "3.0.37"
private const val MISTRAL_MAX_EMBEDDINGS_PER_CALL: Int = 32

public typealias MistralProviderOptions = MistralLanguageModelOptions

@Serializable
@Poko
public class MistralProviderSettings internal constructor(
    public val baseURL: String = "https://api.mistral.ai/v1",
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun toCompatible(): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings {
            name("mistral")
            baseUrl(baseURL.trimEnd('/'))
            apiKey(apiKey)
            headers(ProviderHeaders.withUserAgentSuffix(headers, "ai-sdk/mistral/$MISTRAL_VERSION"))
            providerOptionsName("mistral")
            supportsStructuredOutputs(true)
            chatSeedKey("random_seed")
            maxEmbeddingsPerCall(MISTRAL_MAX_EMBEDDINGS_PER_CALL)
            // Mistral accepts PDFs by URL (sent as document_url), so they pass through.
            supportedUrls(mapOf("application/pdf" to listOf("^https://.*$")))
            // Mistral's chat wire shape differs from OpenAI's; rewrite the OpenAI-shaped body
            // into Mistral's shape post-build (the message converter is otherwise shared).
            transformChatRequestBody { mistralTransformChatBody(it) }
            transformChatResponse { transformChatResponse(it) }
        }

    /** Wire-shape helpers translating between the OpenAI-compatible body and Mistral's API. */
    public companion object {
        /**
         * Rewrites the OpenAI-compatible chat body into Mistral's wire shape:
         * - tool_choice "required" / {function:{name}} → "any" (specific also filters `tools`),
         * - tool messages gain `name` (from the assistant tool_calls' id→name map),
         * - user `file` content parts (PDFs) become `document_url` parts,
         * - the final assistant message gets `prefix: true`.
         */
        private fun mistralTransformChatBody(body: JsonObject): JsonObject {
            val messages = JsonAccess.arr(body, "messages")
            val toolCallNames = mistralToolCallNameMap(messages)
            val lastAssistantIndex = messages?.indexOfLast {
                ((it as? JsonObject)?.get("role") as? JsonPrimitive)?.content == "assistant"
            } ?: -1

            return buildJsonObject {
                for ((key, value) in body) {
                    when (key) {
                        "tool_choice" -> put("tool_choice", mistralToolChoice(value))
                        "tools" -> put("tools", mistralFilterTools(value, body["tool_choice"]))
                        "messages" -> put("messages", mistralRewriteMessages(messages, toolCallNames, lastAssistantIndex))
                        else -> put(key, value)
                    }
                }
            }
        }

        /** id → toolName from assistant tool_calls, so tool-result messages can carry the name. */
        private fun mistralToolCallNameMap(messages: JsonArray?): Map<String, String> = buildMap {
            messages.orEmpty().forEach { msg ->
                ((msg as? JsonObject)?.get("tool_calls") as? JsonArray)?.forEach { call ->
                    val obj = call as? JsonObject ?: return@forEach
                    val id = (obj["id"] as? JsonPrimitive)?.content
                    val name = ((JsonAccess.obj(obj, "function"))?.get("name") as? JsonPrimitive)?.content
                    if (id != null && name != null) put(id, name)
                }
            }
        }

        /** "required"/{function:{name}} → "any"; otherwise pass through ("auto"/"none"). */
        private fun mistralToolChoice(value: JsonElement?): JsonElement = when {
            value is JsonPrimitive && value.content == "required" -> JsonPrimitive("any")
            value is JsonObject -> JsonPrimitive("any")
            else -> value ?: JsonPrimitive("auto")
        }

        /** When tool_choice names a specific tool, Mistral filters `tools` to just that one. */
        private fun mistralFilterTools(tools: JsonElement?, toolChoice: JsonElement?): JsonElement {
            val arr = tools as? JsonArray ?: return tools ?: JsonArray(emptyList())
            val name = ((toolChoice as? JsonObject)?.get("function") as? JsonObject)?.get("name")
                ?.let { (it as? JsonPrimitive)?.content }
            return if (name == null) {
                arr
            } else {
                JsonArray(
                    arr.filter {
                        (((it as? JsonObject)?.get("function") as? JsonObject)?.get("name") as? JsonPrimitive)?.content == name
                    },
                )
            }
        }

        private fun mistralRewriteMessages(
            messages: JsonArray?,
            toolCallNames: Map<String, String>,
            lastAssistantIndex: Int,
        ): JsonArray = JsonArray(
            messages.orEmpty().mapIndexed { index, msg ->
                val obj = msg as? JsonObject ?: return@mapIndexed msg
                when ((obj["role"] as? JsonPrimitive)?.content) {
                    "tool" -> {
                        val name = (obj["tool_call_id"] as? JsonPrimitive)?.content?.let { toolCallNames[it] }
                        if (name == null) obj else JsonObject(obj + ("name" to JsonPrimitive(name)))
                    }
                    "user" -> mistralRewriteUserContent(obj)
                    "assistant" ->
                        if (index == lastAssistantIndex) JsonObject(obj + ("prefix" to JsonPrimitive(true))) else obj
                    else -> obj
                }
            },
        )

        /** Rewrite user `file` content parts to Mistral's `document_url` shape. */
        private fun mistralRewriteUserContent(message: JsonObject): JsonObject {
            val content = JsonAccess.arr(message, "content") ?: return message
            val rewritten = content.map { part ->
                val obj = part as? JsonObject ?: return@map part
                if ((obj["type"] as? JsonPrimitive)?.content != "file") return@map part
                val fileData = (JsonAccess.obj(obj, "file"))?.get("file_data") ?: return@map part
                buildJsonObject {
                    put("type", JsonPrimitive("document_url"))
                    put("document_url", fileData)
                }
            }
            return JsonObject(message + ("content" to JsonArray(rewritten)))
        }

        private fun transformChatResponse(body: JsonObject): JsonObject {
            val choices = JsonAccess.arr(body, "choices")
            return if (choices == null) {
                body
            } else {
                JsonObject(body + ("choices" to JsonArray(choices.map(::transformResponseChoice))))
            }
        }

        private fun transformResponseChoice(choice: JsonElement): JsonElement {
            val obj = choice as? JsonObject
            val updates = obj?.let {
                buildMap<String, JsonElement> {
                    (JsonAccess.obj(it, "message"))?.let(::transformResponseContent)?.let { message ->
                        put("message", message)
                    }
                    (JsonAccess.obj(it, "delta"))?.let(::transformResponseContent)?.let { delta ->
                        put("delta", delta)
                    }
                }
            }.orEmpty()
            return if (obj == null || updates.isEmpty()) choice else JsonObject(obj + updates)
        }

        private fun transformResponseContent(value: JsonObject): JsonObject {
            val content = JsonAccess.arr(value, "content")
            return if (content == null) {
                value
            } else {
                val text = textContent(content)
                val reasoning = reasoningContent(content)
                val transformed = value.toMutableMap()
                if (text.isEmpty()) transformed.remove("content") else transformed["content"] = JsonPrimitive(text)
                if (reasoning.isNotEmpty()) transformed["reasoning_content"] = JsonPrimitive(reasoning)
                JsonObject(transformed)
            }
        }

        private fun textContent(content: JsonArray): String =
            content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                obj.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
                    ?.get("text")?.let { it as? JsonPrimitive }?.contentOrNull
            }.joinToString("")

        private fun reasoningContent(content: JsonArray): String =
            content.joinToString("") { part ->
                val obj = part as? JsonObject
                if ((obj?.get("type") as? JsonPrimitive)?.contentOrNull == "thinking") thinkingText(obj["thinking"]) else ""
            }

        private fun thinkingText(value: JsonElement?): String =
            (value as? JsonArray).orEmpty().mapNotNull { chunk ->
                val obj = chunk as? JsonObject ?: return@mapNotNull null
                obj.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
                    ?.get("text")?.let { it as? JsonPrimitive }?.contentOrNull
            }.joinToString("")
    }
}

public class MistralProviderSettingsBuilder internal constructor() {
    private var baseURL: String = "https://api.mistral.ai/v1"
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()

    public fun baseURL(value: String) {
        baseURL = value
    }

    public fun apiKey(value: String?) {
        apiKey = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    internal fun build(): MistralProviderSettings =
        MistralProviderSettings(
            baseURL = baseURL,
            apiKey = apiKey,
            headers = headers,
        )
}

public fun MistralProviderSettings(
    block: MistralProviderSettingsBuilder.() -> Unit = {},
): MistralProviderSettings =
    MistralProviderSettingsBuilder().apply(block).build()

@Serializable
public data class MistralLanguageModelOptions(
    val safePrompt: Boolean? = null,
    val documentImageLimit: Int? = null,
    val documentPageLimit: Int? = null,
    val structuredOutputs: Boolean? = null,
    val strictJsonSchema: Boolean? = null,
    val parallelToolCalls: Boolean? = null,
    val reasoningEffort: String? = null,
)

public class MistralProvider(
    client: HttpClient,
    public val settings: MistralProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(client, settings.toCompatible())

    override val providerId: String = "mistral"

    public operator fun invoke(modelId: ModelId): LanguageModel = chat(modelId)

    public fun chat(modelId: ModelId): LanguageModel =
        MistralChatLanguageModel(compatible.chatModel(modelId.value))

    public fun embedding(modelId: ModelId): EmbeddingModel =
        MistralEmbeddingModel(compatible.embeddingModel(modelId.value))

    override fun languageModel(modelId: String): LanguageModel = chat(ModelId(modelId))
    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)

    override fun imageModel(modelId: String): ImageModel =
        throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** PascalCase factory mirroring [OpenAI]. */
public fun Mistral(
    client: HttpClient,
    settings: MistralProviderSettings = MistralProviderSettings(),
): MistralProvider = MistralProvider(client, settings)

private class MistralChatLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params.copy(providerOptions = transformMistralProviderOptions(params.providerOptions))).let {
            LanguageModelResult(
                text = it.text,
                toolCalls = it.toolCalls,
                finishReason = if (it.rawFinishReason == "model_length") FinishReason.Length else it.finishReason,
                usage = it.usage,
                providerMetadata = it.providerMetadata,
                content = it.content,
                rawFinishReason = it.rawFinishReason,
                warnings = it.warnings,
                request = it.request,
                response = it.response,
            )
        }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(params.copy(providerOptions = transformMistralProviderOptions(params.providerOptions))).map { event ->
            if (event is StreamEvent.Finish && event.rawFinishReason == "model_length") {
                StreamEvent.Finish(
                    totalSteps = event.totalSteps,
                    finishReason = FinishReason.Length,
                    usage = event.usage,
                    providerMetadata = event.providerMetadata,
                    rawFinishReason = event.rawFinishReason,
                )
            } else {
                event
            }
        }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params.copy(providerOptions = transformMistralProviderOptions(params.providerOptions))).let {
            LanguageModelStreamResult(
                stream = it.stream.map { event ->
                    if (event is StreamEvent.Finish && event.rawFinishReason == "model_length") {
                        StreamEvent.Finish(
                            totalSteps = event.totalSteps,
                            finishReason = FinishReason.Length,
                            usage = event.usage,
                            providerMetadata = event.providerMetadata,
                            rawFinishReason = event.rawFinishReason,
                        )
                    } else {
                        event
                    }
                },
                request = it.request,
                response = it.response,
            )
        }

    private fun transformMistralProviderOptions(options: ProviderOptions): ProviderOptions {
        val map = options.toMap()
        val mistral = JsonAccess.obj(map, "mistral") ?: return options
        val transformed = buildJsonObject {
            for ((key, value) in mistral) {
                when (key) {
                    "safePrompt" -> put("safe_prompt", value)
                    "documentImageLimit" -> put("document_image_limit", value)
                    "documentPageLimit" -> put("document_page_limit", value)
                    "parallelToolCalls" -> put("parallel_tool_calls", value)
                    "reasoningEffort" -> put("reasoning_effort", value)
                    "structuredOutputs" -> put("structured_outputs", value)
                    else -> put(key, value)
                }
            }
        }
        return ProviderOptions.Raw(JsonObject(map + ("mistral" to (transformed as JsonElement))))
    }
}

private class MistralEmbeddingModel(
    private val delegate: EmbeddingModel,
) : EmbeddingModel by delegate {
    override val maxEmbeddingsPerCall: Int = MISTRAL_MAX_EMBEDDINGS_PER_CALL
    override val supportsParallelCalls: Boolean = false
}
