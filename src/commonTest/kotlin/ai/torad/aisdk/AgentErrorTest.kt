package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Surface tests for the [AgentError] taxonomy (Phase 4C gap #25).
 *
 * Verifies:
 *  - Each variant constructs cleanly + produces a sensible message.
 *  - `cause` chains are preserved.
 *  - The sealed parent allows `when` pattern matching that's
 *    exhaustive (the compile-time guarantee is what makes this
 *    discriminated taxonomy useful at consumer call sites).
 */
class AgentErrorTest {

    @Test
    fun `given a NoSuchTool error when constructed then the message names the missing tool and available names`() {
        // GIVEN / WHEN
        val err = AgentError.NoSuchTool(
            toolName = "weather",
            availableTools = listOf("getLineup", "saveContact"),
        )

        // THEN
        assertTrue(err.message!!.contains("weather"))
        assertTrue(err.message!!.contains("getLineup"))
        assertTrue(err.message!!.contains("saveContact"))
        assertEquals("weather", err.toolName)
    }

    @Test
    fun `given an InvalidToolInput when constructed then cause chain is preserved`() {
        // GIVEN
        val parseFailure = IllegalArgumentException("missing field 'city'")

        // WHEN
        val err = AgentError.InvalidToolInput(
            toolName = "weather",
            rawArgs = """{"location":"Paris"}""",
            parseError = parseFailure,
        )

        // THEN — cause chain survives so existing Throwable handlers can read it.
        assertEquals(parseFailure, err.cause, "cause chain preserved")
        assertTrue(err.message!!.contains("weather"))
        assertTrue(err.message!!.contains("city"), "underlying error message embedded")
    }

    @Test
    fun `given a ToolExecution error when constructed then call id and tool name carry through`() {
        // GIVEN
        val executorBug = RuntimeException("DB connection refused")

        // WHEN
        val err = AgentError.ToolExecution(
            toolName = "lookupContacts",
            toolCallId = "call_42",
            executorError = executorBug,
        )

        // THEN
        assertEquals("lookupContacts", err.toolName)
        assertEquals("call_42", err.toolCallId)
        assertEquals(executorBug, err.cause)
    }

    @Test
    fun `given the sealed parent when matched exhaustively then all 6 variants are covered`() {
        // The whole point of the typed taxonomy is exhaustive `when`
        // at call sites. This compile-time check is the safety net.
        val errs = listOf<AgentError>(
            AgentError.NoSuchTool("w", emptyList()),
            AgentError.InvalidToolInput("w", "{}", RuntimeException("x")),
            AgentError.ToolExecution("w", "id", RuntimeException("x")),
            AgentError.ToolCallRepairFailed("w", RuntimeException("orig"), null),
            AgentError.InvalidApprovalResponse("id", emptyList()),
            AgentError.MaxStepsReached(20),
        )
        for (e in errs) {
            // Forces the compiler to check exhaustiveness via assignment to a
            // val; if a new variant is added without updating this when, this
            // test fails to compile.
            val label: String = when (e) {
                is AgentError.NoSuchTool -> "NoSuchTool"
                is AgentError.InvalidToolInput -> "InvalidToolInput"
                is AgentError.ToolExecution -> "ToolExecution"
                is AgentError.ToolCallRepairFailed -> "ToolCallRepairFailed"
                is AgentError.InvalidApprovalResponse -> "InvalidApprovalResponse"
                is AgentError.MaxStepsReached -> "MaxStepsReached"
            }
            assertTrue(label.isNotEmpty(), "every variant produces a label")
        }
    }

    @Test
    fun `given a MaxStepsReached when thrown then it can be caught as RuntimeException`() {
        // Confirms the error chain works with existing Throwable-tolerant code.
        try {
            throw AgentError.MaxStepsReached(stepCount = 20)
        } catch (e: RuntimeException) {
            assertTrue(e is AgentError.MaxStepsReached)
            assertEquals(20, e.stepCount)
            return
        }
        @Suppress("UnreachableCode")
        fail("should have caught the AgentError as a RuntimeException")
    }
}
