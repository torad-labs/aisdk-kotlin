# Security Policy

## Reporting a vulnerability

Report privately through GitHub private vulnerability reporting:

**https://github.com/torad-labs/aisdk-kotlin/security/advisories/new**

Do not open public issues for suspected credential leaks, supply-chain problems, or
exploitable behavior. If private reporting is unavailable to you, email
support@cost-shield.com with `aisdk-kotlin security` in the subject.

### What to expect

| Stage | Target |
| --- | --- |
| Acknowledgement of your report | within 3 business days |
| Initial assessment (severity, affected versions) | within 10 business days |
| Fix or documented mitigation for a confirmed issue | within 90 days of acknowledgement |

Reporters are credited in the advisory unless they ask otherwise. Coordinated disclosure
is preferred: please allow the assessment window before publishing.

## Supported versions

Pre-1.0, only the most recent published version receives security fixes. Fixes ship as a
new version — Maven Central coordinates are immutable, so a released artifact is never
replaced in place.

| Version | Supported |
| --- | --- |
| latest release | yes |
| older releases | no — upgrade to the latest |

## Scope

This artifact includes network-capable provider implementations: Ktor-backed providers,
Gateway, MCP transports, and OpenAI-compatible clients. Treat provider configuration as
security-sensitive — do not commit API keys, prefer environment or secret-store injection.

In scope, and worth reporting privately:

- credential exposure across origins (request headers forwarded to a provider-supplied
  URL, tokens reaching logs or telemetry)
- request-signing, retry, redaction, or transport defects
- deserialization, schema-validation, or tool-execution issues reachable from model output
- supply-chain problems in the published artifacts or their dependencies

Out of scope: vulnerabilities in a provider's own remote service (report those to the
provider), and advisories affecting only this repository's build tooling, which never
reach consumers of the published library.

## Verifying a release

Releases are published to Maven Central with PGP signatures, and GitHub Releases carry a
build-provenance attestation plus an SPDX SBOM. The release pipeline and its gates are
documented in [docs/beta-readiness.md](docs/beta-readiness.md).

## Reading our security signals

The code-scanning tab is kept actionable by construction: anything that appears there
is new and deserves attention. Signals are split across three channels:

| Channel | What it means |
| --- | --- |
| GitHub Issues (auto-filed by CI) | A regression — a watched score dropped below its floor, or a scheduled security workflow failed |
| Security → Code scanning | A new finding (CodeQL, secret scanning, or any Scorecard check not listed in the floors file) |
| Dependabot PRs | Dependency maintenance; security updates are enabled |

Three OpenSSF Scorecard checks are structurally capped for a solo-maintainer project
(Branch-Protection, Code-Review, and the SAST commit window — the first two require a
second human reviewer, the third counts bot-merges that trigger no CI). Their
permanently-red alerts were dismissed on 2026-08-02 with per-alert justification
comments, and the regression signal lives in
[`dev/scorecard-floors.toml`](dev/scorecard-floors.toml): a score dropping below its
recorded floor fails the weekly Scorecard run and auto-files an issue. Raise a floor
only in the same PR that raises the score.

This file is the human half of the contract; the machine half is
[`security-insights.yml`](security-insights.yml) (OpenSSF Security Insights spec).
