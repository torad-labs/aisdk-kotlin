package ai.torad.aisdk

import ai.torad.aisdk.react.UseChatOptions
import ai.torad.aisdk.react.experimental_useObject as reactUseObject
import ai.torad.aisdk.react.useChat
import ai.torad.aisdk.react.useCompletion as reactUseCompletion
import ai.torad.aisdk.rsc.createAI
import ai.torad.aisdk.rsc.createStreamableValue
import ai.torad.aisdk.rsc.getAIState
import ai.torad.aisdk.rsc.readStreamableValue
import ai.torad.aisdk.testing.drainAllItems
import ai.torad.aisdk.ui.ChatRequest
import ai.torad.aisdk.ui.ChatStatus
import ai.torad.aisdk.ui.DirectChatTransport
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

class FrameworkFacadeParityTest {

    @Test
    fun `react useChat facade exposes chat helper surface over KMP Chat`() = runTest {
        val requests = mutableListOf<ChatRequest>()
        val helpers = useChat(
            UseChatOptions(
                id = "react-chat",
                transport = DirectChatTransport { request ->
                    requests += request
                    flowOf(assistant("assistant-1", "pong"))
                },
            ),
        )

        val emitted = drainAllItems(helpers.sendMessage(user("user-1", "ping")))

        assertEquals("react-chat", helpers.id)
        assertEquals("pong", (emitted.single().parts.single() as UIMessagePart.Text).text)
        assertEquals(ChatStatus.Ready, helpers.status)
        assertNull(helpers.error)
        assertEquals(2, helpers.messages.size)
        assertEquals("user-1", requests.single().messages.single().id)
    }

    @Test
    fun `react useCompletion facade delegates to callCompletionApi and keeps state`() = runTest {
        var finished: Pair<String, String>? = null
        val requests = mutableListOf<CompletionRequest>()
        val helpers = reactUseCompletion(
            UseCompletionOptions(
                id = "completion-1",
                initialInput = "hi",
                transport = DirectCompletionTransport { request ->
                    requests += request
                    flowOf("Hel", "lo")
                },
                onFinish = { prompt, completion -> finished = prompt to completion },
            ),
        )

        val result = helpers.handleSubmit()

        assertEquals("Hello", result)
        assertEquals("Hello", helpers.completion)
        assertEquals(false, helpers.isLoading)
        assertNull(helpers.error)
        assertEquals("hi", requests.single().prompt)
        assertEquals("hi" to "Hello", finished)
    }

    @Test
    fun `react and vue object facades stream partial JSON through schema validation`() = runTest {
        val schema = jsonSchema<JsonObject>(
            buildJsonObject { put("type", JsonPrimitive("object")) },
            validate = { it.jsonObject },
        )
        val reactObject = reactUseObject(
            StructuredObjectOptions(
                api = "/api/object",
                schema = schema,
                transport = DirectStructuredObjectTransport<String> {
                    flowOf("""{"content":"Hel""", """lo"}""")
                },
            ),
        )
        val vueObject = ai.torad.aisdk.vue.experimental_useObject(
            StructuredObjectOptions(
                api = "/api/object",
                schema = schema,
                transport = DirectStructuredObjectTransport<String> {
                    flowOf("""{"content":"Vue"}""")
                },
            ),
        )

        reactObject.submit("react")
        vueObject.submit("vue")

        assertEquals(JsonPrimitive("Hello"), reactObject.value?.get("content"))
        assertEquals(JsonPrimitive("Vue"), vueObject.value?.get("content"))
        assertNull(reactObject.error)
        assertNull(vueObject.error)
    }

    @Test
    fun `angular and svelte package class exports construct shared state engines`() = runTest {
        val angular = ai.torad.aisdk.angular.Completion(
            UseCompletionOptions(
                transport = DirectCompletionTransport { flowOf("ng") },
            ),
        )
        val svelte = ai.torad.aisdk.svelte.Completion(
            UseCompletionOptions(
                transport = DirectCompletionTransport { flowOf("sv") },
            ),
        )
        val schema = jsonSchema<JsonObject>(
            buildJsonObject { put("type", JsonPrimitive("object")) },
            validate = { it.jsonObject },
        )
        val svelteObject = ai.torad.aisdk.svelte.Experimental_StructuredObject(
            StructuredObjectOptions(
                api = "/api/object",
                schema = schema,
                transport = DirectStructuredObjectTransport<String> { flowOf("""{"ok":true}""") },
            ),
        )

        angular.complete("hi")
        svelte.complete("hi")
        svelteObject.submit("input")

        assertEquals("ng", angular.completion)
        assertEquals("sv", svelte.completion)
        assertEquals(JsonPrimitive(true), svelteObject.value?.get("ok"))
    }

    @Test
    fun `rsc facade exposes streamable values and ai state helpers`() = runTest {
        val controller = createStreamableValue("initial")
        controller.update("next")
        val values = readStreamableValue(controller.value).take(2).toList()
        val provider = createAI(initialAIState = 1, initialUIState = listOf("message"))

        provider.setAIState(2)
        provider.setUIState(listOf("updated"))

        assertEquals(listOf("initial", "next"), values)
        assertEquals(2, getAIState(provider))
        assertEquals(listOf("updated"), ai.torad.aisdk.rsc.useUIState(provider))
    }

    private fun user(id: String, text: String): UIMessage =
        UIMessage(id, UIMessageRole.User, listOf(UIMessagePart.Text(text)))

    private fun assistant(id: String, text: String): UIMessage =
        UIMessage(id, UIMessageRole.Assistant, listOf(UIMessagePart.Text(text)))
}
