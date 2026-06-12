# @ai-sdk/angular

- Version: 2.0.203
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.202/packages/angular`
- Kotlin parity area: `:aisdk-ui`
- Current parity status: ported-as-kmp-ui: Angular Chat/Completion/StructuredObject runtime concepts are represented by the framework-neutral Kotlin ui package: Chat, ChatTransport, TextStreamChatTransport, UIMessage/UIMessagePart, streamToUiMessages, convertToModelMessages, text/UI message stream responses, tool state, and typed tool-part handler registry; Angular signal/component bindings are intentionally not emitted in the Kotlin runtime module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/angular/src/index.ts` | 5 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `Chat` | value | `src/lib/chat.ng.ts` | `.` |
| `Completion` | value | `src/lib/completion.ng.ts` | `.` |
| `CompletionOptions` | type | `src/lib/completion.ng.ts` | `.` |
| `StructuredObject` | value | `src/lib/structured-object.ng.ts` | `.` |
| `StructuredObjectOptions` | type | `src/lib/structured-object.ng.ts` | `.` |
