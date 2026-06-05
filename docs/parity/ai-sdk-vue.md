# @ai-sdk/vue

- Version: 3.0.197
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/vue`
- Target Kotlin module: `:aisdk-ui`
- Current parity status: ported-as-kmp-ui: Vue Chat/useCompletion/useObject concepts are represented by the framework-neutral Kotlin ui package: Chat, ChatTransport, TextStreamChatTransport, UIMessage/UIMessagePart, streamToUiMessages, convertToModelMessages, stream responses, and typed tool-part handler registry; Vue refs/components are intentionally not emitted in the Kotlin runtime module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/vue/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `Chat` | value | `src/chat.vue.ts` | `.` |
| `experimental_useObject` | value | `src/use-object.ts` | `.` |
| `Experimental_UseObjectHelpers` | type | `src/use-object.ts` | `.` |
| `Experimental_UseObjectOptions` | type | `src/use-object.ts` | `.` |
| `useCompletion` | value | `src/use-completion.ts` | `.` |
| `UseCompletionHelpers` | type | `src/use-completion.ts` | `.` |
