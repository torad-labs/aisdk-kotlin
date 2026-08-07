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
  compose-flow's `setup.toml`. Nothing else diverges — promote improvements
  upstream via the toolkit pipeline, do not fork logic here.
- **Runner:** bun (`bun dev/campaigns/ledger.ts <ledger.toml> <cmd>`).
- **Predecessor:** `manifest.py` (torad-fleet lineage, vendored 2026-07-02)
  remains ONLY for the closed pre-migration campaigns
  (`gate-hardening.toml`, `style-rule-fixes.toml`,
  `stringly-domain-types/campaign.toml`) whose signature/proof format it owns.
  New campaigns use the bun CLI.
