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

The SDK is built with the current Kotlin producer toolchain, but JVM and
Android consumers should not be forced to update their Kotlin plugin solely
because this project did. The build disables Kotlin Gradle Plugin's default
stdlib dependency and publishes the oldest stdlib accepted by the public
dependency floor.

For `0.3.0-beta01`, JVM and Android consumers are supported with Kotlin
`2.3.21` or newer. The artifact is produced with Kotlin `2.4.0`, but the SDK
source is compiled with Kotlin `2.3` language/API settings and publishes
`kotlin-stdlib:2.3.21`. The public dependency floor is compatible with that
choice: Ktor `3.5.0` declares `kotlin-stdlib:2.3.21`, kotlinx.serialization
`1.11.0` declares `2.3.20`, and kotlinx.coroutines `1.11.0` declares `2.2.20`.
Library source cannot use Kotlin `2.4` language features while this pin is in
place; that failure is intentional because it protects the documented consumer
floor.

Kotlin Multiplatform klib consumers are separate: they need the Kotlin Gradle
Plugin version that can consume this release's klib metadata. For
`0.3.0-beta01`, that floor is Kotlin `2.4.0` or a compatible newer KGP because
the published klibs are produced by Kotlin `2.4.0`.

Raising the JVM/Android consumer Kotlin floor, changing the klib KGP floor, or
moving the producer toolchain in a way that changes published metadata is a
consumer-visible compatibility event. It must be called out in the changelog
and this policy.

## Related Compatibility Rules

The interface contract carries API-specific compatibility rules that should not
be duplicated here:

- Ktor client types appear in the public ABI, so a future Ktor major-version
  bump is treated as consumer-visible breaking change.
- SDK sealed hierarchies can grow new leaves in minor releases; consumer
  `when` expressions should keep an `else` branch unless the application
  intentionally recompiles and audits exhaustiveness on every SDK release.
