package ai.torad.aisdk

internal actual fun createMCPStdioProcess(config: StdioConfig): MCPStdioProcess {
    throw UnsupportedOperationException(
        "Stdio MCP transport is not supported on this native target. Use HttpMCPTransport, SseMCPTransport, or a custom MCPTransport.",
    )
}
