package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import ai.torad.aisdk.providers.mockToolInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer

/**
 * Validates the v6-aligned `Flow<TOutput>` tool executor surface (gap #2
 * from historical parity work). The agent loop's
 * `executeTool` runs each tool's executor as a Flow with one-step
 * lookahead — emissions before the last become
 * `StreamEvent.ToolResult(preliminary = true)` (UI-only), the LAST
 * emission becomes the final `ToolResult` that feeds the model on
 * subsequent turns. Single-value tools built via the `tool(...)`
 * factory keep their existing one-shot ergonomics (the factory wraps
 * the body in a one-emission flow internally).
 */
class ToolFlowTest {

    @Serializable data class Empty(val unused: String = "")

    @Test
    fun `executeTool emits preliminary output before upstream completes`() = runTest {
        val secondEmissionReached = CompletableDeferred<Unit>()
        val completionGate = CompletableDeferred<Unit>()
        val preliminarySeen = CompletableDeferred<ExecuteToolResult.Preliminary<String>>()
        val streamerTool = streamingTool<Empty, String, Unit>(
            name = "streamer",
            description = "emits before completion",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            flow {
                emit("v1")
                emit("v2")
                secondEmissionReached.complete(Unit)
                completionGate.await()
            }
        }
        val context = ToolExecutionContext(
            context = Unit,
            abortSignal = AbortSignalNever,
            stepNumber = 1,
            messages = emptyList(),
            toolCallId = "call_1",
        )
        val results = mutableListOf<ExecuteToolResult<String>>()
        val job = launch {
            executeTool(streamerTool, Empty(), context).collect { result ->
                results += result
                if (result is ExecuteToolResult.Preliminary) {
                    preliminarySeen.complete(result)
                }
            }
        }

        secondEmissionReached.await()
        val preliminary = preliminarySeen.await()

        assertEquals("v1", preliminary.output)
        assertEquals(listOf<ExecuteToolResult<String>>(ExecuteToolResult.Preliminary("v1")), results)

        completionGate.complete(Unit)
        job.join()

        assertEquals(
            listOf<ExecuteToolResult<String>>(ExecuteToolResult.Preliminary("v1"), ExecuteToolResult.Final("v2")),
            results,
        )
    }

    @Test
    fun `tool content result rejects malformed isError flag`() {
        assertFailsWith<WireDecodeException> {
            toolResultOutputFromWire(
                buildJsonObject {
                    put("type", JsonPrimitive("content"))
                    put("value", kotlinx.serialization.json.JsonArray(emptyList()))
                    put("isError", JsonNull)
                },
            )
        }
    }

