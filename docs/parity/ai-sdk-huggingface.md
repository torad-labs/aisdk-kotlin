# @ai-sdk/huggingface

- Version: 1.0.53
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/huggingface`
- Kotlin parity area: `:aisdk-provider-huggingface`
- Current parity status: ported: createHuggingFace/huggingface, HuggingFaceProviderSettings, HuggingFace Responses model aliases/options/error alias, Responses request mapping, provider-option metadata/instructions/strict-json/reasoning handling, JSON schema format mapping, function tool/tool-choice mapping, unsupported parameter warnings, message/image conversion, response text/reasoning/source/function/MCP tool parsing, SSE stream mapping, usage parsing, auth/header behavior, and unsupported embedding/image model guidance are represented as a Kotlin facade folded into the root module; VERSION is exposed as HUGGINGFACE_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/huggingface/src/index.ts` | 7 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createHuggingFace` | value | `src/huggingface-provider.ts` | `.` |
| `huggingface` | value | `src/huggingface-provider.ts` | `.` |
| `HuggingFaceErrorData` | type | `@ai-sdk/openai-compatible` | `.` |
| `HuggingFaceProvider` | type | `src/huggingface-provider.ts` | `.` |
| `HuggingFaceProviderSettings` | type | `src/huggingface-provider.ts` | `.` |
| `HuggingFaceResponsesModelId` | type | `src/responses/huggingface-responses-settings.ts` | `.` |
| `HuggingFaceResponsesSettings` | type | `src/responses/huggingface-responses-settings.ts` | `.` |
