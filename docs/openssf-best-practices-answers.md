# OpenSSF Best Practices badge — prepared answers

Answer sheet for <https://www.bestpractices.dev> (the BadgeApp, formerly CII Best
Practices). Registration needs a real GitHub sign-in and cannot be automated, so this
exists to make it copy-paste: sign in, add
`https://github.com/torad-labs/aisdk-kotlin`, then work down the form.

Closes Scorecard alert #15 (`CIIBestPractices`, low) once the badge reaches *passing*.
Scorecard reads the badge's published level, so the alert clears only after the form is
submitted — nothing in this repository can clear it on its own.

**Every claim below was checked against the repository, not assumed.** One gap is left,
and it is the only thing here that cannot be done from a commit; everything else is
already true and only needs the justification text pasted into the form.

---

## The one thing left to do

| Criterion | What is missing | Fix |
| --- | --- | --- |
| `homepage_url` | The repo's **Homepage** field is empty (`homepage: null` from the API). | Repo → About → ⚙. Set it to the docs Pages URL or to the repo URL. Left for you rather than guessed at, since Pages may not be live yet. |

Topics are also empty on the repo. Not a badge criterion, but it is the same one-minute
trip to the About panel.

Already closed while preparing this sheet:

- **`test_policy`** — `CONTRIBUTING.md` had *Development*, *Code Standards*, and *Commit
  Style*, but never stated that new functionality requires tests. The project did this in
  practice; the criterion asks for it to be **documented**. A "Tests" section now says so,
  including the fix-needs-a-test-that-fails-on-unmodified-`main` rule.
- **`discussion`** — already satisfied by GitHub Issues (`has_issues: true`). The form
  wants a URL: `https://github.com/torad-labs/aisdk-kotlin/issues`.

---

## Basics

| Criterion | Answer | Justification to paste |
| --- | --- | --- |
| `description_good` | Met | README.md opens with what the library is and who it targets (Kotlin Multiplatform: JVM, Android, Native/iOS). |
| `interact` | Met | `CONTRIBUTING.md` documents the development loop and the required gate. |
| `contribution` | Met | `CONTRIBUTING.md`. |
| `contribution_requirements` | Met | `CONTRIBUTING.md` §Code Standards and §Commit Style; enforced mechanically by `.claude/hooks/rules/ci-gate.sh` in pre-commit and CI. |
| `license_location` | Met | `LICENSE` at the repository root. |
| `floss_license` / `floss_license_osi` | Met | Apache-2.0 (OSI-approved). |
| `homepage_url` | **Open** | Set the repo Homepage field — see above. |
| `sites_https` | Met | Project and repo are served by GitHub over HTTPS. |
| `discussion` | Met | GitHub Issues. |
| `english` | Met | All documentation is in English. |
| `maintained` | Met | Active development; see the commit history. |

## Change control

| Criterion | Answer | Justification to paste |
| --- | --- | --- |
| `repo_public`, `repo_track`, `repo_distributed` | Met | Public Git repository on GitHub. |
| `repo_interim` | Met | `main` carries interim work between releases. |
| `version_unique` | Met | `VERSION_NAME` in `gradle.properties` is the single source; Maven Central coordinates are immutable, so a version is never reused. |
| `version_semver` | Met | Semantic versioning with pre-release identifiers, e.g. `0.3.0-beta01`. |
| `version_tags` | Met | Releases are tagged `v${project_version}`; the tag is an **output** of a promotion (`.github/workflows/release.yml`), derived from the project version rather than being the trigger. |
| `release_notes` | Met | `CHANGELOG.md`, and the beta-readiness gate fails a release whose `VERSION_NAME` has no entry. |
| `release_notes_vulns` | Met | `SECURITY.md` documents that fixes ship as a new version and are described in the advisory. |

## Reporting

| Criterion | Answer | Justification to paste |
| --- | --- | --- |
| `report_process`, `report_tracker`, `report_archive` | Met | GitHub Issues; publicly archived. |
| `report_responses`, `enhancement_responses` | Met | Maintained; see issue history. |
| `vulnerability_report_process` | Met | `SECURITY.md` — GitHub private vulnerability reporting, with an email fallback. |
| `vulnerability_report_private` | Met | Private reporting is enabled: <https://github.com/torad-labs/aisdk-kotlin/security/advisories/new>. |
| `vulnerability_report_response` | Met | `SECURITY.md` commits to acknowledgement within 3 business days, assessment within 10, and a fix or documented mitigation within 90 days. |

