# @ai-sdk/test-server

- Version: 1.0.5
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/test-server`
- Kotlin parity area: `:aisdk-test-server`
- Current parity status: ported: createTestServer, TestResponseController, UrlResponse, UrlHandler, and UrlHandlers are represented as a Kotlin-native in-memory server with a Ktor MockEngine bridge folded into the root module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/test-server/src/index.ts` | 5 |
| `./with-vitest` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/test-server/src/with-vitest.ts` | 1 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createTestServer` | value | `src/create-test-server.ts` | `.`, `./with-vitest` |
| `TestResponseController` | value | `src/create-test-server.ts` | `.` |
| `UrlHandler` | type | `src/create-test-server.ts` | `.` |
| `UrlHandlers` | type | `src/create-test-server.ts` | `.` |
| `UrlResponse` | type | `src/create-test-server.ts` | `.` |
