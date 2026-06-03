# @ai-sdk/langchain

- Version: 2.0.202
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/langchain`
- Target Kotlin module: `:aisdk-langchain`
- Current parity status: ported: toBaseMessages, convertModelMessages, toUIMessageStream, StreamCallbacks, and LangSmithDeploymentTransport are represented as Kotlin-native UI/Flow adapters folded into the root module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/langchain/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `convertModelMessages` | value | `src/adapter.ts` | `.` |
| `LangSmithDeploymentTransport` | value | `src/transport.ts` | `.` |
| `LangSmithDeploymentTransportOptions` | type | `src/transport.ts` | `.` |
| `StreamCallbacks` | type | `src/stream-callbacks.ts` | `.` |
| `toBaseMessages` | value | `src/adapter.ts` | `.` |
| `toUIMessageStream` | value | `src/adapter.ts` | `.` |
