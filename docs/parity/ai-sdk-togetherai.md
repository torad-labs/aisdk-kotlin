# @ai-sdk/togetherai

- Version: 2.0.53
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/togetherai`
- Target Kotlin module: `:aisdk-provider-togetherai`
- Current parity status: ported: createTogetherAI/togetherai, provider settings, image/reranking option surfaces, TogetherAIErrorData, chat/completion/embedding/image routing, and TogetherAI reranking are represented as a Kotlin facade folded into the root module; VERSION is exposed as TOGETHERAI_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/togetherai/src/index.ts` | 10 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createTogetherAI` | value | `src/togetherai-provider.ts` | `.` |
| `togetherai` | value | `src/togetherai-provider.ts` | `.` |
| `TogetherAIErrorData` | type | `@ai-sdk/openai-compatible` | `.` |
| `TogetherAIImageModelOptions` | type | `src/togetherai-image-model.ts` | `.` |
| `TogetherAIImageProviderOptions` | type | `src/togetherai-image-model.ts` | `.` |
| `TogetherAIProvider` | type | `src/togetherai-provider.ts` | `.` |
| `TogetherAIProviderSettings` | type | `src/togetherai-provider.ts` | `.` |
| `TogetherAIRerankingModelOptions` | type | `src/reranking/togetherai-reranking-options.ts` | `.` |
| `TogetherAIRerankingOptions` | type | `src/reranking/togetherai-reranking-options.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
