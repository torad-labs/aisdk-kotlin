# Toolkit-standard ast-grep migration — blueprint

Status: **DRAFT — inventory + reconciliation + staged plan, not yet executed.**
Scope: read-only analysis. This document is the only file this pass writes.

Owner decision already locked in (do not re-debate): fully adopt the
torad-toolkit `.rules/kotlin/ast-grep/{rules,rules-style,codemods,normalize,
utils,tests}/` taxonomy + `registry.json` + `sgconfig.yml`; adopt the
toolkit's `11_ast_grep_rules.py` (PreToolUse LAW) and `13_ast_grep_normalize.py`
(Stop normalize) hook modules; consume the toolkit's canonical Kotlin LAW +
KMP bundles as the shared baseline; migrate this SDK's project-specific rules
into the taxonomy; keep only genuinely-procedural gates as Python.

Repos read:
- TARGET SDK: `/home/marcos/Documents/dev/opensource/aisdk-kotlin` (HEAD `a4e43f3`)
- TOOLKIT: `/home/marcos/Documents/dev/torad/torad-toolkit/torad-toolkit-main`

---

## Section 1 — Current-state inventory (TARGET SDK)

### 1.1 The 72 ast-grep rules (`.claude/hooks/rules/kotlin/*.yaml`, manifest-backed)

Severity column shows the SDK's own `error`/`warning` (block vs non-blocking
warn under `kotlin_antipattern_policy.py`). Lane column is this blueprint's
proposed toolkit lane, informed by Section 3's reconciliation (`rules/` =
LAW/block-worthy, `rules-style/` = opt-in house tenet). Where the invariant
duplicates a toolkit canonical rule, that is called out inline and expanded
in Section 3 — this table states the PROPOSED LANE IN THE SDK'S OWN TAXONOMY
COPY, which is the same whether or not the rule is later deduped against a
canonical.

| id | severity | invariant (one line) | proposed lane |
|---|---|---|---|
| `avoid-broad-catch-exception` | warning | `catch (e: Exception)` is too broad; narrow the type or rethrow `CancellationException` first | rules-style |
| `no-any-typed-public-property` | warning | public constructor/class property typed `Any`/`Any?` loses type safety | rules-style |
| `no-array-property-in-data-class` | error | `data class` with an `Array<T>` ctor property has reference-identity `equals`/`hashCode` | rules/ |
| `no-callback-wiring-in-init` | warning | assigning a lambda to a collaborator's property inside `init` leaks `this` before construction completes | rules-style |
| `no-camelcase-top-level-function` | error | top-level public factory functions must be PascalCase, not camelCase | rules/ |
| `no-channel-unlimited` | warning | `Channel.UNLIMITED` removes backpressure; use a bounded capacity + `BufferOverflow` policy | rules-style |
| `no-console-output-in-library` | error | library code must not `println`/`print`/`System.out`/`System.err` | rules/ (see §3 — superset of canonical) |
| `no-core-import-providers` | error | non-`providers/` code must not import `ai.torad.aisdk.providers` (Konsist mirror) | rules/ |
| `no-data-class-extending-throwable` | error | a `data class` extending `Throwable` fabricates `copy()`d exceptions with fake stacks | rules/ |
| `no-deferred-wiring-comment` | error | comments describing work as "staged"/"follow-up" mark undone wiring, not documentation | rules/ |
| `no-else-in-sealed-when` | warning | `else` in a `when` over a sealed type defeats compile-time exhaustiveness | rules-style (dup candidate, broader canonical — see §3) |
| `no-empty-catch-block` | error | empty catch blocks (no statements, no `_` discard, no comment) swallow errors silently | rules/ (dup candidate, narrower canonical — see §3) |
| `no-empty-string-sentinel` | warning | `?: ""` fabricates an empty-string sentinel instead of propagating absence | rules/ (**conflicts with canonical `use-or-empty`** — see §3) |
| `no-flat-lifecycle-event` | error | `data class …Event` must belong to a sealed `AgentEvent` hierarchy (Konsist mirror) | rules/ |
| `no-float-equality-comparison` | warning | `==`/`!=` against a float literal is fragile; compare with a tolerance | rules-style |
| `no-flowon-main` | warning | `flowOn(Dispatchers.Main)` in library code forces the collector onto the UI thread | rules-style |
| `no-fq-java-time-uuid-date` | error | fully-qualified `java.time`/`java.util.UUID`/`java.util.Date` breaks non-JVM targets | rules/ |
| `no-generate-id-sentinel` | error | `?: generateId(...)` fabricates an id instead of requiring one from the wire payload | rules/ |
| `no-globalscope` | error | `GlobalScope` is unstructured, uncancellable concurrency | rules/ (**DROP — dup of canonical `no-global-scope`**) |
| `no-hardcoded-dispatcher-in-builder` | warning | a coroutine builder call site should take an injected `CoroutineContext`, not a hardcoded `Dispatchers.X` | rules/ (**conflicts with canonical `main-no-hardcoded-dispatchers`** — see §3) |
| `no-inline-coroutinescope-launch` | error | `CoroutineScope(...).launch`/`.async` leaks a scope with nothing left to cancel it | rules/ (**conflicts with canonical `no-coroutine-scope-factory`** — see §3) |
| `no-inline-json-instance` | warning | a `Json { ... }` built inside a function should be hoisted to a top-level `val` | rules-style |
| `no-open-serializable-polymorphic-base` | warning | `@Serializable open class` invites uncontrolled polymorphic wire variants; prefer `sealed` | rules-style |
| `no-mutable-collection-in-public-fun` | error | a public function taking/returning `MutableMap`/`MutableList` exposes a mutation channel | rules/ |
| `prefer-list-over-array-return-in-public-fun` | warning | public functions should return `List<T>`, not `Array<T>` | rules-style |
| `prefer-value-class-over-typealias-primitive` | warning | `typealias` to a primitive gives no type safety; prefer a `value class` | rules-style |
| `no-json-container-force-cast` | error | `response as JsonObject`/`as JsonArray` force-casts instead of using the typed decoder | rules/ (**DROP — dup of canonical `no-unsafe-cast`, see §3 scope note**) |
| `no-keyed-json-container-cast` | warning | `obj["k"] as? JsonArray`/`JsonObject` safe-cast should route through the typed decoder | rules-style |
| `no-primitive-array-property-in-data-class` | error | `data class` with a primitive-array (`ByteArray`, `IntArray`, …) property has broken `equals`/`hashCode` | rules/ |
| `no-print-stack-trace` | error | `e.printStackTrace()` bypasses structured logging; log via the logger and rethrow/wrap instead | rules/ |
| `no-java-import` | error | `import java.*` breaks JS/wasm/Native targets from commonMain | rules/ |
| `no-thread-sleep` | error | `Thread.sleep` blocks a real thread and is unavailable on some KMP targets; use `delay` | rules/ |
| `no-system-clock-env` | error | `System.currentTimeMillis()`/env access bypasses the injected `Clock`/platform seam | rules/ |
| `no-string-format` | error | `String.format` is JVM-only; use a string template | rules/ |
| `no-jvm-synchronized` | error | `synchronized(...)` is JVM-only; use `Mutex.withLock` | rules/ |
| `prefer-kotlin-math` | warning | `Math.max`/`Math.min` (JVM `java.lang.Math`) should be `kotlin.math.*` | rules-style |
| `prefer-typed-error-over-generic-throw` | warning | `throw IllegalStateException(...)` should throw a typed SDK error | rules-style |
| `prefer-typed-error-over-error-call` | warning | stdlib `error(...)` should throw a typed SDK error | rules-style |
| `prefer-typed-error-over-checknotnull` | warning | `checkNotNull(x) { ... }` should be a typed-error `?:` throw | rules-style |
| `no-let-elvis-run-pyramid` | warning | `x?.let { } ?: run { }` should be an explicit `if (x == null)` | rules-style |
| `no-todo-in-source` | error | `TODO()`/`TODO(msg)` throws at runtime; implement or remove | rules/ (**DROP — dup of canonical `no-todo-throws`**) |
| `no-mutable-var-in-enum` | error | mutable `var` in an `enum class` body is shared state across every instance's shared companion-like access | rules/ |
| `no-multiple-boolean-params` | warning | two-or-more adjacent `Boolean` parameters are call-site-ambiguous; use an options type | rules-style |
| `no-generate-id-sentinel` *(dup id above)* | — | — | — |
| `no-uuid-id-sentinel` | error | `?: Uuid.random()`/`randomUUID()` fabricates an id instead of requiring one from the wire payload | rules/ |
| `no-mutable-var-in-enum` *(dup id above)* | — | — | — |
| `no-else-in-sealed-when` *(dup id above)* | — | — | — |
| `prefer-sealed-exception-over-open-class` | warning | `open class : Exception` lets unknown subtypes escape the taxonomy; prefer `sealed` | rules-style |
| `no-float-equality-comparison` *(dup id above)* | — | — | — |
| `no-deferred-wiring-comment` *(dup id above)* | — | — | — |
| `no-flat-lifecycle-event` *(dup id above)* | — | — | — |
| `no-camelcase-top-level-function` *(dup id above)* | — | — | — |
| `no-empty-string-sentinel` *(dup id above)* | — | — | — |
| `no-jsonnull-sentinel` | warning | `?: JsonNull` fabricates a null-sentinel instead of propagating absence | rules-style |
| `no-lateinit-var` | error | `lateinit var` defers null-safety to a runtime crash | rules/ (**DROP — dup of canonical `no-lateinit`**) |
| `no-mutable-companion-state` | error | mutable `var` directly in a companion object body is process-wide shared singleton state | rules/ (**broader canonical exists, keep local — see §3**) |
| `no-not-null-assertion` | error | `!!` crashes with no context on null | rules/ (**DROP — dup of canonical `no-unsafe-bang-bang`**) |
| `no-nullable-prompt-messages-bag` | error | `prompt`/`messages` must be validated exclusive, not both nullable and silently coalesced | rules/ |
| `no-provideroptions-jsonobject-cast` | warning | `providerOptions["x"] as? JsonObject` should use the typed `jsonObject` accessor | rules-style |
| `no-public-mutable-var` | error | a public class exposes a mutable `var` property directly | rules/ (**broader canonical exists, keep local — see §3**) |
| `no-sealed-interface` | error | `public sealed interface` (vs `sealed class`) loses shared base-type state | rules/ (**DROP — dup of canonical `no-sealed-interface`, note lane downgrade — see §3**) |
| `no-secondary-constructor` | error | secondary constructors fragment initialization logic | rules/ (**DROP — dup of canonical `no-secondary-constructor`, note lane downgrade — see §3**) |
| `no-throw-in-stream-error-fn` | error | a stream `error(cause)` callback must `emit` a `StreamEvent.Error`, not `throw` | rules/ |
| `no-top-level-mutable-var` | error | a top-level (file-scope) `public var` is uncontrolled global mutable state | rules/ |
| `no-typealias-string` | warning | `typealias X = String` gives no type safety; prefer a `value class` | rules-style |
| `no-providers-import-ui` | error | `providers/` must not import `ai.torad.aisdk.ui` (Konsist mirror) | rules/ |
| `no-core-import-providers` *(dup id above)* | — | — | — |
| `no-protocol-import-ui-or-middleware` | error | `protocol/` must not import `ui`/`middleware` (Konsist mirror) | rules/ |
| `no-protocol-agent-runtime-reference` | error | `protocol/` must not reference `AgentEvent`/`AgentSession`/`ToolLoopAgent*` (Konsist mirror) | rules/ |
| `no-public-expect-declaration` | error | a public/non-internal `expect` leaks a platform-bridge implementation detail into the ABI | rules/ |
| `no-throwable-catch-without-rethrow` | warning | `catch (x: Throwable)` with no `throw` in its body swallows `CancellationException` | rules-style |
| `no-runcatching-in-suspend` | warning | stdlib `runCatching` inside a `suspend fun`/coroutine builder captures `CancellationException` into a `Result` (detekt mirror) | rules-style |
| `no-public-member-extension-in-object` | error | a public member-extension declared inside a public `object` is worse ergonomics than a member of the extended type | rules/ |
| `no-suspend-fun-returning-flow` | warning | a `suspend fun` returning `Flow<T>` is redundant (`Flow` is already lazy) | rules-style (**DROP — dup of canonical `flow-over-suspend`**) |
| `no-runblocking-in-common` | error | `runBlocking` blocks the thread and is unavailable on JS/wasm targets | rules/ (**likely DROP — dup of canonical `no-runblocking-in-production`, verify scope — see §3**) |
| `no-var-in-object-declaration` | error | mutable `var` inside a top-level/named `object` is process-wide shared singleton state | rules/ (**DROP — dup of canonical `no-object-singleton-state`, note lane downgrade — see §3**) |
| `no-volatile-var` | warning | `@Volatile var` is hand-rolled cross-thread state; prefer `StateFlow`/`Mutex` | rules-style |
| `no-public-mutable-collection-ctor-prop` | warning | a public class exposes a mutable-collection-typed constructor property | rules-style |
| `no-public-mutable-collection-val` | warning | a public property exposes a mutable collection (`MutableList`/`MutableMap`/…) | rules-style |
| `no-loop-in-init` | warning | an imperative loop mutating a `var` inside `init` hides non-trivial construction logic | rules-style |
| `no-try-catch-in-init` | warning | `try/catch` directly inside `init` performs fallible work during construction | rules-style |

