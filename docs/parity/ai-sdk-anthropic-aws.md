# @ai-sdk/anthropic-aws

- Version: 1.0.6
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.204/packages/anthropic-aws`
- Kotlin parity area: `:aisdk-provider-anthropic-aws`
- Current parity status: ported: createAnthropicAws/anthropicAws, AnthropicAwsProviderSettings, AnthropicAwsCredentials alias, AWS-hosted Anthropic base URL mapping, workspace header, API-key auth, native SigV4 signing, Anthropic Messages generate/stream reuse, Anthropic hosted tool descriptors, unsupported embedding/image errors, and user-agent behavior are represented as a Kotlin facade folded into the root module; VERSION is exposed as ANTHROPIC_AWS_VERSION until package modules are split

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.204/packages/anthropic-aws/src/index.ts` | 6 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `anthropicAws` | value | `src/anthropic-aws-provider.ts` | `.` |
| `AnthropicAwsCredentials` | type | `src/anthropic-aws-fetch.ts` | `.` |
| `AnthropicAwsProvider` | type | `src/anthropic-aws-provider.ts` | `.` |
| `AnthropicAwsProviderSettings` | type | `src/anthropic-aws-provider.ts` | `.` |
| `createAnthropicAws` | value | `src/anthropic-aws-provider.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
