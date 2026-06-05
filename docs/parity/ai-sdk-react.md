# @ai-sdk/react

- Version: 3.0.199
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/react`
- Target Kotlin module: `:aisdk-ui`
- Current parity status: ported-as-kmp-ui: React Chat/useChat/useCompletion/useObject concepts are represented by the framework-neutral Kotlin ui package: Chat, ChatRequest, ChatTransport, TextStreamChatTransport, UIMessage/UIMessagePart, streamToUiMessages, convertToModelMessages, TextStreamResponse/UIMessageStreamResponse, createUiMessageStream, tool approval state, and typed tool-part handler registry; React hooks themselves are intentionally not emitted in the Kotlin runtime module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/react/src/index.ts` | 9 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `Chat` | value | `src/chat.react.ts` | `.` |
| `experimental_useObject` | value | `src/use-object.ts` | `.` |
| `Experimental_UseObjectHelpers` | type | `src/use-object.ts` | `.` |
| `Experimental_UseObjectOptions` | type | `src/use-object.ts` | `.` |
| `useChat` | value | `src/use-chat.ts` | `.` |
| `UseChatHelpers` | type | `src/use-chat.ts` | `.` |
| `UseChatOptions` | type | `src/use-chat.ts` | `.` |
| `useCompletion` | value | `src/use-completion.ts` | `.` |
| `UseCompletionHelpers` | type | `src/use-completion.ts` | `.` |
