package ai.torad.aisdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

@PublishedApi
internal val aiSdkJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

// Outbound codec — emits defaults so round-trips are stable. Paired with the
// inbound lenient [aiSdkJson]; together these are the two core JSON instances
// (M-3). Provider-local codecs remain only where they encode real wire quirks.
internal val aiSdkOutputJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun <T> encodeJsonElement(value: T, serializer: KSerializer<T>): JsonElement =
    aiSdkJson.encodeToJsonElement(serializer, value)

inline fun <reified T> encodeJsonElement(value: T): JsonElement =
    encodeJsonElement(value, serializer())

fun <T> JsonElement.decodeAs(serializer: KSerializer<T>): T =
    aiSdkJson.decodeFromJsonElement(serializer, this)

inline fun <reified T> JsonElement.decodeAs(): T =
    decodeAs(serializer())

fun <T> JsonObjectBuilder.putJson(name: String, value: T, serializer: KSerializer<T>) {
    put(name, encodeJsonElement(value, serializer))
}

inline fun <reified T> JsonObjectBuilder.putJson(name: String, value: T) {
    putJson(name, value, serializer())
}

fun <T> JsonObjectBuilder.putJsonIfNotNull(name: String, value: T?, serializer: KSerializer<T>) {
    if (value != null) putJson(name, value, serializer)
}

inline fun <reified T> JsonObjectBuilder.putJsonIfNotNull(name: String, value: T?) {
    putJsonIfNotNull(name, value, serializer())
}

fun <T> Map<String, JsonElement>.decodeValue(name: String, serializer: KSerializer<T>): T? =
    this[name]?.decodeAs(serializer)

inline fun <reified T> Map<String, JsonElement>.decodeValue(name: String): T? =
    decodeValue(name, serializer())

fun Map<String, JsonElement>?.providerMetadata(provider: String): JsonElement? =
    this?.get(provider)

fun <T> Map<String, JsonElement>?.decodeProviderMetadata(
    provider: String,
    serializer: KSerializer<T>,
): T? = providerMetadata(provider)?.decodeAs(serializer)

inline fun <reified T> Map<String, JsonElement>?.decodeProviderMetadata(provider: String): T? =
    decodeProviderMetadata(provider, serializer())

fun <T> GenerateTextResult<*>.providerMetadataAs(provider: String, serializer: KSerializer<T>): T? =
    providerMetadata.decodeProviderMetadata(provider, serializer)

inline fun <reified T> GenerateTextResult<*>.providerMetadataAs(provider: String): T? =
    providerMetadataAs(provider, serializer())

fun <T> GenerateObjectResult<*>.providerMetadataAs(provider: String, serializer: KSerializer<T>): T? =
    providerMetadata.decodeProviderMetadata(provider, serializer)

inline fun <reified T> GenerateObjectResult<*>.providerMetadataAs(provider: String): T? =
    providerMetadataAs(provider, serializer())

fun <T> LanguageModelResult.providerMetadataAs(provider: String, serializer: KSerializer<T>): T? =
    providerMetadata.decodeProviderMetadata(provider, serializer)

inline fun <reified T> LanguageModelResult.providerMetadataAs(provider: String): T? =
    providerMetadataAs(provider, serializer())

val ContentPart.metadata: Map<String, JsonElement>?
    get() = when (this) {
        is ContentPart.Text -> providerMetadata
        is ContentPart.Reasoning -> providerMetadata
        is ContentPart.ToolCall -> providerMetadata
        is ContentPart.ToolResult -> providerMetadata
        is ContentPart.ToolApprovalRequest -> providerMetadata
        is ContentPart.ToolApprovalResponse -> null
        is ContentPart.Source -> providerMetadata
        is ContentPart.File -> providerMetadata
        is ContentPart.Image -> providerMetadata
    }

fun <T> ContentPart.providerMetadataAs(provider: String, serializer: KSerializer<T>): T? =
    metadata.decodeProviderMetadata(provider, serializer)

inline fun <reified T> ContentPart.providerMetadataAs(provider: String): T? =
    providerMetadataAs(provider, serializer())

val StreamEvent.metadata: Map<String, JsonElement>?
    get() = when (this) {
        is StreamEvent.StreamStart -> null
        is StreamEvent.ResponseMetadata -> null
        is StreamEvent.StepStart -> providerMetadata
        is StreamEvent.TextStart -> providerMetadata
        is StreamEvent.TextDelta -> providerMetadata
        is StreamEvent.TextEnd -> providerMetadata
        is StreamEvent.ReasoningStart -> providerMetadata
        is StreamEvent.ReasoningDelta -> providerMetadata
        is StreamEvent.ReasoningEnd -> providerMetadata
        is StreamEvent.SourcePart -> providerMetadata
        is StreamEvent.FilePart -> providerMetadata
        is StreamEvent.ToolInputStart -> providerMetadata
        is StreamEvent.ToolInputDelta -> providerMetadata
        is StreamEvent.ToolInputEnd -> providerMetadata
        is StreamEvent.ToolCall -> providerMetadata
        is StreamEvent.ToolResult -> providerMetadata
        is StreamEvent.ToolError -> providerMetadata
        is StreamEvent.ToolApprovalRequest -> providerMetadata
        is StreamEvent.ToolOutputDenied -> providerMetadata
        is StreamEvent.StepFinish -> providerMetadata
        is StreamEvent.Finish -> providerMetadata
        StreamEvent.Abort -> null
        is StreamEvent.Error -> null
        is StreamEvent.Raw -> null
    }

fun <T> StreamEvent.providerMetadataAs(provider: String, serializer: KSerializer<T>): T? =
    metadata.decodeProviderMetadata(provider, serializer)

inline fun <reified T> StreamEvent.providerMetadataAs(provider: String): T? =
    providerMetadataAs(provider, serializer())
