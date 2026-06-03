# @ai-sdk/mistral

- Version: 3.0.37
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/mistral`
- Target Kotlin module: `:aisdk-provider-mistral`
- Current parity status: ported: createMistral/mistral, MistralProviderSettings, MistralLanguageModelOptions, chat and embedding aliases, Mistral auth/user-agent behavior, random_seed mapping, provider-option snake_case mapping, reasoning extraction, usage parsing, embedding ordering, and unsupported image model errors are represented as a Kotlin OpenAI-compatible facade folded into the root module; VERSION is exposed as MISTRAL_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/mistral/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createMistral` | value | `src/mistral-provider.ts` | `.` |
| `mistral` | value | `src/mistral-provider.ts` | `.` |
| `MistralLanguageModelOptions` | type | `src/mistral-chat-options.ts` | `.` |
| `MistralProvider` | type | `src/mistral-provider.ts` | `.` |
| `MistralProviderSettings` | type | `src/mistral-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
