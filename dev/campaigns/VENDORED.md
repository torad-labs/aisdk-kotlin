# Vendored ledger machinery

Lineage (§16: vendor the canonical CLI per repo, run selftest at every
vendoring, note lineage, never hand-fork divergent logic):

- **Source:** `~/Documents/dev/infra/compose-flow/repo/dev` (the leading copy),
  copied 2026-08-06.
- **Files:** `dev/campaigns/{ledger-core,ledger}.ts`, `dev/manifest.ts`,
  `.claude/hooks/grant-store.ts`.
- **2026-10-03, the Oct 3 campaign rules (global CLAUDE.md §16-17):** the
  earned-row plane (`ledger-earn`, `earn-core`, `matrix-earn`, `review`,
  `hydrate`, `dev/matrix.ts`, `dev/gates/cli-selftest.ts`,
  `dev/earn-artifacts/`) had no consumer and was deleted. `ledger.ts` lost the
  grant wall on `add-law`/`amend-header`/`amend`, the `claim`/`release-stale`
  verbs and the "DO NOT COMMIT" packet; its packet now carries the four-step
  finish, and its selftest holds the properties `cli-selftest.ts` witnessed.
  `claimed_by`/`claimed_at` still parse so old rows read.
- **Grant issuance (no ledger consumer since 2026-10-03).** No ledger verb
  needs a grant any more. The issuer hook, `grant-store.ts` and their test below
  remain until `.claude/settings.json` drops the UserPromptSubmit wiring.
  The bun hook runtime (`runner.ts`, `registry.ts`, `modules/20-grant-issue.ts`) is NOT
  vendored — this repo's hooks are the Python orchestrator, and importing a
  second hook runtime to carry one command was disproportionate. Instead
  `.claude/hooks/modules/userpromptsubmit/grant_issue_policy.py` (wired via
  `.claude/hooks/orchestrator/userpromptsubmit.py` in settings.json) writes the
  same `Grant` token shape `grant-store.ts` reads, with the same 8h clamp.
  `grant-store.ts` remains the single source of truth for how a token is
  INTERPRETED; the hook owns only how one is CREATED.
  The security property is preserved rather than traded away: issuance sits on
  UserPromptSubmit, which fires only on text a human typed, so an assistant
  still cannot authorise itself. Never add an `issue()` mode to a script or CLI
  — that is the escape the whole design exists to prevent.
  Teeth: `.claude/hooks/tests/test_grant_issue_policy.py`, run by `ci-gate.sh`,
  fails if the reason requirement, the read-only subcommands, the token shape,
  or the `MAX_HOURS` clamp is weakened. (The `GUARDED` list in `grant-store.ts`
  names both Python paths for upstream parity, but is informational here: its
  consumer is not vendored.)

  **Typing `/grant` is the only sanctioned issuance.** A seat must never invoke
  the handler itself, and must never write `.claude/.grant.json` directly, even
  with after-the-fact operator authorization and even when it discloses doing
  so. `grant-store.ts` explains why: an attestation satisfied by the party the
  gate distrusts carries none of the property the gate exists for. A seat that
  needs a grant asks the operator to type one and waits. There is one recorded
  exception, on ledger items M05/L10, taken while this repo had no issuer at
  all; both entries are annotated as closed precedent, not as a pattern.
- **Runner:** bun (`bun dev/campaigns/ledger.ts <ledger.toml> <cmd>`).
- **Predecessor:** `manifest.py` (torad-fleet lineage, vendored 2026-07-02) was
  deleted on 2026-10-03. Every ledger here, `stringly-domain-types/campaign.toml`
  included, runs on the bun CLI, which reads `# LAW` lines in any form
  (`# LAW:`, `# LAW [date]:`, `# LAW LP-6 …`).
