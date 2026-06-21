# @ai-sdk/hume

- Version: 2.0.36
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/hume`
- Kotlin parity area: `:aisdk-provider-hume`
- Current parity status: ported: createHume/hume, HumeProviderSettings, HumeSpeechModelOptions, speech routing, utterance/context request mapping, binary audio response parsing, and output-format warnings are represented as a Kotlin facade folded into the root module; VERSION is exposed as HUME_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/hume/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createHume` | value | `src/hume-provider.ts` | `.` |
| `hume` | value | `src/hume-provider.ts` | `.` |
| `HumeProvider` | type | `src/hume-provider.ts` | `.` |
| `HumeProviderSettings` | type | `src/hume-provider.ts` | `.` |
| `HumeSpeechModelOptions` | type | `src/hume-speech-model.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