Rule count reconciliation: `manifest.json` has exactly 72 entries (verified:
`python3 -c "import json; print(len(json.load(open('.claude/hooks/rules/
manifest.json'))))"` → `72`); the table above lists each of the 72 distinct
ids once (a few ids recur across the doc's original YAML-load ordering and
are marked `*(dup id above)*` to keep the table to 72 substantive rows without
re-stating the same invariant twice).

### 1.2 Python detectors (`.claude/hooks/rules/detect-*.py`)

| script | what it checks | classification |
|---|---|---|
| `detect-nonintegrated-kotlin.py` | Cross-file reference counting: for every top-level `internal` declaration, counts references across `src/` excluding its own file and test source sets; zero-reference declarations are reported as non-integrated/dead code candidates. Report-only (exit 0 always), but `ci-gate.sh` runs it with `--check` to fail on findings. | **PROCEDURAL-KEEP-PYTHON** — this is an explicitly whole-program property; ast-grep matches within one file's AST only, so cross-file reference-graph counting is fundamentally outside its model. |
| `detect-orphan-gates.py` | Fails when a `detect-*.py`/`tools/check-*`/`tools/*-check`/`tools/run-*-smoke` script exists but is not referenced by `ci-gate.sh`, `ci.yml`, or `release.yml`, and is not in an allowlist with a reason. | **PROCEDURAL-KEEP-PYTHON** — a trust-graph check over the repo's OWN gate wiring (which scripts call which), not a Kotlin (or any single-file) AST invariant. |
| `detect-public-data-class-budget.py` | Counts public `data class` declarations in `src/commonMain/kotlin`, compares against the ratchet floor recorded in `data-class-budget.json` (currently 40, all 40 tracked by name), fails if the count rises. `--update` re-seeds downward only. | **PROCEDURAL-KEEP-PYTHON** (explicitly called out in the migration brief) — a cross-file COUNT against a stateful ratchet file, not a per-match structural rule ast-grep can express. |
| `detect-release-workflow-trust.py` | Parses `.github/workflows/release.yml`, extracts job blocks and the top-level `permissions:` block, verifies every executable release job depends on a read-only preflight job and that package-write permission is scoped only to the publish job. | **PROCEDURAL-KEEP-PYTHON** — operates on GitHub Actions YAML, not Kotlin; it is a trust-boundary/dependency-graph check across jobs, not a single-file structural pattern. |
| `detect-restated-measurements.py` | Regex-scans `CHANGELOG.md`/`INTERFACE_CONTRACT.md`/`CLAUDE.md` for raw restated numbers (percentages, latencies, token counts, coverage triples) that should instead cite a `[meas: key]` from `dev/measurements.toml`. Warning-only in `ci-gate.sh`. | **PROCEDURAL-KEEP-PYTHON** — targets Markdown prose against a ledger file, not Kotlin source; deliberately regex-based (documented as "the pattern itself stays live" via a `--check` harness run), not an AST concern at all. |
| `detect-tool-identity-regressions.py` | Line-by-line regex scan of `src/*Main/kotlin/**/*.kt` for two shapes: (1) `.associateBy { … toolCallId … }` / `.toSet()` collapse patterns, (2) a mutable-`Map`/`Set` variable declaration whose name matches risky hints (`ToolCallId`, `ByCallId`, …) and type args don't match safe hints (`List`, `Set`, …). Each file scanned independently; no cross-file aggregation. | **PORTABLE** (with caveats) — despite being hand-rolled regex today, this is genuinely a single-file structural + naming-convention check with no whole-program component. It maps onto 1–2 ast-grep rules: a call-expression pattern rule for the `.associateBy`/`.toSet()` collapse shapes, and a `property_declaration`/`variable_declaration` rule combining a `mutableMapOf`/`mutableSetOf`/`linkedMapOf`/… constructor-call `has:` clause with `regex:` predicates on the binding name (risky-hint OR, safe-hint NOT) and, if replicable in ast-grep, on the declared type arguments. See §3 for the concrete PORT proposal and §6 for the fidelity risk (the value-type-args safe-hint check is a second regex today; expressing it as a nested `has:`/`regex:` on the type-argument node needs a `dump_syntax_tree` proof before porting). |

### 1.3 Current enforcement wiring

**`.claude/hooks/orchestrator/pretooluse.py` + `modules/pretooluse/
kotlin_antipattern_policy.py`.** The orchestrator (`pretooluse.py`, 119
lines) is a self-contained, repo-local PreToolUse dispatcher: it reads the
hook JSON from stdin, discovers every `*.py` file under `.claude/hooks/
modules/pretooluse/` (skipping `__init__.py` and `disabled_*`), sorts them by
a `MODULE_ORDER` int attribute (default 1000, filename as tiebreaker), then
for each module calls `applies(data)` then `run(data)`, expecting `None` or
an `orchestrator.result.HookResult(kind, payload, module_name)` with
`kind ∈ {"block","warn","pass"}` (no `"inject"` kind exists in the SDK's
version). A `"block"` from any module short-circuits and writes
`{"decision":"block","reason":...}` to stdout; a `"warn"` writes to stderr
and continues; there is a 9-second overall time budget and per-module
exception isolation (`"REPO HOOK POLICY INCOMPLETE"` block on 5+ crashed
modules or one that overran a hard-coded scan timeout).
`kotlin_antipattern_policy.py` (`MODULE_ORDER = 10`, so it runs first) is one
of six pretooluse modules today (the others are `fleet_protocol_policy.py`
[20], `ledger_cli_only_policy.py` [24], `no_versioned_filename_policy.py`
[25], `no_interpreter_source_writes_policy.py` [26], `rule_selfcheck_policy.py`
[30]). It watches `Write`/`Edit`/`MultiEdit`, reconstructs the planned
full-file before/after content, scans both with every `.claude/hooks/rules/
kotlin/*.yaml` file individually via `ast-grep scan --rule <file> <tmpfile>
--json=compact`, diffs the hit multisets (occurrence-counted, keyed on
`(rule_id, first-non-blank-line-of-match)`) to find genuinely *introduced*
violations, applies a JVM-source-set carve-out (`no-java-import`,
`no-thread-sleep`, `no-string-format`, `no-print-stack-trace` are skipped
under `jvmMain`/`jvmTest`/`androidMain`/`jvmAndAndroidMain`/…), a
consumer-tree carve-out (`samples/`, `smoke-tests/` are fully exempt), and an
autofix-registry-driven auto-correction path (currently just
`no-throwable-catch-without-rethrow`) that offers corrected file content in
the block message instead of a bare violation list.

