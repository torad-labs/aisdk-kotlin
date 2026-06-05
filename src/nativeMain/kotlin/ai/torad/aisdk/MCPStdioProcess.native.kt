package ai.torad.aisdk

// Shared by every Kotlin/Native target (iOS + Linux). Stdio MCP spawns a child
// process; no Native target exposes a portable subprocess API through this SDK,
// so the actual rejects it. Use HttpMCPTransport / SseMCPTransport instead.
internal actual fun createMCPStdioProcess(config: StdioConfig): MCPStdioProcess {
    throw UnsupportedOperationException(
        "Stdio MCP transport is not supported on this native target. " +
            "Use HttpMCPTransport, SseMCPTransport, or a custom MCPTransport.",
    )
}
