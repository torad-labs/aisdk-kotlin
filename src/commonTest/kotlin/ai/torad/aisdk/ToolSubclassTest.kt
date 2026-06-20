package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import ai.torad.aisdk.providers.mockToolInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The class-based extend path for tools (mirrors how a concrete agent extends
 * [ToolLoopAgent]): a named [Tool] / [StreamingTool] subclass with constructor
 * dependency injection, reused across instances.
 */
class ToolSubclassTest {

    @Serializable data class Query(val q: String)

    @Serializable data class Answer(val text: String)

    private fun ctx() = ToolExecutionContext<Unit>(
        context = Unit,
        abortSignal = AbortSignalNever,
        stepNumber = 0,
        messages = emptyList(),
        toolCallId = "call_1",
    )

    /** Reusable, dependency-injected single-value tool. */
    private class PrefixTool(private val prefix: String) : StreamingTool<Query, Answer, Unit>() {
        override val schema = ToolSchema("prefix", "Prefixes the query")
        override val inputSerializer = serializer<Query>()
        override val outputSerializer = serializer<Answer>()
        override fun ToolExecutionContext<Unit>.executeStream(input: Query): Flow<Answer> = flow {
            emit(Answer("$prefix:${input.q}"))
        }
    }

    @Test
    fun `subclassed Tool executes and instantiates independently with injected deps`() = runTest {
        val a = PrefixTool("A")
        val b = PrefixTool("B")

        val aResult = (a.execute(Query("hi"), ctx()).first() as ToolResult.Success).value
        val bResult = (b.execute(Query("hi"), ctx()).first() as ToolResult.Success).value
        assertEquals(Answer("A:hi"), aResult)
        assertEquals(Answer("B:hi"), bResult)

        // Stateless (I-8): one instance is safely reused across tool sets.
        assertEquals("prefix", toolSetOf(a).names().single())
        assertEquals(setOf("prefix"), toolSetOf(a).descriptors.map { it.name }.toSet())
    }

    @Test
    fun `subclassed Tool drives the canonical executor as a single final emission`() = runTest {
        val results = ExecuteTool(PrefixTool("X"), Query("hi"), ctx()).toList()
        val final = assertIs<ExecuteToolResult.Final<Answer>>(results.single())
        assertEquals(Answer("X:hi"), final.output)
    }

    /** Reusable streaming tool: a preliminary snapshot, then the final answer. */
    private class ProgressTool : StreamingTool<Query, Answer, Unit>() {
        override val schema = ToolSchema("progress", "Emits a preliminary snapshot then the final answer")
        override val inputSerializer = serializer<Query>()
        override val outputSerializer = serializer<Answer>()
        override fun ToolExecutionContext<Unit>.executeStream(input: Query): Flow<Answer> = flow {
            emit(Answer("partial"))
            emit(Answer("final:${input.q}"))
        }
    }

    @Test
    fun `subclassed StreamingTool surfaces preliminary then final`() = runTest {
        val results = ExecuteTool(ProgressTool(), Query("hi"), ctx()).toList()
        assertEquals(2, results.size)
        assertEquals(Answer("partial"), assertIs<ExecuteToolResult.Preliminary<Answer>>(results[0]).output)
        assertEquals(Answer("final:hi"), assertIs<ExecuteToolResult.Final<Answer>>(results[1]).output)
    }

    /** Subclass overriding the optional approval hook. */
    private class GatedTool : StreamingTool<Query, Answer, Unit>() {
        override val schema = ToolSchema("gated", "Requires approval when the query mentions 'danger'")
        override val inputSerializer = serializer<Query>()
        override val outputSerializer = serializer<Answer>()
        override fun ToolExecutionContext<Unit>.executeStream(input: Query): Flow<Answer> = flow {
            emit(Answer("done"))
        }
        override suspend fun needsApproval(input: Query, options: ToolPredicateOptions<Unit>): Boolean =
            "danger" in input.q
    }

