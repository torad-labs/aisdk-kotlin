package ai.torad.aisdk.protocol

import ai.torad.aisdk.ParseOpenAIToolInput
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.WireDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal object ProtocolJson {
    fun JsonObject.stringFromAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }

    fun requiredOneOfString(
        obj: JsonObject,
        provider: String,
        operation: String,
        vararg keys: String,
    ): String =
        WireDecoder.requiredOneOfString(obj, provider, operation, "$", *keys)

    fun intOrNull(obj: JsonObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.intOrNull }

    /**
     * v3 response-side tool `input` is a STRINGIFIED JSON object on the wire
     * (`LanguageModelV3ToolCall.input: string`). Parse it like every other
     * provider does; anything already structured passes through untouched.
     */
    fun toolInput(value: JsonElement): JsonElement =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.let { ParseOpenAIToolInput(it.content) } ?: value

    /**
     * v3 splices BOTH source variants into a single `type: "source"` part discriminated by
     * `sourceType` (`url` | `document`); `source-url`/`source-document` are the UI-layer names,
     * accepted here as aliases. Returns null for an unrecognized discriminator so the caller
     * falls through to its raw passthrough rather than guessing a variant.
     */
    fun sourceType(type: String, obj: JsonObject): StreamEvent.SourcePart.SourceType? = when (type) {
        "source-url" -> StreamEvent.SourcePart.SourceType.Url
        "source-document" -> StreamEvent.SourcePart.SourceType.Document
        else -> when ((obj["sourceType"] as? JsonPrimitive)?.contentOrNull) {
            "url" -> StreamEvent.SourcePart.SourceType.Url
            "document" -> StreamEvent.SourcePart.SourceType.Document
            else -> null
        }
    }
}
