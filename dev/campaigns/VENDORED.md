# Vendored ledger/matrix/declare-and-earn machinery

Lineage (§16: vendor the canonical CLI per repo, run selftest at every
vendoring, note lineage, never hand-fork divergent logic):

- **Source:** `~/Documents/dev/infra/compose-flow/repo/dev` (the leading copy),
  copied 2026-08-06.
- **Files:** `dev/campaigns/{ledger-core,ledger,ledger-earn,earn-core,matrix-earn,review,hydrate}.ts`,
  `dev/matrix.ts`, `dev/manifest.ts`, `dev/gates/cli-selftest.ts`,
  `dev/earn-artifacts/` skeleton, `.claude/hooks/grant-store.ts`.
- **Local edits:** `review.ts` `defaultLedger()` points at
  `dev/campaigns/sdk-review.toml` (this repo's active campaign) instead of
  compose-flow's `setup.toml`. Nothing else diverges in LOGIC — promote
  improvements upstream via the toolkit pipeline, do not fork logic here.
- **Grant issuance is re-implemented in Python, not vendored.** The bun hook
  runtime (`runner.ts`, `registry.ts`, `modules/20-grant-issue.ts`) is NOT
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
- **Predecessor:** `manifest.py` (torad-fleet lineage, vendored 2026-07-02)
  remains ONLY for the closed pre-migration campaigns
  (`gate-hardening.toml`, `style-rule-fixes.toml`,
  `stringly-domain-types/campaign.toml`) whose signature/proof format it owns.
  New campaigns use the bun CLI.
