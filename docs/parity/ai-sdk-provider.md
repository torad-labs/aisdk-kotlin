# @ai-sdk/provider

- Version: 3.0.10
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.204/packages/provider`
- Kotlin parity area: `:aisdk-provider`
- Current parity status: ported: provider contracts for language/embedding/image/speech/transcription/reranking/video models, provider metadata/options, warnings, usage, request/response metadata, provider interfaces, V2/V3-compatible folded type aliases, and public AI SDK error classes are represented as Kotlin contracts folded into the root module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.204/packages/provider/src/index.ts` | 122 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `AISDKError` | value | `src/errors/ai-sdk-error.ts` | `.` |
| `APICallError` | value | `src/errors/api-call-error.ts` | `.` |
| `EmbeddingModelV2` | type | `src/embedding-model/v2/embedding-model-v2.ts` | `.` |
| `EmbeddingModelV2Embedding` | type | `src/embedding-model/v2/embedding-model-v2-embedding.ts` | `.` |
| `EmbeddingModelV3` | type | `src/embedding-model/v3/embedding-model-v3.ts` | `.` |
| `EmbeddingModelV3CallOptions` | type | `src/embedding-model/v3/embedding-model-v3-call-options.ts` | `.` |
| `EmbeddingModelV3Embedding` | type | `src/embedding-model/v3/embedding-model-v3-embedding.ts` | `.` |
| `EmbeddingModelV3Middleware` | type | `src/embedding-model-middleware/v3/embedding-model-v3-middleware.ts` | `.` |
| `EmbeddingModelV3Result` | type | `src/embedding-model/v3/embedding-model-v3-result.ts` | `.` |
| `EmptyResponseBodyError` | value | `src/errors/empty-response-body-error.ts` | `.` |
| `Experimental_VideoModelV3` | type | `src/video-model/v3/video-model-v3.ts` | `.` |
| `Experimental_VideoModelV3CallOptions` | type | `src/video-model/v3/video-model-v3-call-options.ts` | `.` |
| `Experimental_VideoModelV3File` | type | `src/video-model/v3/video-model-v3-file.ts` | `.` |
| `Experimental_VideoModelV3VideoData` | type | `src/video-model/v3/video-model-v3.ts` | `.` |
| `getErrorMessage` | value | `src/errors/get-error-message.ts` | `.` |
| `ImageModelV2` | type | `src/image-model/v2/image-model-v2.ts` | `.` |
| `ImageModelV2CallOptions` | type | `src/image-model/v2/image-model-v2-call-options.ts` | `.` |
| `ImageModelV2CallWarning` | type | `src/image-model/v2/image-model-v2-call-warning.ts` | `.` |
| `ImageModelV2ProviderMetadata` | type | `src/image-model/v2/image-model-v2.ts` | `.` |
| `ImageModelV3` | type | `src/image-model/v3/image-model-v3.ts` | `.` |
| `ImageModelV3CallOptions` | type | `src/image-model/v3/image-model-v3-call-options.ts` | `.` |
| `ImageModelV3File` | type | `src/image-model/v3/image-model-v3-file.ts` | `.` |
| `ImageModelV3Middleware` | type | `src/image-model-middleware/v3/image-model-v3-middleware.ts` | `.` |
| `ImageModelV3ProviderMetadata` | type | `src/image-model/v3/image-model-v3.ts` | `.` |
| `ImageModelV3Usage` | type | `src/image-model/v3/image-model-v3-usage.ts` | `.` |
| `InvalidArgumentError` | value | `src/errors/invalid-argument-error.ts` | `.` |
| `InvalidPromptError` | value | `src/errors/invalid-prompt-error.ts` | `.` |
| `InvalidResponseDataError` | value | `src/errors/invalid-response-data-error.ts` | `.` |
| `isJSONArray` | value | `src/json-value/is-json.ts` | `.` |
| `isJSONObject` | value | `src/json-value/is-json.ts` | `.` |
| `isJSONValue` | value | `src/json-value/is-json.ts` | `.` |
| `JSONArray` | type | `src/json-value/json-value.ts` | `.` |
| `JSONObject` | type | `src/json-value/json-value.ts` | `.` |
| `JSONParseError` | value | `src/errors/json-parse-error.ts` | `.` |
| `JSONSchema7` | type | `json-schema` | `.` |
| `JSONSchema7Definition` | type | `json-schema` | `.` |
| `JSONValue` | type | `src/json-value/json-value.ts` | `.` |
| `LanguageModelV2` | type | `src/language-model/v2/language-model-v2.ts` | `.` |
| `LanguageModelV2CallOptions` | type | `src/language-model/v2/language-model-v2-call-options.ts` | `.` |
| `LanguageModelV2CallWarning` | type | `src/language-model/v2/language-model-v2-call-warning.ts` | `.` |
| `LanguageModelV2Content` | type | `src/language-model/v2/language-model-v2-content.ts` | `.` |
| `LanguageModelV2DataContent` | type | `src/language-model/v2/language-model-v2-data-content.ts` | `.` |
| `LanguageModelV2File` | type | `src/language-model/v2/language-model-v2-file.ts` | `.` |
| `LanguageModelV2FilePart` | type | `src/language-model/v2/language-model-v2-prompt.ts` | `.` |
| `LanguageModelV2FinishReason` | type | `src/language-model/v2/language-model-v2-finish-reason.ts` | `.` |
| `LanguageModelV2FunctionTool` | type | `src/language-model/v2/language-model-v2-function-tool.ts` | `.` |
| `LanguageModelV2Message` | type | `src/language-model/v2/language-model-v2-prompt.ts` | `.` |
| `LanguageModelV2Middleware` | type | `src/language-model-middleware/v2/language-model-v2-middleware.ts` | `.` |
| `LanguageModelV2Prompt` | type | `src/language-model/v2/language-model-v2-prompt.ts` | `.` |
| `LanguageModelV2ProviderDefinedTool` | type | `src/language-model/v2/language-model-v2-provider-defined-tool.ts` | `.` |
| `LanguageModelV2Reasoning` | type | `src/language-model/v2/language-model-v2-reasoning.ts` | `.` |
| `LanguageModelV2ReasoningPart` | type | `src/language-model/v2/language-model-v2-prompt.ts` | `.` |
| `LanguageModelV2ResponseMetadata` | type | `src/language-model/v2/language-model-v2-response-metadata.ts` | `.` |
| `LanguageModelV2Source` | type | `src/language-model/v2/language-model-v2-source.ts` | `.` |
| `LanguageModelV2StreamPart` | type | `src/language-model/v2/language-model-v2-stream-part.ts` | `.` |
| `LanguageModelV2Text` | type | `src/language-model/v2/language-model-v2-text.ts` | `.` |
| `LanguageModelV2TextPart` | type | `src/language-model/v2/language-model-v2-prompt.ts` | `.` |
| `LanguageModelV2ToolCall` | type | `src/language-model/v2/language-model-v2-tool-call.ts` | `.` |
| `LanguageModelV2ToolCallPart` | type | `src/language-model/v2/language-model-v2-prompt.ts` | `.` |
| `LanguageModelV2ToolChoice` | type | `src/language-model/v2/language-model-v2-tool-choice.ts` | `.` |
| `LanguageModelV2ToolResultOutput` | type | `src/language-model/v2/language-model-v2-prompt.ts` | `.` |
| `LanguageModelV2ToolResultPart` | type | `src/language-model/v2/language-model-v2-prompt.ts` | `.` |
| `LanguageModelV2Usage` | type | `src/language-model/v2/language-model-v2-usage.ts` | `.` |
| `LanguageModelV3` | type | `src/language-model/v3/language-model-v3.ts` | `.` |
| `LanguageModelV3CallOptions` | type | `src/language-model/v3/language-model-v3-call-options.ts` | `.` |
| `LanguageModelV3Content` | type | `src/language-model/v3/language-model-v3-content.ts` | `.` |
| `LanguageModelV3DataContent` | type | `src/language-model/v3/language-model-v3-data-content.ts` | `.` |
| `LanguageModelV3File` | type | `src/language-model/v3/language-model-v3-file.ts` | `.` |
| `LanguageModelV3FilePart` | type | `src/language-model/v3/language-model-v3-prompt.ts` | `.` |
| `LanguageModelV3FinishReason` | type | `src/language-model/v3/language-model-v3-finish-reason.ts` | `.` |
| `LanguageModelV3FunctionTool` | type | `src/language-model/v3/language-model-v3-function-tool.ts` | `.` |
| `LanguageModelV3GenerateResult` | type | `src/language-model/v3/language-model-v3-generate-result.ts` | `.` |
| `LanguageModelV3Message` | type | `src/language-model/v3/language-model-v3-prompt.ts` | `.` |
| `LanguageModelV3Middleware` | type | `src/language-model-middleware/v3/language-model-v3-middleware.ts` | `.` |
| `LanguageModelV3Prompt` | type | `src/language-model/v3/language-model-v3-prompt.ts` | `.` |
| `LanguageModelV3ProviderTool` | type | `src/language-model/v3/language-model-v3-provider-tool.ts` | `.` |
| `LanguageModelV3Reasoning` | type | `src/language-model/v3/language-model-v3-reasoning.ts` | `.` |
| `LanguageModelV3ReasoningPart` | type | `src/language-model/v3/language-model-v3-prompt.ts` | `.` |
| `LanguageModelV3ResponseMetadata` | type | `src/language-model/v3/language-model-v3-response-metadata.ts` | `.` |
| `LanguageModelV3Source` | type | `src/language-model/v3/language-model-v3-source.ts` | `.` |
| `LanguageModelV3StreamPart` | type | `src/language-model/v3/language-model-v3-stream-part.ts` | `.` |
| `LanguageModelV3StreamResult` | type | `src/language-model/v3/language-model-v3-stream-result.ts` | `.` |
| `LanguageModelV3Text` | type | `src/language-model/v3/language-model-v3-text.ts` | `.` |
| `LanguageModelV3TextPart` | type | `src/language-model/v3/language-model-v3-prompt.ts` | `.` |
| `LanguageModelV3ToolApprovalRequest` | type | `src/language-model/v3/language-model-v3-tool-approval-request.ts` | `.` |
| `LanguageModelV3ToolApprovalResponsePart` | type | `src/language-model/v3/language-model-v3-prompt.ts` | `.` |
| `LanguageModelV3ToolCall` | type | `src/language-model/v3/language-model-v3-tool-call.ts` | `.` |
| `LanguageModelV3ToolCallPart` | type | `src/language-model/v3/language-model-v3-prompt.ts` | `.` |
| `LanguageModelV3ToolChoice` | type | `src/language-model/v3/language-model-v3-tool-choice.ts` | `.` |
| `LanguageModelV3ToolResult` | type | `src/language-model/v3/language-model-v3-tool-result.ts` | `.` |
| `LanguageModelV3ToolResultOutput` | type | `src/language-model/v3/language-model-v3-prompt.ts` | `.` |
| `LanguageModelV3ToolResultPart` | type | `src/language-model/v3/language-model-v3-prompt.ts` | `.` |
| `LanguageModelV3Usage` | type | `src/language-model/v3/language-model-v3-usage.ts` | `.` |
| `LoadAPIKeyError` | value | `src/errors/load-api-key-error.ts` | `.` |
| `LoadSettingError` | value | `src/errors/load-setting-error.ts` | `.` |
| `NoContentGeneratedError` | value | `src/errors/no-content-generated-error.ts` | `.` |
| `NoSuchModelError` | value | `src/errors/no-such-model-error.ts` | `.` |
| `ProviderV2` | type | `src/provider/v2/provider-v2.ts` | `.` |
| `ProviderV3` | type | `src/provider/v3/provider-v3.ts` | `.` |
| `RerankingModelV3` | type | `src/reranking-model/v3/reranking-model-v3.ts` | `.` |
| `RerankingModelV3CallOptions` | type | `src/reranking-model/v3/reranking-model-v3-call-options.ts` | `.` |
| `SharedV2Headers` | type | `src/shared/v2/shared-v2-headers.ts` | `.` |
| `SharedV2ProviderMetadata` | type | `src/shared/v2/shared-v2-provider-metadata.ts` | `.` |
| `SharedV2ProviderOptions` | type | `src/shared/v2/shared-v2-provider-options.ts` | `.` |
| `SharedV3Headers` | type | `src/shared/v3/shared-v3-headers.ts` | `.` |
| `SharedV3ProviderMetadata` | type | `src/shared/v3/shared-v3-provider-metadata.ts` | `.` |
| `SharedV3ProviderOptions` | type | `src/shared/v3/shared-v3-provider-options.ts` | `.` |
| `SharedV3Warning` | type | `src/shared/v3/shared-v3-warning.ts` | `.` |
| `SpeechModelV2` | type | `src/speech-model/v2/speech-model-v2.ts` | `.` |
| `SpeechModelV2CallOptions` | type | `src/speech-model/v2/speech-model-v2-call-options.ts` | `.` |
| `SpeechModelV2CallWarning` | type | `src/speech-model/v2/speech-model-v2-call-warning.ts` | `.` |
| `SpeechModelV3` | type | `src/speech-model/v3/speech-model-v3.ts` | `.` |
| `SpeechModelV3CallOptions` | type | `src/speech-model/v3/speech-model-v3-call-options.ts` | `.` |
| `TooManyEmbeddingValuesForCallError` | value | `src/errors/too-many-embedding-values-for-call-error.ts` | `.` |
| `TranscriptionModelV2` | type | `src/transcription-model/v2/transcription-model-v2.ts` | `.` |
| `TranscriptionModelV2CallOptions` | type | `src/transcription-model/v2/transcription-model-v2-call-options.ts` | `.` |
| `TranscriptionModelV2CallWarning` | type | `src/transcription-model/v2/transcription-model-v2-call-warning.ts` | `.` |
| `TranscriptionModelV3` | type | `src/transcription-model/v3/transcription-model-v3.ts` | `.` |
| `TranscriptionModelV3CallOptions` | type | `src/transcription-model/v3/transcription-model-v3-call-options.ts` | `.` |
| `TypeValidationContext` | type | `src/errors/type-validation-error.ts` | `.` |
| `TypeValidationError` | value | `src/errors/type-validation-error.ts` | `.` |
| `UnsupportedFunctionalityError` | value | `src/errors/unsupported-functionality-error.ts` | `.` |
