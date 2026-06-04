# @ai-sdk/deepinfra

- Version: 2.0.52
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.196/packages/deepinfra`
- Target Kotlin module: `:aisdk-provider-deepinfra`
- Current parity status: ported: createDeepInfra/deepinfra, provider settings, chat/completion/embedding/image routing, DeepInfraErrorData, and DeepInfra usage correction are represented as a Kotlin facade folded into the root module; VERSION is exposed as DEEPINFRA_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.196/packages/deepinfra/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createDeepInfra` | value | `src/deepinfra-provider.ts` | `.` |
| `deepinfra` | value | `src/deepinfra-provider.ts` | `.` |
| `DeepInfraErrorData` | type | `@ai-sdk/openai-compatible` | `.` |
| `DeepInfraProvider` | type | `src/deepinfra-provider.ts` | `.` |
| `DeepInfraProviderSettings` | type | `src/deepinfra-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
