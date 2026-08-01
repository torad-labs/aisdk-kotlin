#!/usr/bin/env python3
"""Validate release workflow trust boundaries.

Release jobs run untrusted repository code before publishing artifacts. This
guard keeps read-only validation in preflight, requires every executable release
job to depend on it, and ensures package write permission is scoped only to the
publish job.
"""
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Job:
    name: str
    block: str


def top_level_permissions_block(text: str) -> str:
    match = re.search(r"(?m)^permissions:\n(?P<body>(?:^  .+\n)+)", text)
    return match.group("body") if match else ""


def parse_jobs(text: str) -> dict[str, Job]:
    jobs_start = re.search(r"(?m)^jobs:\s*$", text)
    if jobs_start is None:
        return {}
    tail = text[jobs_start.end() :]
    matches = list(re.finditer(r"(?m)^  ([A-Za-z0-9_-]+):\s*$", tail))
    jobs: dict[str, Job] = {}
    for index, match in enumerate(matches):
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(tail)
        name = match.group(1)
        jobs[name] = Job(name, tail[start:end])
    return jobs


def extract_needs(block: str) -> set[str]:
    scalar = re.search(r"(?m)^    needs:\s*([A-Za-z0-9_-]+)\s*$", block)
    if scalar:
        return {scalar.group(1)}
    inline = re.search(r"(?m)^    needs:\s*\[([^\]]+)]\s*$", block)
    if inline:
        return {item.strip() for item in inline.group(1).split(",") if item.strip()}
    block_match = re.search(r"(?ms)^    needs:\s*\n(?P<body>(?:      - .+\n)+)", block)
    if block_match:
        return {
            line.strip()[2:].strip()
            for line in block_match.group("body").splitlines()
            if line.strip().startswith("- ")
        }
    return set()


def has_packages_write(block: str) -> bool:
    return re.search(r"(?m)^      packages:\s*write\s*$", block) is not None


def runs_repo_code(block: str) -> bool:
    return any(
        needle in block
        for needle in (
            "run: ./gradlew",
            "run: node tools/",
            "bash .claude/hooks/rules/ci-gate.sh",
            "run: |\n          ./gradlew",
        )
    )


# --- credential isolation -------------------------------------------------------
#
# A job that can sign or publish must not also execute third-party code. The failure
# this prevents is not hypothetical: a dependency lifecycle script (npm postinstall),
# a cloned upstream repo, or a test-suite plugin running in the same job as
# SIGNING_KEY + id-token:write yields SIGNED malicious artifacts on Maven Central,
# which is unrecoverable for a published library.
#
# Deliberately NOT matched: ./gradlew publishAllPublicationsToLocalStagingRepository
# and the signing/attest/upload steps. Those MUST run in the credentialed job — the
# signed bundle cannot be produced anywhere else — so Gradle plugins do execute there
# by necessity. The win is removing the test suite, npm, and the upstream clone from
# the key's blast radius, not pretending a full SLSA build/publish split is reachable.
_PUBLISH_CREDENTIAL_PERMISSIONS = re.compile(
    r"(?m)^      (?:id-token|attestations|packages):\s*write\s*$"
)
_PUBLISH_CREDENTIAL_SECRETS = ("secrets.SIGNING_", "secrets.SONATYPE_")

_THIRD_PARTY_EXECUTION = (
    (r"\bnpm\s+(?:ci|install|i)\b", "npm install"),
    (r"\bpnpm\s+(?:install|i)\b", "pnpm install"),
    (r"\byarn\s+install\b", "yarn install"),
    (r"\bpip\s+install\b", "pip install"),
    (r"\bgit\s+clone\b", "git clone"),
    (r"\./gradlew\s+check\b", "./gradlew check"),
    (r"\bnode\s+tools/", "node tools/"),
)


def job_holds_publish_credentials(block: str) -> bool:
    if _PUBLISH_CREDENTIAL_PERMISSIONS.search(block):
        return True
    return any(secret in block for secret in _PUBLISH_CREDENTIAL_SECRETS)


