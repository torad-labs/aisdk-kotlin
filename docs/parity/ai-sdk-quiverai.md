# @ai-sdk/quiverai

- Version: 1.0.0
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/quiverai`
- Target Kotlin module: `:aisdk-provider-quiverai`
- Current parity status: ported: createQuiverAI/quiverai, QuiverAIProviderSettings, QuiverAIImageModelId, QuiverAIImageModelOptions, SVG generation, vectorization, reference image validation, option snake_case mapping, unsupported option warnings, SVG byte conversion, usage/provider metadata, and QuiverAI error parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as QUIVERAI_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/quiverai/src/index.ts` | 7 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createQuiverAI` | value | `src/quiverai-provider.ts` | `.` |
| `quiverai` | value | `src/quiverai-provider.ts` | `.` |
| `QuiverAIImageModelId` | type | `src/quiverai-image-settings.ts` | `.` |
| `QuiverAIImageModelOptions` | type | `src/quiverai-image-model-options.ts` | `.` |
| `QuiverAIProvider` | type | `src/quiverai-provider.ts` | `.` |
| `QuiverAIProviderSettings` | type | `src/quiverai-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
