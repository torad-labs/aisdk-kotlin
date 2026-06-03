# @ai-sdk/mcp

- Version: 1.0.45
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/mcp`
- Target Kotlin module: `:aisdk-mcp`
- Current parity status: in-progress: MCP JSON-RPC contracts, client handshake, capability-gated APIs, dynamic tool conversion, elicitation, OAuth type surface, and stdio API shape are currently folded into the root module; HTTP/SSE/stdio platform transports remain open

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/mcp/src/index.ts` | 26 |
| `./mcp-stdio` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/mcp/src/tool/mcp-stdio/index.ts` | 2 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `auth` | value | `src/tool/oauth.ts` | `.` |
| `Configuration` | type | `src/tool/types.ts` | `.` |
| `createMCPClient` | value | `src/tool/mcp-client.ts` | `.` |
| `ElicitationRequest` | type | `src/tool/types.ts` | `.` |
| `ElicitationRequestSchema` | value | `src/tool/types.ts` | `.` |
| `ElicitResult` | type | `src/tool/types.ts` | `.` |
| `ElicitResultSchema` | value | `src/tool/types.ts` | `.` |
| `experimental_createMCPClient` | value | `src/tool/mcp-client.ts` | `.` |
| `experimental_MCPClient` | type | `src/tool/mcp-client.ts` | `.` |
| `experimental_MCPClientCapabilities` | type | `src/tool/types.ts` | `.` |
| `experimental_MCPClientConfig` | type | `src/tool/mcp-client.ts` | `.` |
| `Experimental_StdioMCPTransport` | value | `src/tool/mcp-stdio/mcp-stdio-transport.ts` | `./mcp-stdio` |
| `JSONRPCError` | type | `src/tool/json-rpc-message.ts` | `.` |
| `JSONRPCMessage` | type | `src/tool/json-rpc-message.ts` | `.` |
| `JSONRPCNotification` | type | `src/tool/json-rpc-message.ts` | `.` |
| `JSONRPCRequest` | type | `src/tool/json-rpc-message.ts` | `.` |
| `JSONRPCResponse` | type | `src/tool/json-rpc-message.ts` | `.` |
| `ListToolsResult` | type | `src/tool/types.ts` | `.` |
| `MCPClient` | type | `src/tool/mcp-client.ts` | `.` |
| `MCPClientCapabilities` | type | `src/tool/types.ts` | `.` |
| `MCPClientConfig` | type | `src/tool/mcp-client.ts` | `.` |
| `MCPTransport` | type | `src/tool/mcp-transport.ts` | `.` |
| `OAuthClientInformation` | type | `src/tool/oauth-types.ts` | `.` |
| `OAuthClientMetadata` | type | `src/tool/oauth-types.ts` | `.` |
| `OAuthClientProvider` | type | `src/tool/oauth.ts` | `.` |
| `OAuthTokens` | type | `src/tool/oauth-types.ts` | `.` |
| `StdioConfig` | type | `src/tool/mcp-stdio/mcp-stdio-transport.ts` | `./mcp-stdio` |
| `UnauthorizedError` | value | `src/tool/oauth.ts` | `.` |
