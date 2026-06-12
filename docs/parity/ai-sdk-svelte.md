# @ai-sdk/svelte

- Version: 4.0.202
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.202/packages/svelte`
- Kotlin parity area: `:aisdk-ui`
- Current parity status: ported-as-kmp-ui: Svelte Chat/Completion/StructuredObject store concepts are represented by the framework-neutral Kotlin ui package: Chat, ChatTransport, TextStreamChatTransport, UIMessage/UIMessagePart, streamToUiMessages, convertToModelMessages, stream responses, and typed tool-part handler registry; Svelte stores/components are intentionally not emitted in the Kotlin runtime module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/svelte/src/index.ts` | 8 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `Chat` | value | `src/chat.svelte.ts` | `.` |
| `Completion` | value | `src/completion.svelte.ts` | `.` |
| `CompletionOptions` | type | `src/completion.svelte.ts` | `.` |
| `createAIContext` | value | `src/context-provider.ts` | `.` |
| `CreateUIMessage` | type | `src/chat.svelte.ts` | `.` |
| `Experimental_StructuredObject` | value | `src/structured-object.svelte.ts` | `.` |
| `Experimental_StructuredObjectOptions` | type | `src/structured-object.svelte.ts` | `.` |
| `UIMessage` | type | `src/chat.svelte.ts` | `.` |
