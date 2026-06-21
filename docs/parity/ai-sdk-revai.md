# @ai-sdk/revai

- Version: 2.0.36
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/revai`
- Kotlin parity area: `:aisdk-provider-revai`
- Current parity status: ported: createRevai/revai, RevaiProviderSettings, RevaiTranscriptionModelOptions, multipart media/config job submission, job status polling, transcript retrieval, Rev.ai config passthrough, transcript text reconstruction, word timing segment parsing, and failure/timeout handling are represented as a Kotlin facade folded into the root module; VERSION is exposed as REVAI_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/revai/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createRevai` | value | `src/revai-provider.ts` | `.` |
| `revai` | value | `src/revai-provider.ts` | `.` |
| `RevaiProvider` | type | `src/revai-provider.ts` | `.` |
| `RevaiProviderSettings` | type | `src/revai-provider.ts` | `.` |
| `RevaiTranscriptionModelOptions` | type | `src/revai-transcription-model.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
