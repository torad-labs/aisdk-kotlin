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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

public const val MISTRAL_VERSION: String = "3.0.37"
private const val MISTRAL_MAX_EMBEDDINGS_PER_CALL: Int = 32

public typealias MistralProviderOptions = MistralLanguageModelOptions

// Wire-shape helpers translating between the OpenAI-compatible body and Mistral's API.

/**
 * Rewrites the OpenAI-compatible chat body into Mistral's wire shape:
 * - tool_choice "required" → "any"; specific `{type:function,function:{name}}` is preserved
 *   (and `tools` is still filtered to that name as a belt-and-suspenders),
 * - tool messages gain `name` (from the assistant tool_calls' id→name map),
 * - user `file` content parts (PDFs) become `document_url` parts,
 * - `prefix: true` is opt-in via providerOptions.mistral.prefix (or a top-level body
 *   `prefix` flag). When enabled, it is applied only if the conversation ends on an
 *   assistant message (true prefix-continuation). Never rewrite assistant messages by default.
 */
private fun MistralTransformChatBody(body: JsonObject): JsonObject {
    val messages = JsonAccess.arr(body, "messages")
    val toolCallNames = MistralToolCallNameMap(messages)
    val lastIndex = messages?.lastIndex ?: -1
    val lastIsAssistant = lastIndex >= 0 &&
        (((messages?.getOrNull(lastIndex) as? JsonObject)?.get("role") as? JsonPrimitive)?.content == "assistant")
    // Opt-in only. A bare boolean body key is stripped below so it is not sent top-level.
    val prefixEnabled = (body["prefix"] as? JsonPrimitive)?.booleanOrNull == true
    val prefixAssistantIndex = if (prefixEnabled && lastIsAssistant) lastIndex else -1

    return buildJsonObject {
        for ((key, value) in body) {
            when (key) {
                "tool_choice" -> put("tool_choice", MistralToolChoice(value))
                "tools" -> put("tools", MistralFilterTools(value, body["tool_choice"]))
                "messages" -> put(
                    "messages",
                    MistralRewriteMessages(messages, toolCallNames, prefixAssistantIndex)
                )
                // Message-level flag only — never a top-level chat field.
                "prefix" -> Unit
                else -> put(key, value)
            }
        }
    }
}

/** id → toolName from assistant tool_calls, so tool-result messages can carry the name. */
private fun MistralToolCallNameMap(messages: JsonArray?): Map<String, String> = buildMap {
    messages.orEmpty().forEach { msg ->
        ((msg as? JsonObject)?.get("tool_calls") as? JsonArray)?.forEach { call ->
            val obj = call as? JsonObject ?: return@forEach
            val id = (obj["id"] as? JsonPrimitive)?.content
            val name = ((JsonAccess.obj(obj, "function"))?.get("name") as? JsonPrimitive)?.content
            if (id != null && name != null) put(id, name)
        }
    }
}

/**
 * Mistral accepts "auto"/"none"/"any" and the OpenAI-shaped specific-tool object
 * `{"type":"function","function":{"name":...}}`. Only map the OpenAI "required" alias
 * to Mistral's "any"; do not collapse specific-tool objects.
 */
private fun MistralToolChoice(value: JsonElement?): JsonElement = when {
    value is JsonPrimitive && value.content == "required" -> JsonPrimitive("any")
    value is JsonObject -> value
    else -> value ?: JsonPrimitive("auto")
}

/** When tool_choice names a specific tool, Mistral filters `tools` to just that one. */
private fun MistralFilterTools(tools: JsonElement?, toolChoice: JsonElement?): JsonElement {
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

private fun MistralRewriteMessages(
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
            "user" -> MistralRewriteUserContent(obj)
            "assistant" ->
                if (index == lastAssistantIndex) JsonObject(obj + ("prefix" to JsonPrimitive(true))) else obj
            else -> obj
        }
    },
)

/** Rewrite user `file` content parts to Mistral's `document_url` shape. */
private fun MistralRewriteUserContent(message: JsonObject): JsonObject {
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

private fun MistralTransformChatResponse(body: JsonObject): JsonObject {
    val choices = JsonAccess.arr(body, "choices")
    return if (choices == null) {
        body
    } else {
        JsonObject(body + ("choices" to JsonArray(choices.map(::MistralTransformResponseChoice))))
    }
}

private fun MistralTransformResponseChoice(choice: JsonElement): JsonElement {
    val obj = choice as? JsonObject
    val updates = obj?.let {
        buildMap<String, JsonElement> {
            (JsonAccess.obj(it, "message"))?.let(::MistralTransformResponseContent)?.let { message ->
                put("message", message)
            }
            (JsonAccess.obj(it, "delta"))?.let(::MistralTransformResponseContent)?.let { delta ->
                put("delta", delta)
            }
        }
    }.orEmpty()
    return if (obj == null || updates.isEmpty()) choice else JsonObject(obj + updates)
}

private fun MistralTransformResponseContent(value: JsonObject): JsonObject {
    val content = JsonAccess.arr(value, "content")
    return if (content == null) {
        value
    } else {
        val text = MistralTextContent(content)
        val reasoning = MistralReasoningContent(content)
        val transformed = value.toMutableMap()
        if (text.isEmpty()) transformed.remove("content") else transformed["content"] = JsonPrimitive(text)
        if (reasoning.isNotEmpty()) transformed["reasoning_content"] = JsonPrimitive(reasoning)
        JsonObject(transformed)
    }
}

private fun MistralTextContent(content: JsonArray): String =
    content.mapNotNull { part ->
        val obj = part as? JsonObject ?: return@mapNotNull null
        obj.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            ?.get("text")?.let { it as? JsonPrimitive }?.contentOrNull
    }.joinToString("")

private fun MistralReasoningContent(content: JsonArray): String =
    content.joinToString("") { part ->
        val obj = part as? JsonObject
        if ((obj?.get("type") as? JsonPrimitive)?.contentOrNull == "thinking") {
            MistralThinkingText(
                obj["thinking"]
            )
        } else {
            ""
        }
    }

private fun MistralThinkingText(value: JsonElement?): String =
    (value as? JsonArray).orEmpty().mapNotNull { chunk ->
        val obj = chunk as? JsonObject ?: return@mapNotNull null
        obj.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            ?.get("text")?.let { it as? JsonPrimitive }?.contentOrNull
    }.joinToString("")

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class MistralProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.mistral.ai/v1",
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
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
            transformChatRequestBody { MistralTransformChatBody(it) }
            transformChatResponse { MistralTransformChatResponse(it) }
        }
}

