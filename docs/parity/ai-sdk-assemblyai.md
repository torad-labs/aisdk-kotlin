# @ai-sdk/assemblyai

- Version: 2.0.33
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/assemblyai`
- Kotlin parity area: `:aisdk-provider-assemblyai`
- Current parity status: ported: createAssemblyAI/assemblyai, AssemblyAIProviderSettings, AssemblyAITranscriptionModelOptions, upload/submit/poll transcription flow, provider-option snake_case mapping, transcript status errors, response headers/body, and segment parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as ASSEMBLYAI_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/assemblyai/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `assemblyai` | value | `src/assemblyai-provider.ts` | `.` |
| `AssemblyAIProvider` | type | `src/assemblyai-provider.ts` | `.` |
| `AssemblyAIProviderSettings` | type | `src/assemblyai-provider.ts` | `.` |
| `AssemblyAITranscriptionModelOptions` | type | `src/assemblyai-transcription-model.ts` | `.` |
| `createAssemblyAI` | value | `src/assemblyai-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
