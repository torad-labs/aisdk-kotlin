package ai.torad.aisdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@OptIn(ExperimentalAiSdkApi::class, ExperimentalCoroutinesApi::class, InternalAiSdkApi::class)
class McpStdioTransportTest : MCPClientTestBase() {

    /**
     * True when this host cannot run the POSIX `/bin/sh` fixtures:
     * - Native/iOS: ProcessBuilder actual throws [UnsupportedOperationException]
     * - Windows JVM: CreateProcess fails looking up `/bin/sh` (IOException)
     *
     * Matched by type/name so commonTest stays multiplatform (no `java.io` import).
     */
    private fun Throwable.isStdioShellUnavailable(): Boolean {
        if (this is UnsupportedOperationException) return true
        var cur: Throwable? = this
        while (cur != null) {
            val name = cur::class.simpleName.orEmpty()
            val msg = cur.message.orEmpty()
            if (name == "IOException" &&
                (msg.contains("/bin/sh") ||
                    msg.contains("CreateProcess") ||
                    msg.contains("Cannot run program") ||
                    msg.contains("The system cannot find the file"))
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private suspend fun startOrSkip(transport: Experimental_StdioMCPTransport): Boolean =
        try {
            transport.start()
            true
        } catch (error: Throwable) {
            if (error.isStdioShellUnavailable()) return false
            throw error
        }

    @Test
    fun `stdio transport exchanges newline-delimited JSON-RPC with process`() = runTest {
        val transport = Experimental_StdioMCPTransport(
            StdioConfig {
                command("/bin/sh")
                args(listOf("-c", "while IFS= read -r line; do printf '%s\\n' \"\$line\"; done"))
            },
        )
        var received: JSONRPCMessage? = null
        transport.setOnMessage { received = it }

        if (!startOrSkip(transport)) return@runTest
        transport.send(JSONRPCNotification(method = "notifications/test"))
        waitForRealTime { received != null }

        val notification = assertIs<JSONRPCNotification>(received)
        assertEquals("notifications/test", notification.method)
        transport.close()
    }

    @Test
    fun `stdio transport survives a child that floods stderr before responding`() = runTest {
        // Regression: the child's stderr pipe was never drained, so a server that logs a large
        // banner/diagnostics to stderr blocks on the write once the ~64KB pipe buffer fills, then
        // stops producing stdout and hangs the JSON-RPC reader forever.
        val transport = Experimental_StdioMCPTransport(
            StdioConfig {
                command("/bin/sh")
                // Write ~256KB to stderr (well past the pipe buffer) BEFORE emitting any stdout.
                args(
                    listOf(
                        "-c",
                        "yes 0123456789abcdef | head -c 262144 1>&2; " +
                            "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"notifications/ready\"}'",
                    ),
                )
            },
        )
        var received: JSONRPCMessage? = null
        transport.setOnMessage { received = it }

        if (!startOrSkip(transport)) return@runTest
        waitForRealTime { received != null } // pre-fix: child blocks on stderr -> stdout never comes -> timeout

        assertEquals("notifications/ready", assertIs<JSONRPCNotification>(received).method)
        transport.close()
    }

    @Test
    fun `stdio close gives the child a graceful termination window`() = runTest {
        val transport = Experimental_StdioMCPTransport(
            StdioConfig {
                command("/bin/sh")
                args(
                    listOf(
                        "-c",
                        "trap 'sleep 0.08; exit 0' TERM; " +
                            "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"notifications/ready\"}'; " +
                            "while true; do sleep 1; done",
                    ),
                )
            },
        )
        var received: JSONRPCMessage? = null
        var closed = false
        transport.setOnMessage { received = it }
        if (!startOrSkip(transport)) return@runTest
        try {
            waitForRealTime { received != null }
            val mark = TimeSource.Monotonic.markNow()
            transport.close()
            val elapsed = mark.elapsedNow()
            closed = true
            assertTrue(
                elapsed.inWholeMilliseconds >= 50,
                "close returned before the SIGTERM handler had a graceful window: $elapsed",
            )
        } finally {
            if (!closed) transport.close()
        }
    }

    @Test
    fun `stdio reader rejects an over-limit line with a typed error`() = runTest {
        val transport = Experimental_StdioMCPTransport(
            StdioConfig {
                command("/bin/sh")
                args(listOf("-c", "head -c 1048577 /dev/zero | tr '\\000' x; sleep 1"))
            },
        )
        var errored: Throwable? = null
        transport.setOnError { errored = it }
        if (!startOrSkip(transport)) return@runTest
        try {
            waitForRealTime { errored != null }
            val error = assertIs<MCPClientError>(errored)
            assertTrue(
                error.message?.contains("stdio line exceeded") == true,
                "over-limit line should surface the typed stdio cap error, got: ${error.message}",
            )
        } finally {
            transport.close()
        }
    }

    @Test
    fun `stdio reader EOF tears down the process so a later send reports not-connected`() = runTest {
        // Regression: when the child exits (readLine -> null) the reader fired onClose but never
        // destroyed the process or nulled the field — leaking the handle/FDs (and a reconnect would
        // overwrite the still-open process). Observable proxy: post-fix the field is nulled, so a
        // send after EOF reports the clean "not connected" error instead of a write-to-dead-pipe.
        val transport = Experimental_StdioMCPTransport(
            StdioConfig {
                command("/bin/sh")
                args(listOf("-c", "exit 0"))
            }, // exits immediately -> reader EOF
        )
        var closed = false
        transport.setOnClose { closed = true }
        if (!startOrSkip(transport)) return@runTest
        waitForRealTime { closed } // reader hit EOF and ran its teardown

        val error = assertFailsWith<MCPClientError> { transport.send(JSONRPCNotification(method = "x")) }
        assertTrue(
            error.message?.contains("not connected") == true,
            "EOF nulls the process; send reports not-connected (pre-fix: a write-to-dead-pipe error)",
        )
        transport.close()
    }
}