    @Test
    fun `tagged tool result rejects missing required value`() {
        assertFailsWith<WireDecodeException> {
            toolResultOutputFromWire(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                },
            )
        }
    }

    @Test
    fun `given a single-value tool when invoked then it emits exactly one final ToolResult with preliminary false`() =
        runTest {
            // GIVEN
            val pingTool = tool<Empty, String, Unit>(
                name = "ping",
                description = "respond with pong",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
            ) { _ -> "pong" }
            val streamAgent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "ping",
                    toolInput = mockToolInput("unused" to ""),
                    finalText = "done",
                ),
                instructions = "use ping",
                tools = toolSetOf(pingTool),
            )

            // WHEN
            val events = mutableListOf<StreamEvent>()
            streamAgent.stream(prompt = "go").collect { events.add(it) }

            // THEN
            val toolResults = events.filterIsInstance<StreamEvent.ToolResult>()
            assertEquals(1, toolResults.size, "single-value tool emits exactly one ToolResult")
            assertEquals(false, toolResults.single().preliminary, "the one emission is final")
            assertIs<ToolResultOutput.Text>(toolResults.single().output)
        }

    @Test
    fun `given a streamingTool emitting three values when invoked then first two are preliminary and last is final`() =
        runTest {
            // GIVEN
            val streamerTool = streamingTool<Empty, String, Unit>(
                name = "streamer",
                description = "emits 3 values",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
            ) { _ ->
                flow {
                    emit("v1")
                    emit("v2")
                    emit("v3")
                }
            }
            val streamAgent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "streamer",
                    toolInput = mockToolInput("unused" to ""),
                    finalText = "done",
                ),
                instructions = "use streamer",
                tools = toolSetOf(streamerTool),
            )

            // WHEN
            val events = mutableListOf<StreamEvent>()
            streamAgent.stream(prompt = "go").collect { events.add(it) }

            // THEN
            val toolResults = events.filterIsInstance<StreamEvent.ToolResult>()
            assertEquals(3, toolResults.size, "3 emissions → 3 ToolResult events")
            assertEquals(true, toolResults[0].preliminary, "1st emission is preliminary")
            assertEquals(true, toolResults[1].preliminary, "2nd emission is preliminary")
            assertEquals(false, toolResults[2].preliminary, "3rd emission is the final result")
            // The final ToolResult's output is what the model sees on the next turn.
            assertEquals(
                JsonPrimitive("v3"),
                toolResults[2].outputJson,
                "the final ToolResult carries the LAST emission",
            )
        }

    @Test
    fun `given toModelOutput when tool completes then stream carries full and model-visible output shapes`() =
        runTest {
            val pingTool = tool<Empty, String, Unit>(
                name = "ping",
                description = "respond with pong",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
                toModelOutput = { output, options ->
                    ToolResultOutput.Text("summary:${options.toolCallId}:$output")
                },
            ) { _ -> "pong" }
            val streamAgent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "ping",
                    toolInput = mockToolInput("unused" to ""),
                    finalText = "done",
                ),
                instructions = "use ping",
                tools = toolSetOf(pingTool),
            )

            val events = mutableListOf<StreamEvent>()
            streamAgent.stream(prompt = "go").collect { events += it }

            val toolResult = events.filterIsInstance<StreamEvent.ToolResult>().single()
            assertEquals(JsonPrimitive("pong"), toolResult.outputJson)
            assertEquals(ToolResultOutput.Text("pong"), toolResult.output)
            assertEquals(ToolResultOutput.Text("summary:call_1:pong"), toolResult.modelOutput)
            assertEquals(false, toolResult.isError)

            val generateAgent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "ping",
                    toolInput = mockToolInput("unused" to ""),
                    finalText = "done",
                ),
                instructions = "use ping",
                tools = toolSetOf(pingTool),
            )
            val result = generateAgent.generate(prompt = "go")
            val toolMessage = result.messages.filter { it.role == MessageRole.Tool }.last()
            val toolPart = toolMessage.content.single()
            assertIs<ContentPart.ToolResult>(toolPart)
            assertEquals(JsonPrimitive("summary:call_1:pong"), toolPart.modelVisible)
        }

    @Test
    fun `given toModelOutput error when tool completes then stream and message mark the result as error`() =
        runTest {
            val pingTool = tool<Empty, String, Unit>(
                name = "ping",
                description = "respond with pong",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
                toModelOutput = { _, _ -> ToolResultOutput.Error("redacted failure") },
            ) { _ -> "pong" }
            val streamAgent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "ping",
                    toolInput = mockToolInput("unused" to ""),
                    finalText = "done",
                ),
                instructions = "use ping",
                tools = toolSetOf(pingTool),
            )

            val events = mutableListOf<StreamEvent>()
            streamAgent.stream(prompt = "go").collect { events += it }

            val toolResult = events.filterIsInstance<StreamEvent.ToolResult>().single()
            assertTrue(toolResult.isError)
            assertEquals(ToolResultOutput.Error("redacted failure"), toolResult.modelOutput)

            val generateAgent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "ping",
                    toolInput = mockToolInput("unused" to ""),
                    finalText = "done",
                ),
                instructions = "use ping",
                tools = toolSetOf(pingTool),
            )
            val result = generateAgent.generate(prompt = "go")
            val toolPart = result.messages
                .filter { it.role == MessageRole.Tool }
                .last()
                .content
                .single()
            assertIs<ContentPart.ToolResult>(toolPart)
            assertTrue(toolPart.isError)
            assertEquals(JsonPrimitive("Error: redacted failure"), toolPart.modelVisible)
        }

    @Test
    fun `given a streamingTool emitting one value when invoked then a single final ToolResult emits`() =
        runTest {
            // GIVEN
            val streamerTool = streamingTool<Empty, String, Unit>(
                name = "streamer",
                description = "one value",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
            ) { _ -> flow { emit("only") } }
            val agent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "streamer",
                    toolInput = mockToolInput("unused" to ""),
                    finalText = "done",
                ),
                instructions = "",
                tools = toolSetOf(streamerTool),
            )

            // WHEN
            val events = mutableListOf<StreamEvent>()
            agent.stream(prompt = "go").collect { events.add(it) }

            // THEN
            val toolResults = events.filterIsInstance<StreamEvent.ToolResult>()
            assertEquals(1, toolResults.size, "single emission → single ToolResult")
            assertEquals(false, toolResults.single().preliminary, "single emission is final")
        }

    @Test
    fun `given a streamingTool emitting zero values when invoked then ToolError fires with empty-emission message`() =
        runTest {
            // GIVEN
            val emptyTool = streamingTool<Empty, String, Unit>(
                name = "empty",
                description = "emits nothing",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
            ) { _ -> flow { /* no emissions */ } }
            val agent = TestToolLoopAgent<Unit, String>(
                model = mockLanguageModelToolThenText(
                    toolName = "empty",
                    toolInput = mockToolInput("unused" to ""),
                    finalText = "done",
                ),
                instructions = "",
                tools = toolSetOf(emptyTool),
            )

            // WHEN
            val events = mutableListOf<StreamEvent>()
            agent.stream(prompt = "go").collect { events.add(it) }

            // THEN
            val toolErrors = events.filterIsInstance<StreamEvent.ToolError>()
            assertEquals(1, toolErrors.size, "empty flow becomes one ToolError")
            assertTrue(
                toolErrors.single().message.contains("no values"),
                "error message names the empty-emission cause (got '${toolErrors.single().message}')",
            )
            // No ToolResult events emitted (preliminary or final).
            assertTrue(
                events.none { it is StreamEvent.ToolResult },
                "empty flow emits no ToolResult events",
            )
        }
}
