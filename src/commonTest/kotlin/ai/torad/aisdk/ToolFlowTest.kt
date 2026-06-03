package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import ai.torad.aisdk.providers.mockToolInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Validates the v6-aligned `Flow<TOutput>` tool executor surface (gap #2
 * from `docs/AISDK_PORT_GAPS.md`). The agent loop's
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
    fun `given a single-value tool when invoked then it emits exactly one final ToolResult with preliminary false`() =
        runTest {
            // GIVEN
            val pingTool = tool<Empty, String, Unit>(
                name = "ping",
                description = "respond with pong",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
            ) { _ -> "pong" }
            val agent = ToolLoopAgent<Unit, String>(
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
            agent.stream(prompt = "go").collect { events.add(it) }

            // THEN
            val toolResults = events.filterIsInstance<StreamEvent.ToolResult>()
            assertEquals(1, toolResults.size, "single-value tool emits exactly one ToolResult")
            assertEquals(false, toolResults.single().preliminary, "the one emission is final")
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
            val agent = ToolLoopAgent<Unit, String>(
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
            agent.stream(prompt = "go").collect { events.add(it) }

            // THEN
            val toolResults = events.filterIsInstance<StreamEvent.ToolResult>()
            assertEquals(3, toolResults.size, "3 emissions → 3 ToolResult events")
            assertEquals(true, toolResults[0].preliminary, "1st emission is preliminary")
            assertEquals(true, toolResults[1].preliminary, "2nd emission is preliminary")
            assertEquals(false, toolResults[2].preliminary, "3rd emission is the final result")
            // The final ToolResult's output is what the model sees on the next turn.
            assertEquals(
                kotlinx.serialization.json.JsonPrimitive("v3"),
                toolResults[2].outputJson,
                "the final ToolResult carries the LAST emission",
            )
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
            val agent = ToolLoopAgent<Unit, String>(
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
            val agent = ToolLoopAgent<Unit, String>(
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
