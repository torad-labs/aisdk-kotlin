# OpenSSF Best Practices Badge — submission dossier for torad-labs/aisdk-kotlin

Working document for earning the **passing** badge (closes code-scanning alert #15,
`CIIBestPracticesID`). Fetched live 2026-08-02; answers verified against the repo
the same day. Source of truth at paste time: <https://www.bestpractices.dev/en/criteria/0>.

## How to use this

1. Register: <https://www.bestpractices.dev/en/projects/new> → "Log in with GitHub"
   (OAuth scope `user:email, read:org`; auto-creates the account).
2. New project: repo URL **exactly** `https://github.com/torad-labs/aisdk-kotlin`
   (Scorecard matches on this string), homepage = same, badge series = **passing**
   (not baseline-1).
3. You land on `…/projects/<id>/passing/edit`. Work through the six panels; every
   answer below is pre-filled with status + justification text to paste.
4. Add the badge to README.md (replace `<id>`):

   ```markdown
   [![OpenSSF Best Practices](https://www.bestpractices.dev/projects/<id>/badge)](https://www.bestpractices.dev/projects/<id>)
   ```

5. Next weekly Scorecard run (or manual `workflow_dispatch`) detects the entry via
   `GET https://www.bestpractices.dev/projects.json?url=<encoded repo url>` and reads
   `badge_level`. NOTE: Scorecard maps passing → 5/10 (silver 7, gold 10). If alert
   #15 still shows open after detection, that is the check's cap, not a missing
   badge — the remediation it asks for is done.

Attainment rule (site text): all MUST/MUST NOT met; all SHOULD met or unmet-with-justification;
all SUGGESTED at least considered. Nothing at passing level requires 2FA, a second
maintainer, or signed releases.

## Verified repo facts used below (checked 2026-08-02)

- Release `v0.3.0-alpha01` (prerelease, human-written notes) + tags `v0.2.0`,
  `v0.3.0-alpha01`, `v0.3.0-beta01`; CHANGELOG.md maintained.
- Open Dependabot alerts: **0**. Secret scanning + push protection + Dependabot
  security updates: enabled server-side.
- Hosted Dokka reference docs: <https://torad-labs.github.io/aisdk-kotlin/> (GitHub
  Pages from `main`, serves Dokka HTML).
- Issues: 2 total, both responded (#42 answered by maintainer; #11 maintainer's own
  tracking issue). Majority-acknowledged ✓.
- No `http://` download links anywhere in docs (only `localhost` examples and one
  security-review prose discussion).
- SECURITY.md: private reporting via GitHub private vulnerability reporting + email;
  ack ≤3 business days, assessment ≤10, fix ≤90.

## Criterion-by-criterion answers (all 67)

Status · justification to paste (where required).

### Basics — website content

| # | Criterion | Answer |
|---|---|---|
| 1 | description_good (MUST) | **Met** — README opening describes the KMP AI SDK. |
| 2 | interact (MUST) | **Met** — README has Maven Central coordinates + Issues; CONTRIBUTING.md. |
| 3 | contribution (MUST, met-url) | **Met** — `https://github.com/torad-labs/aisdk-kotlin/blob/main/CONTRIBUTING.md` |
| 4 | contribution_requirements (SHOULD, met-url) | **Met** — same URL; Code Standards section (detekt/ktlint/Konsist, conventional PR titles). |

### Basics — FLOSS license

| # | Criterion | Answer |
|---|---|---|
| 5 | floss_license (MUST) | **Met** — Apache-2.0. |
| 6 | floss_license_osi (SUGGESTED) | **Met** — Apache-2.0 is OSI-approved. |
| 7 | license_location (MUST, met-url) | **Met** — `https://github.com/torad-labs/aisdk-kotlin/blob/main/LICENSE` |

### Basics — documentation

| # | Criterion | Answer |
|---|---|---|
| 8 | documentation_basics (MUST) | **Met** — README quickstart + docs/wiki. |
| 9 | documentation_interface (MUST) | **Met** — Hosted Dokka KDoc `https://torad-labs.github.io/aisdk-kotlin/` + committed ABI dumps (`api/*.api`) + INTERFACE_CONTRACT.md; explicitApi() forces a documented public surface. |

### Basics — other

