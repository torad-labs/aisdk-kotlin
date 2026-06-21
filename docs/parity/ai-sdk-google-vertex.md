# @ai-sdk/google-vertex

- Version: 4.0.147
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/google-vertex`
- Kotlin parity area: `:aisdk-provider-google-vertex`
- Current parity status: ported: createVertex/vertex, createVertexAnthropic/vertexAnthropic, createVertexMaas/vertexMaas, createGoogleVertexXai/googleVertexXai, GoogleVertexProviderSettings and option aliases, Vertex publisher/express-mode base URL construction including global, regional, and eu/us REP hosts, Bearer/API-key/header auth behavior, Vertex Gemini language/embedding/image/video routing through the Google core adapter, Vertex hosted tool descriptors, Vertex Anthropic rawPredict/streamRawPredict routing with Vertex body/header transforms, Vertex MaAS OpenAI-compatible routing, Vertex xAI OpenAI-compatible routing with xAI request/usage transforms, unsupported model errors, and KMP host-injected credential boundaries are represented as Kotlin facades folded into the root module; VERSION is exposed as GOOGLE_VERTEX_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/google-vertex/src/index.ts` | 13 |
| `./edge` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/google-vertex/src/edge/index.ts` | 4 |
| `./anthropic` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/google-vertex/src/anthropic/index.ts` | 4 |
| `./anthropic/edge` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/google-vertex/src/anthropic/edge/index.ts` | 4 |
| `./maas` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/google-vertex/src/maas/index.ts` | 5 |
| `./maas/edge` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/google-vertex/src/maas/edge/index.ts` | 5 |
| `./xai` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/google-vertex/src/xai/index.ts` | 5 |
| `./xai/edge` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/google-vertex/src/xai/edge/index.ts` | 5 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createGoogleVertexXai` | value | `src/xai/google-vertex-xai-provider-node.ts` | `./xai`, `./xai/edge` |
| `createVertex` | value | `src/google-vertex-provider-node.ts` | `.`, `./edge` |
| `createVertexAnthropic` | value | `src/anthropic/google-vertex-anthropic-provider-node.ts` | `./anthropic`, `./anthropic/edge` |
| `createVertexMaas` | value | `src/maas/google-vertex-maas-provider-node.ts` | `./maas`, `./maas/edge` |
| `GoogleVertexAnthropicProvider` | type | `src/anthropic/google-vertex-anthropic-provider-node.ts` | `./anthropic`, `./anthropic/edge` |
| `GoogleVertexAnthropicProviderSettings` | type | `src/anthropic/google-vertex-anthropic-provider-node.ts` | `./anthropic`, `./anthropic/edge` |
| `GoogleVertexEmbeddingModelOptions` | type | `src/google-vertex-embedding-options.ts` | `.` |
| `GoogleVertexImageModelOptions` | type | `src/google-vertex-image-model.ts` | `.` |
| `GoogleVertexImageProviderOptions` | type | `src/google-vertex-image-model.ts` | `.` |
| `GoogleVertexMaasModelId` | type | `src/maas/google-vertex-maas-options.ts` | `./maas`, `./maas/edge` |
| `GoogleVertexMaasProvider` | type | `src/maas/google-vertex-maas-provider-node.ts` | `./maas`, `./maas/edge` |
| `GoogleVertexMaasProviderSettings` | type | `src/maas/google-vertex-maas-provider-node.ts` | `./maas`, `./maas/edge` |
| `GoogleVertexProvider` | type | `src/google-vertex-provider-node.ts` | `.`, `./edge` |
| `GoogleVertexProviderSettings` | type | `src/google-vertex-provider-node.ts` | `.`, `./edge` |
| `GoogleVertexTranscriptionModelId` | type | `src/google-vertex-transcription-model-options.ts` | `.` |
| `GoogleVertexTranscriptionModelOptions` | type | `src/google-vertex-transcription-model-options.ts` | `.` |
| `GoogleVertexVideoModelId` | type | `src/google-vertex-video-settings.ts` | `.` |
| `GoogleVertexVideoModelOptions` | type | `src/google-vertex-video-model.ts` | `.` |
| `GoogleVertexVideoProviderOptions` | type | `src/google-vertex-video-model.ts` | `.` |
| `googleVertexXai` | value | `src/xai/google-vertex-xai-provider-node.ts` | `./xai`, `./xai/edge` |
| `GoogleVertexXaiModelId` | type | `src/xai/google-vertex-xai-options.ts` | `./xai`, `./xai/edge` |
| `GoogleVertexXaiProvider` | type | `src/xai/google-vertex-xai-provider-node.ts` | `./xai`, `./xai/edge` |
| `GoogleVertexXaiProviderSettings` | type | `src/xai/google-vertex-xai-provider-node.ts` | `./xai`, `./xai/edge` |
| `VERSION` | value | `src/version.ts` | `.` |
| `vertex` | value | `src/google-vertex-provider-node.ts` | `.`, `./edge` |
| `vertexAnthropic` | value | `src/anthropic/google-vertex-anthropic-provider-node.ts` | `./anthropic`, `./anthropic/edge` |
| `vertexMaas` | value | `src/maas/google-vertex-maas-provider-node.ts` | `./maas`, `./maas/edge` |
