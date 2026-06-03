# @ai-sdk/codemod

- Version: 3.0.6
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/codemod`
- Target Kotlin module: `:aisdk-codemod`
- Current parity status: ported-as-kmp-tooling: upstream exposes a JavaScript codemod CLI with no runtime exports; AISDK Kotlin ships deterministic migration helpers in `ai.torad.aisdk.codemod` for the load-bearing v5→v6 rewrites that matter to Kotlin hosts: data-stream to UI-message-stream helper names, v5 `useChat` input helper removal, and framework package import mapping to Kotlin facade packages.

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `bin:codemod` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/codemod/src/bin/codemod.ts` | 0 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
