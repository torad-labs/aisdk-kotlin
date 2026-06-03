# @ai-sdk/angular

- Version: 2.0.196
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/angular`
- Target Kotlin module: `:aisdk-ui`
- Current parity status: ported-as-kmp-facade: Angular Chat/Completion/StructuredObject runtime concepts are represented by package-named Kotlin facades in `ai.torad.aisdk.angular`, backed by the shared KMP Chat, completion, structured-object, UI message, stream, transport, response, tool state, and typed tool-part primitives. The facade exports `Chat`, `Completion`, `StructuredObject`, option types, and the shared `UIMessage` shape for Kotlin UI hosts.

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/angular/src/index.ts` | 5 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `Chat` | value | `src/lib/chat.ng.ts` | `.` |
| `Completion` | value | `src/lib/completion.ng.ts` | `.` |
| `CompletionOptions` | type | `src/lib/completion.ng.ts` | `.` |
| `StructuredObject` | value | `src/lib/structured-object.ng.ts` | `.` |
| `StructuredObjectOptions` | type | `src/lib/structured-object.ng.ts` | `.` |
