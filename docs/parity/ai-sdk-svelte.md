# @ai-sdk/svelte

- Version: 4.0.195
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/svelte`
- Target Kotlin module: `:aisdk-ui`
- Current parity status: ported-as-kmp-facade: Svelte Chat/Completion/StructuredObject store concepts are represented by package-named Kotlin facades in `ai.torad.aisdk.svelte`, backed by the shared KMP Chat, completion, structured-object, UI message, stream, transport, and typed tool-part primitives. The facade exports `Chat`, `Completion`, `Experimental_StructuredObject`, `createAIContext`, option types, and the shared `UIMessage` shape for Kotlin UI hosts.

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/svelte/src/index.ts` | 8 |

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
