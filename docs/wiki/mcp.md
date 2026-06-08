# Model Context Protocol

MCP lets an app connect to external servers that expose tools, resources, and
prompts. AI SDK Kotlin includes an MCP client and transports, then converts MCP
tools into normal SDK tools.

## Create A Client

```kotlin
val transport = createMcpTransport(
    client = httpClient,
    config = MCPTransportConfig(
        type = MCPTransportKind.Http,
        url = "https://tools.example.com/mcp",
        headers = mapOf("Authorization" to "Bearer $token"),
    ),
)

val mcp = createMCPClient(
    MCPClientConfig(transport = transport),
)
```

Use HTTP for production-style deployments. SSE is available for servers that
still expose that transport. Stdio is for local process-backed servers on
platforms where process support exists.

## Use MCP Tools

```kotlin
val mcpTools = mcp.tools<AppContext>()

val agent = ToolLoopAgent<AppContext, String>(
    model = model,
    instructions = "Use connected tools when they are relevant.",
    tools = mcpTools,
    stopWhen = stepCountIs(8),
)
```

MCP tool definitions are converted to the same `ToolSet<TContext>` shape as
local tools, so agents can mix local and MCP tools without a separate execution
path.

## List Tools

```kotlin
val tools = mcp.listTools()

tools.tools.forEach { definition ->
    println("${definition.name}: ${definition.description}")
}
```

List tools at startup for diagnostics, allowlists, or tool-picking UI.

## Resources

```kotlin
val resources = mcp.listResources()

val first = resources.resources.firstOrNull()
if (first != null) {
    val content = mcp.readResource(first.uri)
    cache.store(first.uri, content)
}
```

Resources are server-owned content. Read them through the client and decide in
your app whether to cache, summarize, or feed them into prompts.

## Prompts

```kotlin
val prompts = mcp.experimental_listPrompts()

val reviewPrompt = mcp.experimental_getPrompt(
    name = "code_review",
    arguments = buildJsonObject {
        put("path", JsonPrimitive("src/commonMain"))
    },
)
```

Prompt support is experimental. Treat server prompts as templates that still
need app-side policy and validation.

## Elicitation

Some MCP servers request extra input while a tool runs. Register a handler
when your app can safely ask the user or supply a policy decision:

```kotlin
mcp.onElicitationRequest(ElicitationRequestSchema) { request ->
    ElicitResult(
        action = "accept",
        content = buildJsonObject {
            put("confirmed", JsonPrimitive(true))
        },
    )
}
```

Only enable elicitation for trusted servers and user flows where interruption
is expected.

## Cleanup

Close the client when the host scope ends:

```kotlin
try {
    agent.generate(prompt = "Use the connected server.", options = context)
} finally {
    mcp.close()
}
```

## Tips

- Prefer HTTP transport for deployable servers.
- Keep MCP toolsets allowlisted in production.
- Convert external tool output with `toModelOutput` when it is too large for
  future model turns.
- Log server capabilities at startup so missing tools fail visibly.

## Related

- [Tools](tools.md)
- [Agents](agents.md)
- [Providers And Models](providers.md)
- [Application Patterns](application-patterns.md)
