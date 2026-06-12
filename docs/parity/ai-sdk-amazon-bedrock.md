# @ai-sdk/amazon-bedrock

- Version: 4.0.116
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.202/packages/amazon-bedrock`
- Kotlin parity area: `:aisdk-provider-amazon-bedrock`
- Current parity status: ported: createAmazonBedrock/bedrock, createBedrockAnthropic/bedrockAnthropic, createBedrockMantle/bedrockMantle, AmazonBedrockProviderSettings, Bedrock credentials/settings aliases, native SigV4 signing, Converse chat request/response mapping, multimodal message conversion, Bedrock toolConfig/tool-choice mapping, reasoning/service-tier/additionalModelRequestFields mapping, JSON and binary Smithy event-stream decoding, embedding/image/reranking runtime adapters, Mantle OpenAI-compatible chat routing, bearer-token auth, usage/provider metadata, and unsupported/default provider errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as AMAZON_BEDROCK_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/amazon-bedrock/src/index.ts` | 11 |
| `./anthropic` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/amazon-bedrock/src/anthropic/index.ts` | 5 |
| `./mantle` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/amazon-bedrock/src/mantle/index.ts` | 5 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `AmazonBedrockEmbeddingModelOptions` | type | `src/bedrock-embedding-options.ts` | `.` |
| `AmazonBedrockLanguageModelOptions` | type | `src/bedrock-chat-options.ts` | `.` |
| `AmazonBedrockProvider` | type | `src/bedrock-provider.ts` | `.` |
| `AmazonBedrockProviderSettings` | type | `src/bedrock-provider.ts` | `.` |
| `AmazonBedrockRerankingModelOptions` | type | `src/reranking/bedrock-reranking-options.ts` | `.` |
| `AnthropicProviderOptions` | type | `@ai-sdk/anthropic` | `.` |
| `bedrock` | value | `src/bedrock-provider.ts` | `.` |
| `bedrockAnthropic` | value | `src/anthropic/bedrock-anthropic-provider.ts` | `./anthropic` |
| `BedrockAnthropicModelId` | type | `src/anthropic/bedrock-anthropic-options.ts` | `./anthropic` |
| `BedrockAnthropicProvider` | type | `src/anthropic/bedrock-anthropic-provider.ts` | `./anthropic` |
| `BedrockAnthropicProviderSettings` | type | `src/anthropic/bedrock-anthropic-provider.ts` | `./anthropic` |
| `bedrockMantle` | value | `src/mantle/bedrock-mantle-provider.ts` | `./mantle` |
| `BedrockMantleModelId` | type | `src/mantle/bedrock-mantle-options.ts` | `./mantle` |
| `BedrockMantleProvider` | type | `src/mantle/bedrock-mantle-provider.ts` | `./mantle` |
| `BedrockMantleProviderSettings` | type | `src/mantle/bedrock-mantle-provider.ts` | `./mantle` |
| `BedrockProviderOptions` | type | `src/bedrock-chat-options.ts` | `.` |
| `BedrockRerankingOptions` | type | `src/reranking/bedrock-reranking-options.ts` | `.` |
| `createAmazonBedrock` | value | `src/bedrock-provider.ts` | `.` |
| `createBedrockAnthropic` | value | `src/anthropic/bedrock-anthropic-provider.ts` | `./anthropic` |
| `createBedrockMantle` | value | `src/mantle/bedrock-mantle-provider.ts` | `./mantle` |
| `VERSION` | value | `src/version.ts` | `.` |
