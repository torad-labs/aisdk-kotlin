package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import ai.torad.aisdk.providers.MockToolInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Loop-contract parity behaviors wired in the 2026-05-30 cleanup:
 *   #16 prepareStep `experimental_context` overrides the running context
 *   #18 tool `onInputStart` / `onInputDelta` / `onInputAvailable` fire
 *   #21 `ToolStreamWriter` write-back into the active output stream
 */
class ToolLoopContractGapsTest {

    @Serializable
    data class Empty(val unused: String = "")

    // ---- #16 -------------------------------------------------------------

    @Test
    fun `given no prepareStep override when a tool runs then it sees the original call context`() = runTest {
        // GIVEN a probe tool that records the context it executed with.
        val seen = mutableListOf<String?>()
        val probe = Tool<Empty, String, String>(
            name = "probe",
            description = "records its context",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            seen.add(this.context)
            "ok"
        }
        val agent = TestToolLoopAgent<String, String>(
            model = MockLanguageModelToolThenText("probe", MockToolInput("unused" to ""), "done"),
            instructions = "run probe",
            tools = ToolSet(probe),
        )

        // WHEN streamed with the call context "initial" and no prepareStep.
        val stream: Flow<StreamEvent> = agent.stream(prompt = "go", options = "initial")
        stream.collect { }

        // THEN the tool saw the original context.
        assertEquals(listOf<String?>("initial"), seen)
    }

    @Test
    fun `given a prepareStep that sets experimental_context when a tool runs then it sees the override`() = runTest {
        // GIVEN a prepareStep that evolves the context to "augmented".
        val seen = mutableListOf<String?>()
        val probe = Tool<Empty, String, String>(
            name = "probe",
            description = "records its context",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            seen.add(this.context)
            "ok"
        }
        val agent = TestToolLoopAgent<String, String>(
            model = MockLanguageModelToolThenText("probe", MockToolInput("unused" to ""), "done"),
            instructions = "run probe",
            tools = ToolSet(probe),
            prepareStep = { StepSettings(experimental_context = "augmented") },
        )

        // WHEN streamed with "initial" but the step overrides it.
        val stream: Flow<StreamEvent> = agent.stream(prompt = "go", options = "initial")
        stream.collect { }

        // THEN the tool saw the override, not the call context.
        assertEquals(listOf<String?>("augmented"), seen)
    }

    // ---- #18 -------------------------------------------------------------

    @Test
    fun `given a tool with onInput hooks when its input streams in then all three hooks fire`() = runTest {
        // GIVEN a tool recording each lifecycle hook.
        val starts = mutableListOf<String>()
        val deltas = mutableListOf<String>()
        val avails = mutableListOf<String>()
        val probe = Tool<Empty, String, Unit>(
            name = "probe",
            description = "lifecycle probe",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            onInputStart = { id -> starts.add(id) },
            onInputDelta = { id, _ -> deltas.add(id) },
            onInputAvailable = { callId, _ -> avails.add(callId) },
        ) { _ -> "ok" }
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText("probe", MockToolInput("unused" to ""), "done"),
            instructions = "run probe",
            tools = ToolSet(probe),
        )

        // WHEN the model streams the tool's input then calls it.
        agent.stream(prompt = "go").collect { }

        // THEN start/delta fire on the streaming id; available on the call id.
        assertEquals(listOf("ti1"), starts)
        assertEquals(listOf("ti1"), deltas)
        assertEquals(listOf("call_1"), avails)
    }

    @Test
    fun `given an onInputStart that throws when the tool streams then the run still completes`() = runTest {
        // GIVEN a pre-warm hook that fails — it must not abort inference.
        val probe = Tool<Empty, String, Unit>(
            name = "probe",
            description = "throwing pre-warm",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            onInputStart = { error("pre-warm boom") },
        ) { _ -> "ok" }
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText("probe", MockToolInput("unused" to ""), "done"),
            instructions = "run probe",
            tools = ToolSet(probe),
        )

        // WHEN streamed.
        val events = mutableListOf<StreamEvent>()
        agent.stream(prompt = "go").collect { events.add(it) }

        // THEN the tool still ran and the final text arrived (hook error isolated).
        assertEquals(1, events.count { it is StreamEvent.ToolResult && it.toolName == "probe" })
        assertEquals(1, events.count { it is StreamEvent.TextDelta && it.text == "done" })
    }

    // ---- #21 -------------------------------------------------------------

    @Test
    fun `given a tool that writes custom data when it runs then a Raw event appears in the stream`() = runTest {
        // GIVEN a tool that pushes a progress payload via the writer.
        val writerTool = Tool<Empty, String, Unit>(
            name = "progressing",
            description = "writes back into the stream",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            writer.writeData(JsonPrimitive("step-1-done"))
            "final"
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText("progressing", MockToolInput("unused" to ""), "done"),
            instructions = "run it",
            tools = ToolSet(writerTool),
        )

        // WHEN streamed.
        val events = mutableListOf<StreamEvent>()
        agent.stream(prompt = "go").collect { events.add(it) }

        // THEN the custom write surfaced as a Raw event with the payload.
        val raw = events.filterIsInstance<StreamEvent.Raw>()
        assertEquals(1, raw.size)
        assertEquals(JsonPrimitive("step-1-done"), raw[0].rawValue)
    }
}
