# claude-directive-exception: estimation_deferral verbatim quotation of campaign ledger notes for the FR review; deferral wording here is inventoried data, not deferred work

# FR-04 Final Campaign Report (Fable/orchestrator) — gate-hardening campaign close

**Date:** 2026-07-03. **Tip:** 4297cc3 (beta-follow-ups). **Ledger:** 45 of 48 items verified; RR-06/RR-07 remain operator-gated (release execution); FR-04 closes with this report.

## Verdict

**v0.3.0-beta01 is READY to tag once the fold-in PR (beta-follow-ups → main, squash) merges green.** I adopt FR-03's verdict with its two recommended pre-tag ride-alongs now LANDED and its highest-value un-run proof now OBSERVED. Nothing outstanding blocks the tag. Release execution (fold-in → tag → first live publish → RR-07 release page) awaits the operator's go.

## Final 5-criteria scores (pre-campaign → close)

| Criterion | Before | After | Chief movers |
|---|---|---|---|
| API design & ABI stability | 7 | 8 | RR-01 (ctor demotion, proven on BOTH dumps), GH-23 set-ratchet, BP-01/BP-02 policies. Cap: 57 grandfathered data classes (tracked debt, BL-058). |
| Test quality | 7 | 8 | RR-03 deflake, 64/64 fixtures + hunk-mode, 26-check harness, 78 hook tests (38+26+6+5 after FX-01). Cap: ~44% branch coverage. |
| Robustness | 8 | 9 | GH-03/04 (now LIVE-PROVEN both directions), GH-12/13/19/22, FX-01 (both fail-opens closed), FX-03 (parity gate — caught real drift on first run). |
| Documentation | 5 | 8 | BP-01 evolution policy, INTERFACE_CONTRACT, CHANGELOG, GH-06 misfire protocol, BP-04 Dokka+Pages, BP-07 target matrix, BP-08 sample, FX-02 corrections. |
| Release engineering | 9 | 9 | Layered release.yml (dry-run proven, "would publish 38 artifacts"), BP-06 provenance, BP-03 snapshots, RR-04 protection, POM Central-compliant. Cap: first real publish is inherently unproven until it runs (R1). |

FR-03 scored release engineering 9 with the caps named; the FX wave and probes closed robustness caps F-01/F-02/R4 after that scoring. I keep the numbers as adjudicated — the improvements land inside the caps' stated rationale, not beyond the rubric.

## What the multi-model pipeline itself produced (stage → distinct contribution)

- **Haiku (FR-01):** full inventory + gate timing (~11s/commit). Its arithmetic and 3 workflow rows needed orchestrator correction — the model-tier law ("Haiku gathers, orchestrator judges") behaving exactly as designed. Incident: the machine-global anti-drift hook blocked its first run and it tried to sanitize quoted evidence; killed by operator, relaunched with the hook's documented exception directive.
- **Sonnet (FR-02):** the review's one genuinely new defect (F-01 fail-open in `validate_rules --manifest`), 24 evidence-backed holds, heavy verifies, and resolution of the timing anomaly (machine load, not gate property).
- **Opus (FR-03):** severity discrimination both directions (F-01 major→minor with reachability analysis; F-03 minor→by-design), corrected FR-02's imprecisions (second caller; `always()` aggregator), independently closed 3 pipeline gaps (klib ctor-clean, POM Central-compliant, snapshot version auto-derivation), and ranked the completeness critique that drove FR-04's probes.
- **Fable (FR-04, this stage):** hand re-probes confirmed F-01 (own repro), klib cleanliness (anchored grep after catching a substring artifact), and caught the **fixture-cwd trap** Opus's "2-line fix" would have tripped (harness spawns with cwd=fixture-dir → the fix needed a `--budget` flag, not a bare `__file__` anchor). Dispatched and verified the FX wave. Ran the live network-mask probes (PR #12, closed): **Probe A** — broken clone URL → freshness green-with-warning ("Unable to fetch… internal gates still run" annotation verbatim), verify/verify-apple green. **Probe B** — wrong reference pin → freshness genuinely red, verify + verify-apple still completed. R4 converted from reasoned to observed.
- **Live catch during verification:** FX-03's new ast-grep parity gate FAILED its first real execution — `~/.local/bin/ast-grep` had drifted 0.42.1 → 0.44.0 between FR-02's measurement and FX verification. F-05's "circumstantial match" was already broken hours after being measured. Remediated per the gate's own message (`npm ci`); CI structurally immune (gate step precedes `npm ci`, checksum-pinned binary). LP-1 in one act: the first execution of a new check caught a real, silent drift.

## Residual-risk register at close

- **R1 — first live Central publish** (Sonatype auth/GPG/async validation): OPEN by design, operator-gated; release.yml polls deployment status and fails closed on FAILED.
- **R2 — dark workflows** (snapshots/docs/canary arm only from main): OPEN until fold-in; canary consumer source pre-proven against mavenLocal; Pages enabled; snapshot version auto-derives.
- **R3 — validate_rules/budget fail-opens: CLOSED** (FX-01, verified by orchestrator repro + standing fail-direction tests).
- **R4 — network-mask fix unproven: CLOSED** (probes A+B observed live).
- **R5 — 57 grandfathered public data classes:** OPEN, ratcheted (set-tracking), migration tracked in docs/data-class-audit.md + BL-058.
- **R6 — native/provider runtime tested only in macOS CI:** OPEN, accepted for beta; verify-apple is a required check.
- **NEW (hygiene, post-tag): VERSION_NAME bump** after tagging so snapshots track the next unreleased version (recorded under RR-06).

## Operator decision points (in order)

1. **Fold-in PR**: beta-follow-ups → main, one PR, squash (your standing preference). Arms the three dark workflows; first snapshot publish fires on that merge.
2. **Tag v0.3.0-beta01** (RR-06): triggers release.yml live — first real Central publish + docs deploy + provenance attestation.
3. **RR-07**: GitHub Release page with CHANGELOG excerpt + beta stability promise.

Campaign close: 48 ledger items, 46 verified by independent re-execution, 2 held for release execution. Zero unverified claims remain in the ledger.
