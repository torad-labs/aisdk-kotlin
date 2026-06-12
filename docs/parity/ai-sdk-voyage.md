# @ai-sdk/voyage

- Version: 1.0.5
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.202/packages/voyage`
- Kotlin parity area: `:aisdk-provider-voyage`
- Current parity status: ported: createVoyage/voyage, VoyageProviderSettings, embedding/reranking option surfaces, embedding routing, reranking routing, usage parsing, and embedding input limits are represented as a Kotlin facade folded into the root module; VERSION is exposed as VOYAGE_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/voyage/src/index.ts` | 7 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createVoyage` | value | `src/voyage-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
| `voyage` | value | `src/voyage-provider.ts` | `.` |
| `VoyageEmbeddingModelOptions` | type | `src/voyage-embedding-options.ts` | `.` |
| `VoyageProvider` | type | `src/voyage-provider.ts` | `.` |
| `VoyageProviderSettings` | type | `src/voyage-provider.ts` | `.` |
| `VoyageRerankingModelOptions` | type | `src/reranking/voyage-reranking-options.ts` | `.` |
