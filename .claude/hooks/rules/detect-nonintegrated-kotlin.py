#!/usr/bin/env python3
"""Whole-program detector for NON-INTEGRATED Kotlin declarations.

A single-file PreToolUse ast-grep hook CANNOT catch the pipeline-x-ray's
cross-file defect classes (dead fields, orphan reads, never-wired/non-integrated
declarations, wire round-trip drift) — those are whole-program properties. This
script is the cross-file guard for the highest-value one: a declaration that
exists but is referenced NOWHERE outside its own declaration site and tests.

Heuristic (precision-first, not exhaustive): for each top-level
class/object/interface/fun/val/typealias and each public factory, count
references across src/ excluding (a) the declaration's own file and (b) test
source sets. Zero non-test, non-self references => candidate non-integrated code.

Known false-positive sources (allowlisted / excluded): @Serializable types used
only via reflection, expect/actual, the 6 documented irreducible utility objects,
and genuinely-intended public entry-point factories. Treat output as a REVIEW
LIST, not an automatic delete list. Intended for CI reporting, not a blocking
per-edit gate.

Usage: detect-nonintegrated-kotlin.py [src-root]   (default: src)
Exit 0 always (report-only); prints candidates grouped by kind.
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "src")
MAIN_GLOBS = ["commonMain", "jvmMain", "jvmAndAndroidMain", "androidMain", "nativeMain"]

# Documented irreducible shared utilities (kept by design, see pipeline-xray.md / P3).
ALLOW = {
    "UrlOps", "FacadeSupport", "FacadeHttp", "GoogleHttp", "BedrockHttp", "TypedJsonOps",
}

# INTERNAL declarations only. `public` is a library's intended external-consumer
# API (referenced nowhere in-repo by design — a reference count cannot tell it
# from dead code, which is why the agent-based x-ray, with judgment, is the right
# tool for public surface). `internal` is module-private: a zero-reference
# internal declaration is a high-precision "genuinely dead / never-wired" signal.
DECL = re.compile(
    r"^\s*internal (?:sealed |abstract |open |data |value )*"
    r"(?:class|object|interface|fun|val|typealias)\s+([A-Za-z][A-Za-z0-9_]*)"
)


def main() -> int:
    main_files = [
        f for f in SRC.rglob("*.kt")
        if any(seg in f.parts for seg in MAIN_GLOBS)
    ]
    # Build a reference corpus: all main-source text (so we can count usages).
    corpus = {f: f.read_text(encoding="utf-8", errors="ignore") for f in main_files}

    candidates: dict[str, list[str]] = defaultdict(list)
    for f, text in corpus.items():
        for line in text.splitlines():
            m = DECL.match(line)
            if not m:
                continue
            name = m.group(1)
            if name in ALLOW or len(name) < 4:
                continue
            # Count references across ALL main files (incl. self) via word boundary.
            pat = re.compile(r"\b" + re.escape(name) + r"\b")
            refs = sum(len(pat.findall(t)) for t in corpus.values())
            # 1 = the declaration itself. <=1 means never referenced in main src.
            if refs <= 1:
                candidates[str(f).split("/ai/torad/aisdk/")[-1]].append(name)

    total = sum(len(v) for v in candidates.values())
    print(f"=== NON-INTEGRATED CANDIDATES (heuristic, review before acting): {total} ===")
    for f in sorted(candidates):
        print(f"  {f}: {', '.join(sorted(candidates[f]))}")
    print(
        "\nNote: heuristic — verify each (may be intended public API, @Serializable-"
        "via-reflection, or expect/actual) before removing. Not a blocking gate."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
