# AI SDK Kotlin Wiki

AI SDK Kotlin is a Kotlin Multiplatform port of Vercel AI SDK v6 public
feature areas. It is a green-room Kotlin API, not an official Vercel package
and not a TypeScript source translation.

This wiki mirrors the useful navigation shape of the upstream AI SDK docs:
start with installation, pick a provider, learn the Core primitives, build
agents, connect UI streams, then verify and release.

## Start Here

- [Getting Started](getting-started.md): install the artifact, choose a
  provider, and run a first tool-loop agent.
- [Core](core.md): text generation, streaming, structured output, embeddings,
  media model families, middleware, telemetry, and errors.
- [Agents](agents.md): `Agent`, `ToolLoopAgent`, tools, loop control,
  lifecycle hooks, approval, subagents, and memory boundaries.
- [Providers and Models](providers.md): gateway, OpenAI-compatible providers,
  provider registry, package facades, and custom provider contracts.
- [UI and Streams](ui-and-streams.md): `Flow<StreamEvent>`, `UIMessage`,
  chat transports, stream responses, and host rendering.
- [Coding Agents](coding-agents.md): how Codex, Claude Code, OpenCode,
  Cursor, and similar tools should inspect this project safely.
- [Testing and Release](testing-and-release.md): local verification, parity
  checks, CI, publication, and release gates.
- [Troubleshooting](troubleshooting.md): common failures and the fastest
  evidence to inspect.

## Reference Map

- [Interface contract](../../INTERFACE_CONTRACT.md)
- [Usage guide](../AISDK_USAGE.md)
- [Best practices](../AISDK_BEST_PRACTICES.md)
- [Architecture decisions](../AISDK_PORT_DECISIONS.md)
- [Closed port audit](../AISDK_PORT_GAPS.md)
- [Functionality audit](../FUNCTIONALITY_AUDIT.md)
- [Parity ledgers](../parity/README.md)
- [Publishing](../PUBLISHING.md)
- [Local LLM context](../../llms.txt)

## Package Shape

The current publication is a single root artifact:

```kotlin
dependencies {
    implementation("ai.torad:aisdk-kotlin:<version>")
}
```

The API is organized in code as if future artifacts may split along Vercel AI
SDK package boundaries: core, provider contracts, provider-utils, gateway,
OpenAI-compatible, provider facades, MCP, UI, devtools, validation, and test
server helpers. Until that split happens, those package surfaces are folded
into the root module.

## Compatibility Policy

- Kotlin code should depend on `Agent<TContext, TOutput>` and model/provider
  interfaces where possible.
- Provider-specific behavior should live in provider facades, provider options,
  middleware, or host transports, not in application orchestration.
- Browser, Node.js, React, Vue, Svelte, and RSC primitives are mapped to KMP
  equivalents where the underlying runtime concept exists.
- Platform-owned work such as secure key storage, process spawning, HTTP
  client selection, UI rendering, and persistent DevTools storage belongs in
  host apps or future platform modules.

## Upstream Reference

Parity is verified against the pinned Vercel AI SDK v6 reference stored under
`.reference/vercel-ai-sdk-ai-6.0.195`. Regenerate ledgers after refreshing the
reference:

```sh
node tools/check-ai-sdk-reference.mjs
node tools/generate-parity-ledger.mjs --check
```