| # | Criterion | Answer |
|---|---|---|
| 10 | sites_https (MUST) | **Met** — github.com, Maven Central, Pages all HTTPS. |
| 11 | discussion (MUST) | **Met** — GitHub Issues/PRs. |
| 12 | english (SHOULD) | **Met**. |
| 13 | maintained (MUST) | **Met** — active since 2026-06-03; pursuing this badge is itself evidence. |

### Change Control — repo

| # | Criterion | Answer |
|---|---|---|
| 14 | repo_public (MUST) | **Met**. |
| 15 | repo_track (MUST) | **Met** — git. |
| 16 | repo_interim (MUST) | **Met** — PR-based flow. |
| 17 | repo_distributed (SUGGESTED) | **Met** — git. |

### Change Control — versioning

| # | Criterion | Answer |
|---|---|---|
| 18 | version_unique (MUST) | **Met** — `0.2.0`, `0.3.0-alpha01`, `0.3.0-beta01`; snapshots timestamped. |
| 19 | version_semver (SUGGESTED) | **Met** — CHANGELOG follows SemVer. |
| 20 | version_tags (SUGGESTED) | **Met** — git tags per release. |

### Change Control — release notes

| # | Criterion | Answer |
|---|---|---|
| 21 | release_notes (MUST, met-url) | **Met** — `https://github.com/torad-labs/aisdk-kotlin/releases` + CHANGELOG.md (human-written, not raw VCS log). |
| 22 | release_notes_vulns (MUST, na-just) | **N/A** — "No publicly known vulnerabilities with CVE assignments have been fixed in any release." |

### Reporting — bugs

| # | Criterion | Answer |
|---|---|---|
| 23 | report_process (MUST, met-url) | **Met** — `https://github.com/torad-labs/aisdk-kotlin/issues` |
| 24 | report_tracker (SHOULD) | **Met** — GitHub Issues. |
| 25 | report_responses (MUST) | **Met** — both issues on file received responses; majority acknowledged. |
| 26 | enhancement_responses (SHOULD) | **Met** — same evidence. |
| 27 | report_archive (MUST, met-url) | **Met** — public issue archive (same URL). |

### Reporting — vulnerabilities

| # | Criterion | Answer |
|---|---|---|
| 28 | vulnerability_report_process (MUST, met-url) | **Met** — `https://github.com/torad-labs/aisdk-kotlin/blob/main/SECURITY.md` |
| 29 | vulnerability_report_private (MUST, met-url) | **Met** — SECURITY.md gives the private advisory form (`/security/advisories/new`) + email fallback. ⚠ one-click confirm: open the link once to see it renders a draft advisory. |
| 30 | vulnerability_report_response (MUST, na-just) | **N/A** — "No vulnerability reports received in the last 6 months." |

### Quality — build

| # | Criterion | Answer |
|---|---|---|
| 31 | build (MUST) | **Met** — `./gradlew build`, wrapper checked in. |
| 32 | build_common_tools (SUGGESTED) | **Met** — Gradle. |
| 33 | build_floss_tools (SHOULD) | **Unmet + justification**: "JVM/Native targets build with FLOSS-only tools (Gradle, Kotlin); the Android target requires the Android SDK, which is no-cost but not FLOSS." |

### Quality — tests

| # | Criterion | Answer |
|---|---|---|
| 34 | test (MUST) | **Met** — `src/*Test` suites; CONTRIBUTING documents `./gradlew check`; ci.yml runs them. |
| 35 | test_invocation (SHOULD) | **Met** — standard Gradle invocation. |
| 36 | test_most (SUGGESTED) | **Met** — branch coverage measured in `dev/measurements.toml` (Kover). |
| 37 | test_continuous_integration (SUGGESTED) | **Met** — required checks verify / verify-apple / dependency-review on every PR. |

### Quality — new-functionality testing

| # | Criterion | Answer |
|---|---|---|
| 38 | test_policy (MUST) | **Met** — CONTRIBUTING/CLAUDE.md: fixes need a test that fails on unmodified main. |
| 39 | tests_are_added (MUST) | **Met** — e.g. PRs #40/#41 shipped ast-grep rule fixtures with every gate change; ClusterFuzzLite fuzz target landed with its harness; provider golden-coverage manifest is enforced by tests. (Swap in preferred PR links at paste time.) |
| 40 | tests_documented_added (SUGGESTED) | **Met** — CONTRIBUTING Tests section. |

### Quality — warnings

