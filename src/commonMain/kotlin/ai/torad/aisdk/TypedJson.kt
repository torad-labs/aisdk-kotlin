package ai.torad.aisdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.serializer

@PublishedApi
internal val aiSdkJson: Json = Json {
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

public val ContentPart.metadata: ProviderMetadata
    get() = when (this) {
        is ContentPart.Text -> providerMetadata
        is ContentPart.Reasoning -> providerMetadata
        is ContentPart.ToolCall -> providerMetadata
        is ContentPart.ToolResult -> providerMetadata
        is ContentPart.ToolApprovalRequest -> providerMetadata
        is ContentPart.ToolApprovalResponse -> ProviderMetadata.None
        is ContentPart.Source -> providerMetadata
        is ContentPart.File -> providerMetadata
        is ContentPart.Image -> providerMetadata
    }

public val StreamEvent.metadata: ProviderMetadata
    get() = when (this) {
        is StreamEvent.StreamStart -> ProviderMetadata.None
        is StreamEvent.ResponseMetadata -> ProviderMetadata.None
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
        StreamEvent.Abort -> ProviderMetadata.None
        is StreamEvent.Error -> ProviderMetadata.None
        is StreamEvent.Raw -> ProviderMetadata.None
    }

// Typed JSON operations. Previously loose top-level funs (decision-C cleanup):
// member functions and member-extension functions now live on this object so
// none remain camelCase top-level `fun`s. Callers use a member-import or
// `with(TypedJsonOps) { ... }`.
public object TypedJsonOps {
    public fun <T> encodeJsonElement(value: T, serializer: KSerializer<T>): JsonElement =
        aiSdkJson.encodeToJsonElement(serializer, value)

    public inline fun <reified T> encodeJsonElement(value: T): JsonElement =
        encodeJsonElement(value, serializer())

    public fun <T> JsonElement.decodeAs(serializer: KSerializer<T>): T =
        aiSdkJson.decodeFromJsonElement(serializer, this)

    public inline fun <reified T> JsonElement.decodeAs(): T =
        decodeAs(serializer())

    public fun <T> JsonObjectBuilder.putJson(name: String, value: T, serializer: KSerializer<T>) {
        put(name, encodeJsonElement(value, serializer))
    }

    public inline fun <reified T> JsonObjectBuilder.putJson(name: String, value: T) {
        putJson(name, value, serializer())
    }

    public fun <T> JsonObjectBuilder.putJsonIfNotNull(name: String, value: T?, serializer: KSerializer<T>) {
        if (value != null) putJson(name, value, serializer)
    }

    public inline fun <reified T> JsonObjectBuilder.putJsonIfNotNull(name: String, value: T?) {
        putJsonIfNotNull(name, value, serializer())
    }

    public fun <T> Map<String, JsonElement>.decodeValue(name: String, serializer: KSerializer<T>): T? =
        this[name]?.decodeAs(serializer)

    public inline fun <reified T> Map<String, JsonElement>.decodeValue(name: String): T? =
        decodeValue(name, serializer())

    public fun Map<String, JsonElement>?.providerMetadata(provider: String): JsonElement? =
        this?.get(provider)

    public fun <T> Map<String, JsonElement>?.decodeProviderMetadata(
        provider: String,
        serializer: KSerializer<T>,
    ): T? = providerMetadata(provider)?.decodeAs(serializer)

    public inline fun <reified T> Map<String, JsonElement>?.decodeProviderMetadata(provider: String): T? =
        decodeProviderMetadata(provider, serializer())

    public fun <T> GenerateTextResult<*>.providerMetadataAs(provider: String, serializer: KSerializer<T>): T? =
        providerMetadata.toMap().decodeProviderMetadata(provider, serializer)

    public inline fun <reified T> GenerateTextResult<*>.providerMetadataAs(provider: String): T? =
        providerMetadataAs(provider, serializer())

    public fun <T> GenerateObjectResult<*>.providerMetadataAs(provider: String, serializer: KSerializer<T>): T? =
        providerMetadata.toMap().decodeProviderMetadata(provider, serializer)

    public inline fun <reified T> GenerateObjectResult<*>.providerMetadataAs(provider: String): T? =
        providerMetadataAs(provider, serializer())

    public fun <T> LanguageModelResult.providerMetadataAs(provider: String, serializer: KSerializer<T>): T? =
        providerMetadata.toMap().decodeProviderMetadata(provider, serializer)

    public inline fun <reified T> LanguageModelResult.providerMetadataAs(provider: String): T? =
        providerMetadataAs(provider, serializer())

    public fun <T> ContentPart.providerMetadataAs(provider: String, serializer: KSerializer<T>): T? =
        metadata.toMap().decodeProviderMetadata(provider, serializer)

    public inline fun <reified T> ContentPart.providerMetadataAs(provider: String): T? =
        providerMetadataAs(provider, serializer())

    public fun <T> StreamEvent.providerMetadataAs(provider: String, serializer: KSerializer<T>): T? =
        metadata.toMap().decodeProviderMetadata(provider, serializer)

    public inline fun <reified T> StreamEvent.providerMetadataAs(provider: String): T? =
        providerMetadataAs(provider, serializer())

    public fun jsonNumber(obj: JsonObject, vararg names: String): Double =
        jsonNumberOrNull(obj, *names) ?: 0.0

    public fun jsonNumberOrNull(obj: JsonObject, vararg names: String): Double? =
        names.firstNotNullOfOrNull { name -> (obj[name] as? JsonPrimitive)?.doubleOrNull }

    public fun jsonInt(obj: JsonObject, vararg names: String): Int =
        jsonIntOrNull(obj, *names) ?: 0

    public fun jsonIntOrNull(obj: JsonObject, vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> (obj[name] as? JsonPrimitive)?.intOrNull }
}
