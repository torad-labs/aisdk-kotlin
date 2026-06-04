# @ai-sdk/groq

- Version: 3.0.39
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.196/packages/groq`
- Target Kotlin module: `:aisdk-provider-groq`
- Current parity status: ported: createGroq/groq, Groq tools, chat/transcription routing, provider settings, and option surfaces are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as GROQ_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.196/packages/groq/src/index.ts` | 9 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `browserSearch` | value | `src/tool/browser-search.ts` | `.` |
| `createGroq` | value | `src/groq-provider.ts` | `.` |
| `groq` | value | `src/groq-provider.ts` | `.` |
| `GroqLanguageModelOptions` | type | `src/groq-chat-options.ts` | `.` |
| `GroqProvider` | type | `src/groq-provider.ts` | `.` |
| `GroqProviderOptions` | type | `src/groq-chat-options.ts` | `.` |
| `GroqProviderSettings` | type | `src/groq-provider.ts` | `.` |
| `GroqTranscriptionModelOptions` | type | `src/groq-transcription-options.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
