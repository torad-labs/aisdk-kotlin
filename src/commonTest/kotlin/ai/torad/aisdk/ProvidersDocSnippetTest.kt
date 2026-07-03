package ai.torad.aisdk

import ai.torad.aisdk.middleware.LoggingMiddleware
import ai.torad.aisdk.providers.LiteRTChannel
import ai.torad.aisdk.providers.LiteRTContent
import ai.torad.aisdk.providers.LiteRTConversation
import ai.torad.aisdk.providers.LiteRTConversationFactory
import ai.torad.aisdk.providers.LiteRTConversationRequest
import ai.torad.aisdk.providers.LiteRTLanguageModel
import ai.torad.aisdk.providers.LiteRTLanguageModelSettings
import ai.torad.aisdk.providers.LiteRTMessage
import ai.torad.aisdk.providers.LiteRTMessageRole
import ai.torad.aisdk.providers.LiteRTSamplerConfig
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.providers.OpenAICompatible
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compiles and executes the provider wiki snippets against the real provider APIs. */
class ProvidersDocSnippetTest {
    private class AppLiteRTEngine {
        private var capturedSamplerConfig: LiteRTSamplerConfig? = null
        private var capturedChannels: List<LiteRTChannel>? = null
        private var capturedAutomaticToolCalling: Boolean? = null

        val samplerConfig: LiteRTSamplerConfig? get() = capturedSamplerConfig
        val channels: List<LiteRTChannel>? get() = capturedChannels
        val automaticToolCalling: Boolean get() = capturedAutomaticToolCalling == true

        // Mirrors the documented host-engine bridge signature.
        @Suppress("LongParameterList")
        fun createConversation(
            systemInstruction: List<LiteRTContent>,
            initialMessages: List<LiteRTMessage>,
            tools: List<LanguageModelTool>,
            samplerConfig: LiteRTSamplerConfig?,
            channels: List<LiteRTChannel>?,
            automaticToolCalling: Boolean,
        ): AppLiteRTSession {
            check(systemInstruction.isEmpty())
            check(initialMessages.isEmpty())
            check(tools.isEmpty())
            capturedSamplerConfig = samplerConfig
            capturedChannels = channels
            capturedAutomaticToolCalling = automaticToolCalling
            return AppLiteRTSession()
        }
    }

    private class AppLiteRTSession {
        suspend fun send(message: LiteRTMessage, extraContext: Map<String, JsonElement>): LiteRTMessage {
            check(message.role == LiteRTMessageRole.User)
            check(extraContext.isEmpty())
            return LiteRTMessage {
                role(LiteRTMessageRole.Model)
                content(listOf(LiteRTContent.Text("local answer")))
            }
        }