def third_party_execution_hits(block: str) -> list[str]:
    return [label for pattern, label in _THIRD_PARTY_EXECUTION if re.search(pattern, block)]


# --- step-scoped credential exposure ---------------------------------------------
#
# The job-scoped check above deliberately tolerates Gradle in the publish job (the
# signed bundle cannot be produced anywhere else). What it was structurally blind to:
# a STEP whose `env:` exports the signing secrets ALSO launching a SECOND Gradle build
# — `tools/run-local-staging-smoke` (whose consumer builds resolve AGP/KMP plugin
# markers with no verification-metadata.xml of their own) or an explicit `-p`/`-b`
# build-root switch. That second build's plugins execute with SIGNING_* sitting in
# their process env. The pre-merge adversarial review of PR #14 found exactly this
# shape live in the bundle step. `env:` on a step is step-scoped in Actions, so the
# fix is a step split — and the wall must be STEP-scoped too: at job scope this
# pattern would misfire on the compliant split (the smoke stays in the publish job,
# in a later step that carries no secrets, because it consumes build/staging-deploy
# from the credentialed step).
_STEP_START_RE = re.compile(r"(?m)^      - ")
_SECOND_BUILD = (
    (r"tools/run-local-staging-smoke", "tools/run-local-staging-smoke (second, unverified Gradle build)"),
    (r"\./gradlew\s[^\n]*(?:\s-p\s|--project-dir\b)", "./gradlew -p/--project-dir (second build root)"),
    (r"\./gradlew\s[^\n]*(?:\s-b\s|--build-file\b)", "./gradlew -b/--build-file (second build script)"),
)


def job_steps(job_block: str) -> list[str]:
    starts = [m.start() for m in _STEP_START_RE.finditer(job_block)]
    return [job_block[s:e] for s, e in zip(starts, starts[1:] + [len(job_block)])]


# A JOB-level `env:` is inherited by every step, so secrets declared there make the whole
# job credentialed even when no step mentions them. Without this, moving SIGNING_KEY up to
# `jobs.publish.env` would leave every step looking secret-free and silently defeat the
# step-scoped check below.
#
# Matched narrowly (the job block's own 4-space `env:` mapping, ending at the next 4-space
# key such as `steps:`) rather than by scanning the whole job text: a job block CONTAINS
# its steps, so "any secret anywhere in the job" would classify every step as credentialed
# and re-create the job-scoped false positive this check exists to avoid — the compliant
# split keeps the secret-free smoke step in the same job as the credentialed one.
_JOB_ENV_RE = re.compile(r"(?m)^    env:\s*\n(?P<body>(?:^      .*\n|^\s*\n)*)")


def job_level_env(job_block: str) -> str:
    match = _JOB_ENV_RE.search(job_block)
    return match.group("body") if match else ""


def credentialed_step_second_build_hits(job_block: str) -> list[str]:
    inherited = any(secret in job_level_env(job_block) for secret in _PUBLISH_CREDENTIAL_SECRETS)
    hits: list[str] = []
    for step in job_steps(job_block):
        credentialed = inherited or any(secret in step for secret in _PUBLISH_CREDENTIAL_SECRETS)
        if not credentialed:
            continue
        hits.extend(label for pattern, label in _SECOND_BUILD if re.search(pattern, step))
    return hits


def npmrc_disables_scripts(workflow_path: Path) -> bool:
    """True when a tracked root .npmrc sets ignore-scripts=true (repo-wide opt-out)."""
    # .github/workflows/release.yml -> repo root is three parents up.
    npmrc = workflow_path.resolve().parent.parent.parent / ".npmrc"
    try:
        return re.search(r"(?m)^\s*ignore-scripts\s*=\s*true\s*$", npmrc.read_text(encoding="utf-8")) is not None
    except OSError:
        return False


def npm_without_ignore_scripts(block: str) -> bool:
    npm_calls = re.findall(r"\bnpm\s+(?:ci|install|i)\b[^\n]*", block)
    return any("--ignore-scripts" not in call for call in npm_calls)


