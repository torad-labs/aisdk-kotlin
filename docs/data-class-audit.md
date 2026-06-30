# Public `data class` audit (BL-058/A1)

Policy and rationale: see [CLAUDE.md](../CLAUDE.md) → "Public value types".
Enforcement: the public data-class budget gate in `.claude/hooks/rules/ci-gate.sh`
(`detect-public-data-class-budget.py` + `data-class-budget.json`), a one-way
ratchet that blocks new public `data class`es and ratchets down as types migrate.

## Decision

- **Approach:** `@Poko class` for growable read-only types; builders for
  construct-types; `data class` only for genuinely-frozen small value types.
- **Scope:** audit all public `data class`es case-by-case.
- **Current budget:** `378` public `data class` declarations in commonMain (seed).

## Classification

_Pending — classification pass to enumerate every public `data class` as
DEMOTE-to-@Poko / KEEP-frozen / BUILDER-front, with a one-line reason each. This
table drives the batched migration; as each batch lands, lower the budget with
`detect-public-data-class-budget.py --update`._
