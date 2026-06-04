# @ai-sdk/llamaindex

- Version: 2.0.196
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.196/packages/llamaindex`
- Target Kotlin module: `:aisdk-llamaindex`
- Current parity status: ported: toUIMessageStream is represented as a Kotlin Flow adapter over LlamaIndexEngineResponse, with callback lifecycle support folded into the root module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.196/packages/llamaindex/src/index.ts` | 1 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `toUIMessageStream` | value | `src/llamaindex-adapter.ts` | `.` |
