# @ai-sdk/react

- Version: 3.0.197
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/react`
- Target Kotlin module: `:aisdk-ui`
- Current parity status: ported-as-kmp-facade: React Chat/useChat/useCompletion/useObject concepts are represented by package-named Kotlin facades in `ai.torad.aisdk.react`, backed by the shared KMP Chat, completion, structured-object, UI message, stream, transport, tool approval, and typed tool-part primitives. The facade exports `useChat`, `useCompletion`, `experimental_useObject`, helper/state types, and the shared `UIMessage` shape for Kotlin UI hosts.

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/react/src/index.ts` | 9 |

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
