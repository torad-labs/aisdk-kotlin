# @ai-sdk/bytedance

- Version: 1.0.14
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.195/packages/bytedance`
- Target Kotlin module: `:aisdk-provider-bytedance`
- Current parity status: ported: createByteDance/byteDance, ByteDanceProviderSettings, ByteDanceVideoModelId, ByteDanceVideoProviderOptions, video task creation, status polling, URL video output, resolution mapping, reference image/video/audio content mapping, provider-option snake_case mapping, warnings, task metadata, and ByteDance error parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as BYTEDANCE_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.195/packages/bytedance/src/index.ts` | 7 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `byteDance` | value | `src/bytedance-provider.ts` | `.` |
| `ByteDanceProvider` | type | `src/bytedance-provider.ts` | `.` |
| `ByteDanceProviderSettings` | type | `src/bytedance-provider.ts` | `.` |
| `ByteDanceVideoModelId` | type | `src/bytedance-video-settings.ts` | `.` |
| `ByteDanceVideoProviderOptions` | type | `src/bytedance-video-model.ts` | `.` |
| `createByteDance` | value | `src/bytedance-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