/** @since 0.3.0-beta01 */
public class MistralProviderSettingsBuilder {
    private var baseURL: String = "https://api.mistral.ai/v1"
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): MistralProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): MistralProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): MistralProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): MistralProviderSettings =
        MistralProviderSettings(
            baseURL = baseURL,
            apiKey = apiKey,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun MistralProviderSettings(
    block: MistralProviderSettingsBuilder.() -> Unit = {},
): MistralProviderSettings =
    MistralProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class MistralLanguageModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val safePrompt: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val documentImageLimit: Int? = null,
    /** @since 0.3.0-beta01 */
    public val documentPageLimit: Int? = null,
    /** @since 0.3.0-beta01 */
    public val structuredOutputs: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val strictJsonSchema: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val parallelToolCalls: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val reasoningEffort: String? = null,
    /**
     * When true and the final message is an assistant turn, set `prefix: true` on that
     * message so Mistral continues it. Off by default — automatic rewrite changes semantics.
     * @since 0.3.0-beta01
     */
    public val prefix: Boolean? = null,
)

/** @since 0.3.0-beta01 */
public class MistralLanguageModelOptionsBuilder {
    private var safePrompt: Boolean? = null
    private var documentImageLimit: Int? = null
    private var documentPageLimit: Int? = null
    private var structuredOutputs: Boolean? = null
    private var strictJsonSchema: Boolean? = null
    private var parallelToolCalls: Boolean? = null
    private var reasoningEffort: String? = null
    private var prefix: Boolean? = null

    /** @since 0.3.0-beta01 */
    public fun safePrompt(value: Boolean?): MistralLanguageModelOptionsBuilder {
        safePrompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun documentImageLimit(value: Int?): MistralLanguageModelOptionsBuilder {
        documentImageLimit = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun documentPageLimit(value: Int?): MistralLanguageModelOptionsBuilder {
        documentPageLimit = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun structuredOutputs(value: Boolean?): MistralLanguageModelOptionsBuilder {
        structuredOutputs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun strictJsonSchema(value: Boolean?): MistralLanguageModelOptionsBuilder {
        strictJsonSchema = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun parallelToolCalls(value: Boolean?): MistralLanguageModelOptionsBuilder {
        parallelToolCalls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun reasoningEffort(value: String?): MistralLanguageModelOptionsBuilder {
        reasoningEffort = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun prefix(value: Boolean?): MistralLanguageModelOptionsBuilder {
        prefix = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): MistralLanguageModelOptions =
        MistralLanguageModelOptions(
            safePrompt = safePrompt,
            documentImageLimit = documentImageLimit,
            documentPageLimit = documentPageLimit,
            structuredOutputs = structuredOutputs,
            strictJsonSchema = strictJsonSchema,
            parallelToolCalls = parallelToolCalls,
            reasoningEffort = reasoningEffort,
            prefix = prefix,
        )
}

/** @since 0.3.0-beta01 */
public fun MistralLanguageModelOptions(
    block: MistralLanguageModelOptionsBuilder.() -> Unit = {},
): MistralLanguageModelOptions =
    MistralLanguageModelOptionsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class MistralProvider(
    client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: MistralProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(client, settings.toCompatible())

    override val providerId: String = "mistral"

    public operator fun invoke(modelId: ModelId): LanguageModel = chat(modelId)

    /** @since 0.3.0-beta01 */
    public fun chat(modelId: ModelId): LanguageModel =
        MistralChatLanguageModel(compatible.chatModel(modelId.value))

    /** @since 0.3.0-beta01 */
    public fun embedding(modelId: ModelId): EmbeddingModel =
        MistralEmbeddingModel(compatible.embeddingModel(modelId.value))

    override fun languageModel(modelId: String): LanguageModel = chat(ModelId(modelId))
    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))

    /** @since 0.3.0-beta01 */
    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)

    override fun imageModel(modelId: String): ImageModel =
        throw NoSuchModelError(providerId, "imageModel", modelId)
}

/**
 * PascalCase factory mirroring [OpenAI].
 * @since 0.3.0-beta01
 */
public fun Mistral(
    client: HttpClient,
    settings: MistralProviderSettings = MistralProviderSettings(),
): MistralProvider = MistralProvider(client, settings)

private class MistralChatLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(
            params.toBuilder().providerOptions(transformMistralProviderOptions(params.providerOptions)).build()
        ).let {
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
        delegate.stream(
            params.toBuilder().providerOptions(transformMistralProviderOptions(params.providerOptions)).build()
        ).map { event ->
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
        delegate.streamResult(
            params.toBuilder().providerOptions(transformMistralProviderOptions(params.providerOptions)).build()
        ).let {
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
