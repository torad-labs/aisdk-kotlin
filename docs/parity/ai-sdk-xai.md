# @ai-sdk/xai

- Version: 3.0.93
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/xai`
- Kotlin parity area: `:aisdk-provider-xai`
- Current parity status: ported: createXai/xai, XaiProviderSettings, chat/responses/image/video model factories, hosted xAI tool descriptors, chat option snake_case mapping, citations, image generation/editing, image provider metadata, video generation/edit/extend/reference routing, video polling, warnings, URL video outputs, xAI auth/user-agent behavior, and unsupported embedding errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as XAI_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/xai/src/index.ts` | 22 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `codeExecution` | value | `src/tool/index.ts` | `.` |
| `createXai` | value | `src/xai-provider.ts` | `.` |
| `mcpServer` | value | `src/tool/index.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
| `viewImage` | value | `src/tool/index.ts` | `.` |
| `viewXVideo` | value | `src/tool/index.ts` | `.` |
| `webSearch` | value | `src/tool/index.ts` | `.` |
| `xai` | value | `src/xai-provider.ts` | `.` |
| `XaiErrorData` | type | `src/xai-error.ts` | `.` |
| `XaiImageModelOptions` | type | `src/xai-image-options.ts` | `.` |
| `XaiImageProviderOptions` | type | `src/xai-image-options.ts` | `.` |
| `XaiLanguageModelChatOptions` | type | `src/xai-chat-options.ts` | `.` |
| `XaiLanguageModelResponsesOptions` | type | `src/responses/xai-responses-options.ts` | `.` |
| `XaiProvider` | type | `src/xai-provider.ts` | `.` |
| `XaiProviderOptions` | type | `src/xai-chat-options.ts` | `.` |
| `XaiProviderSettings` | type | `src/xai-provider.ts` | `.` |
| `XaiResponsesProviderOptions` | type | `src/responses/xai-responses-options.ts` | `.` |
| `xaiTools` | value | `src/tool/index.ts` | `.` |
| `XaiVideoModelId` | type | `src/xai-video-settings.ts` | `.` |
| `XaiVideoModelOptions` | type | `src/xai-video-options.ts` | `.` |
| `XaiVideoProviderOptions` | type | `src/xai-video-options.ts` | `.` |
| `xSearch` | value | `src/tool/index.ts` | `.` |
