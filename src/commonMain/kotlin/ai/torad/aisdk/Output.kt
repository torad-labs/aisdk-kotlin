package ai.torad.aisdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured output for [generateText] / [streamText] / [Agent].
 *
 * Per invariant I-3 / I-6, structured output goes through `Output`.
 * Prefer `generateText(output = ...)` / `streamText(output = ...)`.
 * `generateObject()` and `streamObject()` are compatibility shims for
 * v6 call sites that still use the deprecated object helpers.
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
    open val schemaDescription: String? = null
    abstract val schemaJson: String
    open val schema: JsonElement by lazy { aiSdkOutputJson.parseToJsonElement(schemaJson) }
    abstract fun decode(text: String): T

    /** `Output.object()` in v6, renamed to `obj` because `object` is a Kotlin keyword. */
    class Obj<T>(
        val serializer: KSerializer<T>,
        val name: String = serializer.descriptor.serialName.substringAfterLast('.'),
        override val schemaDescription: String? = null,
    ) : Output<T>() {
        override val schemaName: String = name
        override val schemaJson: String = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("title", JsonPrimitive(name))
        }.toString()
        override fun decode(text: String): T = aiSdkOutputJson.decodeFromString(serializer, text)
    }

    class Arr<T>(
        val elementSerializer: KSerializer<T>,
        val name: String = elementSerializer.descriptor.serialName.substringAfterLast('.') + "[]",
        override val schemaDescription: String? = null,
    ) : Output<List<T>>() {
        override val schemaName: String = name
        override val schemaJson: String = buildJsonObject {
            put("\$schema", JsonPrimitive("http://json-schema.org/draft-07/schema#"))
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "elements",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", JsonPrimitive("object"))
                                    put("title", JsonPrimitive(name))
                                },
                            )
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("elements"))))
            put("additionalProperties", JsonPrimitive(false))
        }.toString()

        override fun decode(text: String): List<T> {
            val listSerializer = ListSerializer(elementSerializer)
            val element = aiSdkOutputJson.parseToJsonElement(text)
            val elements = when (element) {
                is JsonArray -> element
                is JsonObject -> element["elements"] as? JsonArray
                else -> null
            } ?: error("Expected a JSON array or an object with an `elements` array")
            return aiSdkOutputJson.decodeFromJsonElement(listSerializer, elements)
        }
    }

    class Choice<T>(
        val options: List<T>,
        val encode: (T) -> String,
        val decodeChoice: (String) -> T,
        val name: String = "choice",
        override val schemaDescription: String? = null,
    ) : Output<T>() {
        private val encodedOptions: List<String> = options.map(encode)

        override val schemaName: String = name
        override val schemaJson: String = buildJsonObject {
            put("\$schema", JsonPrimitive("http://json-schema.org/draft-07/schema#"))
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "result",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("enum", JsonArray(encodedOptions.map(::JsonPrimitive)))
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("result"))))
            put("additionalProperties", JsonPrimitive(false))
        }.toString()

        override fun decode(text: String): T {
            val value = extractChoiceValue(text)
            require(value in encodedOptions) {
                "Expected one of ${encodedOptions.joinToString(prefix = "[", postfix = "]")}, got `$value`"
            }
            return decodeChoice(value)
        }
    }

    class JsonTree(
        val name: String = "json",
        override val schemaDescription: String? = null,
    ) : Output<JsonElement>() {
        override val schemaName: String = name
        override val schemaJson: String = "{}"
        override fun decode(text: String): JsonElement = aiSdkOutputJson.parseToJsonElement(text)
    }

    companion object {
        fun <T> obj(
            serializer: KSerializer<T>,
            name: String = serializer.descriptor.serialName.substringAfterLast('.'),
            description: String? = null,
        ): Output<T> = Obj(serializer, name, description)

        fun <T> array(
            elementSerializer: KSerializer<T>,
            name: String = elementSerializer.descriptor.serialName.substringAfterLast('.') + "[]",
            description: String? = null,
        ): Output<List<T>> = Arr(elementSerializer, name, description)

        fun choice(
            options: Iterable<String>,
            name: String = "choice",
            description: String? = null,
        ): Output<String> = Choice(options.toList(), encode = { it }, decodeChoice = { it }, name, description)

        fun choice(
            vararg options: String,
            name: String = "choice",
            description: String? = null,
        ): Output<String> = choice(options.asIterable(), name, description)

        fun json(
            name: String = "json",
            description: String? = null,
        ): Output<JsonElement> = JsonTree(name, description)
    }

}

// Top-level constructors + codec.
// Naming: `output<Variant>(...)` so call sites read as `outputObj(...)`
// while `Output.obj(...)` also works for v6-shaped call sites.

fun <T> outputObj(
    serializer: KSerializer<T>,
    name: String = serializer.descriptor.serialName.substringAfterLast('.'),
    description: String? = null,
): Output<T> = Output.Obj(serializer, name, description)

fun <T> outputArray(
    elementSerializer: KSerializer<T>,
    name: String = elementSerializer.descriptor.serialName.substringAfterLast('.') + "[]",
    description: String? = null,
): Output<List<T>> = Output.Arr(elementSerializer, name, description)

fun outputChoice(
    options: Iterable<String>,
    name: String = "choice",
    description: String? = null,
): Output<String> = Output.choice(options, name, description)

fun outputChoice(
    vararg options: String,
    name: String = "choice",
    description: String? = null,
): Output<String> = Output.choice(options.asIterable(), name, description)

fun <T> outputChoice(
    options: Iterable<T>,
    encode: (T) -> String,
    decodeChoice: (String) -> T,
    name: String = "choice",
    description: String? = null,
): Output<T> = Output.Choice(options.toList(), encode, decodeChoice, name, description)

fun outputJson(
    name: String = "json",
    description: String? = null,
): Output<JsonElement> = Output.JsonTree(name, description)

internal fun Output<*>.toResponseFormat(): ResponseFormat = ResponseFormat.Json(
    schemaName = schemaName,
    schemaDescription = schemaDescription,
    schemaJson = runCatching { schema }.getOrNull(),
)

private fun extractChoiceValue(text: String): String {
    val element = aiSdkOutputJson.parseToJsonElement(text)
    return when (element) {
        is JsonObject -> element["result"]?.jsonPrimitive?.contentOrNull
        is JsonPrimitive -> element.contentOrNull
        else -> null
    } ?: error("Expected a JSON object with a `result` string")
}
