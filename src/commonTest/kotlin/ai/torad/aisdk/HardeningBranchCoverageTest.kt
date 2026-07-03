@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.middleware.AddToolInputExamplesMiddleware
import ai.torad.aisdk.protocol.GatewayContentDecoder
import ai.torad.aisdk.protocol.GatewayContentEncoder
import ai.torad.aisdk.providers.Vercel
import ai.torad.aisdk.providers.VercelProviderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardeningBranchCoverageTest {
    @Test
    fun `gateway media encoder maps url and document source branches`() {
        val urlSource = GatewayContentEncoder.encode(
            ContentPart.Source(
                sourceType = StreamEvent.SourcePart.SourceType.Url,
                sourceId = "src_url",
                url = "https://example.test/source",
                title = "Example",
            )
        )
        assertEquals("source-url", urlSource.stringField("type"))
        assertEquals("src_url", urlSource.stringField("sourceId"))
        assertEquals("https://example.test/source", urlSource.stringField("url"))

        val documentSource = GatewayContentEncoder.encode(
            ContentPart.Source(
                sourceType = StreamEvent.SourcePart.SourceType.Document,
                sourceId = "src_doc",
                title = "Spec",
                mediaType = "application/pdf",
                filename = "spec.pdf",
            )
        )
        assertEquals("source-document", documentSource.stringField("type"))
        assertEquals("application/pdf", documentSource.stringField("mediaType"))
        assertEquals("spec.pdf", documentSource.stringField("filename"))
        assertNull(documentSource["url"])
    }

    @Test
    fun `gateway media encoder maps file and image branches`() {
        val inlineFile = GatewayContentEncoder.encode(
            ContentPart.File(
                mediaType = "text/plain",
                base64 = "aGk=",
                filename = "hi.txt",
            )
        )
        assertEquals("file", inlineFile.stringField("type"))
        assertEquals("aGk=", inlineFile.stringField("data"))
        assertEquals("hi.txt", inlineFile.stringField("filename"))

        val remoteFile = GatewayContentEncoder.encode(
            ContentPart.File(
                mediaType = "text/plain",
                url = "https://files.test/hi.txt",
            )
        )
        assertEquals("https://files.test/hi.txt", remoteFile.stringField("url"))
        assertNull(remoteFile["data"])

        val inlineImage = GatewayContentEncoder.encode(
            ContentPart.Image(
                mediaType = "image/png",
                base64 = "iVBORw0KGgo=",
            )
        )
        assertEquals("image", inlineImage.stringField("type"))
        assertEquals("iVBORw0KGgo=", inlineImage.stringField("data"))

        val remoteImage = GatewayContentEncoder.encode(
            ContentPart.Image(
                mediaType = "image/png",
                url = "https://images.test/a.png",
            )
        )
        assertEquals("https://images.test/a.png", remoteImage.stringField("url"))
        assertNull(remoteImage["data"])
    }

    @Test
    fun `gateway content encoder maps text reasoning raw and metadata branches`() {
        val metadata = ProviderMetadata.Raw(
            buildJsonObject {
                put("gateway", buildJsonObject { put("trace", "encoder") })
            }
        )

        val text = GatewayContentEncoder.encode(ContentPart.Text("hello", providerMetadata = metadata))
        assertEquals("text", text.stringField("type"))
        assertEquals("hello", text.stringField("text"))
        assertEquals("encoder", text.metadataField("trace"))

        val reasoning = GatewayContentEncoder.encode(ContentPart.Reasoning("because", providerMetadata = metadata))
        assertEquals("reasoning", reasoning.stringField("type"))
        assertEquals("because", reasoning.stringField("text"))

        val rawObject = buildJsonObject {
            put("type", "future-part")
            put("payload", "kept")
        }
        assertEquals(rawObject, GatewayContentEncoder.encode(ContentPart.Raw(rawObject)))

        val rawPrimitive = GatewayContentEncoder.encode(ContentPart.Raw(JsonPrimitive("opaque")))
        assertEquals("raw", rawPrimitive.stringField("type"))
        assertEquals("opaque", rawPrimitive["rawValue"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `gateway content encoder maps tool call and result flags`() {
        val metadata = ProviderMetadata.Raw(
            buildJsonObject {
                put("gateway", buildJsonObject { put("trace", "encoder") })
            }
        )
        val input = buildJsonObject { put("city", "Paris") }
        val defaultToolCall = GatewayContentEncoder.encode(ContentPart.ToolCall("call_1", "lookup", input))
        assertEquals("tool-call", defaultToolCall.stringField("type"))
        assertNull(defaultToolCall["providerExecuted"])
        assertNull(defaultToolCall["dynamic"])

        val flaggedToolCall = GatewayContentEncoder.encode(
            ContentPart.ToolCall(
                toolCallId = "call_2",
                toolName = "lookup",
                input = input,
                providerExecuted = true,
                dynamic = true,
                providerMetadata = metadata,
            )
        )
        assertEquals(true, flaggedToolCall.booleanField("providerExecuted"))
        assertEquals(true, flaggedToolCall.booleanField("dynamic"))
        assertEquals("encoder", flaggedToolCall.metadataField("trace"))

        val defaultToolResult = GatewayContentEncoder.encode(
            ContentPart.ToolResult("call_1", "lookup", JsonPrimitive("done"))
        )
        assertNull(defaultToolResult["dynamic"])
        assertNull(defaultToolResult["providerExecuted"])

        val flaggedToolResult = GatewayContentEncoder.encode(
            ContentPart.ToolResult(
                toolCallId = "call_2",
                toolName = "lookup",
                output = JsonPrimitive("done"),
                modelVisible = JsonPrimitive("summary"),
                isError = true,
                dynamic = true,
                providerExecuted = true,
                providerMetadata = metadata,
            )
        )
        assertEquals("summary", flaggedToolResult["modelVisible"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, flaggedToolResult.booleanField("isError"))
        assertEquals(true, flaggedToolResult.booleanField("dynamic"))
        assertEquals(true, flaggedToolResult.booleanField("providerExecuted"))
    }

    @Test
    fun `gateway content encoder maps approval request and response fields`() {
        val metadata = ProviderMetadata.Raw(
            buildJsonObject {
                put("gateway", buildJsonObject { put("trace", "encoder") })
            }
        )
        val input = buildJsonObject { put("city", "Paris") }
        val approvalRequest = GatewayContentEncoder.encode(
            ContentPart.ToolApprovalRequest(
                toolCallId = "call_3",
                toolName = "lookup",
                input = input,
                approvalId = "approval_1",
                signature = "sig",
                providerMetadata = metadata,
            )
        )
        assertEquals("approval_1", approvalRequest.stringField("approvalId"))
        assertEquals("sig", approvalRequest.stringField("signature"))
        assertEquals("encoder", approvalRequest.metadataField("trace"))

        val approvalResponse = GatewayContentEncoder.encode(
            ContentPart.ToolApprovalResponse(
                toolCallId = "call_3",
                approved = false,
                reason = "denied",
                approvalId = "approval_1",
            )
        )
        assertEquals("tool-approval-response", approvalResponse.stringField("type"))
        assertEquals(false, approvalResponse.booleanField("approved"))
        assertEquals("denied", approvalResponse.stringField("reason"))
    }

    @Test
    fun `gateway content decoder maps tool call flags`() {
        val input = buildJsonObject { put("city", "Paris") }
        val toolCall = assertIs<ContentPart.ToolCall>(
            GatewayContentDecoder.decode(
                buildJsonObject {
                    put("type", "tool-call")
                    put("toolCallId", "call_1")
                    put("toolName", "lookup")
                    put("input", input)
                }
            )
        )
        assertFalse(toolCall.providerExecuted)
        assertFalse(toolCall.dynamic)

        val flaggedToolCall = assertIs<ContentPart.ToolCall>(
            GatewayContentDecoder.decode(
                buildJsonObject {
                    put("type", "tool-call")
                    put("toolCallId", "call_2")
                    put("toolName", "lookup")
                    put("input", input)
                    put("providerExecuted", true)
                    put("dynamic", true)
                }
            )
        )
        assertTrue(flaggedToolCall.providerExecuted)
        assertTrue(flaggedToolCall.dynamic)
    }

    @Test
    fun `gateway content decoder maps tool result fallback and explicit fields`() {
        val output = JsonPrimitive("full")
        val outputOnlyResult = assertIs<ContentPart.ToolResult>(
            GatewayContentDecoder.decode(
                buildJsonObject {
                    put("type", "tool-result")
                    put("toolCallId", "call_1")
                    put("toolName", "lookup")
                    put("output", output)
                }
            )
        )
        assertFalse(outputOnlyResult.isError)
        assertEquals(output, outputOnlyResult.modelVisible)
        assertFalse(outputOnlyResult.dynamic)
        assertFalse(outputOnlyResult.providerExecuted)

        val modelOutputResult = assertIs<ContentPart.ToolResult>(
            GatewayContentDecoder.decode(
                buildJsonObject {
                    put("type", "tool-result")
                    put("toolCallId", "call_2")
                    put("toolName", "lookup")
                    put("output", output)
                    put("modelOutput", JsonPrimitive("summary"))
                    put("isError", true)
                    put("dynamic", true)
                    put("providerExecuted", true)
                }
            )
        )
        assertEquals(JsonPrimitive("summary"), modelOutputResult.modelVisible)
        assertTrue(modelOutputResult.isError)
        assertTrue(modelOutputResult.dynamic)
        assertTrue(modelOutputResult.providerExecuted)
    }

    @Test
    fun `gateway content decoder maps media defaults and unknown raw parts`() {
        val file = assertIs<ContentPart.File>(
            GatewayContentDecoder.decode(
                buildJsonObject {
                    put("type", "file")
                    put("data", JsonPrimitive("ZmlsZQ=="))
                }
            )
        )
        assertEquals("application/octet-stream", file.mediaType)
        assertEquals("ZmlsZQ==", file.base64)

        val image = assertIs<ContentPart.Image>(
            GatewayContentDecoder.decode(
                buildJsonObject {
                    put("type", "image")
                    put("data", JsonPrimitive("aW1hZ2U="))
                }
            )
        )
        assertEquals("image/*", image.mediaType)
        assertEquals("aW1hZ2U=", image.base64)

        val unknown = buildJsonObject {
            put("type", "future-part")
            put("payload", "kept")
        }
        val raw = assertIs<ContentPart.Raw>(GatewayContentDecoder.decode(unknown))
        assertEquals(unknown, raw.rawValue)
    }

    @Test
    fun `vercel facade applies OpenAI-compatible settings and rejects unsupported families`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.test/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id": "chatcmpl_vercel",
                              "created": 1780000000,
                              "model": "v0-test",
                              "choices": [
                                {
                                  "message": {"role": "assistant", "content": "Welcome."},
                                  "finish_reason": "stop"
                                }
                              ]
                            }
                            """.trimIndent(),
                        )
                    )
                ),
            )
        )
        fixture.server.start()
        try {
            val provider = Vercel(
                client = fixture.httpClient(),
                settings = VercelProviderSettings {
                    apiKey("secret")
                    baseURL("https://api.test/v1")
                    headers(mapOf("x-vercel-test" to "yes"))
                },
            )

            val generated = TextGenerator(provider(ModelId("v0-test")))
                .generate(GenerationInput.Prompt("Say hi"))
                .single()

            assertEquals("Welcome.", generated.text)
            val call = fixture.calls.single()
            assertEquals("Bearer secret", call.requestHeaders.headerValue("Authorization"))
            assertEquals("yes", call.requestHeaders.headerValue("x-vercel-test"))
            assertEquals("v0-test", call.requestBodyJson.jsonObject.stringField("model"))
            val requestMessage = call.requestBodyJson.jsonObject
                .getValue("messages")
                .jsonArray
                .single()
                .jsonObject
            assertEquals("Say hi", requestMessage.stringField("content"))

            assertFailsWith<NoSuchModelError> { provider.textEmbeddingModel("embed") }
            assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
            assertFailsWith<NoSuchModelError> { provider.imageModel("image") }
            assertEquals("https://api.v0.dev/v1", VercelProviderSettings().baseURL)
        } finally {
            fixture.server.stop()
        }
    }

    @Test
    fun `loop termination covers stop condition stop finish terminal and continuing branches`() = runTest {
        val toolCall = ContentPart.ToolCall(
            toolCallId = "call_1",
            toolName = "lookup",
            input = JsonObject(emptyMap()),
        )
        val neverStop = StopCondition { false }

        assertTrue(LoopTermination.isLoopFinished(loopState(FinishReason.ToolCalls), StopCondition { true }))
        assertTrue(LoopTermination.isLoopFinished(loopState(FinishReason.Stop), neverStop))
        assertFalse(
            LoopTermination.isLoopFinished(
                loopState(FinishReason.Stop, toolCallsThisStep = listOf(toolCall)),
                neverStop,
            )
        )

        for (reason in listOf(
            FinishReason.ToolApprovalRequested,
            FinishReason.Length,
            FinishReason.ContentFilter,
            FinishReason.Error,
        )) {
            assertTrue(LoopTermination.isLoopFinished(loopState(reason), neverStop), "$reason should terminate")
        }

        assertFalse(LoopTermination.isLoopFinished(loopState(FinishReason.ToolCalls), neverStop))
        assertFalse(LoopTermination.isLoopFinished(loopState(FinishReason.Other), neverStop))
    }

    @Test
    fun `add tool input examples preserves missing and empty entries and appends configured examples`() = runTest {
        val model = CapturingModel()
        val tools = listOf(
            LanguageModelTool("missing", "Missing tool.", """{"type":"object"}"""),
            LanguageModelTool("empty", "Empty tool.", """{"type":"object"}"""),
            LanguageModelTool("lookup", "Lookup tool.", """{"type":"object"}"""),
        )
        val wrapped = WrapLanguageModel(
            model,
            listOf(
                AddToolInputExamplesMiddleware(
                    mapOf(
                        "empty" to emptyList(),
                        "lookup" to listOf("""{"city":"Paris"}""", """{"city":"Lima"}"""),
                    )
                ),
            ),
        )

        wrapped.generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                tools(tools)
            }
        )

        val seen = model.capturedParams().tools.associateBy { it.name }
        assertEquals("Missing tool.", seen.getValue("missing").description)
        assertEquals("Empty tool.", seen.getValue("empty").description)
        assertEquals(
            """
            Lookup tool.

            Example: {"city":"Paris"}
            Example: {"city":"Lima"}
            """.trimIndent(),
            seen.getValue("lookup").description,
        )
    }

    private class CapturingModel : LanguageModel {
        override val modelId: String = "test/capturing"
        override val provider: String = "test"

        private var captured: LanguageModelCallParams? = null

        fun capturedParams(): LanguageModelCallParams =
            requireNotNull(captured)

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            captured = params
            return LanguageModelResult(
                text = "ok",
                finishReason = FinishReason.Stop,
                usage = Usage(),
            )
        }

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> {
            captured = params
            return emptyFlow()
        }
    }

    private fun loopState(
        reason: FinishReason,
        toolCallsThisStep: List<ContentPart.ToolCall> = emptyList(),
    ): LoopState =
        LoopState(
            stepNumber = 1,
            totalSteps = 1,
            lastFinishReason = reason,
            toolCallsThisStep = toolCallsThisStep,
            toolCallsAllSteps = toolCallsThisStep,
        )

    private fun JsonObject.stringField(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.booleanField(name: String): Boolean? =
        get(name)?.jsonPrimitive?.boolean

    private fun JsonObject.metadataField(name: String): String? =
        get("providerMetadata")
            ?.jsonObject
            ?.get("gateway")
            ?.jsonObject
            ?.stringField(name)

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
