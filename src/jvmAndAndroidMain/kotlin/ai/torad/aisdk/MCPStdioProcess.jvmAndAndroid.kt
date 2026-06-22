package ai.torad.aisdk

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Shared by the JVM and Android targets (both java.io / ProcessBuilder backed) via the
// jvmAndAndroidMain intermediate source set — one source of truth for the stdio transport.
internal actual fun CreateMCPStdioProcess(config: StdioConfig): MCPStdioProcess =
    JvmMCPStdioProcess(config)

private const val STDERR_DRAIN_BUFFER = 8192

private class JvmMCPStdioProcess(config: StdioConfig) : MCPStdioProcess {
    private val process = ProcessBuilder(listOf(config.command) + config.args).apply {
        if (config.cwd != null) directory(File(config.cwd))
        environment().putAll(config.env)
    }.start()
    private val reader = BufferedReader(InputStreamReader(process.inputStream))
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream))

    // Continuously drain the child's stderr so its pipe buffer can never fill and deadlock the
    // child: a full stderr pipe blocks the child's next stderr write, which stalls its stdout and
    // hangs our JSON-RPC reader. A daemon thread keeps this version-independent —
    // ProcessBuilder.Redirect.DISCARD needs Android API 28 but minSdk is 26 — and avoids
    // redirectErrorStream(true), which would corrupt the line-delimited JSON-RPC stdout. Reads in
    // fixed chunks (constant memory) and exits on EOF (process exit) or when close() shuts the stream.
    private val stderrDrain = Thread {
        runCatching {
            val buffer = ByteArray(STDERR_DRAIN_BUFFER)
            while (process.errorStream.read(buffer) >= 0) { /* discard */ }
        }
    }.apply {
        name = "mcp-stdio-stderr-drain"
        isDaemon = true
        start()
    }

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        reader.readLine()
    }

    override suspend fun writeLine(line: String) = withContext(Dispatchers.IO) {
        writer.write(line)
        writer.newLine()
        writer.flush()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { writer.close() }
        runCatching { reader.close() }
        // Close stderr too (destroy() does not close parent-held stream fds): releases the fd and
        // unblocks the drain thread's read so the daemon exits promptly.
        runCatching { process.errorStream.close() }
        stderrDrain.interrupt()
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
        Unit
    }
}
