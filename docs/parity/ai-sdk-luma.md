# @ai-sdk/luma

- Version: 2.0.33
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/luma`
- Kotlin parity area: `:aisdk-provider-luma`
- Current parity status: ported: createLuma/luma, LumaProviderSettings, LumaImageModelOptions/LumaImageProviderOptions, async image generation, polling, image download, seed/size warnings, provider-option passthrough, URL reference images, character/style/modify_image reference mapping, and mask/base64 rejection are represented as a Kotlin facade folded into the root module; VERSION is exposed as LUMA_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/luma/src/index.ts` | 8 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createLuma` | value | `src/luma-provider.ts` | `.` |
| `luma` | value | `src/luma-provider.ts` | `.` |
| `LumaErrorData` | type | `src/luma-image-model.ts` | `.` |
| `LumaImageModelOptions` | type | `src/luma-image-model.ts` | `.` |
| `LumaImageProviderOptions` | type | `src/luma-image-model.ts` | `.` |
| `LumaProvider` | type | `src/luma-provider.ts` | `.` |
| `LumaProviderSettings` | type | `src/luma-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
