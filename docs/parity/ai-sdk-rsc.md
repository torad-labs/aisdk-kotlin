# @ai-sdk/rsc

- Version: 2.0.197
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/rsc`
- Target Kotlin module: `:aisdk-server`
- Current parity status: ported-as-kmp-server: RSC streamable-value/UI concepts are represented by Kotlin server/ui stream primitives: createTextStreamResponse, pipeTextStreamToResponse, createUiMessageStreamResponse, pipeUiMessageStreamToResponse, createUiMessageStream, UIMessageStreamWriter, read/merge/error stream handling via Flow, ChatTransport, and UIMessage state conversion; React Server Components runtime bindings and JSX UI streaming are intentionally not emitted in the Kotlin runtime module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/rsc/src/index.ts` | 29 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `AIAction` | type | `src/types.ts` | `.` |
| `AIActions` | type | `src/types.ts` | `.` |
| `AIProvider` | type | `src/types.ts` | `.` |
| `AIProviderProps` | type | `src/types.ts` | `.` |
| `createAI` | value | `src/rsc-server.ts` | `.` |
| `createStreamableUI` | value | `src/rsc-server.ts` | `.` |
| `createStreamableValue` | value | `src/rsc-server.ts` | `.` |
| `getAIState` | value | `src/rsc-server.ts` | `.` |
| `getMutableAIState` | value | `src/rsc-server.ts` | `.` |
| `InferActions` | type | `src/types.ts` | `.` |
| `InferAIState` | type | `src/types.ts` | `.` |
| `InferUIState` | type | `src/types.ts` | `.` |
| `InternalAIProviderProps` | type | `src/types.ts` | `.` |
| `InternalAIStateStorageOptions` | type | `src/types.ts` | `.` |
| `JSONValue` | type | `src/types.ts` | `.` |
| `MutableAIState` | type | `src/types.ts` | `.` |
| `OnGetUIState` | type | `src/types.ts` | `.` |
| `OnSetAIState` | type | `src/types.ts` | `.` |
| `readStreamableValue` | value | `src/rsc-client.ts` | `.` |
| `ServerWrappedAction` | type | `src/types.ts` | `.` |
| `ServerWrappedActions` | type | `src/types.ts` | `.` |
| `StreamableValue` | type | `src/streamable-value/streamable-value.ts` | `.` |
| `streamUI` | value | `src/rsc-server.ts` | `.` |
| `useActions` | value | `src/rsc-client.ts` | `.` |
| `useAIState` | value | `src/rsc-client.ts` | `.` |
| `useStreamableValue` | value | `src/rsc-client.ts` | `.` |
| `useSyncUIState` | value | `src/rsc-client.ts` | `.` |
| `useUIState` | value | `src/rsc-client.ts` | `.` |
| `ValueOrUpdater` | type | `src/types.ts` | `.` |
