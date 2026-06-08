# @ai-sdk/gateway

- Version: 3.0.125
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.197/packages/gateway`
- Kotlin parity area: `:aisdk-gateway`
- Current parity status: ported: createGateway/createGatewayProvider/gateway, GatewayProviderSettings, model aliases, gateway hosted tools, auth headers/API-key/OIDC method propagation, metadata caching, credits/spend/generation endpoints, Gateway error classes, and Ktor HTTP transport for language/embedding/image/video/reranking calls and SSE streams are represented as Kotlin facades folded into the root module

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.197/packages/gateway/src/index.ts` | 27 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `createGateway` | value | `src/gateway-provider.ts` | `.` |
| `createGatewayProvider` | value | `src/gateway-provider.ts` | `.` |
| `gateway` | value | `src/gateway-provider.ts` | `.` |
| `GatewayAuthenticationError` | value | `src/errors/index.ts` | `.` |
| `GatewayCreditsResponse` | type | `src/gateway-fetch-metadata.ts` | `.` |
| `GatewayError` | value | `src/errors/index.ts` | `.` |
| `GatewayErrorResponse` | type | `src/errors/index.ts` | `.` |
| `GatewayGenerationInfo` | type | `src/gateway-generation-info.ts` | `.` |
| `GatewayGenerationInfoParams` | type | `src/gateway-generation-info.ts` | `.` |
| `GatewayInternalServerError` | value | `src/errors/index.ts` | `.` |
| `GatewayInvalidRequestError` | value | `src/errors/index.ts` | `.` |
| `GatewayLanguageModelEntry` | type | `src/gateway-model-entry.ts` | `.` |
| `GatewayLanguageModelOptions` | type | `src/gateway-provider-options.ts` | `.` |
| `GatewayLanguageModelSpecification` | type | `src/gateway-model-entry.ts` | `.` |
| `GatewayModelEntry` | type | `src/gateway-model-entry.ts` | `.` |
| `GatewayModelId` | type | `src/gateway-language-model-settings.ts` | `.` |
| `GatewayModelNotFoundError` | value | `src/errors/index.ts` | `.` |
| `GatewayProvider` | type | `src/gateway-provider.ts` | `.` |
| `GatewayProviderOptions` | type | `src/gateway-provider-options.ts` | `.` |
| `GatewayProviderSettings` | type | `src/gateway-provider.ts` | `.` |
| `GatewayRateLimitError` | value | `src/errors/index.ts` | `.` |
| `GatewayRerankingModelId` | type | `src/gateway-reranking-model-settings.ts` | `.` |
| `GatewayResponseError` | value | `src/errors/index.ts` | `.` |
| `GatewaySpendReportParams` | type | `src/gateway-spend-report.ts` | `.` |
| `GatewaySpendReportResponse` | type | `src/gateway-spend-report.ts` | `.` |
| `GatewaySpendReportRow` | type | `src/gateway-spend-report.ts` | `.` |
| `GatewayVideoModelId` | type | `src/gateway-video-model-settings.ts` | `.` |
