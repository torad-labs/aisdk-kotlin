# @ai-sdk/anthropic

- Version: 3.0.81
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/anthropic`
- Target Kotlin module: `:aisdk-provider-anthropic`
- Current parity status: ported: createAnthropic/anthropic, AnthropicProviderSettings, Anthropic message model aliases/options/metadata aliases, Anthropic hosted tool descriptors, auth-token/api-key validation, Anthropic headers/beta/user-agent behavior, messages request conversion, multimodal image/PDF/text document blocks, cache-control/file citation metadata, thinking/adaptive-thinking options, structured output_config, metadata/MCP/container/context option mapping, function/provider tool mapping, response text/reasoning/source/tool parsing, usage/cache iteration accounting, SSE event mapping, container-id forwarding helper, and unsupported embedding/image model errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as ANTHROPIC_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/anthropic/src/index.ts` | 11 |
| `./internal` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/anthropic/src/internal/index.ts` | 4 |

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
