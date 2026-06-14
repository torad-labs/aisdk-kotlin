// One small pure function per JSON-Schema kind (primitive/object/list/map/enum/sealed/
// nullable) — the count is inherent to the format, not a god object.
@file:Suppress("TooManyFunctions")

package ai.torad.aisdk

/**
 * SerialDescriptor -> JSON Schema generation, extracted from Tool.kt to keep that
 * file focused on the tool/tool-set surface. Pure functions: no IO, no shared
 * state, safe to call eagerly. Consumed by `ToolSet.descriptors` via [jsonSchemaFor].
 */

internal fun jsonSchemaFor(tool: Tool<*, *, *>): String {
    tool.metadata["inputSchema"]?.let { return it.toString() }
    val descriptor = tool.inputSerializer.descriptor
    return descriptorToJsonSchema(descriptor).toString()
}

/**
 * Walk a kotlinx.serialization [kotlinx.serialization.descriptors.SerialDescriptor]
 * and produce a JSON Schema describing the shape. The output feeds two
 * downstream consumers:
 *  - Cloud providers (OpenAI / Anthropic / Gemini) that expect a strict
 *    OpenAPI-flavoured schema on `tools[].function.parameters`.
 *  - On-device providers (LiteRT-LM) whose
 *    chat-template renders the schema into Gemma 4 / FunctionGemma
 *    native `<|tool>declaration:...<tool|>` blocks. Without proper
 *    `properties` + `required` + `type` fields the model has no signal
 *    on what each tool expects, and routinely emits malformed calls or
 *    calls the wrong tool entirely.
 *
 * Handles the JSON-Schema subset every provider in the spec sheet
 * understands: string / integer / number / boolean primitives, nested
 * objects, arrays, and `required` lists. Pure function — no IO, no
 * side-effects — so it's testable and safe to call eagerly from tool
 * factories.
 */
private const val SCHEMA_TYPE_KEY = "type"
private const val SCHEMA_STRING = "string"
private const val SCHEMA_INTEGER = "integer"
private const val SCHEMA_NUMBER = "number"
private const val SCHEMA_BOOLEAN = "boolean"
private const val SCHEMA_OBJECT = "object"
private const val SCHEMA_ARRAY = "array"

private fun descriptorToJsonSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String> = emptySet(),
): kotlinx.serialization.json.JsonElement =
    if (descriptor.isNullable) {
        nullableSchema(descriptorToNonNullJsonSchema(descriptor, seen))
    } else {
        descriptorToNonNullJsonSchema(descriptor, seen)
    }

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Suppress("ReturnCount") // one early return per descriptor kind reads clearer than a single exit
private fun descriptorToNonNullJsonSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String> = emptySet(),
): kotlinx.serialization.json.JsonElement {
    if (descriptor.serialName.startsWith("kotlinx.serialization.json.")) {
        return openJsonObjectSchema()
    }
    if (descriptor.serialName in seen) {
        return openJsonObjectSchema()
    }
    val nextSeen = seen + descriptor.serialName
    if (descriptor.isInline && descriptor.elementsCount == 1) {
        return descriptorToJsonSchema(descriptor.getElementDescriptor(0), nextSeen)
    }
    val kind = descriptor.kind
    primitiveKindToType(kind)?.let { return typeOnlySchema(it) }
    return when (kind) {
        kotlinx.serialization.descriptors.StructureKind.CLASS,
        kotlinx.serialization.descriptors.StructureKind.OBJECT,
        -> objectSchema(descriptor, nextSeen)
        kotlinx.serialization.descriptors.StructureKind.LIST -> listSchema(descriptor, nextSeen)
        kotlinx.serialization.descriptors.StructureKind.MAP -> mapSchema(descriptor, nextSeen)
        is kotlinx.serialization.descriptors.SerialKind.ENUM -> enumSchema(descriptor)
        is kotlinx.serialization.descriptors.PolymorphicKind.SEALED -> sealedSchema(descriptor, nextSeen)
        is kotlinx.serialization.descriptors.PolymorphicKind.OPEN -> jsonObj(
            SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_OBJECT),
        )
        else -> typeOnlySchema(SCHEMA_STRING)
    }
}