        fun stream(message: LiteRTMessage, extraContext: Map<String, JsonElement>): Flow<LiteRTMessage> {
            check(message.role == LiteRTMessageRole.User)
            check(extraContext.isEmpty())
            return flowOf(
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("local answer")))
                },
            )
        }

        fun cancel() {
            // Host LiteRT sessions should abort active generation here when supported.
        }

        fun close() {
            // Host LiteRT sessions should release native resources here when needed.
        }
    }

    private class AppLiteRTConversationFactory(
        private val engine: AppLiteRTEngine,
    ) : LiteRTConversationFactory {
        override suspend fun create(request: LiteRTConversationRequest): LiteRTConversation {
            val session = engine.createConversation(
                systemInstruction = request.systemInstruction,
                initialMessages = request.initialMessages,
                tools = request.tools,
                samplerConfig = request.samplerConfig,
                channels = request.channels,
                automaticToolCalling = request.automaticToolCalling,
            )
            return AppLiteRTConversation(session)
        }
    }

    private class AppLiteRTConversation(
        private val session: AppLiteRTSession,
    ) : LiteRTConversation {
        override suspend fun send(
            message: LiteRTMessage,
            extraContext: Map<String, JsonElement>,
        ): LiteRTMessage = session.send(message, extraContext)

        override fun stream(
            message: LiteRTMessage,
            extraContext: Map<String, JsonElement>,
        ): Flow<LiteRTMessage> = session.stream(message, extraContext)

        override fun cancel() {
            session.cancel()
        }

        override fun close() {
            session.close()
        }
    }

    private fun noNetworkClient(): HttpClient = HttpClient(
        MockEngine {
            error("Provider documentation snippets should not make network requests.")
        },
    )

    @Test
    fun `providers wiki gateway and openai-compatible snippets compile`() {
        val gatewayApiKey = "gateway-key"
        val client = noNetworkClient()
        val localApiKey = "local-key"

        val gatewayProvider = Gateway(
            GatewayProviderSettings {
                apiKey(gatewayApiKey)
                transport(KtorGatewayTransport(client))
            },
        )

        val gatewayModel = gatewayProvider.languageModel("anthropic/claude-sonnet-4.5")

        val provider = OpenAICompatible(
            client = client,
            settings = OpenAICompatibleProviderSettings {
                name("local")
                baseUrl("http://localhost:11434/v1")
                apiKey(localApiKey)
                includeUsage(true)
            },
        )

        val model = provider.chatModel("llama3.2")

        assertEquals("gateway", gatewayProvider.providerId)
        assertEquals("anthropic/claude-sonnet-4.5", gatewayModel.modelId)
        assertEquals("local", provider.providerId)
        assertEquals("llama3.2", model.modelId)
    }

    @Test
    fun `providers wiki litert params registry and custom provider snippets compile and run`() = runTest {
        val params = LanguageModelCallParams {
            messages(listOf(UserMessage("Plan the next step.")))
            providerOptions(
                ProviderOptions.ofPairs(
                    "litert" to buildJsonObject {
                        put("enableThinking", JsonPrimitive(true))
                        put(
                            "extraContext",
                            buildJsonObject {
                                put("screen", JsonPrimitive("home"))
                            }
                        )
                    },
                ),
            )
        }

        val gatewayProvider = Provider(
            providerId = "gateway",
            languageModels = mapOf("anthropic/claude-sonnet-4.5" to MockLanguageModelTextOnly("gateway ok")),
        )
        val openaiProvider = Provider(
            providerId = "openai",
            languageModels = mapOf("gpt-4o-mini" to MockLanguageModelTextOnly("openai ok")),
        )
        val registry = ProviderRegistry(
            providers = mapOf(
                "gateway" to gatewayProvider,
                "openai" to openaiProvider,
            ),
            defaultProviderId = "gateway",
            languageModelMiddleware = listOf(LoggingMiddleware(NoopLogger)),
        )

        val model = registry.languageModel("gateway:anthropic/claude-sonnet-4.5")
        val provider = Provider(
            providerId = "test",
            languageModels = mapOf("small" to MockLanguageModelTextOnly("ok")),
        )

        val registryText = TextGenerator(model).generate(GenerationInput.Prompt("hello")).first().text
        val customText = TextGenerator(provider.languageModel("small"))
            .generate(GenerationInput.Prompt("hello"))
            .first()
            .text

        assertEquals(
            "Plan the next step.",
            params.messages.single().content.single().let {
                (it as ContentPart.Text).text
            }
        )
        assertEquals(
            "home",
            params.providerOptions.toMap().getValue("litert").jsonObject
                .getValue("extraContext").jsonObject
                .getValue("screen").jsonPrimitive.content,
        )
        assertEquals("gateway ok", registryText)
        assertEquals("ok", customText)
    }

    @Test
    fun `providers wiki litert factory snippet compiles and runs`() = runTest {
        val engine = AppLiteRTEngine()

        val model = LiteRTLanguageModel(
            modelId = "gemma-litert",
            conversationFactory = AppLiteRTConversationFactory(engine),
            settings = LiteRTLanguageModelSettings(
                block = {
                    defaultSamplerConfig(
                        LiteRTSamplerConfig {
                            topK(40)
                            topP(0.95)
                            temperature(0.7)
                        },
                    )
                    channels(
                        listOf(
                            LiteRTChannel {
                                channelName("thinking")
                                start("<think>")
                                end("</think>")
                            },
                        ),
                    )
                },
            ),
        )

        val text = TextGenerator(model).generate(GenerationInput.Prompt("Run locally.")).first().text

        assertEquals("local answer", text)
        assertEquals(40, engine.samplerConfig?.topK)
        assertEquals("thinking", engine.channels?.single()?.channelName)
        assertEquals(false, engine.automaticToolCalling)
    }
}
