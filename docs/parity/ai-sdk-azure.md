# @ai-sdk/azure

- Version: 3.0.69
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.196/packages/azure`
- Target Kotlin module: `:aisdk-provider-azure`
- Current parity status: ported: createAzure/azure, AzureOpenAIProviderSettings, Azure OpenAI model aliases, responses/chat/completion/embedding/image/transcription/speech routing, api-key and tokenProvider authentication, v1 and deployment URL formats, api-version handling, Azure hosted OpenAI tools, per-call language headers, and OpenAI provider-option forwarding are represented as a Kotlin facade folded into the root module; VERSION is exposed as AZURE_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.196/packages/azure/src/index.ts` | 13 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `azure` | value | `src/azure-openai-provider.ts` | `.` |
| `AzureOpenAIProvider` | type | `src/azure-openai-provider.ts` | `.` |
| `AzureOpenAIProviderSettings` | type | `src/azure-openai-provider.ts` | `.` |
| `AzureResponsesProviderMetadata` | type | `src/azure-openai-provider-metadata.ts` | `.` |
| `AzureResponsesReasoningProviderMetadata` | type | `src/azure-openai-provider-metadata.ts` | `.` |
| `AzureResponsesSourceDocumentProviderMetadata` | type | `src/azure-openai-provider-metadata.ts` | `.` |
| `AzureResponsesTextProviderMetadata` | type | `src/azure-openai-provider-metadata.ts` | `.` |
| `createAzure` | value | `src/azure-openai-provider.ts` | `.` |
| `OpenAIChatLanguageModelOptions` | type | `@ai-sdk/openai` | `.` |
| `OpenAILanguageModelChatOptions` | type | `@ai-sdk/openai` | `.` |
| `OpenAILanguageModelResponsesOptions` | type | `@ai-sdk/openai` | `.` |
| `OpenAIResponsesProviderOptions` | type | `@ai-sdk/openai` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
