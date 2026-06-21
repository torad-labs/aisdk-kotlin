# @ai-sdk/alibaba

- Version: 1.0.29
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/alibaba`
- Kotlin parity area: `:aisdk-provider-alibaba`
- Current parity status: ported: createAlibaba/alibaba, AlibabaProviderSettings, Alibaba chat aliases/options/cache-control/usage aliases, Alibaba OpenAI-compatible chat routing, Qwen thinking option mapping, parallel tool-call option mapping, cache-write usage correction, reasoning/tool-call parsing via the shared chat adapter, DashScope text-embedding (text-embedding-v3/v4) embedding model with text-type/dimension/output-type options and sparse-output rejection, DashScope video task creation/polling, T2V/I2V/R2V input mapping, video option mapping, URL video outputs, warnings, error handling, and provider metadata are represented as a Kotlin facade folded into the root module; VERSION is exposed as ALIBABA_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/alibaba/src/index.ts` | 15 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `alibaba` | value | `src/alibaba-provider.ts` | `.` |
| `AlibabaCacheControl` | type | `src/alibaba-chat-prompt.ts` | `.` |
| `AlibabaChatModelId` | type | `src/alibaba-chat-options.ts` | `.` |
| `AlibabaEmbeddingModelId` | type | `src/alibaba-embedding-options.ts` | `.` |
| `AlibabaEmbeddingModelOptions` | type | `src/alibaba-embedding-options.ts` | `.` |
| `AlibabaLanguageModelOptions` | type | `src/alibaba-chat-options.ts` | `.` |
| `AlibabaProvider` | type | `src/alibaba-provider.ts` | `.` |
| `AlibabaProviderOptions` | type | `src/alibaba-chat-options.ts` | `.` |
| `AlibabaProviderSettings` | type | `src/alibaba-provider.ts` | `.` |
| `AlibabaUsage` | type | `src/convert-alibaba-usage.ts` | `.` |
| `AlibabaVideoModelId` | type | `src/alibaba-video-settings.ts` | `.` |
| `AlibabaVideoModelOptions` | type | `src/alibaba-video-model.ts` | `.` |
| `AlibabaVideoProviderOptions` | type | `src/alibaba-video-model.ts` | `.` |
| `createAlibaba` | value | `src/alibaba-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
