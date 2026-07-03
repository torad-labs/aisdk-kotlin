# @ai-sdk/baseten

- Version: 1.0.54
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/baseten`
- Kotlin parity area: `:aisdk-provider-baseten`
- Current parity status: ported: createBaseten/baseten, BasetenProviderSettings, BasetenChatModelId, BasetenEmbeddingModelOptions, BasetenErrorData, default Model API chat routing, custom /sync/v1 chat routing, /predict chat rejection, embedding modelURL validation, /sync to /sync/v1 embedding normalization, and Baseten auth/user-agent behavior are represented as a Kotlin OpenAI-compatible facade folded into the root module; VERSION is exposed as BASETEN_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/baseten/src/index.ts` | 8 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `baseten` | value | `src/baseten-provider.ts` | `.` |
| `BasetenChatModelId` | type | `src/baseten-chat-options.ts` | `.` |
| `BasetenEmbeddingModelOptions` | type | `src/baseten-embedding-options.ts` | `.` |
| `BasetenErrorData` | type | `src/baseten-provider.ts` | `.` |
| `BasetenProvider` | type | `src/baseten-provider.ts` | `.` |
| `BasetenProviderSettings` | type | `src/baseten-provider.ts` | `.` |
| `createBaseten` | value | `src/baseten-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
