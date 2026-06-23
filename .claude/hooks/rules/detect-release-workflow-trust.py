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
        gate_position = publish.block.find("bash .claude/hooks/rules/ci-gate.sh")
        check_position = publish.block.find("./gradlew check")
        if gate_position == -1:
            issues.append("publish job must run the architecture gate")
        elif check_position != -1 and gate_position > check_position:
            issues.append("publish job must run the architecture gate before ./gradlew check")

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