def transitive_needs(jobs: dict[str, Job], start: str) -> set[str]:
    """Every job reachable from `start` through needs: edges."""
    seen: set[str] = set()
    frontier = [start]
    while frontier:
        current = frontier.pop()
        for dependency in extract_needs(jobs[current].block) if current in jobs else set():
            if dependency not in seen:
                seen.add(dependency)
                frontier.append(dependency)
    return seen


def validate(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    issues: list[str] = []
    top_permissions = top_level_permissions_block(text)
    if "contents: read" not in top_permissions:
        issues.append("top-level permissions must include contents: read")
    if "packages: write" in top_permissions:
        issues.append("top-level permissions must not grant packages: write")

    jobs = parse_jobs(text)
    preflight = jobs.get("preflight")
    if preflight is None:
        issues.append("release workflow must define a preflight job")
    else:
        for required in (
            "fetch-depth: 0",
            'test "${tag_version}" = "${project_version}"',
            "SNAPSHOT",
            'git merge-base --is-ancestor "${GITHUB_SHA}" origin/main',
        ):
            if required not in preflight.block:
                issues.append(f"preflight job is missing required guard: {required}")
        if has_packages_write(preflight.block):
            issues.append("preflight job must not have packages: write")

    for job in jobs.values():
        needs = extract_needs(job.block)
        if job.name != "preflight" and runs_repo_code(job.block) and "preflight" not in needs:
            issues.append(f"job {job.name} runs repo code before depending on preflight")
        if job.name != "publish" and has_packages_write(job.block):
            issues.append(f"job {job.name} must not have packages: write")
        step_hits = credentialed_step_second_build_hits(job.block)
        if step_hits:
            issues.append(
                f"job {job.name} exports publish credentials into a step that also launches a "
                f"second Gradle build ({', '.join(sorted(set(step_hits)))}); split the step so "
                "the secrets' env ends before the second build starts"
            )
        if job_holds_publish_credentials(job.block):
            hits = third_party_execution_hits(job.block)
            if hits:
                issues.append(
                    f"job {job.name} executes third-party code ({', '.join(hits)}) while holding "
                    "publish credentials; move verification into a contents: read job consumed via needs:"
                )
            if npm_without_ignore_scripts(job.block) and not npmrc_disables_scripts(path):
                issues.append(
                    f"job {job.name} runs npm without --ignore-scripts while holding publish credentials"
                )

    publish = jobs.get("publish")
    if publish is None:
        issues.append("release workflow must define a publish job")
    else:
        publish_needs = extract_needs(publish.block)
        for required_need in ("preflight", "verify-apple"):
            if required_need not in publish_needs:
                issues.append(f"publish job must need {required_need}")
        if not has_packages_write(publish.block):
            issues.append("publish job must explicitly scope packages: write")

        # The architecture gate must still guard every release, but it no longer has to
        # live INSIDE publish -- requiring that is what forced the gate and the signing
        # key into one job. Follow it to whichever job runs it, and require publish to
        # depend on that job so the ordering guarantee is preserved across the split.
        gate_jobs = [j for j in jobs.values() if "bash .claude/hooks/rules/ci-gate.sh" in j.block]
        if not gate_jobs:
            issues.append("release workflow must run the architecture gate before publishing")
        else:
            reachable = transitive_needs(jobs, "publish") | {"publish"}
            if not any(j.name in reachable for j in gate_jobs):
                issues.append(
                    "publish job must depend (directly or transitively) on the job running the architecture gate"
                )
            for gate_job in gate_jobs:
                gate_position = gate_job.block.find("bash .claude/hooks/rules/ci-gate.sh")
                check_position = gate_job.block.find("./gradlew check")
                if check_position != -1 and gate_position > check_position:
                    issues.append(
                        f"job {gate_job.name} must run the architecture gate before ./gradlew check"
                    )

    return issues


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("workflow", nargs="?", default=".github/workflows/release.yml")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    issues = validate(Path(args.workflow))
    if not issues:
        print("release workflow trust gate OK")
        return 0
    print("RELEASE WORKFLOW TRUST GATE FAILED:")
    for issue in issues:
        print(f"  - {issue}")
    return 1 if args.check else 0


if __name__ == "__main__":
    raise SystemExit(main())
