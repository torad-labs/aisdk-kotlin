package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Invariant I-2 — tools use [Tool.inputSerializer], NOT v5's `parameters`.
 * Invariant I-8 — tools are stateless (factory produces no shared state).
 */
class ToolTest {

    @Serializable data class WeatherInput(val location: String)
    @Serializable data class WeatherOutput(val tempF: Int, val condition: String)

    @Test
    fun `tool_factory_carries_input_and_output_serializers`() {
        val weather = tool<WeatherInput, WeatherOutput, Unit>(
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
        val a = tool<WeatherInput, String, Unit>(
            name = "a",
            description = "",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { "" }
        val set = toolSetOf(a)
        assertNotNull(set.find("a"))
        assertNull(set.find("b"))
    }

    @Test
    fun `toolSet_descriptors_carry_each_tool`() {
        val a = tool<WeatherInput, String, Unit>(
            name = "a", description = "alpha",
            inputSerializer = serializer(), outputSerializer = serializer(),
        ) { "" }
        val b = tool<WeatherInput, String, Unit>(
            name = "b", description = "beta",
            inputSerializer = serializer(), outputSerializer = serializer(),
        ) { "" }
        val set = toolSetOf(a, b)
        val names = set.descriptors.map { it.name }.toSet()
        assertEquals(setOf("a", "b"), names)
    }
}