**`.claude/hooks/rules/ci-gate.sh`.** A ~110-line bash script invoked by both
`.githooks/pre-commit` and the `ci.yml` verify job. It first checksum-pins the
local `ast-grep` binary's version against `package.json`'s
`@ast-grep/cli` devDependency (fails closed on mismatch — see §2.3/§6 for the
version-drift risk this already documents against itself). It then: (a) for
every `severity: error` rule file, runs `ast-grep scan --rule <file>` across
`src/commonMain/kotlin src/jvmMain/kotlin src/jvmAndAndroidMain/kotlin
src/nativeMain/kotlin` (with `no-java-import`/`no-thread-sleep`/
`no-string-format`/`no-print-stack-trace` scoped to only
`commonMain`+`nativeMain`, and `no-camelcase-top-level-function` additionally
scanning `commonTest`) and fails on any hit; (b) reports (does not fail on)
exactly two named `warning` rules (`no-throwable-catch-without-rethrow`,
`no-runcatching-in-suspend`) across the same dirs — **the other ~40 warning
rules are never whole-tree-scanned by CI at all**, they are edit-time-only
nudges; (c) runs the cross-file `detect-nonintegrated-kotlin.py --check`;
(d) runs `validate_rules.py` in parse/manifest/hunk modes plus an autofix
pre-pass and `tools/run-gate-fixtures.mjs`; (e) runs
`validate_migration_rules.py`, `validate_python_guard_rules.py`,
`detect-restated-measurements.py --check`, `detect-orphan-gates.py`,
`detect-tool-identity-regressions.py --check`,
`detect-public-data-class-budget.py --check`, `tools/beta-readiness-check`,
`detect-release-workflow-trust.py --check`.

**`.claude/settings.json`.** Minimal — a single `PreToolUse` hook entry
(matcher `""`, i.e. all tools) pointing at
`.claude/hooks/orchestrator/pretooluse.py` with a 10-second timeout. There is
**no PostToolUse, Stop, UserPromptSubmit, SessionStart, or SessionEnd hook
wired at all** — confirmed by `find .claude/hooks/modules -maxdepth 1 -type d`
(only `pretooluse/` exists) and `grep -n "PostToolUse\|Stop"
.claude/settings.json` (no matches). This matters directly for §4: adopting
the toolkit's Stop-lifecycle normalize module is not a drop-in into existing
infrastructure — it is new orchestration surface.

**detekt (`detekt.yml` + `:detekt-rules`).** Ten custom detekt `Rule` classes
under `detekt-rules/src/main/kotlin/ai/torad/aisdk/detekt/` (`NoNotNullAssertion`,
`NoJsonContainerForceCast`, `NoInlineJsonInstance`, `NoDeferredWiringComment`,
`NoConsoleOutputInLibrary`, `NoFloatEqualityComparison`,
`PreferTypedErrorOverErrorCall`, `PreferTypedErrorOverCheckNotNull`,
`PreferTypedErrorOverGenericThrow`, `NoRunCatchingInSuspendFunction`),
registered via `ToradRuleSetProvider` and activated under the `torad-aisdk:`
block in `detekt.yml`, each explicitly documented as mirroring a specific
ast-grep rule so the same invariant is enforced in the IDE and
`./gradlew check` for every human contributor, not just Claude's edits (see
`docs/enforcement-layers.md` Layer 2). Several built-in detekt rules are also
activated as direct mirrors (`GlobalCoroutineUsage` ≈ `no-globalscope`,
`SwallowedException` with `CancellationException` deliberately not
ignorelisted ≈ the cancellation-correctness rules). detekt is **out of scope**
for this ast-grep migration — it is a separate enforcement layer this
blueprint does not touch.

**Konsist (`src/jvmTest/kotlin/ai/torad/aisdk/arch/KonsistArchitectureTest.kt`,
89 lines, 5 tests).** Whole-codebase declaration-graph invariants a
single-file lint cannot express: `data class …Event` must have a sealed
parent (mirrors `no-flat-lifecycle-event`), no public sealed interfaces
outside the `@Serializable`/private carve-outs (mirrors `no-sealed-interface`),
and the three package-boundary rules (`providers` !→ `ui`, non-`providers`
!→ `providers`, `protocol` !→ `ui`/`middleware`/agent-runtime types) that
`no-providers-import-ui`/`no-core-import-providers`/
`no-protocol-import-ui-or-middleware`/`no-protocol-agent-runtime-reference`
mirror at edit time per `docs/ast-grep-rule-audit.md` rows 1–4. Konsist is
also **out of scope** for this migration, but its existing mirrors matter for
§3: where an SDK ast-grep rule is a deliberate Konsist mirror, demoting it to
`rules-style/` (opt-in, non-blocking) still leaves the Konsist Layer-2
backstop blocking at `./gradlew check` — a real mitigant, not just a downgrade.

---

## Section 2 — Target-state inventory (TOOLKIT)

### 2.1 The `.rules/kotlin/ast-grep/` taxonomy

Concrete example read from `resources/configs/kotlin-rules/ast-grep/` (the
canonical Kotlin bundle) and `resources/configs/kotlin-multiplatform-rules/
ast-grep/` (the KMP domain pack) — both installed side by side under
`.rules/<pack-name>/ast-grep/` by `torad add kotlin-rules` /
`torad add kotlin-multiplatform-rules`:

```
.rules/kotlin/ast-grep/
├── sgconfig.yml        # ruleDirs: [rules, codemods, normalize]; utilDirs: [utils]; testConfigs
├── registry.json        # {"version": 1, "autofix": [...]}  — explicit autofix-eligible rule ids
├── rules/                # 23 LAW files (block-worthy, scanned by default sgconfig)
├── rules-style/          # 42 opt-in house-tenet files (NOT in sgconfig ruleDirs — not scanned by default)
├── codemods/             # per-codemod dirs: codemod.json + rule.yaml + tests/<case>/{input,expected}.kt
├── normalize/             # Stop-time astgrep-fix (*.yaml) and delegate (*.json, e.g. ktlint) rules
├── utils/                 # shared ast-grep rule fragments (utilDirs)
└── tests/                 # native ast-grep test format ({id, valid:[...], invalid:[...]}) + __snapshots__/
```

`sgconfig.yml` content (verbatim):
```yaml
ruleDirs:
  - rules
  - codemods
  - normalize
utilDirs:
  - utils
testConfigs:
  - testDir: tests
    snapshotDir: __snapshots__
```

`registry.json` content (verbatim, kotlin-rules pack): `{"version": 1,
"autofix": []}` — an explicit allowlist of codemod rule ids that are
enrolled for **unattended** autofix at Stop time (empty today for this pack;
the `handler-requires-looper` codemod exists under `codemods/` but is not
yet enrolled). This is the mechanism `13_ast_grep_normalize.py`'s
`_enrolled_codemods()` reads.

**Load-bearing detail: `rules-style/` is not in `sgconfig.yml`'s `ruleDirs`.**
The "opt-in" framing in `docs/ast-grep-enforcement-layers.md` is not a field
on the rule (every `rules-style/*.yaml` still declares `severity: error` in
its own YAML) — it is entirely a function of *which directory the file lives
in* and therefore whether the default `sgconfig.yml` (and hence
`11_ast_grep_rules.py`, which always scans via `--config <sgconfig>`) ever
sees it. A rule moved from `rules/` to `rules-style/` is not "made a
warning" — it stops being scanned by the PreToolUse hook at all unless
something else (a second sgconfig, `ci-gate.sh`, a manual `ast-grep scan
--rule`) opts back in. This is central to §4 and §6.

Four packages contribute the 29-rule "curated Kotlin LAW set" the doc cites:
`kotlin-rules` (23 in `rules/`), `kotlin-android-rules` (2:
`no-asynctask`, `no-findviewbyid`), `kotlin-backend-rules` (2:
`entity-no-public-setter`, `no-field-injection`), `kotlin-multiplatform-rules`
(2: `no-android-import-in-common`, `no-cinterop-import-in-common`). The
android and backend packs are **not applicable** to this SDK (no Android
`View`/`AsyncTask` code, no JPA entities in the library itself — flagged as
an open question in §6 in case `androidMain`/`jvmAndAndroidMain` ever grow
Android-framework-adjacent code).

### 2.2 Canonical Kotlin LAW rules, house tenets, and the KMP domain pack

**LAW (`kotlin-rules/ast-grep/rules/`, 23, all `severity: error`):**
`collect-with-lifecycle` (use `collectAsStateWithLifecycle`), `composable-returns-unit`,
`lazy-items-need-key`, `main-no-hardcoded-dispatchers` (no bare `Dispatchers.IO/Default/Main`
under `**/*Main/**`), `mutablestateof-needs-remember`, `no-compose-in-core`,
`no-coroutine-scope-factory` (no `CoroutineScope(...)`/`MainScope()` construction),
`no-empty-catch`, `no-global-scope`, `no-hardcoded-secrets`, `no-lateinit`,
`no-println-in-production`, `no-raw-thread`, `no-reflection-in-production`,
`no-runblocking-in-production`, `no-system-exit`, `no-todo-throws`,
`no-unsafe-bang-bang` (`!!`), `no-unsafe-cast` (`as` without `?`/nullable-type),
`tests-no-hardcoded-dispatchers`, `tests-no-mockk-static`, `tests-no-runblocking`,
`tests-no-thread-sleep`.

**KMP domain pack (`kotlin-multiplatform-rules/ast-grep/rules/`, 2, LAW):**
`no-android-import-in-common` (no `android.*` import from commonMain — move
behind expect/actual), `no-cinterop-import-in-common` (no `kotlinx.cinterop`
import from commonMain).

