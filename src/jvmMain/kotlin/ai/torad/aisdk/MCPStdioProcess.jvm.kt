package ai.torad.aisdk

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// NOTE: this is byte-for-byte identical to MCPStdioProcess.android.kt (modulo the class name).
// Both are JVM-backed (java.io / ProcessBuilder). A shared `jvmAndAndroid` intermediate
// source set via applyDefaultHierarchyTemplate() + a manual `dependsOn` would collapse the
// two actuals into one source of truth — deferred as build-structural. Keep the two bodies
// in lockstep until then.
internal actual fun CreateMCPStdioProcess(config: StdioConfig): MCPStdioProcess =
    JvmMCPStdioProcess(config)

private class JvmMCPStdioProcess(config: StdioConfig) : MCPStdioProcess {
    private val process = ProcessBuilder(listOf(config.command) + config.args).apply {
        if (config.cwd != null) directory(File(config.cwd))
        environment().putAll(config.env)
    }.start()
    private val reader = BufferedReader(InputStreamReader(process.inputStream))
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream))

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
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
        Unit
    }
}
