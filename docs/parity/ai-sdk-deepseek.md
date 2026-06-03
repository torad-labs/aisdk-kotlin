# @ai-sdk/deepseek

- Version: 2.0.35
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/deepseek`
- Target Kotlin module: `:aisdk-provider-deepseek`
- Current parity status: ported: createDeepSeek/deepseek, provider settings, language options, error data alias, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as DEEPSEEK_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/deepseek/src/index.ts` | 8 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createDeepSeek` | value | `src/deepseek-provider.ts` | `.` |
| `deepseek` | value | `src/deepseek-provider.ts` | `.` |
| `DeepSeekChatOptions` | type | `src/chat/deepseek-chat-options.ts` | `.` |
| `DeepSeekErrorData` | type | `src/chat/deepseek-chat-api-types.ts` | `.` |
| `DeepSeekLanguageModelOptions` | type | `src/chat/deepseek-chat-options.ts` | `.` |
| `DeepSeekProvider` | type | `src/deepseek-provider.ts` | `.` |
| `DeepSeekProviderSettings` | type | `src/deepseek-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
