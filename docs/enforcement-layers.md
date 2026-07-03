# Architectural enforcement — the four layers

This SDK encodes a set of architectural tenets (idiomatic Kotlin, illegal states made
unrepresentable). They are not enforced once — they are enforced in **four complementary
layers**, each covering a gap the others structurally cannot. Removing any layer leaves a
hole; together they make a violation hard to write, impossible to commit, and visible to
every contributor.

```
        WHO it protects        WHEN it fires           SCOPE             can it be skipped?
─────────────────────────────────────────────────────────────────────────────────────────
1 ast-grep PreToolUse hook   Claude        before the edit lands   single file    n/a (pre-write)
2 detekt + Konsist (JVM)     everyone      IDE + `./gradlew check` single + whole  no (in `check`)
3 ci-gate.sh + nonintegrated everyone      pre-commit + CI         whole tree     no (non-bypassable)
4 the Kotlin compiler        everyone      compile                 type system    no
```

## Layer 1 — ast-grep PreToolUse hooks (`.claude/hooks/`)

71 ast-grep rules under `.claude/hooks/rules/kotlin/`, run by the orchestrator
(`.claude/hooks/orchestrator/pretooluse.py` → `modules/pretooluse/kotlin_antipattern_policy.py`)
**before an edit is written**. This is the fastest possible feedback — the bad code never
lands — but it only sees **Claude's** edits, and only one file at a time.

- **Incremental**: grandfathers existing matches, blocks *new* ones. `disabled_`-prefixed
  rules are staged (skipped until renamed to activate).
- **Node-scoped regex only**: `regex:` is always a predicate on a matched AST node (a
  `simple_identifier`, a `comment`), never raw-text matching.
- **Add a rule**: drop a `*.yaml` in `.claude/hooks/rules/kotlin/`, add it to
  `manifest.json`, add a test in `.claude/hooks/tests/`.

## Layer 2 — detekt + Konsist (the developer-facing lints)

Layer 1 protects Claude; **Layer 2 protects every developer**, in the IDE (detekt/Konsist
plugins) and in `./gradlew check`. This is the layer that makes the tenets real for human
contributors — the reason it exists.

### detekt custom rules (`:detekt-rules`)
Per-file, expression/statement-level tenets, mirroring the ast-grep rules, as detekt `Rule`
classes (`detekt-rules/src/main/kotlin/ai/torad/aisdk/detekt/`), registered via
`ToradRuleSetProvider` and the `META-INF/services` entry, activated under the `torad-aisdk`
block in `detekt.yml`. Each rule has a `detekt-test` unit test.

| detekt rule | mirrors ast-grep rule |
|---|---|
| `NoNotNullAssertion` | `no-not-null-assertion` |
| `NoJsonContainerForceCast` | `no-json-container-force-cast` |
| `NoInlineJsonInstance` | `no-inline-json-instance` |
| `NoDeferredWiringComment` | `no-deferred-wiring-comment` |
| `NoConsoleOutputInLibrary` | `no-console-output-in-library` |
| `NoFloatEqualityComparison` | `no-float-equality-comparison` |
| `PreferTypedErrorOverErrorCall` | `prefer-typed-error-over-error-call` |
| `PreferTypedErrorOverCheckNotNull` | `prefer-typed-error-over-checknotnull` |
| `PreferTypedErrorOverGenericThrow` | `prefer-typed-error-over-generic-throw` |
| `NoRunCatchingInSuspendFunction` | `no-runcatching-in-suspend` |

A handful of tenets are detekt **built-ins** (no custom rule needed — enabled via
`buildUponDefaultConfig` + `detekt.yml`): `EmptyCatchBlock`, `PrintStackTrace`,
`TooGenericExceptionCaught`, `SleepInsteadOfDelay`, `GlobalCoroutineUsage`,
`ForbiddenComment`(TODO).

**Add a detekt rule**: write the `Rule` + a `detekt-test` test in `:detekt-rules`, register
it in `ToradRuleSetProvider`, activate it in `detekt.yml`, and regenerate the baseline
(`./gradlew detektBaseline`) to grandfather any pre-existing hits.

### Konsist architecture tests (`src/jvmTest/.../arch/`)
Declaration- and whole-codebase-level invariants that a single-file lint structurally
cannot see — expressed as architecture tests that read like a spec and run in `check`:
e.g. *"every top-level function is a PascalCase factory"*, *"every `*Event` is an
`AgentEvent` subtype"*, *"no sealed interfaces"*, *"no declaration is unreferenced"* (the
cross-file non-integrated check). Konsist sees the full declaration graph, which neither
ast-grep nor a single-file detekt rule can.

### Why not ktlint?
ktlint's *formatting* rules already run here via `detekt-formatting`. ktlint is
formatting-first; these are semantic/structural rules, so they live in detekt (per-file)
and Konsist (architecture) instead of being forced into a formatting tool.

## Layer 3 — `ci-gate.sh` + non-integrated check (non-bypassable, whole-tree)

`.claude/hooks/rules/ci-gate.sh` runs the **same ast-grep rules** over the whole tree at
**commit time** (`.githooks/pre-commit`) and in **CI** (the "Architecture gate" step in
`.github/workflows/ci.yml`) — so the rules apply to *every* commit (human or agent), not
only Claude's edits. Plus `detect-nonintegrated-kotlin.py` — the cross-file
"declared-but-never-referenced (internal)" detector that a per-file hook can't see (until
Layer 2's Konsist subsumes it).

This layer is the backstop: it cannot be skipped (no `--no-verify`, no gate-skip env). A
hook that misfires gets *fixed*, never routed around.

## Layer 4 — the Kotlin compiler

`explicitApi()` + KGP ABI validation (`checkKotlinAbi`) + `expect`/`actual` + sealed
exhaustiveness do the work no linter should: the type system makes whole classes of illegal
state uncompilable. The other three layers exist to catch what the compiler *can't* express.

---

**The throughline**: a tenet worth enforcing is enforced at the earliest layer that can see
it, and re-enforced at every later layer that can too. Defense in depth, not a single gate.
