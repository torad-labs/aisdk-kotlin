# Consumer Migration Rules

This directory contains ast-grep rules for consumers migrating between SDK
versions. These are not repository invariants and are intentionally separate
from `.claude/hooks/rules/manifest.json`.

Run a migration rule against your source tree with:

```bash
ast-grep scan --rule docs/migrations/0.3.0-alpha01-to-beta01-result-constructors.yaml path/to/src
```

When a rule includes a safe `fix:`, apply it with:

```bash
ast-grep scan --update-all --rule docs/migrations/<rule-file>.yaml path/to/src
```

Rule files use the naming convention `<from>-to-<to>-<topic>.yaml`.

## 0.3.0-alpha01 to 0.3.0-beta01 Result Constructors

The beta API closes positional constructors for growable result holders:
`GenerateResult`, `GenerateTextResult`, `StepResult`,
`StructuredObjectFinish`, and `StructuredObjectPhase.Streaming` / `Done`.
There is no public drop-in constructor replacement by design. Consumers should
receive these values from SDK execution surfaces:

- `Agent.generate(...).first()` for `GenerateResult`
- `TextGenerator(model).generate(...).first()` for `GenerateTextResult`
- `Agent.events(...)` / `collectAgentEvents(...)` for step lifecycle data
- `StructuredObject` / `StructuredObjectGenerator` for structured object
  phases and finish callbacks

Tests that previously fabricated these result objects should prefer the shipped
`MockLanguageModel`, `MockLanguageModelTextOnly`, or
`MockLanguageModelToolThenText` helpers and exercise the public API that returns
the result. The constructor migration rule is therefore detection-only: it
points at the call site and names the public path, but it does not rewrite code.
