# @ai-sdk/deepseek

- Version: 2.0.38
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.204/packages/deepseek`
- Kotlin parity area: `:aisdk-provider-deepseek`
- Current parity status: ported: createDeepSeek/deepseek, provider settings, language options, error data alias, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as DEEPSEEK_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.204/packages/deepseek/src/index.ts` | 8 |
| `./internal` | `.reference/vercel-ai-sdk-ai-6.0.204/packages/deepseek/src/internal/index.ts` | 5 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createDeepSeek` | value | `src/deepseek-provider.ts` | `.` |
| `deepseek` | value | `src/deepseek-provider.ts` | `.` |
| `DeepSeekChatConfig` | type | `src/chat/deepseek-chat-language-model.ts` | `./internal` |
| `DeepSeekChatLanguageModel` | value | `src/chat/deepseek-chat-language-model.ts` | `./internal` |
| `DeepSeekChatModelId` | type | `src/chat/deepseek-chat-options.ts` | `./internal` |
| `DeepSeekChatOptions` | type | `src/chat/deepseek-chat-options.ts` | `.` |
| `DeepSeekErrorData` | type | `src/chat/deepseek-chat-api-types.ts` | `.` |
| `deepseekLanguageModelOptions` | value | `src/chat/deepseek-chat-options.ts` | `./internal` |
| `DeepSeekLanguageModelOptions` | type | `src/chat/deepseek-chat-options.ts` | `.`, `./internal` |
| `DeepSeekProvider` | type | `src/deepseek-provider.ts` | `.` |
| `DeepSeekProviderSettings` | type | `src/deepseek-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
