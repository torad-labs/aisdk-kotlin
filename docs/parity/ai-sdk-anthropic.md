# @ai-sdk/anthropic

- Version: 3.0.81
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/anthropic`
- Target Kotlin module: `:aisdk-provider-anthropic`
- Current parity status: missing: no Kotlin module or parity mapping exists yet

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/anthropic/src/index.ts` | 11 |
| `./internal` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/anthropic/src/internal/index.ts` | 4 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `anthropic` | value | `src/anthropic-provider.ts` | `.` |
| `AnthropicLanguageModelOptions` | type | `src/anthropic-messages-options.ts` | `.` |
| `AnthropicMessageMetadata` | type | `src/anthropic-message-metadata.ts` | `.` |
| `AnthropicMessagesLanguageModel` | value | `src/anthropic-messages-language-model.ts` | `./internal` |
| `AnthropicMessagesModelId` | type | `src/anthropic-messages-options.ts` | `./internal` |
| `AnthropicProvider` | type | `src/anthropic-provider.ts` | `.` |
| `AnthropicProviderOptions` | type | `src/anthropic-messages-options.ts` | `.` |
| `AnthropicProviderSettings` | type | `src/anthropic-provider.ts` | `.` |
| `AnthropicToolOptions` | type | `src/anthropic-prepare-tools.ts` | `.` |
| `anthropicTools` | value | `src/anthropic-tools.ts` | `./internal` |
| `AnthropicUsageIteration` | type | `src/anthropic-message-metadata.ts` | `.` |
| `createAnthropic` | value | `src/anthropic-provider.ts` | `.` |
| `forwardAnthropicContainerIdFromLastStep` | value | `src/forward-anthropic-container-id-from-last-step.ts` | `.` |
| `prepareTools` | value | `src/anthropic-prepare-tools.ts` | `./internal` |
| `VERSION` | value | `src/version.ts` | `.` |
