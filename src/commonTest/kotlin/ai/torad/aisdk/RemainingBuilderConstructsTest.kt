@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.middleware.LoggingOptions
import ai.torad.aisdk.providers.AssemblyAICustomSpelling
import ai.torad.aisdk.providers.BedrockCredentials
import ai.torad.aisdk.providers.LiteRTChannel
import ai.torad.aisdk.providers.LiteRTLanguageModelSettings
import ai.torad.aisdk.providers.LiteRTSamplerConfig
import ai.torad.aisdk.providers.OpenResponsesAllowedTools
import ai.torad.aisdk.providers.OpenResponsesOptions
import ai.torad.aisdk.providers.XaiLanguageModelChatOptions
import ai.torad.aisdk.providers.XaiLanguageModelResponsesOptions
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class RemainingBuilderConstructsTest {
    @Test
    fun `remaining Poko builder configs keep value semantics`() {
        val redaction = RedactionOptions {
            replacement("[x]")
            maxStringLength(12)
            minBase64Length(8)
        }
        val equalRedaction = RedactionOptions {
            replacement("[x]")
            maxStringLength(12)
            minBase64Length(8)
        }
        val reconnect = MCPReconnectionOptions {
            initialReconnectionDelayMillis(100)
            reconnectionDelayGrowFactor(2.0)
            maxReconnectionDelayMillis(1_000)
            maxRetries(3)
        }
        val schemaOptions = ToolSchemaOptions {
            strict(false)
            providerExecuted(true)
        }
        val sampler = LiteRTSamplerConfig {
            topK(8)
            topP(0.8)
            temperature(0.6)
            seed(4)
        }
        val toolPolicy = ToolExecutionPolicy {
            maxParallelToolCalls(2)
            maxToolCallsPerStep(4)
            progressBufferCapacity(8)
            toolExecutionTimeout(5.seconds)
        }
        val equalToolPolicy = ToolExecutionPolicy {
            maxParallelToolCalls(2)
            maxToolCallsPerStep(4)
            progressBufferCapacity(8)
            toolExecutionTimeout(5.seconds)
        }

        assertEquals(equalRedaction, redaction)
        assertEquals(equalRedaction.hashCode(), redaction.hashCode())
        assertNotEquals(RedactionOptions { replacement("[other]") }, redaction)
        assertEquals(3, reconnect.maxRetries)
        assertEquals(false, schemaOptions.strict)
        assertEquals(8, sampler.topK)
        assertEquals(equalToolPolicy, toolPolicy)
        assertEquals(equalToolPolicy.hashCode(), toolPolicy.hashCode())
    }

    @Test
    fun `remaining serializable builder configs round trip`() {
        val stdio = StdioConfig {
            command("node")
            args(listOf("server.js"))
            env(mapOf("A" to "B"))
            cwd("/tmp")
        }
        val credentials = BedrockCredentials {
            accessKeyId("ak")
            secretAccessKey("sk")
            sessionToken("st")
            region("us-east-1")
        }
        val spelling = AssemblyAICustomSpelling {
            from(listOf("ai sdk"))
            to("AI SDK")
        }
        val openResponses = OpenResponsesOptions {
            instructions("be terse")
            reasoningEffort("low")
            allowedTools(OpenResponsesAllowedTools {
                toolNames(listOf("search"))
                mode("auto")
            })
        }
        val xaiChat = XaiLanguageModelChatOptions {
            reasoningEffort("low")
            logprobs(true)
            topLogprobs(2)
            searchParameters(JsonPrimitive("web"))
        }
        val xaiResponses = XaiLanguageModelResponsesOptions {
            reasoningEffort("high")
            reasoningSummary("detailed")
        }
        val clientInformation = OAuthClientInformation {
            clientId("client-id")
            clientSecret("client-secret")
            clientIdIssuedAt(1)
        }
        val clientMetadata = OAuthClientMetadata {
            redirectUris(listOf("https://app.example.com/callback"))
            clientName("client")
            scope("tools")
        }
        val configuration = Configuration {
            name("mcp-client")
            version("1.0.0")
            title("MCP Client")
        }
        val elicitationCapability = ElicitationCapability {
            applyDefaults(true)
        }

        assertEquals(stdio, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(stdio)))
        assertEquals(credentials, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(credentials)))
        assertEquals(spelling, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(spelling)))
        assertEquals(openResponses, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(openResponses)))
        assertEquals(xaiChat, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(xaiChat)))
        assertEquals(xaiResponses, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(xaiResponses)))
        assertEquals(clientInformation, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(clientInformation)))
        assertEquals(clientMetadata, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(clientMetadata)))
        assertEquals(configuration, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(configuration)))
        assertEquals(elicitationCapability, aiSdkJson.decodeFromString(aiSdkJson.encodeToString(elicitationCapability)))
    }

    @Test
    fun `remaining regular builder configs preserve fields and keep identity semantics`() {
        val transport = DirectCompletionTransport { emptyFlow() }
        val completion = UseCompletionOptions(
            block = {
                api("/api/custom")
                transport(transport)
            },
        )
        val equalShapeCompletion = UseCompletionOptions(
            block = {
                api("/api/custom")
                transport(transport)
            },
        )
        val objectOptions = StructuredObjectOptions<String, String> {
            api("/api/object")
            schema(
                Schemas.jsonSchema(
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                    },
                ),
            )
            initialValue("partial")
        }
        val logging = LoggingOptions {
            recordInputs(true)
            redactor(AiSdkDefaultRedactor)
        }
        val providerOptions = ProviderToolFactoryOptions<String, String, Unit> {
            outputSerializer(serializer<String>())
            name("providerTool")
        }
        val predicate = ToolPredicateOptions<Unit> {
            toolCallId("call-1")
            messages(emptyList())
            experimental_context(Unit)
        }
        val settings = LiteRTLanguageModelSettings(
            block = {
                provider("local")
                channels(listOf(LiteRTChannel("thinking", "<think>", "</think>")))
                toolCallIdGenerator { "call-fixed" }
            },
        )
        val retryPolicy = RetryPolicy {
            maxRetries(1)
            baseDelayMs(0)
            delayGenerator(RetryDelayGenerator.deterministic(0))
        }
        val equalShapeRetryPolicy = RetryPolicy {
            maxRetries(1)
            baseDelayMs(0)
            delayGenerator(retryPolicy.delayGenerator)
        }
        val providerMiddleware = ProviderMiddleware {
            languageModelMiddlewares(emptyList())
            embeddingModelMiddlewares(emptyList())
            imageModelMiddlewares(emptyList())
        }
        val idGenerator = IdGenerator {
            prefix("msg")
            size(4)
        }
        val equalShapeIdGenerator = IdGenerator {
            prefix("msg")
            size(4)
        }
        val languageModel = object : LanguageModel {
            override val modelId: String = "mock"

            override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
                LanguageModelResult("ok", finishReason = FinishReason.Stop, usage = Usage())

            override fun stream(params: LanguageModelCallParams) = emptyFlow<StreamEvent>()
        }
        val customProvider = CustomProvider {
            providerId("custom")
            languageModel("chat", languageModel)
        }
        val equalShapeCustomProvider = CustomProvider {
            providerId("custom")
            languageModel("chat", languageModel)
        }

        assertSame(transport, completion.transport)
        assertNotEquals(equalShapeCompletion, completion)
        assertEquals("/api/object", objectOptions.api)
        assertEquals("partial", objectOptions.initialValue)
        assertSame(AiSdkDefaultRedactor, logging.redactor)
        assertEquals("providerTool", providerOptions.name)
        assertEquals("call-1", predicate.toolCallId)
        assertEquals("call-fixed", settings.toolCallIdGenerator())
        assertEquals(1, retryPolicy.maxRetries)
        assertNotEquals(equalShapeRetryPolicy, retryPolicy)
        assertEquals(0, providerMiddleware.languageModelMiddlewares.size)
        assertEquals("msg", idGenerator.prefix)
        assertEquals(4, idGenerator.size)
        assertNotEquals(equalShapeIdGenerator, idGenerator)
        assertEquals("custom", customProvider.providerId)
        assertSame(languageModel, customProvider.languageModel("chat"))
        assertNotEquals(equalShapeCustomProvider, customProvider)
    }
}
