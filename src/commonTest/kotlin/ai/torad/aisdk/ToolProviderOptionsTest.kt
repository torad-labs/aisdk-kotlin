package ai.torad.aisdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolProviderOptionsTest {
    @kotlinx.serialization.Serializable
    private data class Q(val q: String = "")

    @Test
    fun `tool providerOptions flow onto the LanguageModelTool descriptor`() {
        val cacheControl: kotlinx.serialization.json.JsonElement =
            buildJsonObject { put("cacheControl", JsonPrimitive(true)) }
        val opts = ProviderOptions.Raw(JsonObject(mapOf("anthropic" to cacheControl)))
        val t = Tool<Q, String, Unit>(
            name = "search",
            description = "d",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            providerOptions = opts,
        ) { "ok" }
        val descriptor = toolSetOf(t).descriptors.single()
        assertEquals(opts, descriptor.providerOptions, "providerOptions threaded to the wire descriptor")
    }
}
