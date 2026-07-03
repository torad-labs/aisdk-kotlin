package ai.torad.aisdk

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamEventWireFormatTest {
    @Test
    fun `stream events use stable serial names not Kotlin class names`() {
        val encoded = aiSdkOutputJson.encodeToString(
            StreamEvent.serializer(),
            StreamEvent.ToolApprovalRequest(
                toolCallId = "call_1",
                toolName = "lookup",
                inputJson = JsonPrimitive("{}"),
            ),
        )

        assertTrue("\"type\":\"tool-approval-request\"" in encoded, encoded)
        assertTrue("ai.torad.aisdk" !in encoded, encoded)
        assertTrue("StreamEvent" !in encoded, encoded)
    }

    @Test
    fun `stream event Poko leaves round trip polymorphically as sealed StreamEvent`() {
        val events = listOf(
            StreamEvent.TextDelta(id = "text_1", text = "hello"),
            StreamEvent.ToolApprovalRequest(
                toolCallId = "call_1",
                toolName = "lookup",
                inputJson = buildJsonObject {
                    put("query", JsonPrimitive("weather"))
                },
                approvalId = "approval_1",
                signature = "signature_1",
            ),
            StreamEvent.Finish(
                totalSteps = 2,
                finishReason = FinishReason.Stop,
                usage = Usage(
                    inputTokens = Usage.InputTokenBreakdown(total = 3),
                    outputTokens = Usage.OutputTokenBreakdown(total = 5),
                ),
                rawFinishReason = "stop",
            ),
        )

        for (event in events) {
            val encoded = aiSdkOutputJson.encodeToString(StreamEvent.serializer(), event)
            val decoded = aiSdkJson.decodeFromString(StreamEvent.serializer(), encoded)

            assertEquals(event, decoded, encoded)
        }
    }

    @Test
    fun `stream event wire format preserves metadata text and media fields`() {
        val metadata = ProviderMetadata.Raw(
            buildJsonObject {
                put("gateway", buildJsonObject { put("trace", "stream") })
            }
        )
        assertRoundTrips(
            StreamEvent.StreamStart(),
            StreamEvent.StreamStart(listOf(CallWarning("unsupported-setting", "topK ignored"))),
            StreamEvent.ResponseMetadata(),
            StreamEvent.ResponseMetadata(
                id = "resp_1",
                timestampMillis = 1_780_000_000_000L,
                modelId = "model_1",
                headers = mapOf("x-request-id" to "req_1"),
                body = buildJsonObject { put("raw", true) },
            ),
            StreamEvent.StepStart(1),
            StreamEvent.StepStart(2, metadata),
            StreamEvent.TextStart("text_1"),
            StreamEvent.TextStart("text_2", metadata),
            StreamEvent.TextDelta("text_1", "hello"),
            StreamEvent.TextDelta("text_2", "world", metadata),
            StreamEvent.TextEnd("text_1"),
            StreamEvent.TextEnd("text_2", metadata),
            StreamEvent.ReasoningStart("reasoning_1"),
            StreamEvent.ReasoningStart("reasoning_2", metadata),
            StreamEvent.ReasoningDelta("reasoning_1", "why"),
            StreamEvent.ReasoningDelta("reasoning_2", "because", metadata),
            StreamEvent.ReasoningEnd("reasoning_1"),
            StreamEvent.ReasoningEnd("reasoning_2", metadata),
            StreamEvent.SourcePart("source_1", StreamEvent.SourcePart.SourceType.Url),
            StreamEvent.SourcePart(
                id = "source_2",
                sourceType = StreamEvent.SourcePart.SourceType.Document,
                url = "https://example.test/doc",
                title = "Spec",
                mediaType = "application/pdf",
                providerMetadata = metadata,
            ),
            StreamEvent.FilePart("file_1", "text/plain", "ZmlsZQ=="),
            StreamEvent.FilePart("file_2", "image/png", "aW1hZ2U=", metadata),
            StreamEvent.Data("progress", JsonPrimitive(1)),
            StreamEvent.Data("progress", JsonPrimitive(2), id = "data_1", transient = true),
        )
    }

    @Test
    fun `stream event wire format preserves tool lifecycle fields`() {
        val metadata = ProviderMetadata.Raw(
            buildJsonObject {
                put("gateway", buildJsonObject { put("trace", "stream") })
            }
        )
        val input = buildJsonObject { put("city", "Paris") }
        assertRoundTrips(
            StreamEvent.ToolInputStart("input_1", "lookup"),
            StreamEvent.ToolInputStart("input_2", "lookup", metadata),
            StreamEvent.ToolInputDelta("input_1", """{"city":"""),
            StreamEvent.ToolInputDelta("input_2", """"Paris"}""", metadata),
            StreamEvent.ToolInputEnd("input_1"),
            StreamEvent.ToolInputEnd("input_2", metadata),
            StreamEvent.ToolCall("call_1", "lookup", input),
            StreamEvent.ToolCall("call_2", "lookup", input, metadata),
            StreamEvent.ToolResult("call_1", "lookup", JsonPrimitive("done")),
            StreamEvent.ToolResult(
                toolCallId = "call_2",
                toolName = "lookup",
                outputJson = JsonPrimitive("done"),
                modelOutput = ToolResultOutput.Text("summary"),
                isError = false,
                preliminary = true,
                providerMetadata = metadata,
            ),
            StreamEvent.ToolError("call_1", "lookup", "failed"),
            StreamEvent.ToolError("call_2", "lookup", "failed", providerMetadata = metadata),
            StreamEvent.ToolApprovalRequest("call_1", "lookup", input),
            StreamEvent.ToolApprovalRequest(
                toolCallId = "call_2",
                toolName = "lookup",
                inputJson = input,
                approvalId = "approval_1",
                signature = "sig",
                providerMetadata = metadata,
            ),
            StreamEvent.ToolOutputDenied("call_1", "lookup", "approval_1"),
            StreamEvent.ToolOutputDenied("call_2", "lookup", "approval_2", "no", metadata),
        )
    }

    @Test
    fun `stream event wire format preserves terminal fields`() {
        val metadata = ProviderMetadata.Raw(
            buildJsonObject {
                put("gateway", buildJsonObject { put("trace", "stream") })
            }
        )
        assertRoundTrips(
            StreamEvent.StepFinish(1, FinishReason.Stop, Usage()),
            StreamEvent.StepFinish(2, FinishReason.Length, Usage.of(promptTokens = 1, completionTokens = 2), metadata),
            StreamEvent.Finish(1, FinishReason.Stop, Usage()),
            StreamEvent.Finish(
                totalSteps = 2,
                finishReason = FinishReason.Length,
                usage = Usage.of(promptTokens = 3, completionTokens = 4),
                providerMetadata = metadata,
                rawFinishReason = "MAX_TOKENS",
            ),
            StreamEvent.Error("failed"),
            StreamEvent.Raw(buildJsonObject { put("provider", "chunk") }),
        )
    }

    @Test
    fun `stream event optional fields keep the documented default wire shape`() {
        val defaultSource = aiSdkOutputJson.encodeToString(
            StreamEvent.serializer(),
            StreamEvent.SourcePart("source_1", StreamEvent.SourcePart.SourceType.Url),
        )
        val defaultSourceJson = aiSdkJson.parseToJsonElement(defaultSource).jsonObject
        assertNull(defaultSourceJson["providerMetadata"])
        assertEquals(JsonNull, defaultSourceJson["title"])

        val metadata = ProviderMetadata.Raw(
            buildJsonObject {
                put("gateway", buildJsonObject { put("trace", "stream") })
            }
        )
        val explicitSource = aiSdkOutputJson.encodeToString(
            StreamEvent.serializer(),
            StreamEvent.SourcePart(
                id = "source_1",
                sourceType = StreamEvent.SourcePart.SourceType.Document,
                title = "Spec",
                providerMetadata = metadata,
            ),
        )
        val explicitSourceJson = aiSdkJson.parseToJsonElement(explicitSource).jsonObject
        assertEquals("Spec", explicitSourceJson["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "stream",
            explicitSourceJson["providerMetadata"]
                ?.jsonObject
                ?.get("gateway")
                ?.jsonObject
                ?.get("trace")
                ?.jsonPrimitive
                ?.contentOrNull,
        )
    }

    @Test
    fun `stream event Poko leaf keeps value semantics`() {
        val first = StreamEvent.TextDelta(id = "text_1", text = "hello")
        val equal = StreamEvent.TextDelta(id = "text_1", text = "hello")
        val different = StreamEvent.TextDelta(id = "text_1", text = "goodbye")

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, different)
    }

    private fun assertRoundTrips(vararg events: StreamEvent) {
        for (event in events) {
            val encoded = aiSdkOutputJson.encodeToString(StreamEvent.serializer(), event)
            val decoded = aiSdkJson.decodeFromString(StreamEvent.serializer(), encoded)

            assertEquals(event, decoded, encoded)
        }
    }
}
