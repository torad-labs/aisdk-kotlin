# @ai-sdk/vercel

- Version: 2.0.53
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/vercel`
- Kotlin parity area: `:aisdk-provider-vercel`
- Current parity status: ported: createVercel/vercel, VercelProviderSettings, VercelErrorData, and chat-only routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as VERCEL_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/vercel/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createVercel` | value | `src/vercel-provider.ts` | `.` |
| `vercel` | value | `src/vercel-provider.ts` | `.` |
| `VercelErrorData` | type | `@ai-sdk/openai-compatible` | `.` |
| `VercelProvider` | type | `src/vercel-provider.ts` | `.` |
| `VercelProviderSettings` | type | `src/vercel-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
