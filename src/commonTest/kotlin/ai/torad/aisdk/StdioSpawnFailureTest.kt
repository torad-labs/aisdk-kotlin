package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalAiSdkApi::class)
class StdioSpawnFailureTest {
    /**
     * Regression: start() ran begin() (Idle->Active + built a CoroutineScope) BEFORE
     * CreateMCPStdioProcess. If the spawn threw (a non-existent command, bad cwd, permission error),
     * start() propagated with no cleanup — leaving the lifecycle wedged Active (every later start()
     * threw "already started") and the freshly built scope leaked.
     *
     * The same catch path also NonCancellably finishes a stale-process close and clears the
     * process field when start fails after begin() (cancellation mid-close included), so a
     * later start does not race a dangling handle.
     */
    @Test
    fun `stdio start that fails to spawn resets the lifecycle instead of wedging Active`() = runTest {
        val transport = Experimental_StdioMCPTransport(
            StdioConfig {
                command("/nonexistent/aisdk-mcp-binary-xyz")
            },
        )

        // First start: the spawn throws (IOException on JVM, UnsupportedOperationException on native).
        assertFails { transport.start() }
        // A SECOND start must reach the spawn again — proving the lifecycle reset to Idle, rather
        // than returning the "already started" guard a wedged-Active lifecycle would.
        val second = assertFails { transport.start() }
        assertFalse(
            second.message?.contains("already started") == true,
            "spawn failure reset the lifecycle to Idle (pre-fix it stayed Active)",
        )
    }

    @Test
    fun `cancel during stale process close clears the handle and allows a succeeding retry`() = runTest {
        // Review regression: cancellation during closeProcessPreservingCancellation(stale) used to
        // reset the lifecycle while leaving `process` pointing at stale. A concurrent restart then
        // raced the dangling handle. The catch path must NonCancellably finish teardown and null
        // the field before rethrowing.
        val staleCloseCalls = mutableListOf<String>()
        val stale = object : MCPStdioProcess {
            override suspend fun readLine(): String? = null
            override suspend fun writeLine(line: String) = Unit
            override suspend fun close() {
                staleCloseCalls += "close"
                throw CancellationException("cancelled during stale close")
            }
        }

        var spawnCount = 0
        val live = object : MCPStdioProcess {
            override suspend fun readLine(): String? = null // EOF immediately after start
            override suspend fun writeLine(line: String) = Unit
            override suspend fun close() {
                staleCloseCalls += "live-close"
            }
        }

        val transport = Experimental_StdioMCPTransport(
            StdioConfig { command("unused-with-injected-factory") },
        )
        transport.prepareProcessForTest(
            stale = stale,
            factory = {
                spawnCount += 1
                live
            },
        )

        assertTrue(transport.hasProcessForTest, "seeded stale handle is visible before start")

        val first = assertFails { transport.start() }
        assertTrue(
            first is CancellationException,
            "stale-close cancellation propagates after cleanup, got: ${first::class.simpleName}: ${first.message}",
        )
        assertFalse(
            transport.hasProcessForTest,
            "process field must be cleared even when stale close is cancelled",
        )
        assertEquals(0, spawnCount, "spawn must not run when stale close cancels first")
        // NonCancellable teardown path invokes close again (best-effort) after the cancelled close.
        assertTrue(
            staleCloseCalls.isNotEmpty(),
            "stale close must have been attempted",
        )

        // Retry: lifecycle Idle + process null → factory runs and start completes.
        // (close() is permanent Closed — reconnect is Active→Idle via reader EOF, not via close.)
        transport.start()
        assertEquals(1, spawnCount, "retry start reaches the process factory")
        transport.close()
        assertFalse(transport.hasProcessForTest, "close releases the process handle")
    }

    /**
     * Review regression: close() reads the `process` field only AFTER it wins the lifecycle
     * transition, so a close() landing between begin() and `process = started` captured the stale
     * handle (or null) and never saw the freshly spawned child. setReader() then no-opped, the
     * reader landed in an already-cancelled scope, and its onReaderExited() returned null for
     * Closed — so the child and its FDs leaked while `process` stayed non-null, leaving send()
     * able to write to an orphaned process on a permanently Closed transport.
     *
     * Driven deterministically by closing from inside the process factory: at that moment start()
     * has passed begin() but has not yet assigned `process`, which is exactly the losing window.
     */
    @Test
    fun `close winning during spawn does not strand the new process`() = runTest {
        var spawnedClosed = false
        val spawned = object : MCPStdioProcess {
            override suspend fun readLine(): String? = null
            override suspend fun writeLine(line: String) = Unit
            override suspend fun close() {
                spawnedClosed = true
            }
        }

        lateinit var transport: Experimental_StdioMCPTransport
        transport = Experimental_StdioMCPTransport(
            StdioConfig { command("unused-with-injected-factory") },
        )
        transport.prepareProcessForTest(
            factory = {
                // close() wins the lifecycle here — after begin(), before `process = started`.
                @Suppress("DEPRECATION")
                kotlinx.coroutines.runBlocking { transport.close() }
                spawned
            },
        )

        assertFails { transport.start() }

        assertTrue(spawnedClosed, "the child spawned during a losing start must still be destroyed")
        assertFalse(
            transport.hasProcessForTest,
            "the process field must not retain a handle on a Closed transport",
        )
    }
}