**House tenets (`kotlin-rules/ast-grep/rules-style/`, 42, opt-in):**
architecture/layering (`converters-must-be-usecases`, `no-invented-layer`,
`repository-injection-discipline`, `usecase-injection-discipline`,
`vm-injection-discipline`, `no-ui-projection-in-di`, `no-network-outside-seam`,
`sql-only-in-persistence`, `embeddings-via-provider`, `no-loose-function`,
`no-lambda-seam`, `tools-stateless`, `tools-no-private-helpers`); Flow/state
(`flow-over-suspend`, `data-layer-flow-only`, `usecases-return-flow`,
`no-logic-in-collect`, `state-must-be-sealed`, `immutable-ui-state`,
`no-logic-in-ui`, `no-logic-in-uistate`, `no-logic-in-viewmodel`,
`no-logic-in-data-class`, `no-mutablestateof-in-viewmodel`,
`uistate-no-nullable-string-defaults`, `sealed-over-stringly`,
`sealed-class-no-body-properties`); safety/style (`no-unsafe-cast`'s
sibling-tenets `no-mutable-var`, `no-else-in-sealed-when`, `no-sealed-interface`,
`no-secondary-constructor`, `no-companion-objects`, `no-extension-functions`,
`no-any-parameters`, `no-object-singleton-state`, `no-name-shadowing`,
`prefer-immutable-collections`, `use-or-empty`, `no-delay-in-production`,
`prompts-no-buildstring`); testing (`tests-flow-via-turbine`); a
reference/opt-in-LAW codemod pairing (`no-handler-without-looper.reference`).

### 2.3 The runtime hook modules and how they route rules

