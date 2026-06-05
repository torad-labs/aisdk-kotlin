# @ai-sdk/klingai

- Version: 3.0.18
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/klingai`
- Target Kotlin module: `:aisdk-provider-klingai`
- Current parity status: ported: createKlingAI/klingai, KlingAIProviderSettings, KlingAIVideoModelId, KlingAIVideoModelOptions/KlingAIVideoProviderOptions, HS256 JWT authentication, text-to-video/image-to-video/motion-control endpoint routing, model-name normalization, provider-option snake_case mapping, passthrough options, task polling, URL video outputs, warnings, failure/timeout handling, and provider metadata are represented as a Kotlin facade folded into the root module; VERSION is exposed as KLINGAI_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/klingai/src/index.ts` | 7 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createKlingAI` | value | `src/klingai-provider.ts` | `.` |
| `klingai` | value | `src/klingai-provider.ts` | `.` |
| `KlingAIProvider` | type | `src/klingai-provider.ts` | `.` |
| `KlingAIProviderSettings` | type | `src/klingai-provider.ts` | `.` |
| `KlingAIVideoModelId` | type | `src/klingai-video-settings.ts` | `.` |
| `KlingAIVideoModelOptions` | type | `src/klingai-video-model.ts` | `.` |
| `KlingAIVideoProviderOptions` | type | `src/klingai-video-model.ts` | `.` |
