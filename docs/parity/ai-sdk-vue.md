# @ai-sdk/vue

- Version: 3.0.195
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/vue`
- Target Kotlin module: `:aisdk-ui`
- Current parity status: ported-as-kmp-facade: Vue Chat/useCompletion/useObject concepts are represented by package-named Kotlin facades in `ai.torad.aisdk.vue`, backed by the shared KMP Chat, completion, structured-object, UI message, stream, transport, and typed tool-part primitives. The facade exports `Chat`, `useCompletion`, `experimental_useObject`, helper/state types, and the shared `UIMessage` shape for Kotlin UI hosts.

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/vue/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `Chat` | value | `src/chat.vue.ts` | `.` |
| `experimental_useObject` | value | `src/use-object.ts` | `.` |
| `Experimental_UseObjectHelpers` | type | `src/use-object.ts` | `.` |
| `Experimental_UseObjectOptions` | type | `src/use-object.ts` | `.` |
| `useCompletion` | value | `src/use-completion.ts` | `.` |
| `UseCompletionHelpers` | type | `src/use-completion.ts` | `.` |
