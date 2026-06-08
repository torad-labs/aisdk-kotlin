# @ai-sdk/devtools

- Version: 0.0.18
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/devtools`
- Kotlin parity area: `:aisdk-devtools`
- Current parity status: ported: the public devToolsMiddleware export is represented as a Kotlin-native recorder-backed middleware with run/step/result recording, generate and stream capture, raw request/response/chunk capture, usage/error recording, and production-environment guard folded into the root module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/devtools/src/index.ts` | 1 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `devToolsMiddleware` | value | `src/middleware.ts` | `.` |