/** Map a kotlinx.serialization primitive kind to its JSON Schema type
 *  name, or null if the kind is structural (object / list / map / enum). */
private fun primitiveKindToType(
    kind: kotlinx.serialization.descriptors.SerialKind,
): String? = when (kind) {
    kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    kotlinx.serialization.descriptors.PrimitiveKind.CHAR,
    -> SCHEMA_STRING
    kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN -> SCHEMA_BOOLEAN
    kotlinx.serialization.descriptors.PrimitiveKind.BYTE,
    kotlinx.serialization.descriptors.PrimitiveKind.SHORT,
    kotlinx.serialization.descriptors.PrimitiveKind.INT,
    kotlinx.serialization.descriptors.PrimitiveKind.LONG,
    -> SCHEMA_INTEGER
    kotlinx.serialization.descriptors.PrimitiveKind.FLOAT,
    kotlinx.serialization.descriptors.PrimitiveKind.DOUBLE,
    -> SCHEMA_NUMBER
    else -> null
}

private fun typeOnlySchema(type: String): kotlinx.serialization.json.JsonObject = jsonObj(
    SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(type),
)

private fun objectSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String>,
): kotlinx.serialization.json.JsonObject {
    val properties = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
    val required = mutableListOf<kotlinx.serialization.json.JsonElement>()
    for (i in 0 until descriptor.elementsCount) {
        val name = descriptor.getElementName(i)
        properties[name] = descriptorToJsonSchema(descriptor.getElementDescriptor(i), seen)
        if (!descriptor.isElementOptional(i)) {
            required.add(kotlinx.serialization.json.JsonPrimitive(name))
        }
    }
    val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
        SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_OBJECT),
        "properties" to kotlinx.serialization.json.JsonObject(properties),
        "additionalProperties" to kotlinx.serialization.json.JsonPrimitive(false),
    )
    if (required.isNotEmpty()) {
        fields["required"] = kotlinx.serialization.json.JsonArray(required)
    }
    return kotlinx.serialization.json.JsonObject(fields)
}

private fun listSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String>,
): kotlinx.serialization.json.JsonObject {
    val items = if (descriptor.elementsCount > 0) {
        descriptorToJsonSchema(descriptor.getElementDescriptor(0), seen)
    } else {
        typeOnlySchema(SCHEMA_STRING)
    }
    return jsonObj(
        SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_ARRAY),
        "items" to items,
    )
}

private fun mapSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String>,
): kotlinx.serialization.json.JsonObject {
    val valueDescriptor = if (descriptor.elementsCount > 1) descriptor.getElementDescriptor(1) else null
    return jsonObj(
        SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_OBJECT),
        "additionalProperties" to (
            valueDescriptor?.let { descriptorToJsonSchema(it, seen) }
                ?: kotlinx.serialization.json.JsonPrimitive(true)
            ),
    )
}

private fun enumSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
): kotlinx.serialization.json.JsonObject {
    val values = (0 until descriptor.elementsCount).map {
        kotlinx.serialization.json.JsonPrimitive(descriptor.getElementName(it))
    }
    return jsonObj(
        SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_STRING),
        "enum" to kotlinx.serialization.json.JsonArray(values),
    )
}

private fun sealedSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String>,
): kotlinx.serialization.json.JsonObject {
    val variants = (0 until descriptor.elementsCount)
        .map { descriptorToJsonSchema(descriptor.getElementDescriptor(it), seen) }
    return jsonObj(
        "oneOf" to kotlinx.serialization.json.JsonArray(variants),
    )
}

private fun nullableSchema(
    schema: kotlinx.serialization.json.JsonElement,
): kotlinx.serialization.json.JsonObject = jsonObj(
    "anyOf" to kotlinx.serialization.json.JsonArray(
        listOf(
            schema,
            jsonObj(SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive("null")),
        ),
    ),
)

private fun openJsonObjectSchema(): kotlinx.serialization.json.JsonObject = jsonObj(
    SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_OBJECT),
    "additionalProperties" to kotlinx.serialization.json.JsonPrimitive(true),
)

private fun jsonObj(
    vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>,
): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(entries.toMap())