Both live under `resources/hooks/claude/modules/{pretooluse,stop}/` in the
toolkit and are distributed as-is by `torad add kotlin-rules` (per
`docs/ast-grep-enforcement-layers.md`: "when the project already has Claude
infrastructure, both runtime hook modules ride along").

**`11_ast_grep_rules.py` (PreToolUse, `MODULE_ORDER = 100`).** Watches only
`Edit`/`Write` (no `MultiEdit`) for `.ts/.tsx/.js/.jsx/.kt`. On each watched
edit it reconstructs the proposed full-file content, then walks **up** from
the target file's directory looking for a `.rules/` directory and globs
`.rules/*/ast-grep/sgconfig.yml` — i.e. it discovers and scans **every**
installed rule pack's default config (whichever packs have a `.rules/<name>/
ast-grep/sgconfig.yml`), not a single hardcoded Kotlin config. For each
discovered sgconfig it runs `ast-grep scan --config <sgconfig> <tempfile>
--json` on both the before- and after-content, filters to
`severity: "error"` findings only (non-error findings, including anything
under `rules-style/` — which is outside `sgconfig.yml`'s `ruleDirs` in the
first place — are never even collected, let alone surfaced), and diffs the
two hit multisets keyed on `(ruleId, whitespace-collapsed full match text)`
(an occurrence-counted grandfather, same spirit as the SDK's own but keyed
on the *full* match span rather than just its first line). Any net-new error
finding blocks with a formatted list (capped at 5, "+N more"). It fails open
(returns `None`, i.e. allow) on `ast-grep` timeout or absence.

**`13_ast_grep_normalize.py` (Stop, `MODULE_ORDER = 13`).** Runs on every
Stop unless `stop_hook_active`. It determines "session-delta files" by
reading a **companion PostToolUse-produced state file**
(`.claude/state/normalize-touched-<session>.json`, written by
`resources/hooks/claude/modules/posttooluse/02_normalize_touch_producer.py`,
which is not one of the two modules named in the migration brief but is a
hard dependency of `13_ast_grep_normalize.py`'s correctness — see §4/§6),
intersected with `git diff --name-only HEAD` + untracked files; if the
touch-state file is absent it fails **closed** (normalizes nothing, on the
stated reasoning that an absent file means the session made no watched
edits). For each delta file it discovers the same `.rules/*/ast-grep/
sgconfig.yml` packs, builds three **temp** configs per pack (normalize-only
from `normalize/*.yaml` [`kind: astgrep-fix`, matched by top-level YAML field
scan] + `normalize/*.json` [`kind: delegate`, e.g. ktlint] + any
`registry.json`-enrolled `codemods/**/rule.yaml`, and separately a LAW-only
config from `rules/`), then iterates up to 3 passes applying
`ast-grep scan -U --config <normalize-config>` and any delegate command
(`ktlint -F` for `.kt`), until convergence. Before writing, it verifies the
fixed output still parses clean (`ast-grep run --kind ERROR`) **and**
introduces no new LAW-severity error (re-scanning the LAW-only config
before/after, same occurrence-counted diff as the PreToolUse module) —
only then does it atomically replace the file. It also logs a one-line
summary to a toolkit-specific campaign ledger via
`scripts/backlog.py <ledger> note <item_id> <summary>` if an active-item
state file names one — this call is a silent no-op in a repo (like this
SDK) that has neither `scripts/backlog.py` nor the toolkit's
`.claude/state/ledger-active-<session>.json` convention, so it is inert but
not a hard blocker.

**Comparison to the SDK's `kotlin_antipattern_policy.py`.** Structurally
similar (full-file before/after diff, occurrence-counted grandfathering,
per-rule-file or per-config `ast-grep scan --json`), but the toolkit module
is narrower in scope on several axes the SDK's own module explicitly
handles today: no `MultiEdit` support, no JVM-source-set carve-out, no
consumer-tree (`samples/`, `smoke-tests/`) exemption, no non-blocking
`warn` surfacing at all (because `rules-style/` isn't scanned), and no
autofix-suggestion-in-block-message UX. See §4 for how these gaps are
proposed to be closed or accepted.

**`ast-grep` version.** The toolkit's `docs/ast-grep-enforcement-layers.md`
pins **`0.44.0`** exactly ("Required `ast-grep`: `0.44.0`", enforced by
`tests/structure/ast-grep-version-pin.test.ts`). The SDK currently pins
**`0.42.1`** in three places that must agree (`package.json`'s
`@ast-grep/cli`, `ci.yml`'s `AST_GREP_VERSION`/`AST_GREP_SHA256`, and
`ci-gate.sh`'s runtime cross-check of the two) — and `ci-gate.sh` itself
already contains a self-check that fails closed on any drift between the
locally-installed binary and `package.json`. The locally installed
`ast-grep` binary on this machine (`~/.local/bin/ast-grep`) currently
reports **`0.44.0`** already — i.e. the dev environment has already drifted
past the SDK's own pin (confirmed live: `ast-grep --version` → `ast-grep
0.44.0` vs `package.json`'s `"@ast-grep/cli": "0.42.1"`). The SDK's own
`dev/campaigns/gate-hardening.toml` independently documents this exact drift
being caught once already ("FX-03's new ast-grep parity gate FAILED its
first real execution — `~/.local/bin/ast-grep` had drifted 0.42.1 → 0.44.0").
This is a live, already-observed risk, not a hypothetical — see §6.

---

## Section 3 — Reconciliation map

Disposition legend: `DROP` = duplicate of a canonical toolkit rule (dedupe
law applies — do not double-own); `MOVE→rules/` / `MOVE→rules-style/` =
project-specific invariant, no canonical equivalent, keep it in the SDK's own
taxonomy copy at the named lane; `PORT→ast-grep` = a Python detector becomes
an ast-grep rule; `KEEP-PYTHON` = procedural gate stays.

**Read this table together with the "conflict" flags — a `DROP` next to a
canonical rule whose behavior actively contradicts an SDK "GOOD" fixture is
called out explicitly and should NOT be executed as a blind drop. These are
the cases most likely to violate the dedupe law if mishandled (double-owning
in the wrong direction, or silently regressing behavior the SDK depends on).**

| SDK item | toolkit disposition |
|---|---|
| `avoid-broad-catch-exception` | MOVE→rules-style (no canonical; SDK's typed-error taxonomy is project-specific) |
| `no-any-typed-public-property` | MOVE→rules-style (related family to canonical `no-any-parameters`, but different AST target — that rule matches bare function `parameter` nodes; this one matches `class_parameter`/`property_declaration` typed `Any`. Not a true duplicate; recommend keeping both side by side, complementary coverage) |
| `no-array-property-in-data-class` | MOVE→rules/ (no canonical equivalent) |
| `no-callback-wiring-in-init` | MOVE→rules-style (no canonical equivalent) |
| `no-camelcase-top-level-function` | MOVE→rules/ (no canonical equivalent — CLAUDE.md's PascalCase-factory API-shape rule is this SDK's own) |
| `no-channel-unlimited` | MOVE→rules-style (no canonical equivalent) |
| `no-console-output-in-library` | **Not a clean DROP.** Canonical `no-println-in-production` (rules/) matches only `println($$$ARGS)`; the SDK's rule is a strict superset (`println`, `print`, `System.out`, `System.err`). Recommend: DROP the `println` overlap, **PORT the residual** (`print`/`System.out`/`System.err`) as a small supplementary `rules/` entry, OR simply keep the SDK's superset rule as MOVE→rules/ instead of dropping. Either way this is a genuine dedupe collision to adjudicate, not a pure duplicate. |
| `no-core-import-providers` | MOVE→rules/ (no canonical equivalent — toolkit has no knowledge of this SDK's package layout; Konsist mirror stays as Layer 2 backstop regardless) |
| `no-data-class-extending-throwable` | MOVE→rules/ (no canonical equivalent) |
| `no-deferred-wiring-comment` | MOVE→rules/ (no canonical equivalent; adjacent family to `no-todo-throws` but different match target — comment tokens, not `TODO()` calls) |
| `no-else-in-sealed-when` | **DROP candidate, but scope-broadening caveat.** Canonical `no-else-in-sealed-when` (rules-style) exists with the SAME id, but its pattern is a blunt `when_entry` regex `^\s*else\s*->` with the toolkit's own comment admitting "catches ALL else branches... review manually for sealed type context" — broader/noisier than the SDK's heuristic (which requires a `when_subject` + a sibling `when_entry` with a dotted sealed-looking `type_test`). Since both lanes are opt-in/non-blocking, risk is low, but expect new noise on ordinary (non-sealed) `when/else` if adopted verbatim. |
| `no-empty-catch-block` | **DROP candidate, but behavior-narrowing caveat.** Canonical `no-empty-catch` (rules/, LAW) matches ANY `catch_block` with no `statements` child — it has **no exemption** for the SDK's accepted `catch (_: Exception) {}` discard idiom or a comment-only catch, both of which the SDK's own rule explicitly carves out (`not: has: kind: line_comment/multiline_comment`, `not: has: simple_identifier regex '^_$'`). Adopting the canonical as-is would newly flag every existing intentionally-empty-but-commented or `_`-discarded catch in the codebase. OPEN QUESTION for §6: accept the stricter canonical (audit/fix all such sites) vs. keep a customized local rule (diverges from the shared baseline). |
| `no-empty-string-sentinel` | **CONFLICT, not a dedupe.** Canonical `use-or-empty` (rules-style) explicitly pattern-matches `$X ?: ""` / `?: emptyList()` / etc. and recommends rewriting to `.orEmpty()` — which is **semantically identical sentinel fabrication**, just different syntax. Importing `use-or-empty` into this project actively contradicts the "no sentinel fabrication" tenet `no-empty-string-sentinel` exists to enforce. Recommend: MOVE `no-empty-string-sentinel` to rules/ unchanged, and do **NOT** import `use-or-empty` into this project's active rule set (or import it explicitly disabled/documented-excluded). |
| `no-flat-lifecycle-event` | MOVE→rules/ (no canonical equivalent; Konsist mirror stays as Layer 2 backstop) |
| `no-float-equality-comparison` | MOVE→rules-style (no canonical equivalent) |
| `no-flowon-main` | MOVE→rules-style (no canonical equivalent) |
| `no-fq-java-time-uuid-date` | MOVE→rules/ (no canonical equivalent; sibling of `no-java-import`, candidate for upstream contribution to `kotlin-multiplatform-rules`) |
| `no-generate-id-sentinel` | MOVE→rules/ (no canonical equivalent) |
| `no-globalscope` | **DROP — duplicate of canonical `no-global-scope`** (rules/). Both target the bare `GlobalScope` identifier; clean, high-confidence dedupe. |
| `no-hardcoded-dispatcher-in-builder` | **CONFLICT, not a dedupe.** Canonical `main-no-hardcoded-dispatchers` (rules/, LAW) bans the bare tokens `Dispatchers.IO`/`.Default`/`.Main` ANYWHERE under `**/*Main/**/*.kt` — which, in KMP source-set naming, means `commonMain`, `jvmMain`, `androidMain`, `nativeMain` ALL match. Its only escape hatches are file-name-based (`**/Dispatchers*.kt`, `**/*DispatcherProvider*.kt`). Critically, this would flag the SDK's own documented **GOOD** fix for `no-hardcoded-dispatcher-in-builder` — `context: CoroutineContext = Dispatchers.Default` as a **default parameter value** — because the canonical rule has no position-aware exemption for default-parameter injection. Recommend: keep the SDK's own narrower, idiom-compatible rule in rules/ rather than replacing it with the canonical; flag for orchestrator adjudication rather than blind DROP. |
| `no-inline-coroutinescope-launch` | **CONFLICT, not a dedupe.** Canonical `no-coroutine-scope-factory` (rules/, LAW) bans the bare CONSTRUCTION `CoroutineScope($$$ARGS)`/`MainScope()` ANYWHERE, not just when immediately chained into `.launch`/`.async` — this would ALSO flag the SDK's own documented **GOOD** fix (`private val scope = CoroutineScope(SupervisorJob() + engineContext)` held as a field and cancelled in `close()`). Recommend: keep the SDK's own narrower rule (only flags the immediate-chain leak shape) in rules/; flag for orchestrator adjudication rather than blind DROP. |
| `no-inline-json-instance` | MOVE→rules-style (no canonical equivalent; detekt mirror `NoInlineJsonInstance` stays as Layer 2) |
| `no-open-serializable-polymorphic-base` | MOVE→rules-style (no canonical equivalent) |
| `no-mutable-collection-in-public-fun` | MOVE→rules/ (related family to canonical `prefer-immutable-collections`, but different AST target — that rule matches `mutableListOf()`/etc. constructor calls in return position; this one matches typed `MutableMap`/`MutableList` parameters/returns on a public function signature. Not a true duplicate; keep both) |
| `prefer-list-over-array-return-in-public-fun` | MOVE→rules-style (no canonical equivalent) |
| `prefer-value-class-over-typealias-primitive` | MOVE→rules-style (no canonical equivalent) |
| `no-json-container-force-cast` | **DROP — duplicate of canonical `no-unsafe-cast`** (rules/, LAW), which bans any `X as T` that is not `as?`/not a nullable-type cast — a strict superset of "cast to `JsonObject`/`JsonArray`". Migration-cost caveat: `no-unsafe-cast`'s scope is far broader than JSON containers, so enabling it as LAW requires auditing/grandfathering ALL `as` force-casts codebase-wide, not just the JSON-decoding ones — a bigger one-time migration surface than today's narrow rule. detekt's `NoJsonContainerForceCast` mirror is unaffected (Layer 2, out of scope). |
| `no-keyed-json-container-cast` | MOVE→rules-style (canonical `no-unsafe-cast` explicitly excludes `as?`, so this SAFE-cast-specific rule is not covered — no duplicate) |
| `no-primitive-array-property-in-data-class` | MOVE→rules/ (no canonical equivalent) |
| `no-print-stack-trace` | MOVE→rules/ (no canonical equivalent — toolkit has no `printStackTrace`-specific rule; detekt built-in `PrintStackTrace` mirror stays as Layer 2, out of scope) |
| `no-java-import` | MOVE→rules/ (no canonical equivalent — complementary to canonical `no-android-import-in-common`, different target namespace; candidate for upstream contribution to `kotlin-multiplatform-rules`) |
| `no-thread-sleep` | MOVE→rules/ (canonical only has `tests-no-thread-sleep`, test-source-scoped for virtual-time hygiene; this SDK's rule bans `Thread.sleep` in PRODUCTION code for KMP-availability reasons — complementary, not duplicate) |
| `no-system-clock-env` | MOVE→rules/ (no canonical equivalent) |
| `no-string-format` | MOVE→rules/ (no canonical equivalent) |
| `no-jvm-synchronized` | MOVE→rules/ (no canonical equivalent) |
| `prefer-kotlin-math` | MOVE→rules-style (no canonical equivalent) |
| `prefer-typed-error-over-generic-throw` | MOVE→rules-style (no canonical equivalent) |
| `prefer-typed-error-over-error-call` | MOVE→rules-style (no canonical equivalent) |
| `prefer-typed-error-over-checknotnull` | MOVE→rules-style (no canonical equivalent) |
| `no-let-elvis-run-pyramid` | MOVE→rules-style (no canonical equivalent) |
| `no-todo-in-source` | **DROP — duplicate of canonical `no-todo-throws`** (rules/). Both match `TODO()`/`TODO($MSG or $$$MSG)`; clean, high-confidence dedupe. |
| `no-mutable-var-in-enum` | MOVE→rules/ (NOT subsumed by canonical `no-mutable-var` — enum bodies use `enum_class_body`, a different tree-sitter node kind than `class_body`, confirmed against the SDK's own rule pattern; no duplicate) |
| `no-multiple-boolean-params` | MOVE→rules-style (no canonical equivalent) |
| `no-uuid-id-sentinel` | MOVE→rules/ (no canonical equivalent) |
| `prefer-sealed-exception-over-open-class` | MOVE→rules-style (no canonical equivalent) |
| `no-jsonnull-sentinel` | MOVE→rules-style (no canonical equivalent; same "no sentinel fabrication" family as `no-empty-string-sentinel`, not directly contradicted by a canonical rule) |
| `no-lateinit-var` | **DROP — duplicate of canonical `no-lateinit`** (rules/). Both match `lateinit var`. Note: the toolkit's own dogfood doc names `no-lateinit` as one of three rules found dead-pattern-broken during calibration and since fixed — re-verify against `resources/hooks/claude/rule-proofs/no-lateinit.json` before trusting it blindly. |
| `no-mutable-companion-state` | **Partial overlap, not a clean DROP.** Canonical `no-mutable-var` (rules-style) is `property_declaration[var] inside class_body` with NO ancestor restriction — confirmed (via the SDK's own `no-mutable-companion-state.yaml`, which proves `companion_object`'s body IS `class_body` kind) to be a strict pattern-superset covering this rule, `no-public-mutable-var`, and `no-var-in-object-declaration` all at once. But it is (a) in the opt-in style lane vs. this SDK's block/error severity (**lane downgrade**), and (b) far broader — it also flags **private** var fields, which this SDK's curated set deliberately allows (see `no-volatile-var`'s own guidance: "If @Volatile is genuinely the right primitive, keep it private"). Recommend keeping the SDK's own narrower, block-lane rule rather than dropping in favor of the noisier broad canonical. |
| `no-not-null-assertion` | **DROP — duplicate of canonical `no-unsafe-bang-bang`** (rules/), which is `pattern: $X!!`. High-confidence dedupe based on matching bad/good fixtures (`maybe!!` → `requireNotNull(maybe)`); byte-for-byte pattern equivalence not verified against the SDK's own YAML in this pass. |
| `no-nullable-prompt-messages-bag` | MOVE→rules/ (no canonical equivalent — highly SDK-specific API-shape invariant) |
| `no-provideroptions-jsonobject-cast` | MOVE→rules-style (no canonical equivalent) |
| `no-public-mutable-var` | **Partial overlap, not a clean DROP** — same reasoning as `no-mutable-companion-state` above (subsumed pattern-wise by canonical `no-mutable-var`, but broader + lane-downgraded). Recommend keeping the SDK's own narrower, block-lane, public-only rule. |
| `no-sealed-interface` | **DROP candidate — near-identical to canonical `no-sealed-interface`** (rules-style): same `class_declaration` + `interface` + `sealed` modifier shape, same `@Serializable`/`private` exemptions, confirmed by direct YAML comparison. **Lane downgrade**: SDK today is `error`/block; canonical lane is opt-in style (not scanned by the default PreToolUse sgconfig at all). Mitigant: the Konsist test `no sealed interfaces in production except serializable wire types and private` stays as an independent Layer-2 backstop regardless of this ast-grep lane choice. |
| `no-secondary-constructor` | **DROP — duplicate of canonical `no-secondary-constructor`** (rules-style), both simply `kind: secondary_constructor`. **Lane downgrade** (SDK block → toolkit opt-in style), no Layer-2 backstop exists for this one today (worth flagging as a genuine enforcement-strength regression if dropped verbatim). |
| `no-throw-in-stream-error-fn` | MOVE→rules/ (no canonical equivalent — highly SDK-specific API-shape invariant) |
| `no-top-level-mutable-var` | MOVE→rules/ (NOT subsumed by canonical `no-mutable-var` — top-level vars have no `class_body` ancestor at all; no duplicate) |
| `no-typealias-string` | MOVE→rules-style (no canonical equivalent) |
| `no-providers-import-ui` | MOVE→rules/ (no canonical equivalent; Konsist mirror stays as Layer 2 backstop) |
| `no-protocol-import-ui-or-middleware` | MOVE→rules/ (no canonical equivalent; Konsist mirror stays as Layer 2 backstop) |
| `no-protocol-agent-runtime-reference` | MOVE→rules/ (no canonical equivalent; Konsist mirror stays as Layer 2 backstop) |
| `no-public-expect-declaration` | MOVE→rules/ (no canonical equivalent — this SDK's own dedupe audit already confirms "nobody" owned this before the rule was added; certainly no toolkit equivalent) |
| `no-throwable-catch-without-rethrow` | MOVE→rules-style (no canonical equivalent) |
| `no-runcatching-in-suspend` | MOVE→rules-style (no canonical equivalent; detekt mirror `NoRunCatchingInSuspendFunction` stays as Layer 2) |
| `no-public-member-extension-in-object` | MOVE→rules/ (**NOT a dup of canonical `no-extension-functions`** — that rule bans essentially ALL extension-function *declarations* project-wide via a receiver-dot regex on `function_declaration`, a normal and idiomatic Kotlin pattern this SDK uses extensively outside of the specific historical anti-pattern it guards against. Importing `no-extension-functions` wholesale would be wildly over-broad for this codebase; do not import it, or import disabled/undocumented. This SDK's own rule is much narrower: only PUBLIC member-extensions declared INSIDE a public `object`.) |
| `no-suspend-fun-returning-flow` | **DROP — duplicate of canonical `flow-over-suspend`** (rules-style). Confirmed identical AST shape: `function_declaration` with `suspend` modifier + a `Flow`-typed return `user_type`. Clean dedupe, no lane-downgrade concern (both non-blocking). |
| `no-runblocking-in-common` | **Likely DROP — duplicate of canonical `no-runblocking-in-production`** (rules/), which matches `runBlocking { ... }`/`runBlocking($$$ARGS) { ... }` under `**/*Main/**/*.kt` (covers commonMain/jvmMain/androidMain/nativeMain alike) minus `Main.kt`/`*Application.kt` CLI-entrypoint exemptions. `ci-gate.sh`'s own dirs list already scans this SDK's rule across commonMain **and** jvmMain **and** nativeMain today (its id name says "-common" but its enforced scope is broader), so the canonical's broader scope may already match today's real enforcement — but the exact `files`/`ignores` glob on the SDK's own YAML was not read in this pass; verify byte-for-byte scope parity before dropping. |
| `no-var-in-object-declaration` | **DROP — duplicate of canonical `no-object-singleton-state`** (rules-style). Confirmed identical AST shape: `object_declaration > class_body > property_declaration[var]`. **Lane downgrade** (SDK block/error → toolkit opt-in style) — no independent Layer-2 Konsist/detekt backstop exists for this specific invariant today, so this is a real enforcement-strength regression if dropped verbatim without re-promoting the canonical (or keeping a local copy) into `rules/`. |
| `no-volatile-var` | MOVE→rules-style (loosely subsumed by the broad canonical `no-mutable-var`, but has distinct, more actionable `@Volatile`-aware messaging the canonical lacks; recommend keeping as a complementary SDK-specific rule rather than dropping) |
| `no-public-mutable-collection-ctor-prop` | MOVE→rules-style (related family to canonical `prefer-immutable-collections`, different AST target — constructor-property type annotation vs. return-position constructor call; not a true duplicate) |
| `no-public-mutable-collection-val` | MOVE→rules-style (same reasoning as above; not a true duplicate) |
| `no-loop-in-init` | MOVE→rules-style (no canonical equivalent) |
| `no-try-catch-in-init` | MOVE→rules-style (no canonical equivalent) |
| `detect-nonintegrated-kotlin.py` | KEEP-PYTHON |
| `detect-orphan-gates.py` | KEEP-PYTHON |
| `detect-public-data-class-budget.py` | KEEP-PYTHON |
| `detect-release-workflow-trust.py` | KEEP-PYTHON |
| `detect-restated-measurements.py` | KEEP-PYTHON |
| `detect-tool-identity-regressions.py` | PORT→ast-grep — proposed as two new `rules/` entries: `no-associate-by-toolcallid-collapse` (call-expression pattern for the `.associateBy{…toolCallId…}` / `.toSet()` collapse shapes) and `no-risky-toolcallid-collection-name` (`property_declaration`/`variable_declaration` + `mutableMapOf`/`mutableSetOf`/`linkedMapOf`/`hashMapOf`/`linkedSetOf`/`hashSetOf` constructor-call `has:` + `regex:` on the binding name for risky/safe hints). Fidelity risk on the value-type-args safe-hint check — see §6. |

### 3.1 Top dedupe collisions (evidence-backed, highest priority for adjudication)

1. **`no-var-in-object-declaration` ↔ canonical `no-object-singleton-state`** —
   confirmed identical AST target (`object_declaration > class_body >
   property_declaration[var]`); dropping it is correct on invariant grounds
   but silently **downgrades a `severity: error` block-lane rule to opt-in
   style** with no independent backstop. Getting this wrong (a blind DROP
   with no compensating action) is a real enforcement-strength regression.
2. **`no-hardcoded-dispatcher-in-builder` ↔ canonical
   `main-no-hardcoded-dispatchers`** — same broad topic, but the canonical
   rule would flag the SDK's own documented GOOD fix (default-parameter
   dispatcher injection). A naive DROP does not just double-own the
   invariant, it actively **contradicts** the SDK's accepted remediation
   pattern.
3. **`no-empty-string-sentinel`/`no-jsonnull-sentinel` ↔ canonical
   `use-or-empty`** — not a duplicate at all but an **opposite
   recommendation**: the canonical rule wants `.orEmpty()`, which is the same
   sentinel-fabrication anti-pattern the SDK rule bans, just spelled
   differently. Importing `use-or-empty` unmodified would put a
   toolkit-endorsed pattern directly at odds with this SDK's own
   architecture tenet.

(A close fourth: `no-inline-coroutinescope-launch` ↔ canonical
`no-coroutine-scope-factory`, same shape of conflict as #2 — the canonical
would flag the SDK's own GOOD fix of holding a `CoroutineScope` in a
lifecycle-managed field.)

---

## Section 4 — Orchestrator/hooks reconciliation

The SDK's PreToolUse orchestrator is not just a Kotlin-rules runner — it
dispatches `fleet_protocol_policy.py`, `ledger_cli_only_policy.py`,
`no_versioned_filename_policy.py`, `no_interpreter_source_writes_policy.py`,
and `rule_selfcheck_policy.py` alongside `kotlin_antipattern_policy.py`.
Adopting the toolkit's ast-grep hook modules must not disturb these.

**Compatibility check performed:** `orchestrator/result.py` differs between
the two repos (toolkit's has an extra `"inject"` `ResultKind` and a
`__post_init__` type guard the SDK's lacks), but neither toolkit module
(`11_ast_grep_rules.py`, `13_ast_grep_normalize.py`) ever constructs an
`"inject"`-kind result — both only ever return `HookResult(kind="block", …)`
/ `HookResult(kind="warn", …)` / `None`. **Both modules are therefore
usable, unmodified on this axis, against the SDK's existing narrower
`ResultKind = Literal["block", "warn", "pass"]`** — no `result.py` change is
required. The toolkit's own dispatcher (`orchestrator/pretooluse.py`, 20
lines) is a thin wrapper around a 747-line `runner.py` with its own
observability/session-registry/ledger-capture machinery
(`resources/hooks/claude/orchestrator/{observability,runner}.py`) that is
**not proposed for adoption** — it is a much larger, toolkit-wide-session
footprint than this migration's locked scope (two named hook modules) calls
for, and importing it would pull in dependencies (session registries, ledger
capture, turn history) this SDK does not otherwise have. Recommendation:
**keep the SDK's own lightweight dispatcher pattern**, port the two modules
into it.

### 4.1 Concrete file plan

- **New**: `.claude/hooks/modules/pretooluse/ast_grep_rules_policy.py` — a
  port of `11_ast_grep_rules.py`, adapted to (a) also watch `MultiEdit`
  (today's `kotlin_antipattern_policy.py` does; the toolkit module does not
  — this is a real capability gap to close, not accept, since Claude
  routinely uses `MultiEdit`), (b) retain the JVM-source-set carve-out and
  consumer-tree (`samples/`, `smoke-tests/`) exemption
  `kotlin_antipattern_policy.py` currently applies, since the ported module
  has neither. Set `MODULE_ORDER` to `10` (replacing
  `kotlin_antipattern_policy.py`'s slot) or a nearby free value; the SDK's
  dispatcher sorts by the `MODULE_ORDER` attribute, not filename, so the
  toolkit's `"11_"` filename-numbering convention is cosmetic here and can
  be dropped to match this repo's existing `snake_case_policy.py` naming —
  flagged as a minor open question (keep toolkit-style filenames for easier
  future upstream diffing vs. match local convention).
- **Retire**: `.claude/hooks/modules/pretooluse/kotlin_antipattern_policy.py`
  once the replacement is verified at feature parity (see open questions
  below for the two behaviors — non-blocking warn surfacing and
  autofix-suggestion-in-message — that the toolkit module does not
  replicate and that this plan does not propose reimplementing inside the
  PreToolUse module; see 4.2).
- **New**: `.claude/hooks/orchestrator/stop.py` — does not exist today and
  must be authored from scratch, modeled directly on the existing
  `orchestrator/pretooluse.py` (same module-discovery/`MODULE_ORDER`/
  `HookResult` dispatch shape, adapted for the Stop event's JSON contract,
  e.g. `stop_hook_active`).
- **New**: `.claude/hooks/modules/stop/ast_grep_normalize_policy.py` — a
  port of `13_ast_grep_normalize.py`, with the `_log_to_ledger`/
  `scripts/backlog.py` call either stripped (it's already a safe no-op in
  this repo, per §2.3) or rewired to this SDK's own ledger convention
  (`dev/campaigns/manifest.py note <id> "..."`, per the project's own
  ledger-first reporting standard) if session-item tracking is wanted here.
- **New (dependency of the Stop module)**:
  `.claude/hooks/orchestrator/posttooluse.py` +
  `.claude/hooks/modules/posttooluse/normalize_touch_producer.py` — a port
  of `02_normalize_touch_producer.py`, required for the Stop module's
  fail-closed session-delta-file restriction to work correctly (without it,
  `13_ast_grep_normalize.py` normalizes nothing, silently, forever — see
  open question below).
- **settings.json**: add a `"Stop"` hook entry pointing at the new
  `orchestrator/stop.py`, and a `"PostToolUse"` hook entry pointing at the
  new `orchestrator/posttooluse.py` (both currently absent — this is new
  lifecycle surface, not a rewire of existing surface).
- **Rules taxonomy**: `.claude/hooks/rules/kotlin/*.yaml` + `manifest.json`
  are retired in place of `.rules/kotlin/ast-grep/{rules,rules-style}/*.yaml`
  + `registry.json` + `sgconfig.yml` (Section 5 stages this explicitly).

### 4.2 Open questions this section cannot cleanly resolve

- **OPEN — non-blocking `warn` surfacing at edit time.** Today, ~43 of the
  72 rules are `severity: warning` and surface as a non-blocking stderr nudge
  on every introducing edit. Under the toolkit model, `rules-style/` (where
  most of these land per §3) is outside `sgconfig.yml`'s `ruleDirs`, so
  `11_ast_grep_rules.py` never scans it — this UX disappears entirely unless
  something new is added (a second sgconfig scanning `rules-style/` at warn
  severity, an extension to the ported PreToolUse module, or accepting the
  loss and relying on `ci-gate.sh`'s narrower 2-rule warning report plus
  occasional manual `ast-grep scan --rule` audits). This needs an explicit
  operator decision, not a default.
- **OPEN — autofix-suggestion-in-block-message UX.** Today's
  `kotlin_antipattern_policy.py` offers corrected file content inline in the
  block message for `autofix-registry.json`-listed rules (currently
  `no-throwable-catch-without-rethrow`). The toolkit's `11_ast_grep_rules.py`
  has no equivalent — autofix only happens later, at Stop, via
  `13_ast_grep_normalize.py`'s enrolled-codemod path. Decide whether to
  reimplement the inline-suggestion UX on the ported PreToolUse module or
  accept that autofixable violations now only self-correct at session end.
- **OPEN — is the PostToolUse touch-producer worth the added surface?** The
  Stop module's correctness (not silently normalizing pre-session WIP) is
  contingent on the PostToolUse producer being installed and working. This
  is real new orchestration surface (a lifecycle hook the SDK has never
  run). Alternative: a simpler Stop module that scopes to `git diff` +
  untracked files with no session-touch restriction, accepting the risk of
  normalizing uncommitted WIP that predates the session — this is a
  deliberate scope-reduction the orchestrator must choose, not something
  this blueprint should default silently.
- **OPEN — grandfathering identity mismatch.** The SDK's own
  `kotlin_antipattern_policy.py` grandfathers on `(rule_id,
  first-non-blank-line-of-match)`; the toolkit's `11_ast_grep_rules.py`
  grandfathers on `(rule_id, whitespace-collapsed FULL match text)`. These
  produce different results on multi-line matches with edited bodies but an
  unchanged first line (SDK: still grandfathered; toolkit: could newly block
  if the body changed at all). Low risk, but worth a conscious choice rather
  than an accidental behavior change on cutover.

---

## Section 5 — Staged, gate-safe execution plan

Each stage keeps `bash .claude/hooks/rules/ci-gate.sh` green and the
PreToolUse orchestrator functional. Each is scoped to be one reviewable
subagent task.

**Stage 0 — pin ast-grep 0.44.0 + version gate.**
What: bump `package.json`'s `@ast-grep/cli`, `ci.yml`'s
`AST_GREP_VERSION`/`AST_GREP_SHA256`, re-verify `ci-gate.sh`'s existing
drift-check still passes at the new pin; re-run `validate_rules.py` and
`tools/run-gate-fixtures.mjs` against the new binary (grammar/behavior
changes between 0.42.1→0.44.0 could shift match counts on existing rules —
this must be checked BEFORE any taxonomy changes, so a regression is
attributable to the version bump alone).
Why safe: no rule files move; only the pinned tool version changes.
Verify: `bash .claude/hooks/rules/ci-gate.sh` green; `ast-grep --version`
matches `package.json`.

**Stage 1 — scaffold `.rules/kotlin/ast-grep/` taxonomy, unwired.**
What: create `.rules/kotlin/ast-grep/{rules,rules-style,codemods,normalize,
utils,tests}/`, `registry.json`, `sgconfig.yml` (copied from the toolkit's
kotlin-rules pack, adjusted `ruleDirs`/`utilDirs` as needed). Nothing reads
this tree yet — `kotlin_antipattern_policy.py` still points at
`.claude/hooks/rules/kotlin/`.
Why safe: purely additive, dead code until wired.
Verify: `bash .claude/hooks/rules/ci-gate.sh` green (unchanged); `ast-grep
scan --config .rules/kotlin/ast-grep/sgconfig.yml` runs without error
(0 rules yet is fine).

**Stage 2 — import canonical baseline bundles, deduped.**
What: copy the toolkit's `kotlin-rules` + `kotlin-multiplatform-rules`
LAW/style yamls into `.rules/kotlin/ast-grep/{rules,rules-style}/`,
EXCLUDING the flagged conflicts (`use-or-empty` — do not import;
`main-no-hardcoded-dispatchers`, `no-coroutine-scope-factory` — import only
if the orchestrator has adjudicated the conflict, otherwise skip and keep
the SDK-local rule from Stage 3 as the sole owner). Run each imported rule
against `src/` read-only (`ast-grep scan --rule <file> src/...`) to measure
real hit counts before enabling anything as blocking.
Why safe: still unwired from any hook; this is a read-only import + a
measurement pass.
Verify: `ast-grep scan --config .rules/kotlin/ast-grep/sgconfig.yml
src/commonMain/kotlin` runs and its finding counts are recorded (informs
Stage 3's grandfathering decisions); `bash .claude/hooks/rules/ci-gate.sh`
still green (still unwired).

**Stage 3 — migrate project-specific rules into lanes, with fixtures.**
What: for every `MOVE→rules/` / `MOVE→rules-style/` row in §3, copy the
YAML into the new taxonomy location (adjusting `files`/`ignores` globs to
this SDK's KMP source-set layout where the toolkit convention
[`**/main/**`, `**/*Main/**`] needs SDK-specific tightening — e.g. the
JVM-platform-OK carve-out four rules currently need scope narrowed to
commonMain+nativeMain only). Port each rule's manifest.json
`badExample`/`goodExample`/`note` into the toolkit's per-rule
`resources/hooks/claude/rule-proofs/<id>.json` four-case shape (`bad`,
`good`, `nearestLegalNeighbor`, `hunk`) — this is new fixture authoring, not
a mechanical copy, since the shapes differ (see §6). For the top 3 dedupe
collisions (§3.1), land the ADJUDICATED outcome, not a default.
Why safe: still unwired; this is where the actual content-authoring risk is
concentrated, so keep it isolated from any hook-wiring change.
Verify: for each moved rule, its own rule-proof (`bad` matches, `good`
does not) passes; `ast-grep scan --config .rules/kotlin/ast-grep/sgconfig.yml
src/` counts match the Stage 2 measurement pass plus the newly-added rules
with zero unexpected new matches beyond what was already known
(commonMain/jvmMain/nativeMain baseline).

**Stage 4 — swap in the toolkit hook modules + settings wiring, retire
`kotlin_antipattern_policy.py`.**
What: land `ast_grep_rules_policy.py` (ported `11_ast_grep_rules.py`,
patched for `MultiEdit` + JVM/consumer-tree carve-outs per §4.1), delete
`kotlin_antipattern_policy.py` and its manifest/rule-file dependencies once
parity is confirmed, resolve the two OPEN QUESTIONS from §4.2 (warn
surfacing, autofix UX) with an explicit decision recorded in this doc or a
follow-up. Author `orchestrator/stop.py` +
`modules/stop/ast_grep_normalize_policy.py` (+ the PostToolUse touch
producer if the corresponding open question is resolved "yes"). Wire
`settings.json`.
Why safe: this is the one genuinely risky stage (behavior of the live
PreToolUse gate changes) — do it in isolation, verified before touching
`ci-gate.sh` or deleting the old rule directory, so a regression is
attributable to this stage alone and revertible independently of Stages 5–6.
Verify: manually exercise a known-bad Kotlin edit through the new
PreToolUse chain (should block); exercise a known-good edit (should pass);
run the existing `.claude/hooks/tests/` suite for the retired module's
former coverage against the new module; `bash
.claude/hooks/rules/ci-gate.sh` still green (still points at the old rule
dir at this point, so this only proves the OTHER pretooluse modules —
`fleet_protocol_policy.py` etc. — still function under the same
dispatcher).

**Stage 5 — port the PORTABLE Python detector.**
What: author the two proposed `rules/` entries for
`detect-tool-identity-regressions.py` (§3, PORT row), prove them with
`dump_syntax_tree`-verified node kinds and `test_match_code_rule`
bad/good/near-neighbor fixtures per this repo's own rule-authoring
discipline; once proven equivalent on the real tree (`find_code_by_rule`
finds the same or a documented superset of what the regex script finds),
retire `detect-tool-identity-regressions.py` and its `ci-gate.sh` line.
Why safe: isolated, single-purpose, and only removes a KEEP-PYTHON→ast-grep
Python script after its replacement is proven on the real tree — no other
gate depends on it.
Verify: `python3 .claude/hooks/rules/detect-tool-identity-regressions.py
src/commonMain/kotlin --check` and the new ast-grep rule(s) produce the same
finding set on the current tree before the old script is deleted; `bash
.claude/hooks/rules/ci-gate.sh` green with the new rule wired into the
error-severity loop.

**Stage 6 — update `ci-gate.sh` + docs + dedupe map.**
What: repoint `ci-gate.sh`'s `RULES_DIR` and per-rule severity loop at
`.rules/kotlin/ast-grep/rules/` (and decide/implement a `rules-style/`
reporting step, mirroring today's 2-named-warning-rule report, per the §4.2
open question resolution); delete `.claude/hooks/rules/kotlin/`,
`manifest.json`, `autofix-registry.json` once nothing references them
(`detect-orphan-gates.py` will catch anything left dangling); update
`docs/enforcement-layers.md`, `docs/ast-grep-rule-audit.md`,
`data-class-budget.json` cross-references if paths changed, and CLAUDE.md's
"Ast-grep rule authoring" section to point at the new taxonomy paths and the
toolkit's rule-proof-harness fixture format instead of `manifest.json`.
Why safe: this is the cutover-completion stage — by now Stage 4/5 have
already proven the new wiring works; this stage just removes the old paths
and updates the gate script + docs to match, which `detect-orphan-gates.py`
and `ci-gate.sh` itself will catch if anything is missed.
Verify: `bash .claude/hooks/rules/ci-gate.sh` green end to end;
`detect-orphan-gates.py` clean; `git grep -n "hooks/rules/kotlin"` returns
only historical/changelog references, not live code paths.

**Stage 7 — final full-gate verification.**
What: full clean-checkout run of `bash .claude/hooks/rules/ci-gate.sh`,
`./gradlew check` (detekt + Konsist unaffected but must still pass), and a
manual PreToolUse/Stop smoke test (introduce a known LAW violation via
`Edit`, confirm block; let a Stop-eligible normalize-fixable pattern land,
confirm the Stop module fixes it without introducing a new LAW error).
Why safe: this is pure verification, no further changes.
Verify: all of the above green; `git status --short` shows the intended
final diff only.

---

## Section 6 — Risks & open questions

**Risks (concrete, evidence-backed):**

1. **ast-grep version mismatch is not hypothetical — it has already
   happened once.** The SDK pins `0.42.1`; the toolkit requires `0.44.0`;
   the locally installed binary on this machine is already `0.44.0`
   (verified live). The SDK's own `dev/campaigns/gate-hardening.toml`
   documents `ci-gate.sh`'s parity gate catching this exact drift once
   already. Bumping to 0.44.0 (Stage 0) must be done first and verified
   against the FULL existing 72-rule set before any taxonomy migration, so
   a match-count regression is attributable to the version bump, not the
   restructure.
2. **Fixture-format mismatch between `manifest.json` and the toolkit's
   rule-proof harness is real, not cosmetic.** SDK: one flat
   `manifest.json` list of `{id, severity, yaml, badExample, goodExample,
   hunkExpectation?, memberExamples?, hunkUnsafe?}`, validated by this
   repo's own `validate_rules.py` (parse/semantic/hunk/autofix modes).
   Toolkit: one JSON file per rule under `resources/hooks/claude/
   rule-proofs/<id>.json` with FOUR named cases (`bad`, `good`,
   `nearestLegalNeighbor`, `hunk`), each `{path, code}`, validated by
   `resources/hooks/claude/lib/rule_proof_harness.py` against a
   `rule-proof-budget.json`. A THIRD format also exists in the toolkit
   (`ast-grep/tests/*.yml` native `{id, valid:[...], invalid:[...]}`, used
   for normalize/codemod idempotence and LAW-clean proofs). Stage 3's
   fixture migration is genuine authoring work, not a mechanical
   reformat — budget accordingly.
3. **Node-kind rendering could differ under 0.44.0 vs the rules' authored
   grammar assumptions.** Several SDK rules have comments documenting
   grammar surprises found by direct calibration against the real tree
   (e.g. `no-empty-catch`'s toolkit comment: "an empty catch body `{}`
   produces NO statements node... the original form could never match" —
   a dead-pattern bug found and fixed at a SPECIFIC ast-grep version). Any
   rule imported or ported must be re-validated with
   `dump_syntax_tree`/`test_match_code_rule` against the pinned 0.44.0
   binary, not assumed correct because it worked at 0.42.1 or because the
   toolkit's own fixture passes in the toolkit's repo.
4. **Enforcement-strength regressions on lane-downgraded dedupe drops.**
   `no-var-in-object-declaration`, `no-sealed-interface`,
   `no-secondary-constructor`, `no-mutable-companion-state`,
   `no-public-mutable-var` are all today `severity: error` (block-lane) and
   their closest canonical equivalents live in `rules-style/` (opt-in,
   unscanned by default). A blind DROP-and-replace silently softens these
   from "Claude cannot write this" to "nobody is told about this at edit
   time." Two of the five (`no-var-in-object-declaration`→
   `no-object-singleton-state`, `no-sealed-interface`) have an independent
   Konsist Layer-2 backstop; the other three (`no-secondary-constructor`,
   `no-mutable-companion-state`, `no-public-mutable-var`) do not — these
   three are the highest-risk drops if executed naively.
5. **`rules-style/` being outside `sgconfig.yml`'s default `ruleDirs` is a
   silent behavior change for ~43 of the SDK's 72 rules**, not just a
   relabeling — see §4.2's first open question. This is the single largest
   UX-shape change in the whole migration and needs an explicit decision.
6. **`detect-tool-identity-regressions.py`'s PORT proposal has an
   unverified fidelity gap.** The safe-value-type-args check
   (`SAFE_VALUE_HINTS` against the declared collection's generic type
   argument) was not proven expressible as a single ast-grep `has:`/`regex:`
   chain in this pass — it needs a `dump_syntax_tree` probe on a real
   `mutableMapOf<String, List<ToolCall>>()`-shaped declaration before Stage
   5 can proceed with confidence that the port is not a silent
   precision/recall regression versus the current regex.
7. **The Stop-normalize module's correctness depends on new PostToolUse
   infrastructure this SDK has never run.** If the touch-producer is
   skipped (per the open §4.2 question resolving "no"), the fallback
   (git-diff + untracked, no session-touch restriction) risks normalizing
   pre-existing uncommitted WIP that predates the session — a real
   surprise-edit risk for a human developer mid-flow.

**Explicit uncertainties / guesses this blueprint made that the orchestrator
should resolve before execution:**

- Whether `no-not-null-assertion` ↔ `no-unsafe-bang-bang` and
  `no-console-output-in-library` ↔ `no-println-in-production`'s remaining
  `print`/`System.out`/`System.err` gap are exactly what's claimed above —
  confirmed via bad/good fixture semantics and toolkit YAML reads, but not
  every SDK rule's raw YAML `rule:` block was read byte-for-byte in this
  pass (72 rules; a handful were inferred from `manifest.json`'s
  `badExample`/`goodExample` pairs plus the id/severity, not the literal
  `rule:` block). Treat every `DROP` in §3 as "high confidence, re-verify
  with `test_match_code_rule` before executing," not as already proven.
- Whether `no-runblocking-in-common`'s exact `files:`/`ignores:` glob
  matches or diverges from canonical `no-runblocking-in-production`'s — the
  SDK rule's own YAML `files:`/`ignores:` fields were not directly read in
  this pass (only inferred from `ci-gate.sh`'s directory-scanning behavior
  and the manifest note). Flagged as "likely DROP, verify" rather than
  asserted.
- Whether `kotlin-android-rules`/`kotlin-backend-rules` are truly
  irrelevant to this SDK — assumed yes (no Android View/AsyncTask code, no
  JPA entities in the library), but `androidMain`/`jvmAndAndroidMain` source
  sets exist and were not audited for any Android-framework-adjacent code
  that might benefit from `no-asynctask`/`no-findviewbyid`.
- Whether the orchestrator wants the toolkit's exact `"NN_name.py"` hook
  filename convention preserved (eases future upstream diffing against the
  toolkit) or wants this repo's existing `snake_case_policy.py` convention
  kept (this blueprint defaulted to describing the latter but did not
  decide it — see §4.1).
- Whether `no-console-output-in-library`'s residual `print`/`System.out`/
  `System.err` coverage should become its own new small `rules/` entry
  (PORT-style) or simply justify keeping the whole SDK rule un-dropped —
  both are presented in §3 as options; no default was picked.

---

## Constraints honored

- Only `docs/reports/toolkit-standard-migration-plan.md` was created in this
  pass. No rule, hook, config, or ABI-dump file was modified. Nothing was
  staged or committed.
