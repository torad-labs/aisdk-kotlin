# @ai-sdk/test-server

- Version: 1.0.5
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/test-server`
- Kotlin parity area: `:aisdk-test-server`
- Current parity status: test-only: createTestServer, UrlResponse, UrlHandler, and related Ktor MockEngine helpers live under commonTest for provider tests and are not part of the shipped runtime API

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/test-server/src/index.ts` | 5 |
| `./with-vitest` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/test-server/src/with-vitest.ts` | 1 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createTestServer` | value | `src/create-test-server.ts` | `.`, `./with-vitest` |
| `TestResponseController` | value | `src/create-test-server.ts` | `.` |
| `UrlHandler` | type | `src/create-test-server.ts` | `.` |
| `UrlHandlers` | type | `src/create-test-server.ts` | `.` |
| `UrlResponse` | type | `src/create-test-server.ts` | `.` |
