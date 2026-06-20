package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.dataAs
import ai.torad.aisdk.ui.metadataAs
import ai.torad.aisdk.ui.outputAs
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

class KotlinIdiomsTest {

    @Serializable
    data class WeatherInput(val city: String)

    @Serializable
    data class WeatherOutput(val temperature: Int)

    @Serializable
    data class Recipe(val name: String)

    @Serializable
    data class ProviderTuning(val reasoningEffort: String)

    @Test
    fun `provider option builder creates nested provider maps`() {
        val settings = callSettings {
            providerOptions {
                provider("openai") {
                    put("reasoningEffort", JsonPrimitive("high"))
                }
                put("trace", JsonPrimitive("enabled"))
            }
        }

        assertEquals("enabled", settings.providerOptions.toMap()["trace"]?.jsonPrimitive?.content)
        val openai = assertIs<JsonObject>(settings.providerOptions.toMap()["openai"])
        assertEquals("high", openai["reasoningEffort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `provider option builder accepts typed serializable payloads`() {
        val settings = callSettings {
            providerOptions {
                provider("openai", ProviderTuning("high"))
                provider("anthropic") {
                    putJson("cache", ProviderTuning("read-write"))
                }
                put("trace", ProviderTuning("sampled"))
            }
        }

        assertEquals("high", settings.providerOptions.toMap().decodeProviderMetadata<ProviderTuning>("openai")?.reasoningEffort)
        assertEquals("sampled", settings.providerOptions.toMap().decodeValue<ProviderTuning>("trace")?.reasoningEffort)
        val anthropic = assertIs<JsonObject>(settings.providerOptions.toMap()["anthropic"])
        assertEquals("read-write", anthropic["cache"]?.decodeAs<ProviderTuning>()?.reasoningEffort)
    }

    @Test
    fun `output exposes structured schema while preserving schema text`() {
        val output = outputObj<Recipe>(serializer(), name = "Recipe")

        assertEquals("object", output.schema.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(output.schema.toString(), output.schemaJson)
    }

    @Test
    fun `language model tool exposes structured parameter schema`() {
        val descriptor = LanguageModelTool(
            name = "weather",
            description = "Get weather.",
            parametersSchemaJson = buildJsonObject {
                put("type", JsonPrimitive("object"))
            }.toString(),
        )

        assertEquals("object", descriptor.parametersSchema.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool set builder infers serializers and rejects duplicate tool names`() {
        val tools = toolSetOf(Tool<WeatherInput, WeatherOutput, Unit>(
            name = "weather",
            description = "Get weather.",
        ) { input ->
            WeatherOutput(temperature = input.city.length)
        })

        val descriptor = tools.descriptors.single()
        assertEquals("weather", descriptor.name)
        assertEquals("object", descriptor.parametersSchema.jsonObject["type"]?.jsonPrimitive?.content)

        assertFailsWith<IllegalArgumentException> {
            val t = tools.find("weather") ?: error("missing tool")
            toolSetOf(t, t)
        }
    }

    @Test
    fun `file data wrappers convert to existing generated file shapes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val generated = generatedFile(
            FileData.Bytes(bytes, mediaType = "application/octet-stream", filename = "data.bin"),
        )

        assertEquals("application/octet-stream", generated.mediaType)
        assertEquals("data.bin", generated.filename)
        assertContentEquals(bytes, generated.bytes())

        val remote = generatedFile(FileData.Url("https://example.com/image.png", mediaType = "image/png"))
        assertEquals("", remote.base64)
        assertEquals("https://example.com/image.png", remote.url)
        assertIs<FileData.Url>(remote.fileData())
    }

    @Test
    fun `agent session uses caller scope and records generated state`() = runTest {
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelTextOnly("hello"),
            instructions = "Be brief.",
            tools = toolSetOf<Unit>(),
        )
        val session = agent.session(this)

        session.submit(prompt = "hi").join()

        val state = session.state.value
        assertEquals(AgentSessionStatus.Ready, state.status)
        assertEquals("hello", state.text)
        assertEquals("hello", state.output)
        assertNotNull(state.lastResult)
        assertEquals(2, state.messages.count { it.role == MessageRole.User || it.role == MessageRole.Assistant })
    }

    @Test
    fun `agent session can collect streaming state in caller scope`() = runTest {
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelTextOnly("hello"),
            instructions = "Be brief.",
            tools = toolSetOf<Unit>(),
        )
        val session = agent.session(this)

        session.submitStreaming(prompt = "hi").join()

        val state = session.state.value
        assertEquals(AgentSessionStatus.Ready, state.status)
        assertEquals("hello", state.text)
        assertEquals(null, state.output)
        assertEquals(2, state.messages.count { it.role == MessageRole.User || it.role == MessageRole.Assistant })
    }

    @Test
    fun `job abort signal follows coroutine cancellation`() {
        val job = Job()
        val signal = job.asAbortSignal()

        assertFalse(signal.isAborted)
        job.cancel()
        assertTrue(signal.isAborted)
    }

    @Test
    fun `metadata helpers decode provider payloads without raw json plumbing`() {
        val metadata = ProviderMetadata.Raw(JsonObject(mapOf("mock" to encodeJsonElement(ProviderTuning("cached")))))
        val result = LanguageModelResult(
            text = "ok",
            finishReason = FinishReason.Stop,
            usage = Usage.of(promptTokens = 1, completionTokens = 1),
            providerMetadata = metadata,
            content = listOf(ContentPart.Text("ok", providerMetadata = metadata)),
        )
        val event = StreamEvent.TextDelta("t1", "ok", providerMetadata = metadata)

        assertEquals("cached", result.providerMetadataAs<ProviderTuning>("mock")?.reasoningEffort)
        assertEquals("cached", result.content.single().providerMetadataAs<ProviderTuning>("mock")?.reasoningEffort)
        assertEquals("cached", event.providerMetadataAs<ProviderTuning>("mock")?.reasoningEffort)
    }

    @Test
    fun `ui helpers decode typed metadata data and tool payloads`() {
        val output = encodeJsonElement(WeatherOutput(temperature = 72))
        val part = UIMessagePart.ToolUI(
            toolCallId = "call_1",
            toolName = "weather",
            state = ToolCallState.OutputAvailable,
            output = output,
        )
        val data = UIMessagePart.Data("weather", output)
        val message = UIMessage(
            id = "m1",
            role = UIMessageRole.Assistant,
            parts = listOf(part, data),
            metadata = mapOf("mock" to encodeJsonElement(ProviderTuning("visible"))),
        )

        assertEquals(72, part.outputAs<WeatherOutput>()?.temperature)
        assertEquals(72, outputAs(part, serializer<WeatherOutput>())?.temperature)
        assertEquals(72, data.dataAs<WeatherOutput>().temperature)
        assertEquals("visible", message.metadataAs<ProviderTuning>("mock")?.reasoningEffort)
    }
}
