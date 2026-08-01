package ai.torad.aisdk

import ai.torad.aisdk.providers.GoogleGenerativeAIProviderSettings
import ai.torad.aisdk.providers.GoogleInteractions
import ai.torad.aisdk.providers.GoogleInteractionsStreamState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleInteractionsDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): googleInteractionsUsage read server token counts via
     * `?.jsonPrimitive?.intOrNull`, which throws IllegalArgumentException when the field is present
     * but a non-primitive (object/array). The safe `(X as? JsonPrimitive)?.…` degrades to null,
     * preserving the existing `?: 0` fallback.
     */
    @Test
    fun `googleInteractionsUsage degrades to zero on a non-primitive token count`() {
        val element = buildJsonObject {
            // total_input_tokens is an object, not a number — a malformed/quirky server usage body.
            put("total_input_tokens", buildJsonObject { put("x", JsonPrimitive(1)) })
        }

        val usage = GoogleInteractions.googleInteractionsUsage(element)

        assertEquals(0, usage.inputTokens.total, "a non-primitive token count degrades to 0, no crash")
    }

    @Test
    fun `unknown annotation type surfaces as raw source metadata`() {
        val annotation = buildJsonObject {
            put("type", "future_citation")
            put("url", "https://source.test")
            put("title", "Future source")
        }

        val source = GoogleInteractions.googleInteractionsAnnotationSources(
            JsonArray(listOf(annotation)),
            generateId = { "source-1" },
            metadata = null,
        ).single()

        assertEquals(StreamEvent.SourcePart.SourceType.Url, source.sourceType)
        assertEquals("https://source.test", source.url)
        assertEquals(annotation, source.providerMetadata.toMap()["google"])
    }

    @Test
    fun `status table maps finish reason and terminality`() {
        val cases = listOf(
            StatusCase("completed", FinishReason.Stop, terminal = true),
            StatusCase("requires_action", FinishReason.ToolCalls, terminal = true),
            StatusCase("failed", FinishReason.Error, terminal = true),
            StatusCase("incomplete", FinishReason.Length, terminal = true),
            StatusCase("budget_exceeded", FinishReason.Length, terminal = true),
            StatusCase("cancelled", FinishReason.Other, terminal = true),
            StatusCase("in_progress", FinishReason.Other, terminal = false),
            StatusCase("future_provider_status", FinishReason.Other, terminal = false),
            StatusCase(null, FinishReason.Other, terminal = false),
        )

        cases.forEach { case ->
            assertEquals(
                case.finishReason,
                GoogleInteractions.googleInteractionsFinishReason(case.raw, hasFunctionCall = false),
                "finish reason for ${case.raw}",
            )
            assertEquals(
                case.terminal,
                GoogleInteractions.googleInteractionsTerminal(case.raw),
                "terminality for ${case.raw}",
            )
        }
        assertEquals(
            FinishReason.ToolCalls,
            GoogleInteractions.googleInteractionsFinishReason("completed", hasFunctionCall = true),
        )
    }

    @Test
    fun `sync and live stream preserve unknown and absent raw status`() {
        val settings = GoogleGenerativeAIProviderSettings()

        listOf("future_provider_status", "", " ", "Completed", null).forEach { raw ->
            assertEquals(false, GoogleInteractions.googleInteractionsTerminal(raw), "terminality for $raw")
            val response = buildJsonObject {
                put("id", "interaction-status")
                raw?.let { put("status", it) }
            }
            val result = GoogleInteractions.googleInteractionsResult(
                response = response,
                requestBody = JsonObject(emptyMap()),
                headers = emptyMap(),
                rawBody = response,
                warnings = emptyList(),
                settings = settings,
            )

            assertEquals(FinishReason.Other, result.finishReason)
            assertEquals(raw, result.rawFinishReason)

            val streamState = GoogleInteractionsStreamState { "generated-id" }
            streamState.accept(
                buildJsonObject {
                    put("event_type", "interaction.created")
                    put("interaction", response)
                }
            )
            val finish = streamState.finishIfNeeded().filterIsInstance<StreamEvent.Finish>().single()

            assertEquals(FinishReason.Other, finish.finishReason)
            assertEquals(raw, finish.rawFinishReason)
        }
    }

    @Test
    fun `background synthesize preserves open raw statuses`() {
        listOf("future_provider_status", null).forEach { raw ->
            val response = buildJsonObject {
                put("id", "interaction-background-status")
                raw?.let { put("status", it) }
            }
            val streamState = GoogleInteractionsStreamState { "generated-id" }

            val finish = streamState.synthesize(response).filterIsInstance<StreamEvent.Finish>().single()

            assertEquals(FinishReason.Other, finish.finishReason)
            assertEquals(raw, finish.rawFinishReason)
        }
    }

    @Test
    fun `status-less requires action fallback remains canonical`() {
        val streamState = GoogleInteractionsStreamState { "generated-id" }
        streamState.accept(buildJsonObject { put("event_type", "interaction.requires_action") })

        val finish = streamState.finishIfNeeded().filterIsInstance<StreamEvent.Finish>().single()

        assertEquals(FinishReason.ToolCalls, finish.finishReason)
        assertEquals("requires_action", finish.rawFinishReason)
        assertEquals(true, GoogleInteractions.googleInteractionsTerminal("requires_action"))
    }

    private class StatusCase(
        val raw: String?,
        val finishReason: FinishReason,
        val terminal: Boolean,
    )
}
