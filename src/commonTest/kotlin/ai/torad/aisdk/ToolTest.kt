package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariant I-2 — tools use [Tool.inputSerializer], NOT v5's `parameters`.
 * Invariant I-8 — tools are stateless (factory produces no shared state).
 */
class ToolTest {

    @Serializable data class WeatherInput(val location: String)

    @Serializable data class WeatherOutput(val tempF: Int, val condition: String)

    @Serializable enum class UnitSystem { Celsius, Fahrenheit }

    @Serializable data class WeatherWindow(val startHour: Int, val endHour: Int?)

    @Serializable
    data class ForecastInput(
        val location: String,
        val days: Int,
        val threshold: Double,
        val includeAlerts: Boolean,
        val tags: List<String>,
        val weights: Map<String, Double>,
        val note: String?,
        val window: WeatherWindow,
        val unit: UnitSystem = UnitSystem.Celsius,
    )

    @Test
    fun `safeValidateTypes rethrows cancellation from custom validators`() {
        val schema = Schemas.jsonSchema<String>(
            buildJsonObject { put("type", JsonPrimitive("string")) },
        ) {
            throw CancellationException("validator cancelled")
        }

        assertFailsWith<CancellationException> {
            Schemas.safeValidateTypes(JsonPrimitive("Paris"), schema)
        }
    }

    @Test
    fun `tool_factory_carries_input_and_output_serializers`() {
        val weather = Tool<WeatherInput, WeatherOutput, Unit>(
            name = "weather",
            description = "Get weather",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { input -> WeatherOutput(72, "sunny in ${input.location}") }
        assertEquals("weather", weather.name)
        assertNotNull(weather.inputSerializer)
        assertNotNull(weather.outputSerializer)
    }

    @Test
    fun `toolSet_finds_by_name_and_misses_return_null`() {
        val a = Tool<WeatherInput, String, Unit>(
            name = "a",
            description = "",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { "" }
        val set = ToolSet(a)
        assertNotNull(set.find("a"))
        assertNull(set.find("b"))
    }

    @Test
    fun `toolSet_descriptors_carry_each_tool`() {
        val a = Tool<WeatherInput, String, Unit>(
            name = "a",
            description = "alpha",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { "" }
        val b = Tool<WeatherInput, String, Unit>(
            name = "b",
            description = "beta",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { "" }
        val set = ToolSet(a, b)
        val names = set.descriptors.map { it.name }.toSet()
        assertEquals(setOf("a", "b"), names)
    }

    @Test
    fun `toolSet descriptors preserve per-tool strict flag`() {
        val loose = Tool<WeatherInput, WeatherOutput, Unit>(
            name = "loose",
            description = "Non-strict schema tool",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            schemaOptions = ToolSchemaOptions {
                strict(false)
            },
        ) { WeatherOutput(72, "sunny") }

        assertEquals(false, ToolSet(loose).descriptors.single().strict)
    }

    @Test
    fun `toolSet_descriptors_derive_strict_json_schema_from_serializers`() {
        val forecast = Tool<ForecastInput, WeatherOutput, Unit>(
            name = "forecast",
            description = "Get forecast",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { WeatherOutput(72, "sunny in ${it.location}") }

        val schema = Json.parseToJsonElement(ToolSet(forecast).descriptors.single().parametersSchemaJson).jsonObject
        val properties = schema["properties"]!!.jsonObject
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals("string", properties["location"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("integer", properties["days"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("number", properties["threshold"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("boolean", properties["includeAlerts"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("array", properties["tags"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(
            "string",
            properties["tags"]?.jsonObject?.get("items")?.jsonObject?.get("type")?.jsonPrimitive?.content
        )
        assertEquals(
            "number",
            properties["weights"]?.jsonObject?.get(
                "additionalProperties"
            )?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
        assertEquals(
            setOf("Celsius", "Fahrenheit"),
            properties["unit"]?.jsonObject?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
        )
        val noteAnyOf = properties["note"]?.jsonObject?.get("anyOf")?.jsonArray.orEmpty()
        assertTrue(noteAnyOf.any { it.jsonObject["type"]?.jsonPrimitive?.content == "string" })
        assertTrue(noteAnyOf.any { it.jsonObject["type"]?.jsonPrimitive?.content == "null" })
        val window = properties["window"]!!.jsonObject
        assertEquals("object", window["type"]?.jsonPrimitive?.content)
        assertEquals(
            setOf("startHour", "endHour"),
            window["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        )
        assertEquals(
            setOf("location", "days", "threshold", "includeAlerts", "tags", "weights", "note", "window"),
            required
        )
        assertTrue("unit" !in required)
    }

    @Serializable
    private sealed class ShapeCmd {
        @Serializable
        @SerialName("circle")
        data class Circle(val radius: Int) : ShapeCmd()

        @Serializable
        @SerialName("rect")
        data class Rect(val w: Int, val h: Int) : ShapeCmd()
    }

    @Test
    fun `sealed-class tool input schema is a oneOf of typed subclass objects`() {
        val tool = Tool<ShapeCmd, String, Unit>(
            name = "shape",
            description = "draw a shape",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { "ok" }

        val schema = Json.parseToJsonElement(ToolSet(tool).descriptors.single().parametersSchemaJson).jsonObject
        val variants = schema["oneOf"]?.jsonArray
        assertNotNull(variants, "a sealed-class input must produce a oneOf of subclass variants")
        assertEquals(2, variants.size)

        // Each variant is keyed by its pinned "type" discriminator const (the subclass serialName).
        val byType = variants.associateBy { v ->
            v.jsonObject["properties"]?.jsonObject?.get("type")?.jsonObject
                ?.get("enum")?.jsonArray?.single()?.jsonPrimitive?.content
        }
        assertEquals(setOf("circle", "rect"), byType.keys)

        val circle = byType["circle"]?.jsonObject
        assertNotNull(circle)
        assertEquals("object", circle["type"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(false), circle["additionalProperties"])
        // The "type" discriminator is required alongside the subclass's own fields.
        assertEquals(
            setOf("type", "radius"),
            circle["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
        )
        assertEquals(
            "integer",
            circle["properties"]?.jsonObject?.get("radius")?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )

        val rect = byType["rect"]?.jsonObject
        assertNotNull(rect)
        assertEquals(
            setOf("type", "w", "h"),
            rect["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
        )
    }

    @Test
    fun `dynamic tool descriptors preserve explicit input schema`() {
        val explicit = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("value", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            )
        }
        val runtime = DynamicTool<Unit>(
            name = "runtime",
            description = "runtime schema",
            inputSchemaJson = explicit.toString(),
        ) { input -> input }

        val descriptorSchema = Json.parseToJsonElement(ToolSet(runtime).descriptors.single().parametersSchemaJson)

        assertEquals(explicit, descriptorSchema)
    }
}
