# @ai-sdk/openai-compatible

- Version: 2.0.49
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.202/packages/openai-compatible`
- Kotlin parity area: `:aisdk-openai-compatible`
- Current parity status: ported: createOpenAICompatible/createOpenAICompatibleProvider, OpenAICompatibleProviderSettings, chat/completion/embedding/image/speech/transcription models, request URL/header/auth/query handling, structured-output controls, provider-option forwarding, request transforms, usage conversion hooks, response metadata, finish-reason/usage mapping, SSE chat/completion streaming, multimodal/tool message conversion, and facade reuse for compatible providers are represented as Kotlin adapters folded into the root module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/openai-compatible/src/index.ts` | 20 |
| `./internal` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/openai-compatible/src/internal/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `convertOpenAICompatibleChatUsage` | value | `src/chat/convert-openai-compatible-chat-usage.ts` | `./internal` |
| `convertToOpenAICompatibleChatMessages` | value | `src/chat/convert-to-openai-compatible-chat-messages.ts` | `./internal` |
| `createOpenAICompatible` | value | `src/openai-compatible-provider.ts` | `.` |
| `getResponseMetadata` | value | `src/chat/get-response-metadata.ts` | `./internal` |
| `mapOpenAICompatibleFinishReason` | value | `src/chat/map-openai-compatible-finish-reason.ts` | `./internal` |
| `MetadataExtractor` | type | `src/chat/openai-compatible-metadata-extractor.ts` | `.` |
| `OpenAICompatibleChatConfig` | type | `src/chat/openai-compatible-chat-language-model.ts` | `./internal` |
| `OpenAICompatibleChatLanguageModel` | value | `src/chat/openai-compatible-chat-language-model.ts` | `.` |
| `OpenAICompatibleChatModelId` | type | `src/chat/openai-compatible-chat-options.ts` | `.` |
| `OpenAICompatibleCompletionLanguageModel` | value | `src/completion/openai-compatible-completion-language-model.ts` | `.` |
| `OpenAICompatibleCompletionModelId` | type | `src/completion/openai-compatible-completion-options.ts` | `.` |
| `OpenAICompatibleCompletionProviderOptions` | type | `src/completion/openai-compatible-completion-options.ts` | `.` |
| `OpenAICompatibleEmbeddingModel` | value | `src/embedding/openai-compatible-embedding-model.ts` | `.` |
| `OpenAICompatibleEmbeddingModelId` | type | `src/embedding/openai-compatible-embedding-options.ts` | `.` |
| `OpenAICompatibleEmbeddingModelOptions` | type | `src/embedding/openai-compatible-embedding-options.ts` | `.` |
| `OpenAICompatibleEmbeddingProviderOptions` | type | `src/embedding/openai-compatible-embedding-options.ts` | `.` |
| `OpenAICompatibleErrorData` | type | `src/openai-compatible-error.ts` | `.` |
| `OpenAICompatibleImageModel` | value | `src/image/openai-compatible-image-model.ts` | `.` |
| `OpenAICompatibleLanguageModelChatOptions` | type | `src/chat/openai-compatible-chat-options.ts` | `.` |
| `OpenAICompatibleLanguageModelCompletionOptions` | type | `src/completion/openai-compatible-completion-options.ts` | `.` |
| `OpenAICompatibleProvider` | type | `src/openai-compatible-provider.ts` | `.` |
| `OpenAICompatibleProviderOptions` | type | `src/chat/openai-compatible-chat-options.ts` | `.` |
| `OpenAICompatibleProviderSettings` | type | `src/openai-compatible-provider.ts` | `.` |
| `prepareTools` | value | `src/chat/openai-compatible-prepare-tools.ts` | `./internal` |
| `ProviderErrorStructure` | type | `src/openai-compatible-error.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
