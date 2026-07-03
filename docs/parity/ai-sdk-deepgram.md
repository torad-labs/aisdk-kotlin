# @ai-sdk/deepgram

- Version: 2.0.36
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/deepgram`
- Kotlin parity area: `:aisdk-provider-deepgram`
- Current parity status: ported: createDeepgram/deepgram, DeepgramProviderSettings, DeepgramSpeechModel/DeepgramSpeechModelOptions, DeepgramSpeechCallOptions alias, DeepgramTranscriptionModelOptions, speech output-format and provider-option query mapping, transcription option query mapping, binary audio response parsing, and word segment parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as DEEPGRAM_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/deepgram/src/index.ts` | 10 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createDeepgram` | value | `src/deepgram-provider.ts` | `.` |
| `deepgram` | value | `src/deepgram-provider.ts` | `.` |
| `DeepgramProvider` | type | `src/deepgram-provider.ts` | `.` |
| `DeepgramProviderSettings` | type | `src/deepgram-provider.ts` | `.` |
| `DeepgramSpeechCallOptions` | type | `src/deepgram-speech-model.ts` | `.` |
| `DeepgramSpeechModel` | value | `src/deepgram-speech-model.ts` | `.` |
| `DeepgramSpeechModelId` | type | `src/deepgram-speech-options.ts` | `.` |
| `DeepgramSpeechModelOptions` | type | `src/deepgram-speech-model.ts` | `.` |
| `DeepgramTranscriptionModelOptions` | type | `src/deepgram-transcription-model.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
