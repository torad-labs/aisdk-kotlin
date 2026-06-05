# @ai-sdk/perplexity

- Version: 3.0.33
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/perplexity`
- Target Kotlin module: `:aisdk-provider-perplexity`
- Current parity status: ported: createPerplexity/perplexity and provider settings are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as PERPLEXITY_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/perplexity/src/index.ts` | 5 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createPerplexity` | value | `src/perplexity-provider.ts` | `.` |
| `perplexity` | value | `src/perplexity-provider.ts` | `.` |
| `PerplexityProvider` | type | `src/perplexity-provider.ts` | `.` |
| `PerplexityProviderSettings` | type | `src/perplexity-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
