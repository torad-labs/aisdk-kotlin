# Coding Agents

This page is for coding agents working on AI SDK Kotlin. It gives the local
source of truth, commands, and boundaries needed to avoid stale assumptions.

## Local Sources Of Truth

- `README.md`: high-level feature list and install path.
- `docs/wiki/README.md`: docs navigation.
- `llms.txt`: compact project context for agents.
- `docs/AISDK_USAGE.md`: task-oriented examples.
- `docs/AISDK_BEST_PRACTICES.md`: application patterns.
- `docs/FUNCTIONALITY_AUDIT.md`: verified feature surface and known extension
  boundaries.
- `docs/parity/README.md`: package/export parity gate.
- `src/commonMain/kotlin/ai/torad/aisdk`: public runtime source.
- `src/commonTest/kotlin/ai/torad/aisdk`: behavior examples and regression
  tests.

## Reference Workflow

When changing ported behavior:

```sh
node tools/check-ai-sdk-reference.mjs
node tools/generate-parity-ledger.mjs --check
```

When changing Kotlin runtime behavior:

```sh
./gradlew jvmTest
```

Before publishing or merging broad changes:

```sh
./gradlew allTests
./gradlew publishToMavenLocal
```

## Safe Edit Rules

- Prefer existing APIs and local patterns over new abstractions.
- Treat generated parity ledgers as release gates, not prose-only notes.
- Add focused tests for every behavior change.
- Keep provider quirks in provider facades or middleware.
- Keep platform-specific details out of `commonMain` unless represented by an
  explicit interface.
- Avoid live network tests. Use fake transports, deterministic mock models, or
  Ktor `MockEngine`.

## Where To Inspect

| Question | Inspect |
|---|---|
| Is an upstream package export represented? | `docs/parity/<package>.md` |
| Is a feature intentionally KMP-mapped? | `docs/AISDK_PORT_DECISIONS.md` |
| Is a missing item still open? | `docs/FUNCTIONALITY_AUDIT.md` |
| How should app code use the SDK? | `docs/AISDK_USAGE.md` and `docs/wiki` |
| How are stream events rendered? | `src/commonMain/kotlin/ai/torad/aisdk/ui` |
| How are model calls mocked? | `src/commonMain/kotlin/ai/torad/aisdk/providers` |

## Security

Never add real provider keys, captured production prompts, raw customer data,
or DevTools recordings to fixtures. Keep credentials in host configuration and
pass only explicit settings or transports into the SDK.
