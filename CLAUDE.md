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
  into the ABI. The complete ABI-evolvable pattern (note: a public `data class`
  with a DSL bolted on, like the original `CallSettings`, does NOT fix the trap —
  its public constructor + `copy()` are still frozen):
  ```kotlin
  @Poko public class CohereProviderSettings internal constructor(
      public val baseURL: String = "...",
      public val apiKey: String? = null,
      public val headers: Map<String, String> = emptyMap(),
  )
  public class CohereProviderSettingsBuilder internal constructor() {
      // private var + public SETTER METHODS — matches CallSettingsBuilder and avoids
      // the no-public-mutable-var gate (a public var here would fail the gate).
      private var baseURL: String = "..."
      private var apiKey: String? = null
      private var headers: Map<String, String> = emptyMap()
      public fun baseURL(value: String) { baseURL = value }
      public fun apiKey(value: String?) { apiKey = value }
      public fun headers(value: Map<String, String>) { headers = value }
      internal fun build() = CohereProviderSettings(baseURL, apiKey, headers)
  }
  public fun CohereProviderSettings(block: CohereProviderSettingsBuilder.() -> Unit = {}):
      CohereProviderSettings = CohereProviderSettingsBuilder().apply(block).build()
  ```
  Result: no public positional constructor (it's `internal`), no `copy()`/`componentN()`
  (`@Poko`), construction via the DSL factory `CohereProviderSettings { apiKey("...") }`.
  Adding a field later = add a builder property + an internal-constructor param — no ABI
  break. Migrate every existing `CohereProviderSettings(apiKey = ...)` call site to the DSL.
  - **Construct-types that hold a FUNCTION or other non-value field** (a transform
    lambda, an auth/id provider, a transport object) are NOT value types — value
    equality on a closure is meaningless. Make these a plain **regular class**
    (NOT `@Poko`, NOT `data class`) + `internal` constructor + builder + DSL factory.
    A regular class already has no `copy()`/`componentN()`, and the internal
    constructor removes the public positional ctor, so it is just as ABI-evolvable —
    it just keeps Kotlin's honest identity equality instead of a fake or field-skipped
    value equality. Do NOT reach for `@Poko.Skip` to force value semantics onto a
    closure-holder; that hides a field from `equals` and lies about the type.
- **`data class` is allowed ONLY for genuinely-frozen, small, wire-shaped value
  types** you are confident will never grow (Jake Wharton's "2D point" carve-out
  — e.g. a 2-field id/ref). "Probably won't change" is not "won't change." When
  unsure, use `@Poko`.

### Enforcement (do not bypass)

`ci-gate.sh` runs the **public data-class budget gate**
(`.claude/hooks/rules/detect-public-data-class-budget.py` + repo-root
`data-class-budget.json`) in pre-commit and CI. It counts public `data class`
declarations in `src/commonMain/kotlin` and **fails if the count rises above the
budget** — so a new public `data class` is blocked with a pointer back to this
section. The budget is a one-way ratchet: re-seed it *downward only* with
`--update` after demoting a type to `@Poko`. The current enforced floor is
recorded in the measurements ledger as `[meas: public_data_class_floor]`;
nested/internal/function-local data classes are not budgeted as public ABI. The
grandfathered existing set is being migrated case-by-case (see
`docs/data-class-audit.md` / backlog BL-058/A1). Never raise the budget to land
a new data class; demote one instead.

## Gate misfires — fix the gate, not the result

A gate misfire is a defect in the trust boundary. It is not permission to route
around the gate, weaken the test, or land a fake-green commit. Misfires invite
dodges; the sanctioned repair path is:

1. Reproduce the exact false positive or inert rule in the smallest fixture.
2. Fix the rule, hook, or detector that made the wrong claim.
3. Add or update the fixture that proves both directions.
4. Re-run `bash .claude/hooks/rules/ci-gate.sh` and the relevant hook suite.

Ast-grep rule fixtures live in `.claude/hooks/rules/manifest.json` and are
validated by `.claude/hooks/rules/validate_rules.py` in both parse mode and
`--manifest` semantic mode. A rule edit without a fixture is not complete; an
enabled rule that cannot match its bad example is dead code.

Budget gates are one-way ratchets. If a legitimate change grows a tracked file
or exhausts a public-surface budget, re-seed the budget in the same commit as
the growth, with the smallest reviewed increase; when reducing debt, re-seed
downward. The public data-class ratchet lives at repo-root
`data-class-budget.json`; ast-grep fixtures live under `.claude/hooks/rules/`.
Never raise a budget to hide accidental drift, and never use `--no-verify` or a
skip env to get past a budget failure.

## Measurements — one ledger, measured-only

Measured project numbers live in `dev/measurements.toml` and are changed only
through `dev/measurements_ledger.py`. Do not copy coverage, gate latency, rule
counts, or budget floors into prose; cite keys such as
`[meas: coverage_branch_percent]` or `[meas: ci_gate_wall_clock_s]`. If a value
changes, supersede the current entry and add the new measured entry with date,
HEAD, exact command, and tool-version provenance. Estimates stay out of the
ledger.

## Ast-grep rule authoring — discovery, dedupe, and codemod discipline

The Kotlin rule package (`.claude/hooks/rules/kotlin/*.yaml` + `manifest.json`)
buys project-specific structural invariants, multi-file codemods, and
structural search — NOT generic linting. detekt/ktlint/Android-Lint-shaped
concerns are out of scope for a new rule here; see the dedupe law below.

**Discover and debug with ast-grep itself, not guesses.** Kotlin is a
built-in ast-grep language (`language: kotlin`, extensions `kt`/`kts`) — no
grammar compilation, no `expandoChar`, no `customLanguages`. Before writing
any `kind:`-based rule, dump the concrete syntax tree of a representative
snippet first (`ast-grep run --pattern '<snippet>' --lang kotlin
--debug-query=cst`, or the ast-grep MCP tool `dump_syntax_tree`) to confirm
the real node kind — ast-grep does not error on a kind that never occurs, so
a guessed kind name silently produces a rule that never matches. Then prove
the rule matches its bad example and skips its good example
(`test_match_code_rule` or `ast-grep scan --inline-rules`) before writing the
manifest entry. Use `mcp__ast-grep__find_code_by_rule` (or `ast-grep scan`)
against the real tree to check for false positives before proposing
error severity.

**Relational fields (`inside:`/`has:`/`precedes:`/`follows:`) need a
deliberate `stopBy`, not a copy-pasted one.** `stopBy: end` searches every
ancestor/descendant; omitting it checks only the immediate parent/child. Most
of this package's checks mean "matches if this ancestor exists ANYWHERE
above" (visibility modifiers, enclosing package, enclosing function) and
need `stopBy: end` — but a handful of existing rules (`no-var-in-object-declaration`,
`no-mutable-var-in-enum`, `no-top-level-mutable-var`) correctly omit it,
because their invariant is specifically about DIRECT nesting (a `var` must be
an immediate child of the object/enum/file body, not nested one intervening
scope deeper) and `stopBy: end` there would silently over-match through
intervening scopes. Decide from the CST which one your invariant needs; do
not default to "always add stopBy: end" without checking whether the
invariant is transitive or direct.

**One owner per invariant (dedupe law).** Before adding a rule, check whether
detekt (`detekt.yml` + `detekt-rules/`), Konsist
(`src/jvmTest/kotlin/ai/torad/aisdk/arch/`), an existing ast-grep rule, or a
python detector (`.claude/hooks/rules/detect-*.py`) already owns the
invariant — `docs/ast-grep-rule-audit.md` is the standing dedupe table;
consult and extend it before adding a rule. Two detectors independently
re-checking the identical shape at the identical layer is drift risk (they
can silently disagree), not defense in depth — that duplication is what this
law forbids. It is correct and expected, by contrast, to add an ast-grep
(edit-time, Claude-only) rule that mirrors an existing Konsist or detekt
invariant when that invariant is genuinely single-file/structural and
currently has no edit-time layer: per `docs/enforcement-layers.md`'s
four-layer model, enforcing the SAME invariant at an EARLIER layer than it
has today closes a real gap; it is not the duplication the dedupe law bans.

**Codemod discipline.** ast-grep's `fix:` (rewrite) is for mechanical
migrations only — a syntactic transform with one obviously-correct output
(renames, import-path swaps, a fixed-default parameter insertion). Never
attach `fix:` to a safety- or architecture-invariant rule in this package
(e.g. `no-globalscope`, `no-not-null-assertion`, an import-boundary rule):
the correct response to "you used a dangerous pattern" is almost always a
redesign a human chooses, not a mechanical rewrite a codemod can safely apply
unattended. Run a sanctioned codemod via
`ast-grep scan --update-all --rule <rule.yaml> <dirs>`; use `--interactive`
to review each hunk when the transform is not mechanical everywhere it
matches.

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
