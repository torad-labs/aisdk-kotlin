# CLAUDE.md — engineering policy for `ai.torad:torad-aisdk`

Project-specific rules for anyone (human or agent) working in this repo. These
are enforced where noted; treat them as binding, not advisory.

This is a **published, ABI-validated Kotlin Multiplatform library**: it runs
`explicitApi()` and commits Kotlin ABI dumps (`api/*.api`, `api/*.klib.api`).
The public API is a contract — once `0.3.0-beta01` ships, breaking it costs
consumers a recompile (or a `NoSuchMethodError`). Optimize for *evolvability*,
not for matching any other SDK's surface.

## Public value types — do NOT use `data class` in the public API

**Decision (2026-06-30, research-backed). Default: `@Poko class`, not `data class`.**

A public `data class` is an ABI-evolvability trap. Adding a constructor field is
a **binary-incompatible** change in two independent ways: the synthesized
constructor signature changes *and* the synthesized `copy()` signature changes
(and `componentN()` shifts unless you only append). `@JvmOverloads`, default
arguments, and `@ConsistentCopyVisibility` do **not** rescue an evolving public
`data class` — already-compiled callers hit `NoSuchMethodError` at link time.

This is not a fringe position. It is what the most binary-compatibility-conscious
Kotlin/JVM SDKs do, including the two most directly comparable to this one:

- **JetBrains' own** library-author guide has a section titled *"Avoid using data
  classes in your API"* — <https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html>
- **OpenAI** and **Anthropic** Java/Kotlin SDKs model response/result types as
  **non-data immutable classes + builders + `JsonField` + `additionalProperties`**,
  not data classes. (anthropic-sdk-java `Message.kt`; openai-java `*Params`/response types.)
- **AWS SDK for Kotlin** has a design-doc section *"Why not data classes?"*;
  **OkHttp/Square** use regular class + Builder; **AndroidX** "strongly
  discourages" data classes in library APIs.
- **Jake Wharton, "Public API challenges in Kotlin"** — the canonical reference:
  avoid `data`; hand-write or generate `equals`/`hashCode`/`toString`.
  <https://jakewharton.com/public-api-challenges-in-kotlin/>

### The rule

- **Growable read-only types** (results, responses, metadata, usage, step
  results — anything the library *produces* and consumers *read*): use
  **`@Poko class`** (the drewhamilton/poko KMP compiler plugin). It generates
  `equals`/`hashCode`/`toString` but deliberately omits `copy()`/`componentN()`,
  so fields can be appended forever without an ABI break. Consumers almost never
  `copy()` a result, so the cost is near zero.
- **Construct-types** that consumers build (settings/params/options/config):
  front them with a **builder/DSL** so a positional constructor is never frozen
  into the ABI (the `CallSettingsBuilder` pattern). The backing class is internal
  detail.
- **`data class` is allowed ONLY for genuinely-frozen, small, wire-shaped value
  types** you are confident will never grow (Jake Wharton's "2D point" carve-out
  — e.g. a 2-field id/ref). "Probably won't change" is not "won't change." When
  unsure, use `@Poko`.

### Enforcement (do not bypass)

`ci-gate.sh` runs the **public data-class budget gate**
(`.claude/hooks/rules/detect-public-data-class-budget.py` + `data-class-budget.json`)
in pre-commit and CI. It counts public `data class` declarations in
`src/commonMain/kotlin` and **fails if the count rises above the budget** — so a
new public `data class` is blocked with a pointer back to this section. The
budget is a one-way ratchet: re-seed it *downward only* with
`--update` after demoting a type to `@Poko`. The grandfathered existing set is
being migrated case-by-case (see `docs/data-class-audit.md` / backlog BL-058/A1).
Never raise the budget to land a new data class; demote one instead.

## API shape — idiomatic Kotlin, not a Vercel transliteration

This is a green-room Kotlin port, not a TypeScript translation. **No loose
top-level functions** for the public entry points — they are hard to discover,
test, and debug. Use structured, discoverable APIs: `TextGenerator(model).generate(...)`,
object-qualified members (`Embedding.embed`, `ImageGeneration.generateImage`),
PascalCase factories (`Tool(...)`, `Output.obj(...)`), and `Agent` subclasses.
Docs and examples must match this real API, not Vercel's loose-function ergonomics.

## Scope

This library targets Kotlin/KMP consumers (JVM, Android, Native/iOS). It is
**not** trying to serve JavaScript `@ai-sdk/react` clients — anyone needing a
React frontend should use the Vercel AI SDK directly. The UI message-stream
types exist for Kotlin hosts; Kotlin encode↔decode round-trips, and JS-client
wire interop is explicitly out of scope.

## Working agreement

- Every commit must pass `bash .claude/hooks/rules/ci-gate.sh` (the pre-commit
  hook runs it). Never `--no-verify`, never a gate-skip env. A misfiring gate
  gets fixed, not bypassed.
- Public API changes regenerate the ABI dumps (`./gradlew updateKotlinAbi`) and
  update `CHANGELOG.md` + `INTERFACE_CONTRACT.md` (the API-review gate expects it).
- Match the surrounding code's idioms; the architecture gate (ast-grep rules in
  `.claude/hooks/rules/kotlin/`) encodes many of them.
