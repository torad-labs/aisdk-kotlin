# @ai-sdk/fal

- Version: 2.0.37
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/fal`
- Kotlin parity area: `:aisdk-provider-fal`
- Current parity status: ported: createFal/fal, FalProviderSettings, Fal image/speech/transcription/video model aliases and option aliases, fal auth/user-agent behavior, direct image generation and image download, image-editing files/mask mapping, image option camelCase/snake_case handling with deprecation warnings, speech URL-audio generation and download, transcription queue submit/poll/chunk mapping, video queue submit/poll URL output mapping, provider metadata, queue progress handling, and unsupported language/embedding errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as FAL_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/fal/src/index.ts` | 12 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createFal` | value | `src/fal-provider.ts` | `.` |
| `fal` | value | `src/fal-provider.ts` | `.` |
| `FalImageModelOptions` | type | `src/fal-image-options.ts` | `.` |
| `FalImageProviderOptions` | type | `src/fal-image-options.ts` | `.` |
| `FalProvider` | type | `src/fal-provider.ts` | `.` |
| `FalProviderSettings` | type | `src/fal-provider.ts` | `.` |
| `FalSpeechModelOptions` | type | `src/fal-speech-model.ts` | `.` |
| `FalTranscriptionModelOptions` | type | `src/fal-transcription-model.ts` | `.` |
| `FalVideoModelId` | type | `src/fal-video-settings.ts` | `.` |
| `FalVideoModelOptions` | type | `src/fal-video-model.ts` | `.` |
| `FalVideoProviderOptions` | type | `src/fal-video-model.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
