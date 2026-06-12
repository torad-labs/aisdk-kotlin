# @ai-sdk/elevenlabs

- Version: 2.0.34
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.202/packages/elevenlabs`
- Kotlin parity area: `:aisdk-provider-elevenlabs`
- Current parity status: ported: createElevenLabs/elevenlabs, ElevenLabsProviderSettings, speech/transcription model id and option surfaces, speech query/body mapping, multipart transcription mapping, binary audio response parsing, and transcription segment parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as ELEVENLABS_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/elevenlabs/src/index.ts` | 9 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createElevenLabs` | value | `src/elevenlabs-provider.ts` | `.` |
| `elevenlabs` | value | `src/elevenlabs-provider.ts` | `.` |
| `ElevenLabsProvider` | type | `src/elevenlabs-provider.ts` | `.` |
| `ElevenLabsProviderSettings` | type | `src/elevenlabs-provider.ts` | `.` |
| `ElevenLabsSpeechModelId` | type | `src/elevenlabs-speech-options.ts` | `.` |
| `ElevenLabsSpeechModelOptions` | type | `src/elevenlabs-speech-model.ts` | `.` |
| `ElevenLabsSpeechVoiceId` | type | `src/elevenlabs-speech-options.ts` | `.` |
| `ElevenLabsTranscriptionModelOptions` | type | `src/elevenlabs-transcription-model.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
