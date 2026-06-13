# @ai-sdk/lmnt

- Version: 2.0.35
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.204/packages/lmnt`
- Kotlin parity area: `:aisdk-provider-lmnt`
- Current parity status: ported: createLMNT/lmnt, LMNTProviderSettings, LMNTSpeechModelOptions, speech routing, JSON request mapping, binary audio response parsing, and response-format warnings are represented as a Kotlin facade folded into the root module; VERSION is exposed as LMNT_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.204/packages/lmnt/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createLMNT` | value | `src/lmnt-provider.ts` | `.` |
| `lmnt` | value | `src/lmnt-provider.ts` | `.` |
| `LMNTProvider` | type | `src/lmnt-provider.ts` | `.` |
| `LMNTProviderSettings` | type | `src/lmnt-provider.ts` | `.` |
| `LMNTSpeechModelOptions` | type | `src/lmnt-speech-model.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
