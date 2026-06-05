# @ai-sdk/moonshotai

- Version: 2.0.23
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/moonshotai`
- Target Kotlin module: `:aisdk-provider-moonshotai`
- Current parity status: ported: createMoonshotAI/moonshotai, provider settings, model id/options aliases, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/moonshotai/src/index.ts` | 7 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createMoonshotAI` | value | `src/moonshotai-provider.ts` | `.` |
| `moonshotai` | value | `src/moonshotai-provider.ts` | `.` |
| `MoonshotAIChatModelId` | type | `src/moonshotai-chat-options.ts` | `.` |
| `MoonshotAILanguageModelOptions` | type | `src/moonshotai-chat-options.ts` | `.` |
| `MoonshotAIProvider` | type | `src/moonshotai-provider.ts` | `.` |
| `MoonshotAIProviderOptions` | type | `src/moonshotai-chat-options.ts` | `.` |
| `MoonshotAIProviderSettings` | type | `src/moonshotai-provider.ts` | `.` |
