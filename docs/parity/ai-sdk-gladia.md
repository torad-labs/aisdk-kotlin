# @ai-sdk/gladia

- Version: 2.0.33
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/gladia`
- Target Kotlin module: `:aisdk-provider-gladia`
- Current parity status: ported: createGladia/gladia, GladiaProviderSettings, GladiaTranscriptionModelOptions, multipart upload, pre-recorded init, result polling, nested provider-option snake_case mapping, transcript failure/timeout handling, response headers/body, provider metadata, and utterance segment parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as GLADIA_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/gladia/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createGladia` | value | `src/gladia-provider.ts` | `.` |
| `gladia` | value | `src/gladia-provider.ts` | `.` |
| `GladiaProvider` | type | `src/gladia-provider.ts` | `.` |
| `GladiaProviderSettings` | type | `src/gladia-provider.ts` | `.` |
| `GladiaTranscriptionModelOptions` | type | `src/gladia-transcription-model.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
