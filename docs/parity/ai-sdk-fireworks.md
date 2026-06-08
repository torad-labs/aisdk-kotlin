# @ai-sdk/fireworks

- Version: 2.0.53
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/fireworks`
- Kotlin parity area: `:aisdk-provider-fireworks`
- Current parity status: ported: createFireworks/fireworks, provider settings, language/embedding option surfaces, FireworksErrorData, FireworksImageModel, chat/completion/embedding routing, and Fireworks image backend routing are represented as a Kotlin facade folded into the root module; VERSION is exposed as FIREWORKS_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/fireworks/src/index.ts` | 13 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createFireworks` | value | `src/fireworks-provider.ts` | `.` |
| `fireworks` | value | `src/fireworks-provider.ts` | `.` |
| `FireworksEmbeddingModelId` | type | `src/fireworks-embedding-options.ts` | `.` |
| `FireworksEmbeddingModelOptions` | type | `src/fireworks-embedding-options.ts` | `.` |
| `FireworksEmbeddingProviderOptions` | type | `src/fireworks-embedding-options.ts` | `.` |
| `FireworksErrorData` | type | `src/fireworks-provider.ts` | `.` |
| `FireworksImageModel` | value | `src/fireworks-image-model.ts` | `.` |
| `FireworksImageModelId` | type | `src/fireworks-image-options.ts` | `.` |
| `FireworksLanguageModelOptions` | type | `src/fireworks-chat-options.ts` | `.` |
| `FireworksProvider` | type | `src/fireworks-provider.ts` | `.` |
| `FireworksProviderOptions` | type | `src/fireworks-chat-options.ts` | `.` |
| `FireworksProviderSettings` | type | `src/fireworks-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
