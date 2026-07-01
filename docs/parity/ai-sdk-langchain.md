# @ai-sdk/langchain

- Version: 2.0.216
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/langchain`
- Kotlin parity area: `:aisdk-langchain`
- Current parity status: not-ported: the LangChain JS bridge symbols toBaseMessages, convertModelMessages, StreamCallbacks, and LangSmithDeploymentTransport are not shipped in the KMP runtime; Kotlin callers use framework-neutral ModelMessage/UIMessage/Flow APIs instead

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/langchain/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `convertModelMessages` | value | `src/adapter.ts` | `.` |
| `LangSmithDeploymentTransport` | value | `src/transport.ts` | `.` |
| `LangSmithDeploymentTransportOptions` | type | `src/transport.ts` | `.` |
| `StreamCallbacks` | type | `src/stream-callbacks.ts` | `.` |
| `toBaseMessages` | value | `src/adapter.ts` | `.` |
| `toUIMessageStream` | value | `src/adapter.ts` | `.` |
