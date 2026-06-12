# @ai-sdk/prodia

- Version: 1.0.33
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.202/packages/prodia`
- Kotlin parity area: `:aisdk-provider-prodia`
- Current parity status: ported: createProdia/prodia, ProdiaProviderSettings, Prodia language/image/video model aliases, Prodia image provider option alias, JSON and multipart job request paths, multipart job/output response parsing, language text/image output mapping, image generation options, video generation options, provider metadata, Prodia auth/user-agent behavior, and unsupported embedding errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as PRODIA_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/prodia/src/index.ts` | 12 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createProdia` | value | `src/prodia-provider.ts` | `.` |
| `prodia` | value | `src/prodia-provider.ts` | `.` |
| `ProdiaImageModelId` | type | `src/prodia-image-settings.ts` | `.` |
| `ProdiaImageModelOptions` | type | `src/prodia-image-model.ts` | `.` |
| `ProdiaImageProviderOptions` | type | `src/prodia-image-model.ts` | `.` |
| `ProdiaLanguageModelId` | type | `src/prodia-language-model-settings.ts` | `.` |
| `ProdiaLanguageModelOptions` | type | `src/prodia-language-model.ts` | `.` |
| `ProdiaProvider` | type | `src/prodia-provider.ts` | `.` |
| `ProdiaProviderSettings` | type | `src/prodia-provider.ts` | `.` |
| `ProdiaVideoModelId` | type | `src/prodia-video-model-settings.ts` | `.` |
| `ProdiaVideoModelOptions` | type | `src/prodia-video-model.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
