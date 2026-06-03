package ai.torad.aisdk

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun createMCPStdioProcess(config: StdioConfig): MCPStdioProcess =
    AndroidMCPStdioProcess(config)

private class AndroidMCPStdioProcess(config: StdioConfig) : MCPStdioProcess {
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
        Unit
    }
}
