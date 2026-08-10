"""Issue, inspect and revoke the grant token — the ONLY issuance path in this repo.

WHY THIS IS A HOOK AND NOT A SCRIPT. The grant opens the load-bearing ledger commands
(`add-law`, `amend`), so what matters is not that issuance is *hard* but that it originates from a
principal the assistant cannot act as. UserPromptSubmit fires only on text a human typed into the
prompt line; an assistant emits tool calls and assistant messages and can never emit a user
prompt. A CLI subcommand or a helper script would hand the assistant the issue path and the gate
would be decorative. `.claude/hooks/grant-store.ts` says the same thing in its own header and
explicitly warns against giving any script an issue mode — this module is the sanctioned
counterpart it describes, written in Python because that is the hook runtime this repo already
has.

WHAT IT DOES NOT CLAIM. An assistant holding unrestricted Bash could write the token file
directly. Nothing on a filesystem prevents that. What the design buys is that the intended path is
operator-only and every other path is CONSPICUOUS — an unscoped Bash write the operator can see.

The token schema is owned by grant-store.ts (`Grant`, TOKEN_RELPATH, the 8h clamp); this writes
exactly that shape so the TypeScript reader stays the single source of truth for how it is read.

  /grant <reason>            issue for the default window
  /grant 30m <reason>        issue for an explicit window (s/m/h, clamped to 8h)
  /grant status              report whether a grant is live
  /grant revoke              drop the token
"""
from __future__ import annotations

import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from orchestrator.result import HookResult

MODULE_ORDER = 20
MODULE_NAME = "grant_issue_policy"

_REPO_ROOT = Path(__file__).resolve().parents[4]
# Mirrors TOKEN_RELPATH in .claude/hooks/grant-store.ts — that file owns the contract.
_TOKEN = _REPO_ROOT / ".claude" / ".grant.json"

DEFAULT_HOURS = 2.0
MAX_HOURS = 8.0

_DURATION = re.compile(r"^(\d+(?:\.\d+)?)(s|m|h)$", re.IGNORECASE)
_UNIT_HOURS = {"s": 1.0 / 3600.0, "m": 1.0 / 60.0, "h": 1.0}


def applies(data: dict[str, Any]) -> bool:
    prompt = str(data.get("prompt") or "").strip()
    return prompt.split(" ", 1)[0].lower() == "/grant" if prompt else False


def run(data: dict[str, Any]) -> HookResult | None:
    parts = str(data.get("prompt") or "").strip().split()
    rest = parts[1:]
    sub = rest[0].lower() if rest else ""

    if sub == "status":
        return _card(_status_lines())
    if sub == "revoke":
        if _TOKEN.exists():
            _TOKEN.unlink()
            return _card(["GRANT REVOKED", "  the load-bearing paths are closed again"])
        return _card(["GRANT REVOKE", "  nothing to revoke — no token present"])

    hours = DEFAULT_HOURS
    if rest and _DURATION.match(rest[0]):
        match = _DURATION.match(rest[0])
        assert match is not None
        hours = float(match.group(1)) * _UNIT_HOURS[match.group(2).lower()]
        rest = rest[1:]

    reason = " ".join(rest).strip()
    if not reason:
        return _card(
            [
                "GRANT NOT ISSUED — a reason is required",
                "  the reason is the audit trail; the token carries it",
                "  usage: /grant [30m] <why this is changing>",
            ]
        )

    clamped = min(max(hours, 0.05), MAX_HOURS)
    expires = datetime.now(timezone.utc) + timedelta(hours=clamped)
    grant = {
        # grant-store.ts re-clamps this on read, so an edited expiry is not honoured.
        "expiresAt": expires.isoformat().replace("+00:00", "Z"),
        "reason": reason,
        "grantedBy": "operator (typed /grant in the prompt line)",
        "sessionId": data.get("session_id"),
    }
    _TOKEN.parent.mkdir(parents=True, exist_ok=True)
    _TOKEN.write_text(json.dumps(grant, indent=2) + "\n", encoding="utf-8")

    return _card(
        [
            "GRANT ISSUED",
            f"  live until {grant['expiresAt']}",
            f"  reason: {reason}",
            "  covers the grant-gated ledger commands (add-law, amend)",
            "  revoke early with /grant revoke",
        ]
    )


def _status_lines() -> list[str]:
    grant = _read_live()
    if grant is None:
        return ["GRANT STATUS", "  none live — load-bearing paths are closed"]
    return [
        "GRANT STATUS",
        f"  live until {grant.get('expiresAt')}",
        f"  reason: {grant.get('reason')}",
    ]


def _read_live() -> dict[str, Any] | None:
    """Liveness with the same clamp grant-store.ts applies, so both readers agree."""
    if not _TOKEN.exists():
        return None
    try:
        grant = json.loads(_TOKEN.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None
    if not isinstance(grant, dict):
        return None
    raw = grant.get("expiresAt")
    if not isinstance(raw, str):
        return None
    try:
        expires = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
    now = datetime.now(timezone.utc)
    if expires <= now or expires > now + timedelta(hours=MAX_HOURS):
        return None
    return grant


def _card(lines: list[str]) -> HookResult:
    return HookResult(kind="warn", payload="\n".join(lines), module_name=MODULE_NAME)
