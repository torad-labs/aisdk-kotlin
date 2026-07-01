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

/**
 * Structured output for [TextGenerator], [StructuredObjectGenerator], and [Agent].
 *
 * Per invariant I-3 / I-6, high-level typed text generation goes through `Output`.
 * Use [TextGenerator.generate] with an `Output` value when you only need the final
 * typed result, or [StructuredObjectGenerator] with a [Schema] when you need
 * streaming partial object phases.
 *
 * Four variants:
 *   - [Obj]    — single typed object (Kotlin-keyword-safe rename of v6's `object`).
 *   - [Arr]    — array of typed objects.
 *   - [Choice] — constrained enum / set of allowed string values.
 *   - [JsonTree] — generic JSON tree, untyped.
 *
 * Idiomatic use:
 * ```
 * val recipe = TextGenerator(model)
 *     .generate(
 *         GenerationInput.Prompt("Generate a chocolate cake recipe"),
 *         Output.obj(serializer<Recipe>()),
 *     )
 *     .first()
 *     .output
 * ```
 */
/** @since 0.3.0-beta01 */
public sealed class Output<T> {

    /** @since 0.3.0-beta01 */
    public abstract val schemaName: String
    /** @since 0.3.0-beta01 */
    public open val schemaDescription: String? = null
    /** @since 0.3.0-beta01 */
    public abstract val schemaJson: String
    /** @since 0.3.0-beta01 */
    public open val schema: JsonElement by lazy { aiSdkOutputJson.parseToJsonElement(schemaJson) }
    /** @since 0.3.0-beta01 */
    public abstract fun decode(text: String): T

    /** @since 0.3.0-beta01 */
    public fun toResponseFormat(): ResponseFormat = ResponseFormat.Json(
        schemaName = schemaName,
        schemaDescription = schemaDescription,
        schemaJson = runCatching { schema }.getOrNull(),
    )

    /**
     * `Output.object()` in v6, renamed to `obj` because `object` is a Kotlin keyword.
     * @since 0.3.0-beta01
     */
    public class Obj<T>(
        /** @since 0.3.0-beta01 */
        public val serializer: KSerializer<T>,
        /** @since 0.3.0-beta01 */
        public val name: String = serializer.descriptor.serialName.substringAfterLast('.'),
        override val schemaDescription: String? = null,
    ) : Output<T>() {
        override val schemaName: String = name

        // Generate the real schema (type/properties/required/additionalProperties:false) from the
        // serializer's descriptor — a property-less object stub makes strict structured-output
        // providers reject the request (400). Reuses the tool-input schema generator.
        override val schemaJson: String = ToolJsonSchema.jsonSchemaFor(serializer.descriptor).toString()
        override fun decode(text: String): T = aiSdkOutputJson.decodeFromString(serializer, text)
    }

    /** @since 0.3.0-beta01 */
    public class Arr<T>(
        /** @since 0.3.0-beta01 */
        public val elementSerializer: KSerializer<T>,
        /** @since 0.3.0-beta01 */
        public val name: String = elementSerializer.descriptor.serialName.substringAfterLast('.') + "[]",
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
                            // Describe the element shape from its serializer instead of a
                            // property-less object stub, so strict providers accept the array.
                            put("items", ToolJsonSchema.jsonSchemaFor(elementSerializer.descriptor))
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
                is JsonObject -> JsonAccess.arr(element, "elements")
                else -> null
            } ?: throw InvalidResponseDataError(element, "Expected a JSON array or an object with an 'elements' array")
            return aiSdkOutputJson.decodeFromJsonElement(listSerializer, elements)
        }
    }

    /** @since 0.3.0-beta01 */
    public class Choice<T>(
        /** @since 0.3.0-beta01 */
        public val options: List<T>,
        /** @since 0.3.0-beta01 */
        public val encode: (T) -> String,
        /** @since 0.3.0-beta01 */
        public val decodeChoice: (String) -> T,
        /** @since 0.3.0-beta01 */
        public val name: String = "choice",
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

    /** @since 0.3.0-beta01 */
    public class JsonTree(
        /** @since 0.3.0-beta01 */
        public val name: String = "json",
        override val schemaDescription: String? = null,
    ) : Output<JsonElement>() {
        override val schemaName: String = name
        override val schemaJson: String = "{}"
        override fun decode(text: String): JsonElement = aiSdkOutputJson.parseToJsonElement(text)
    }

    public companion object {
        public fun <T> obj(
            serializer: KSerializer<T>,
            name: String = serializer.descriptor.serialName.substringAfterLast('.'),
            description: String? = null,
        ): Output<T> = Obj(serializer, name, description)

        public fun <T> array(
            elementSerializer: KSerializer<T>,
            name: String = elementSerializer.descriptor.serialName.substringAfterLast('.') + "[]",
            description: String? = null,
        ): Output<List<T>> = Arr(elementSerializer, name, description)

        /** @since 0.3.0-beta01 */
        public fun choice(
            options: Iterable<String>,
            name: String = "choice",
            description: String? = null,
        ): Output<String> = Choice(options.toList(), encode = { it }, decodeChoice = { it }, name, description)

        /** @since 0.3.0-beta01 */
        public fun choice(
            vararg options: String,
            name: String = "choice",
            description: String? = null,
        ): Output<String> = choice(options.asIterable(), name, description)

        /** @since 0.3.0-beta01 */
        public fun json(
            name: String = "json",
            description: String? = null,
        ): Output<JsonElement> = JsonTree(name, description)

        internal fun extractChoiceValue(text: String): String {
            val element = aiSdkOutputJson.parseToJsonElement(text)
            return when (element) {
                is JsonObject -> (element["result"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> element.contentOrNull
                else -> null
            } ?: throw InvalidResponseDataError(element, "Expected a JSON object with a 'result' string")
        }
    }

}

// Top-level constructors + codec.
// Naming: `Output<Variant>(...)` so call sites read as `OutputObj(...)`
// while `Output.obj(...)` also works for v6-shaped call sites.

public fun <T> OutputObj(
    serializer: KSerializer<T>,
    name: String = serializer.descriptor.serialName.substringAfterLast('.'),
    description: String? = null,
): Output<T> = Output.Obj(serializer, name, description)

public fun <T> OutputArray(
    elementSerializer: KSerializer<T>,
    name: String = elementSerializer.descriptor.serialName.substringAfterLast('.') + "[]",
    description: String? = null,
): Output<List<T>> = Output.Arr(elementSerializer, name, description)

/** @since 0.3.0-beta01 */
public fun OutputChoice(
    options: Iterable<String>,
    name: String = "choice",
    description: String? = null,
): Output<String> = Output.choice(options, name, description)

/** @since 0.3.0-beta01 */
public fun OutputChoice(
    vararg options: String,
    name: String = "choice",
    description: String? = null,
): Output<String> = Output.choice(options.asIterable(), name, description)

public fun <T> OutputChoice(
    options: Iterable<T>,
    encode: (T) -> String,
    decodeChoice: (String) -> T,
    name: String = "choice",
    description: String? = null,
): Output<T> = Output.Choice(options.toList(), encode, decodeChoice, name, description)

/** @since 0.3.0-beta01 */
public fun OutputJson(
    name: String = "json",
    description: String? = null,
): Output<JsonElement> = Output.JsonTree(name, description)
