# Coding Agents

This page is for coding agents working on AI SDK Kotlin. It lists the local
sources of truth, commands, and boundaries needed to avoid stale assumptions.

## Read First

- `README.md`: project summary, install path, included feature areas.
- `docs/wiki/README.md`: human docs navigation.
- `llms.txt`: compact project context for agents.
- `docs/AISDK_PORT.md`: v6-to-KMP mapping and intentional deviations.
- `docs/AISDK_PORT_DECISIONS.md`: locked architecture decisions.
- `docs/KOTLIN_SDK_BEST_PRACTICES.md`: Kotlin/KMP engineering standard.
- `docs/parity/README.md`: generated package/export parity gate.

## Source Map

| Question | Inspect |
|---|---|
| Text generation and streaming | `Generate.kt`, `KotlinApi.kt` |
| Agents and loop behavior | `Agent.kt`, `ToolLoopAgent.kt`, `ToolLoopAgentEngine.kt` |
| Tool definitions and schemas | `Tool.kt`, `ToolApproval.kt`, `ToolCallRepair.kt` |
| Messages and stream events | `ModelMessage.kt`, `Streaming.kt` |
| UI messages and chat | `ui/` |
| Providers | `Provider.kt`, `Gateway.kt`, `providers/` |
| Media, embeddings, reranking | `MediaModels.kt`, `Embedding.kt`, `Rerank.kt` |
| MCP | `MCP.kt` |
| Middleware | `Middleware.kt`, `middleware/` |
| Telemetry and DevTools | `Telemetry.kt`, `DevTools.kt` |
| Tests and examples | `src/commonTest/kotlin/ai/torad/aisdk` |

## Reference Workflow

When changing upstream parity:

```sh
node tools/check-ai-sdk-reference.mjs
node tools/generate-parity-ledger.mjs --check
```

When changing runtime behavior:

```sh
./gradlew jvmTest
```

Before merging broad changes:

```sh
./gradlew check
```

Before release:

```sh
./gradlew check publishToMavenLocal
```

## Safe Edit Rules

- Prefer existing APIs and local patterns over new abstractions.
- Treat generated parity ledgers as generated release gates.
- Add focused tests for behavior changes.
- Keep provider quirks in provider facades, settings, transports, or
  middleware.
- Keep platform-specific APIs out of `commonMain` unless behind an interface
  or `expect`/`actual` boundary.
- Avoid live provider calls in tests.
- Use fake transports, deterministic mock models, and Ktor `MockEngine`.

## Docs Rules

Wiki pages should teach users how to build with the current Kotlin API. Keep
architecture rationale in `AISDK_PORT.md` or `AISDK_PORT_DECISIONS.md`.

When a root reference doc is consolidated into wiki, delete the old file and
update `README.md`, `llms.txt`, and wiki links in the same change.

## Security

Never add real provider keys, captured production prompts, customer data, or
DevTools recordings to fixtures. Keep credentials in host configuration and
pass explicit settings or transports into the SDK.
