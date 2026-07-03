# Evolution Policy

This document defines how `aisdk-kotlin` changes after publication. The
interface inventory remains in [INTERFACE_CONTRACT.md](../INTERFACE_CONTRACT.md);
this file defines the release-stage and deprecation rules around that surface.

## Release Stages

### Alpha

Alpha releases prove shape and feasibility. Public APIs can still move, but
breaking changes must be called out in the changelog and should preserve a
clear migration path when the affected API has documented users.

### Beta

Beta releases are documented, tested, and production-encouraged. The public
surface is expected to change only when a bug, safety issue, or serious
evolvability problem justifies it. Additive changes are preferred; source,
binary, and behavioral breaks require explicit changelog and interface-contract
updates.

### Release Candidate

Release candidates are stable candidates for the next stable line. Only release
blockers should change public API or behavior. Non-blocking cleanup waits for a
later minor release.

### Stable

Stable releases follow semantic versioning. Patch releases fix bugs without
breaking source, binary, or documented behavior. Minor releases add compatible
capability and may deprecate APIs. Major releases can remove deprecated APIs or
make breaking dependency/API moves.

## Deprecation Timeline

Deprecations move in staged releases so consumers can see and act on them:

1. `WARNING` for at least one minor release, with replacement guidance.
2. `ERROR` for at least one later minor release when the replacement has been
   available long enough to migrate.
3. Removal no earlier than the next major release.

Emergency removals for security, legal, or data-corruption issues are allowed,
but must be explained in the changelog.

## Kotlin Version Policy

The SDK publishes Kotlin Multiplatform artifacts, so Kotlin compiler, standard
library, and Gradle plugin compatibility is part of the consumer contract. The
exact supported consumer Kotlin range and update cadence are planned in BP-02;
until that lands, do not infer a compatibility range from this document.

## Related Compatibility Rules

The interface contract carries API-specific compatibility rules that should not
be duplicated here:

- Ktor client types appear in the public ABI, so a future Ktor major-version
  bump is treated as consumer-visible breaking change.
- SDK sealed hierarchies can grow new leaves in minor releases; consumer
  `when` expressions should keep an `else` branch unless the application
  intentionally recompiles and audits exhaustiveness on every SDK release.