## Quality

| Criterion | Answer | Justification to paste |
| --- | --- | --- |
| `build`, `build_common_tools`, `build_floss_tools` | Met | Gradle with the committed wrapper; all build tooling is FLOSS. |
| `test`, `test_invocation` | Met | `./gradlew check` runs the multiplatform test suite. |
| `test_most` | Met | Branch coverage is tracked in `dev/measurements.toml` (`[meas: coverage_branch_percent]`) and enforced by Kover. |
| `test_continuous_integration` | Met | `.github/workflows/ci.yml` runs on every push and pull request across JVM, Android, and Apple targets. |
| `test_policy` | Met | `CONTRIBUTING.md` §Tests requires tests for new functionality, and requires a bug fix to ship a test that fails on unmodified `main`. |
| `tests_are_added`, `tests_documented_added` | Met | Follows from §Tests above. |
| `warnings`, `warnings_fixed`, `warnings_strict` | Met | `explicitApi()`, detekt with a ratcheted baseline budget, ktlint via detekt-formatting, Konsist architecture tests, and a 127-rule ast-grep package. Budgets are one-way ratchets, so warning counts cannot silently grow. |

## Security

| Criterion | Answer | Justification to paste |
| --- | --- | --- |
| `know_secure_design`, `know_common_errors` | Met | Answer for yourself; the repo evidences it (credential-forwarding gate, redaction of telemetry, an explicit transport trust boundary in the release workflow). |
| `crypto_published`, `crypto_call`, `crypto_floss` | Met | The library calls platform crypto and standard algorithms (AWS SigV4 signing, MCP OAuth); it implements no bespoke primitive. |
| `crypto_random` | Met | `SecureRandom.kt` delegates to each platform's CSPRNG. |
| `crypto_password_storage` | N/A | The library stores no passwords. |
| `crypto_keylength`, `crypto_working`, `crypto_weaknesses`, `crypto_pfs` | Met / N/A | TLS is provided by the platform HTTP client (Ktor); the library neither pins nor downgrades it. |
| `delivery_mitm` | Met | Published to Maven Central over HTTPS with detached PGP signatures; GitHub Releases additionally carry a build-provenance attestation and an SPDX SBOM. |
| `delivery_unsigned` | Met | `tools/check-release-bundle` fails the release if **any** staged artifact lacks a `.asc`. |
| `vulnerabilities_fixed_60_days`, `vulnerabilities_critical_fixed` | Met | Dependabot is at **zero** open advisories, driven there by version pins rather than dismissals. Build-classpath advisories are treated as in scope because a compromised build is a supply-chain compromise. |
| `no_leaked_credentials` | Met | gitleaks runs in CI with a repo-specific `.gitleaks.toml`. |

## Analysis

| Criterion | Answer | Justification to paste |
| --- | --- | --- |
| `static_analysis` | Met | detekt (plus custom rules in `:detekt-rules`), Konsist architecture tests, a 127-rule ast-grep package, and CodeQL. |
| `static_analysis_common_vulnerabilities` | Met | CodeQL (`.github/workflows/codeql.yml`), on push, PR, and weekly. |
| `static_analysis_fixed` | Met | The architecture gate is a merge blocker and budgets ratchet downward only. |
| `static_analysis_often` | Met | Every commit — pre-commit hook and CI run the same `ci-gate.sh`. |
| `dynamic_analysis` | Met | ClusterFuzzLite fuzzes changed code on every pull request (`.clusterfuzzlite/`, `.github/workflows/cflite-pr.yml`), with a Jazzer harness over the partial-JSON repair state machine. Property-based fuzzing of the same code also runs in the normal test suite (`FixJsonFuzzTest`). |
| `dynamic_analysis_unsafe` | N/A | Kotlin is memory-safe; the library contains no unsafe/native memory code. |
| `dynamic_analysis_enable_assertions` | Met | Tests run with assertions enabled. |
| `dynamic_analysis_fixed` | Met | A fuzzer crash fails the pull request. |
