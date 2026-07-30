#!/usr/bin/env python3
"""
Wall for the class of bug this repo has produced FOUR times: credentials forwarded to a URL that
came out of a provider RESPONSE.

Instances, all real, all found by hand:
  * Fireworks image download forwarded Authorization to `result.sample`.
  * The FIX was a two-name denylist, so an unknown header name still leaked.
  * A caller plugin could re-add a credential after that policy ran.
  * Black Forest Labs forwarded the `x-key` API key to the signed delivery host — and the existing
    test ASSERTED that it did, pinning the leak as expected behaviour.

Per concept #924 prose cannot stop this: the next provider facade will forward headers because that
is what every sibling POST helper does. So the check is mechanical and narrow.

PREDICATE: a call that passes BOTH
  (a) a URL argument that is response-derived — an identifier like `pollResult.imageUrl`,
      `audioUrl`, `videoUrl`, `result.sample`, i.e. not built from `settings.baseURL` and not a
      literal, and
  (b) a header map argument.

That is exactly the Fireworks/BFL shape and nothing else. It deliberately does NOT flag the
hundreds of legitimate `client.request(baseURL-derived) { headers.forEach ... }` transport calls —
a gate that fires on the sanctioned pattern trains dismissal, which is how the three over-firing
style rules in this same PR came to be ignored.

Fail-closed: an unregistered site is an error, so a NEW provider download that forwards credentials
to a provider-supplied URL cannot land silently. `--update` re-seeds (review the diff).
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ALLOWLIST = Path(__file__).with_name("provider-asset-download-allowlist.json")
SOURCE_ROOT = ROOT / "src" / "commonMain" / "kotlin"

# Identifiers that name a URL taken out of a provider response rather than built from settings.
RESPONSE_DERIVED = re.compile(
    r"\b("
    r"\w*[Pp]ollResult\.\w+|"
    r"\w*(imageUrl|audioUrl|videoUrl|assetUrl|downloadUrl|fileUrl|sampleUrl)\b|"
    r"\w*[Rr]esult\.(sample|url)\b|"
    r"\w*[Rr]esponse\.(url|sample)\b"
    r")"
)
# A header map being handed to the same call.
HEADER_ARG = re.compile(r"\bheaders\b|\brequestHeaders\b|\bcallHeaders\b")
# Downloads read bytes; these are the byte-reading helpers in this codebase.
BYTE_HELPERS = ("DownloadImage", "GetBinary", "getFacadeBinary", "downloadImage", "downloadBinary")


def offending_sites() -> list[tuple[str, int, str]]:
    found: list[tuple[str, int, str]] = []
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        lines = path.read_text(encoding="utf-8").split("\n")
        for index, line in enumerate(lines):
            if not any(helper in line for helper in BYTE_HELPERS):
                continue
            if "fun " in line:  # the declaration, not a call
                continue
            # Calls can wrap; join a small window so multi-line argument lists are seen whole.
            call = " ".join(lines[index : index + 4])
            call = call.split(")")[0] if call.count(")") else call
            if RESPONSE_DERIVED.search(call) and HEADER_ARG.search(call):
                found.append((str(path.relative_to(ROOT)), index + 1, line.strip()[:100]))
    return found


def main() -> int:
    update = "--update" in sys.argv
    current = offending_sites()
    data = (
        json.loads(ALLOWLIST.read_text(encoding="utf-8"))
        if ALLOWLIST.exists()
        else {"schemaVersion": 1, "reviewedCredentialForwardingSites": {}}
    )
    reviewed = data.get("reviewedCredentialForwardingSites", {})

    if update:
        data["reviewedCredentialForwardingSites"] = {
            rel: reviewed.get(rel, "UNREVIEWED — say why credentials may reach this provider URL")
            for rel, _line, _src in current
        }
        ALLOWLIST.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        print(f"credential-forwarding allowlist re-seeded with {len(current)} site(s)")
        return 0

    unregistered = [s for s in current if s[0] not in reviewed]
    if unregistered:
        print("provider asset-download credential gate FAILED:")
        for rel, line, src in unregistered:
            print(f"  {rel}:{line} sends caller headers to a response-derived URL — {src}")
        print()
        print("  A URL that came from a provider response is not a destination you control.")
        print("  Download with NO caller headers (Fal/Luma/Replicate/xAI all do), or route through")
        print("  the per-hop origin policy, or register the site with a reason if it is genuinely")
        print("  same-origin. Naming the credential headers to strip is NOT a fix: that denylist")
        print("  shape is what leaked twice already.")
        return 1

    print(f"provider asset-download credential gate OK: {len(current)} forwarding site(s), all reviewed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
