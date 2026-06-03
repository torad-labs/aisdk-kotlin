package ai.torad.aisdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Structured output for [generateText] / [streamText] / [Agent].
 *
 * Per invariant I-3 / I-6, structured output goes through `Output` only —
 * v5's `generateObject()` and `streamObject()` are removed. Pass an
 * `Output` to constrain the model's final response to a typed shape.
 *
 * Four variants:
 *   - [Obj]    — single typed object (Kotlin-keyword-safe rename of v6's `object`).
 *   - [Arr]    — array of typed objects.
 *   - [Choice] — constrained enum / set of allowed string values.
 *   - [JsonTree] — generic JSON tree, untyped.
 *
 * Idiomatic use:
 * ```
 * val recipe = generateText(
 *     model = ...,
 *     prompt = "Generate a chocolate cake recipe",
 *     output = Output.obj(serializer<Recipe>()),
 * ).output
 * ```
 */
sealed class Output<T> {

    abstract val schemaName: String
    abstract val schemaJson: String
    abstract fun decode(text: String): T

    /** `Output.object()` in v6, renamed to `obj` because `object` is a Kotlin keyword. */
    class Obj<T>(
        val serializer: KSerializer<T>,
        val name: String = serializer.descriptor.serialName.substringAfterLast('.'),
    ) : Output<T>() {
        override val schemaName: String = name
        override val schemaJson: String = """{"type":"object","title":"$name"}"""
        override fun decode(text: String): T = outputJsonCodec.decodeFromString(serializer, text)
    }

    class Arr<T>(
        val elementSerializer: KSerializer<T>,
        val name: String = elementSerializer.descriptor.serialName.substringAfterLast('.') + "[]",
    ) : Output<List<T>>() {
        override val schemaName: String = name
        override val schemaJson: String =
            """{"type":"array","items":{"type":"object","title":"$name"}}"""
        override fun decode(text: String): List<T> =
            outputJsonCodec.decodeFromString(kotlinx.serialization.builtins.ListSerializer(elementSerializer), text)
    }

}

// Top-level constructors + codec.
// Naming: `output<Variant>(...)` so call sites read as `outputObj(...)`
// rather than the v6 `Output.obj(...)` style.
//
// Only Obj + Arr are kept. Choice / JsonTree variants were unused in
// production code (only their own tests referenced them) — deleted per
// YAGNI. Re-add when a real consumer needs constrained-enum or
// arbitrary-JSON output shapes.

private val outputJsonCodec: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun <T> outputObj(serializer: KSerializer<T>): Output<T> = Output.Obj(serializer)

fun <T> outputArray(elementSerializer: KSerializer<T>): Output<List<T>> = Output.Arr(elementSerializer)
