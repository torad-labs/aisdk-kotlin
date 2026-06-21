# @ai-sdk/openai

- Version: 3.0.73
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/openai`
- Kotlin parity area: `:aisdk-openai`
- Current parity status: ported: createOpenAI/openai facade, hosted OpenAI tool descriptors, OpenAI-prefixed provider-tool argument factories, default Responses API model routing, OpenAI Responses request option mapping, supported URL metadata, built-in Responses tool type mapping, Responses metadata/logprob output mapping, OpenAI/Azure file-id prefix handling, and fake HTTP tests are folded into the root module; VERSION is exposed as VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/openai/src/index.ts` | 20 |
| `./internal` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/openai/src/internal/index.ts` | 64 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `applyPatch` | value | `src/tool/apply-patch.ts` | `./internal` |
| `applyPatchArgsSchema` | value | `src/tool/apply-patch.ts` | `./internal` |
| `applyPatchInputSchema` | value | `src/tool/apply-patch.ts` | `./internal` |
| `ApplyPatchOperation` | type | `src/tool/apply-patch.ts` | `./internal` |
| `applyPatchOutputSchema` | value | `src/tool/apply-patch.ts` | `./internal` |
| `applyPatchToolFactory` | value | `src/tool/apply-patch.ts` | `./internal` |
| `codeInterpreter` | value | `src/tool/code-interpreter.ts` | `./internal` |
| `codeInterpreterArgsSchema` | value | `src/tool/code-interpreter.ts` | `./internal` |
| `codeInterpreterInputSchema` | value | `src/tool/code-interpreter.ts` | `./internal` |
| `codeInterpreterOutputSchema` | value | `src/tool/code-interpreter.ts` | `./internal` |
| `codeInterpreterToolFactory` | value | `src/tool/code-interpreter.ts` | `./internal` |
| `createOpenAI` | value | `src/openai-provider.ts` | `.` |
| `fileSearch` | value | `src/tool/file-search.ts` | `./internal` |
| `fileSearchArgsSchema` | value | `src/tool/file-search.ts` | `./internal` |
| `fileSearchOutputSchema` | value | `src/tool/file-search.ts` | `./internal` |
| `hasDefaultResponseFormat` | value | `src/image/openai-image-model-options.ts` | `./internal` |
| `imageGeneration` | value | `src/tool/image-generation.ts` | `./internal` |
| `imageGenerationArgsSchema` | value | `src/tool/image-generation.ts` | `./internal` |
| `imageGenerationOutputSchema` | value | `src/tool/image-generation.ts` | `./internal` |
| `modelMaxImagesPerCall` | value | `src/image/openai-image-model-options.ts` | `./internal` |
| `openai` | value | `src/openai-provider.ts` | `.` |
| `OpenAIChatLanguageModel` | value | `src/chat/openai-chat-language-model.ts` | `./internal` |
| `OpenAIChatLanguageModelOptions` | type | `src/chat/openai-chat-options.ts` | `.` |
| `OpenAIChatModelId` | type | `src/chat/openai-chat-options.ts` | `./internal` |
| `OpenAICompletionLanguageModel` | value | `src/completion/openai-completion-language-model.ts` | `./internal` |
| `OpenAICompletionModelId` | type | `src/completion/openai-completion-options.ts` | `./internal` |
| `OpenAIEmbeddingModel` | value | `src/embedding/openai-embedding-model.ts` | `./internal` |
| `OpenAIEmbeddingModelId` | type | `src/embedding/openai-embedding-options.ts` | `./internal` |
| `openaiEmbeddingModelOptions` | value | `src/embedding/openai-embedding-options.ts` | `./internal` |
| `OpenAIEmbeddingModelOptions` | type | `src/embedding/openai-embedding-options.ts` | `.`, `./internal` |
| `OpenAIImageModel` | value | `src/image/openai-image-model.ts` | `./internal` |
| `openaiImageModelEditOptions` | value | `src/image/openai-image-model-options.ts` | `./internal` |
| `OpenAIImageModelEditOptions` | type | `src/image/openai-image-model-options.ts` | `.`, `./internal` |
| `openaiImageModelGenerationOptions` | value | `src/image/openai-image-model-options.ts` | `./internal` |
| `OpenAIImageModelGenerationOptions` | type | `src/image/openai-image-model-options.ts` | `.`, `./internal` |
| `OpenAIImageModelId` | type | `src/image/openai-image-model-options.ts` | `./internal` |
| `openaiImageModelOptions` | value | `src/image/openai-image-model-options.ts` | `./internal` |
| `OpenAIImageModelOptions` | type | `src/image/openai-image-model-options.ts` | `.`, `./internal` |
| `openaiLanguageModelChatOptions` | value | `src/chat/openai-chat-options.ts` | `./internal` |
| `OpenAILanguageModelChatOptions` | type | `src/chat/openai-chat-options.ts` | `.`, `./internal` |
| `openaiLanguageModelCompletionOptions` | value | `src/completion/openai-completion-options.ts` | `./internal` |
| `OpenAILanguageModelCompletionOptions` | type | `src/completion/openai-completion-options.ts` | `.`, `./internal` |
| `OpenAILanguageModelResponsesOptions` | type | `src/responses/openai-responses-options.ts` | `.` |
| `OpenAIProvider` | type | `src/openai-provider.ts` | `.` |
| `OpenAIProviderSettings` | type | `src/openai-provider.ts` | `.` |
| `OpenAIResponsesLanguageModel` | value | `src/responses/openai-responses-language-model.ts` | `./internal` |
| `OpenaiResponsesProviderMetadata` | type | `src/responses/openai-responses-provider-metadata.ts` | `.`, `./internal` |
| `OpenAIResponsesProviderOptions` | type | `src/responses/openai-responses-options.ts` | `.` |
| `OpenaiResponsesReasoningProviderMetadata` | type | `src/responses/openai-responses-provider-metadata.ts` | `.`, `./internal` |
| `OpenaiResponsesSourceDocumentProviderMetadata` | type | `src/responses/openai-responses-provider-metadata.ts` | `.`, `./internal` |
| `OpenaiResponsesTextProviderMetadata` | type | `src/responses/openai-responses-provider-metadata.ts` | `.`, `./internal` |
| `OpenAISpeechModel` | value | `src/speech/openai-speech-model.ts` | `./internal` |
| `OpenAISpeechModelId` | type | `src/speech/openai-speech-options.ts` | `./internal` |
| `OpenAISpeechModelOptions` | type | `src/speech/openai-speech-options.ts` | `.`, `./internal` |
| `openaiSpeechModelOptionsSchema` | value | `src/speech/openai-speech-options.ts` | `./internal` |
| `OpenAITranscriptionCallOptions` | type | `src/transcription/openai-transcription-model.ts` | `./internal` |
| `OpenAITranscriptionModel` | value | `src/transcription/openai-transcription-model.ts` | `./internal` |
| `OpenAITranscriptionModelId` | type | `src/transcription/openai-transcription-options.ts` | `./internal` |
| `openAITranscriptionModelOptions` | value | `src/transcription/openai-transcription-options.ts` | `./internal` |
| `OpenAITranscriptionModelOptions` | type | `src/transcription/openai-transcription-options.ts` | `.`, `./internal` |
| `ResponsesProviderMetadata` | type | `src/responses/openai-responses-provider-metadata.ts` | `./internal` |
| `ResponsesReasoningProviderMetadata` | type | `src/responses/openai-responses-provider-metadata.ts` | `./internal` |
| `ResponsesSourceDocumentProviderMetadata` | type | `src/responses/openai-responses-provider-metadata.ts` | `./internal` |
| `ResponsesTextProviderMetadata` | type | `src/responses/openai-responses-provider-metadata.ts` | `./internal` |
| `VERSION` | value | `src/version.ts` | `.` |
| `webSearch` | value | `src/tool/web-search.ts` | `./internal` |
| `webSearchArgsSchema` | value | `src/tool/web-search.ts` | `./internal` |
| `webSearchOutputSchema` | value | `src/tool/web-search.ts` | `./internal` |
| `webSearchPreview` | value | `src/tool/web-search-preview.ts` | `./internal` |
| `webSearchPreviewArgsSchema` | value | `src/tool/web-search-preview.ts` | `./internal` |
| `webSearchPreviewInputSchema` | value | `src/tool/web-search-preview.ts` | `./internal` |
| `webSearchToolFactory` | value | `src/tool/web-search.ts` | `./internal` |
