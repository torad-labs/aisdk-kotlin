# @ai-sdk/black-forest-labs

- Version: 1.0.35
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.202/packages/black-forest-labs`
- Kotlin parity area: `:aisdk-provider-black-forest-labs`
- Current parity status: ported: createBlackForestLabs/blackForestLabs, BlackForestLabsProviderSettings, BlackForestLabsImageModelOptions/BlackForestLabsImageProviderOptions, async image submit/poll/download, x-key authentication, size-to-aspect-ratio warnings, input image and mask mapping, BFL provider-option snake_case mapping, poll timeout/interval controls, and provider metadata are represented as a Kotlin facade folded into the root module; VERSION is exposed as BLACK_FOREST_LABS_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.202/packages/black-forest-labs/src/index.ts` | 9 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `blackForestLabs` | value | `src/black-forest-labs-provider.ts` | `.` |
| `BlackForestLabsAspectRatio` | type | `src/black-forest-labs-image-settings.ts` | `.` |
| `BlackForestLabsImageModelId` | type | `src/black-forest-labs-image-settings.ts` | `.` |
| `BlackForestLabsImageModelOptions` | type | `src/black-forest-labs-image-model.ts` | `.` |
| `BlackForestLabsImageProviderOptions` | type | `src/black-forest-labs-image-model.ts` | `.` |
| `BlackForestLabsProvider` | type | `src/black-forest-labs-provider.ts` | `.` |
| `BlackForestLabsProviderSettings` | type | `src/black-forest-labs-provider.ts` | `.` |
| `createBlackForestLabs` | value | `src/black-forest-labs-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
