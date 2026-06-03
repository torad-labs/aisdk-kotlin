# @ai-sdk/google

- Version: 3.0.80
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/google`
- Target Kotlin module: `:aisdk-provider-google`
- Current parity status: in-progress: createGoogleGenerativeAI/google, GoogleGenerativeAIProviderSettings, Google model/option/metadata aliases, Google hosted tool descriptors, Gemini generateContent/streamGenerateContent request conversion, system/user/assistant/tool multimodal prompt mapping, function/provider tool mapping, JSON response format and generationConfig mapping, Gemini response text/reasoning/file/tool/source parsing, usage/provider metadata, SSE stream mapping, embedding single/batch payload mapping, Imagen and Gemini image generation, Veo long-running video polling, auth/user-agent behavior, and unsupported Interactions placeholder are represented as a Kotlin facade folded into the root module; full Google Interactions API remains open; VERSION is exposed as GOOGLE_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/google/src/index.ts` | 20 |
| `./internal` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/google/src/internal/index.ts` | 10 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createGoogleGenerativeAI` | value | `src/google-provider.ts` | `.` |
| `getGroundingMetadataSchema` | value | `src/google-generative-ai-language-model.ts` | `./internal` |
| `getUrlContextMetadataSchema` | value | `src/google-generative-ai-language-model.ts` | `./internal` |
| `google` | value | `src/google-provider.ts` | `.` |
| `GoogleEmbeddingModelOptions` | type | `src/google-generative-ai-embedding-options.ts` | `.` |
| `GoogleErrorData` | type | `src/google-error.ts` | `.` |
| `GoogleGenerativeAIEmbeddingProviderOptions` | type | `src/google-generative-ai-embedding-options.ts` | `.` |
| `GoogleGenerativeAIImageProviderOptions` | type | `src/google-generative-ai-image-model.ts` | `.` |
| `GoogleGenerativeAILanguageModel` | value | `src/google-generative-ai-language-model.ts` | `./internal` |
| `GoogleGenerativeAIModelId` | type | `src/google-generative-ai-options.ts` | `./internal` |
| `GoogleGenerativeAIProvider` | type | `src/google-provider.ts` | `.` |
| `GoogleGenerativeAIProviderMetadata` | type | `src/google-generative-ai-prompt.ts` | `.` |
| `GoogleGenerativeAIProviderOptions` | type | `src/google-generative-ai-options.ts` | `.` |
| `GoogleGenerativeAIProviderSettings` | type | `src/google-provider.ts` | `.` |
| `GoogleGenerativeAIVideoModelId` | type | `src/google-generative-ai-video-settings.ts` | `.` |
| `GoogleGenerativeAIVideoProviderOptions` | type | `src/google-generative-ai-video-model.ts` | `.` |
| `GoogleImageModelOptions` | type | `src/google-generative-ai-image-model.ts` | `.` |
| `GoogleInteractionsAgentName` | type | `src/interactions/google-interactions-agent.ts` | `.` |
| `GoogleInteractionsModelId` | type | `src/interactions/google-interactions-language-model-options.ts` | `.` |
| `GoogleInteractionsProviderMetadata` | type | `src/interactions/google-interactions-provider-metadata.ts` | `.` |
| `GoogleLanguageModelInteractionsOptions` | type | `src/interactions/google-interactions-language-model-options.ts` | `.` |
| `GoogleLanguageModelOptions` | type | `src/google-generative-ai-options.ts` | `.` |
| `googleTools` | value | `src/google-tools.ts` | `./internal` |
| `GoogleVideoModelOptions` | type | `src/google-generative-ai-video-model.ts` | `.` |
| `GroundingMetadataSchema` | type | `src/google-generative-ai-language-model.ts` | `./internal` |
| `PromptFeedbackSchema` | type | `src/google-generative-ai-language-model.ts` | `./internal` |
| `SafetyRatingSchema` | type | `src/google-generative-ai-language-model.ts` | `./internal` |
| `UrlContextMetadataSchema` | type | `src/google-generative-ai-language-model.ts` | `./internal` |
| `UsageMetadataSchema` | type | `src/google-generative-ai-language-model.ts` | `./internal` |
| `VERSION` | value | `src/version.ts` | `.` |
