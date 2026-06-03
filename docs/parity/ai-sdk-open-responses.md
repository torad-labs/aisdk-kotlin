# @ai-sdk/open-responses

- Version: 1.0.16
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/open-responses`
- Target Kotlin module: `:aisdk-open-responses`
- Current parity status: ported: createOpenResponses, OpenResponsesOptions, generate/stream response mapping, supported URL metadata, request option mapping, and fake HTTP tests are folded into the root module; VERSION is exposed as OPEN_RESPONSES_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/open-responses/src/index.ts` | 3 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createOpenResponses` | value | `src/open-responses-provider.ts` | `.` |
| `OpenResponsesOptions` | type | `src/responses/open-responses-options.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
