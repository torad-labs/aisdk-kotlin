# @ai-sdk/cerebras

- Version: 2.0.54
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/cerebras`
- Target Kotlin module: `:aisdk-provider-cerebras`
- Current parity status: ported: createCerebras/cerebras, chat provider settings, CerebrasErrorData, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as CEREBRAS_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/cerebras/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `cerebras` | value | `src/cerebras-provider.ts` | `.` |
| `CerebrasErrorData` | type | `src/cerebras-provider.ts` | `.` |
| `CerebrasProvider` | type | `src/cerebras-provider.ts` | `.` |
| `CerebrasProviderSettings` | type | `src/cerebras-provider.ts` | `.` |
| `createCerebras` | value | `src/cerebras-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