| # | Criterion | Answer |
|---|---|---|
| 41 | warnings (MUST) | **Met** — Kotlin warnings + detekt + ktlint + Konsist + explicitApi(), all FLOSS. |
| 42 | warnings_fixed (MUST) | **Met** — detekt/ktlint failures gate CI (`verify` check, ci-gate.sh). |
| 43 | warnings_strict (SUGGESTED) | **Unmet** (considered): "detekt/ktlint/Konsist gates are maximally strict; compiler warnings not yet promoted to errors." |

### Security — knowledge (self-attested; the two items Marcos answers)

| # | Criterion | Answer |
|---|---|---|
| 44 | know_secure_design (MUST) | **Met** (self-attestation per BadgeApp design) — ⚠ Marcos confirms: knows Saltzer & Schroeder's principles per the criterion's details list. |
| 45 | know_common_errors (MUST) | **Met** (self-attestation) — ⚠ Marcos confirms: can name common vuln classes for an HTTP/API-client library (injection, SSRF, credential handling, insecure deserialization) + a mitigation each. |

### Security — crypto (library implements none; uniform justification)

Justification for all nine: "The library implements no cryptography; TLS for HTTPS
transport is delegated to the platform engine via the ktor client."

| # | Criterion | Answer |
|---|---|---|
| 46–54 | crypto_published, crypto_call, crypto_floss, crypto_keylength, crypto_working, crypto_weaknesses, crypto_pfs, crypto_password_storage, crypto_random | **N/A** (all allow N/A; the criterion details explicitly bless N/A when software doesn't directly use crypto). |

### Security — delivery

| # | Criterion | Answer |
|---|---|---|
| 55 | delivery_mitm (MUST) | **Met** — artifacts via Maven Central (HTTPS); repo via HTTPS/SSH. |
| 56 | delivery_unsigned (MUST) | **Met** — no `http://` hash downloads anywhere (verified by grep 2026-08-02). |

### Security — known vulnerabilities

| # | Criterion | Answer |
|---|---|---|
| 57 | vulnerabilities_fixed_60_days (MUST) | **Met** — 0 open Dependabot alerts (verified via API 2026-08-02); dependency-review gates PRs. |
| 58 | vulnerabilities_critical_fixed (SHOULD) | **Met** — none outstanding. |
| 59 | no_leaked_credentials (MUST) | **Met** — GitHub secret scanning + push protection enabled; `.github/workflows/secret-scan.yml`. |

### Analysis — static

| # | Criterion | Answer |
|---|---|---|
| 60 | static_analysis (MUST, met-just) | **Met** — justification: "CodeQL runs weekly and on every PR; detekt (custom rule pack), ktlint, and Konsist gate every PR via the required verify check." |
| 61 | static_analysis_common_vulnerabilities (SUGGESTED) | **Met** — CodeQL security queries. |
| 62 | static_analysis_fixed (MUST) | **Met** — code-scanning alerts fixed same-week (PRs #40/#41, Scorecard alert sweep 2026-08-02). |
| 63 | static_analysis_often (SUGGESTED) | **Met** — detekt every commit; CodeQL weekly + PRs. |

### Analysis — dynamic

| # | Criterion | Answer |
|---|---|---|
| 64 | dynamic_analysis (SUGGESTED) | **Met** — test suite on every PR + ClusterFuzzLite fuzzing (`cflite-pr.yml`). |
| 65 | dynamic_analysis_unsafe (SUGGESTED, na-just) | **N/A** — "Written in Kotlin, a memory-safe language; no C/C++ is produced." |
| 66 | dynamic_analysis_enable_assertions (SUGGESTED) | **Met** — Gradle Test tasks enable JVM assertions; fuzzers run instrumented builds. |
| 67 | dynamic_analysis_fixed (MUST, na-just) | **N/A** — "No medium+ exploitable vulnerabilities have been discovered via dynamic analysis." |

## Remaining maintainer items (4)

1. **know_secure_design** self-attestation (see #44).
2. **know_common_errors** self-attestation (see #45).
3. Open `https://github.com/torad-labs/aisdk-kotlin/security/advisories/new` once —
   confirm it renders a draft advisory form (#29).
4. Optional: swap the #39 evidence links for PRs you prefer.

## Scorecard (measured)

Met ≈ 52 · N/A ≈ 13 · Unmet+justification = 2 (both permitted: one SHOULD, one
SUGGESTED) · self-attested = 2. No MUST blocked.