    @Test
    fun `subclass overriding needsApproval is consulted per input`() = runTest {
        val gated = GatedTool()
        val opts = ToolPredicateOptions<Unit>(
            toolCallId = "call_1",
            messages = emptyList(),
            experimental_context = Unit,
        )
        assertTrue(gated.needsApproval(Query("danger ahead"), opts))
        assertFalse(gated.needsApproval(Query("all good"), opts))
    }

    // ── End-to-end: a hand-written subclass driven through the full agent loop ──

    @Serializable data class SendInput(val message: String)

    @Serializable data class SendResult(val sent: Boolean)

    /** Gated subclass — overrides both [execute] and [needsApproval]. */
    private class SendTool(private val onSend: () -> Unit) : StreamingTool<SendInput, SendResult, Unit>() {
        override val schema = ToolSchema("send", "Send a message")
        override val inputSerializer = serializer<SendInput>()
        override val outputSerializer = serializer<SendResult>()
        override fun ToolExecutionContext<Unit>.executeStream(input: SendInput): Flow<SendResult> = flow {
            onSend()
            emit(SendResult(sent = true))
        }
        override suspend fun needsApproval(input: SendInput, options: ToolPredicateOptions<Unit>): Boolean = true
    }

    @Test
    fun `subclassed gated Tool pauses for approval then resumes through the agent loop`() = runTest {
        var executed = false
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "send",
                toolInput = mockToolInput("message" to "hi"),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = toolSetOf(SendTool { executed = true }),
        )

        val first = agent.generate(prompt = "trigger")
        assertEquals(FinishReason.ToolApprovalRequested, first.finishReason)
        assertEquals("send", first.pendingApprovals.single().toolName)
        assertFalse(executed, "gated subclass must not execute before approval")

        val resumed = agent.generate(
            messages = first.messages + toolApprovalResponseMessage(
                toolCallId = first.pendingApprovals.single().toolCallId,
                approved = true,
            ),
        )
        assertTrue(executed, "gated subclass executes after approval")
        assertEquals("sent", resumed.text)
        assertEquals(FinishReason.Stop, resumed.finishReason)
    }

    @Serializable data class CityInput(val city: String)

    /** Non-gated subclass — only overrides [execute]. */
    private class CityTool : StreamingTool<CityInput, String, Unit>() {
        override val schema = ToolSchema("weather", "Get weather for a city")
        override val inputSerializer = serializer<CityInput>()
        override val outputSerializer = serializer<String>()
        override fun ToolExecutionContext<Unit>.executeStream(input: CityInput): Flow<String> = flow {
            emit("sunny in ${input.city}")
        }
    }

    @Test
    fun `subclassed Tool now gets experimental_repairToolCall on malformed input`() = runTest {
        // Regression guard for the resolve-before-gate fix: before it, a hand-written
        // subclass was always treated as gated, so malformed input hard-failed without
        // ever reaching repair. Now repair reaches subclass tools like factory tools.
        val malformed = buildJsonObject { put("location", JsonPrimitive("Paris")) }
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(toolName = "weather", toolInput = malformed, finalText = "done"),
            instructions = "",
            tools = toolSetOf(CityTool()),
            experimental_repairToolCall = { failedCall, _, _, _ ->
                val original = failedCall.input.jsonObject["location"]?.jsonPrimitive?.content ?: ""
                failedCall.copy(input = buildJsonObject { put("city", JsonPrimitive(original)) })
            },
        )

        val events = mutableListOf<StreamEvent>()
        agent.stream(prompt = "weather?").collect { events.add(it) }

        val toolResults = events.filterIsInstance<StreamEvent.ToolResult>()
        assertEquals(0, events.filterIsInstance<StreamEvent.ToolError>().size, "repair makes the subclass tool succeed")
        assertEquals(1, toolResults.size)
        assertTrue(
            toolResults.single().outputJson.toString().contains("Paris"),
            "repaired input flowed through: '${toolResults.single().outputJson}'",
        )
    }
}
