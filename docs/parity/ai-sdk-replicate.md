# @ai-sdk/replicate

- Version: 2.0.35
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.204/packages/replicate`
- Kotlin parity area: `:aisdk-provider-replicate`
- Current parity status: ported: createReplicate/replicate, ReplicateProviderSettings, image/video model id and option surfaces, versioned and unversioned prediction routing, prefer wait headers, image output downloads, Flux 2 multi-image warnings, data-URI file conversion, video polling, URL video outputs, prediction metadata, and Replicate error parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as REPLICATE_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.204/packages/replicate/src/index.ts` | 10 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createReplicate` | value | `src/replicate-provider.ts` | `.` |
| `replicate` | value | `src/replicate-provider.ts` | `.` |
| `ReplicateImageModelOptions` | type | `src/replicate-image-model.ts` | `.` |
| `ReplicateImageProviderOptions` | type | `src/replicate-image-model.ts` | `.` |
| `ReplicateProvider` | type | `src/replicate-provider.ts` | `.` |
| `ReplicateProviderSettings` | type | `src/replicate-provider.ts` | `.` |
| `ReplicateVideoModelId` | type | `src/replicate-video-settings.ts` | `.` |
| `ReplicateVideoModelOptions` | type | `src/replicate-video-model.ts` | `.` |
| `ReplicateVideoProviderOptions` | type | `src/replicate-video-model.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
