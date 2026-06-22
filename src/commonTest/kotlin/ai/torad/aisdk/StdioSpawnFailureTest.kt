package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFalse

class StdioSpawnFailureTest {
    /**
     * Regression: start() ran begin() (Idle->Active + built a CoroutineScope) BEFORE
     * CreateMCPStdioProcess. If the spawn threw (a non-existent command, bad cwd, permission error),
     * start() propagated with no cleanup — leaving the lifecycle wedged Active (every later start()
     * threw "already started") and the freshly built scope leaked.
     */
    @Test
    fun `stdio start that fails to spawn resets the lifecycle instead of wedging Active`() = runTest {
        val transport = Experimental_StdioMCPTransport(
            StdioConfig(command = "/nonexistent/aisdk-mcp-binary-xyz"),
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
}
