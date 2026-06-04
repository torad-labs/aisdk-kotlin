# @ai-sdk/cohere

- Version: 3.0.36
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.196/packages/cohere`
- Target Kotlin module: `:aisdk-provider-cohere`
- Current parity status: ported: createCohere/cohere, CohereProviderSettings, Cohere chat/embedding/reranking model aliases, chat request mapping, multimodal image and document prompt conversion, JSON response format, tool choice/tool call mapping, citations, thinking options, Cohere auth/user-agent behavior, embedding options, reranking options, usage parsing, and unsupported image model errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as COHERE_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.196/packages/cohere/src/index.ts` | 10 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `cohere` | value | `src/cohere-provider.ts` | `.` |
| `CohereChatModelOptions` | type | `src/cohere-chat-options.ts` | `.` |
| `CohereEmbeddingModelOptions` | type | `src/cohere-embedding-options.ts` | `.` |
| `CohereLanguageModelOptions` | type | `src/cohere-chat-options.ts` | `.` |
| `CohereProvider` | type | `src/cohere-provider.ts` | `.` |
| `CohereProviderSettings` | type | `src/cohere-provider.ts` | `.` |
| `CohereRerankingModelOptions` | type | `src/reranking/cohere-reranking-options.ts` | `.` |
| `CohereRerankingOptions` | type | `src/reranking/cohere-reranking-options.ts` | `.` |
| `createCohere` | value | `src/cohere-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
