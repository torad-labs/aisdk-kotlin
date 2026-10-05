# Vendored ledger machinery

Lineage (§16: vendor the canonical CLI per repo, run selftest at every
vendoring, note lineage, never hand-fork divergent logic):

- **Source:** `~/Documents/dev/infra/compose-flow/repo/dev` (the leading copy),
  copied 2026-08-06.
- **Files:** `dev/campaigns/{ledger-core,ledger}.ts`, `dev/manifest.ts`.
- **2026-10-03, the Oct 3 campaign rules (global CLAUDE.md §16-17):** the
  earned-row plane (`ledger-earn`, `earn-core`, `matrix-earn`, `review`,
  `hydrate`, `dev/matrix.ts`, `dev/gates/cli-selftest.ts`,
  `dev/earn-artifacts/`) had no consumer and was deleted. `ledger.ts` lost the
  grant wall on `add-law`/`amend-header`/`amend`, the `claim`/`release-stale`
  verbs and the "DO NOT COMMIT" packet; its packet now carries the four-step
  finish, and its selftest holds the properties `cli-selftest.ts` witnessed.
  `claimed_by`/`claimed_at` still parse so old rows read.
- **Grant issuance removed 2026-10-03.** No ledger verb needs a grant, so the
  `/grant` issuer (`.claude/hooks/modules/userpromptsubmit/grant_issue_policy.py`),
  its token reader `.claude/hooks/grant-store.ts` and
  `test_grant_issue_policy.py` were deleted. The UserPromptSubmit orchestrator
  stays wired in `.claude/settings.json` with no modules to load, a no-op,
  until the operator drops that wiring.
- **Runner:** bun (`bun dev/campaigns/ledger.ts <ledger.toml> <cmd>`).
- **Predecessor:** `manifest.py` (torad-fleet lineage, vendored 2026-07-02) was
  deleted on 2026-10-03. Every ledger here, `stringly-domain-types/campaign.toml`
  included, runs on the bun CLI, which reads `# LAW` lines in any form
  (`# LAW:`, `# LAW [date]:`, `# LAW LP-6 …`).
