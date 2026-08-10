#!/usr/bin/env python3
"""Regression tests for the grant issuance policy — the witness the issuer lacked.

`grant_issue_policy.py` opens the two grant-gated ledger commands, so weakening it is exactly the
kind of change that must not land green. Without this file it could: nothing else in the repo
exercises the module, and the `GUARDED` list in grant-store.ts is informational here because its
consumer is not vendored.

What is pinned, in the terms the module's own contract states:
  · a grant with no reason writes NO token (the reason IS the audit trail)
  · `status` and `revoke` never write a token (only issuance does)
  · an issued token carries the full `Grant` shape grant-store.ts reads
  · the window is clamped to MAX_HOURS however large a duration is asked for

Any live token is saved and restored, so running the suite never kills a real grant.
"""
from __future__ import annotations

import json
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ORCHESTRATOR = ROOT / ".claude" / "hooks" / "orchestrator" / "userpromptsubmit.py"
# Mirrors TOKEN_RELPATH in .claude/hooks/grant-store.ts.
TOKEN = ROOT / ".claude" / ".grant.json"
MAX_HOURS = 8

failures: list[str] = []
ran = 0


def check(name: str, condition: bool) -> None:
    global ran
    ran += 1
    if not condition:
        failures.append(name)


def run_event(prompt: str) -> str:
    result = subprocess.run(
        [sys.executable, str(ORCHESTRATOR)],
        input=json.dumps({"prompt": prompt, "session_id": "grant-policy-test"}),
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
    )
    return result.stdout


def main() -> int:
    saved = TOKEN.read_text(encoding="utf-8") if TOKEN.exists() else None
    if TOKEN.exists():
        TOKEN.unlink()

    try:
        # A prompt that is not /grant must be inert — the hook sees every prompt in the session.
        out = run_event("please refactor the streaming path")
        check("an unrelated prompt produces no output", out.strip() == "")
        check("an unrelated prompt writes no token", not TOKEN.exists())

        # The reason is the audit trail; issuing without one must not mint a token.
        out = run_event("/grant")
        check("issuing without a reason writes no token", not TOKEN.exists())
        check("issuing without a reason explains why", "reason is required" in out)

        # Read-only and revoking subcommands must never mint.
        run_event("/grant status")
        check("status writes no token", not TOKEN.exists())
        run_event("/grant revoke")
        check("revoke writes no token", not TOKEN.exists())

        # A real issuance writes the shape grant-store.ts reads.
        run_event("/grant 30m widening the no-python sweep")
        check("issuing with a reason writes a token", TOKEN.exists())
        grant = json.loads(TOKEN.read_text(encoding="utf-8"))
        check(
            "token carries the Grant shape",
            {"expiresAt", "reason", "grantedBy", "sessionId"} <= set(grant),
        )
        check("token records the reason verbatim", grant.get("reason") == "widening the no-python sweep")
        check(
            "token attributes the grant to the operator",
            "operator" in str(grant.get("grantedBy", "")),
        )

        # The clamp is the safety property: an outsized window must not be honoured.
        run_event("/grant 72h sync the ledger schema")
        clamped = json.loads(TOKEN.read_text(encoding="utf-8"))
        expires = datetime.fromisoformat(clamped["expiresAt"].replace("Z", "+00:00"))
        ceiling = datetime.now(timezone.utc) + timedelta(hours=MAX_HOURS, minutes=1)
        check("an outsized window is clamped to the maximum", expires <= ceiling)

        # Revoke actually removes a live token.
        run_event("/grant revoke")
        check("revoke removes a live token", not TOKEN.exists())
    finally:
        if saved is not None:
            TOKEN.write_text(saved, encoding="utf-8")
        elif TOKEN.exists():
            TOKEN.unlink()

    if failures:
        for name in failures:
            print(f"not ok - {name}")
        print(f"FAILED {len(failures)}/{ran}")
        return 1
    print(f"ok {ran}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
