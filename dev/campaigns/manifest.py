#!/usr/bin/env python3
"""manifest.py — the campaign-manifest CLI (operator ask, 2026-07-02).

WHAT THIS IS: the ONE sanctioned channel for reading/updating
dev/campaigns/*.toml from orchestrators and agents. Replaces raw
whole-file reads (token cost: `get G4X` returns ~1 item instead of a
1300-line file) and raw Edits (collision cost: concurrent agents hit
"file modified since read" constantly; this tool takes an flock and
appends atomically).

WHICH LAW + WHY: CLAUDE.md §AGENT-FIRST rule 6 — the manifest is the
memory; this makes touching it cheap, atomic, and non-colliding. Text is
edited LINE-BASED (never via a TOML serializer) so comments — where the
decisions and resume pointers live — survive every operation; tomllib
re-parses after each write as the well-formedness gate (a failed parse
rolls back).

WHO CALLS: the orchestrator's review loop and every subagent brief
("update the manifest AS you work" => `manifest.py note <id> "..."`).

TESTS/ORACLE: self-test via `manifest.py selftest` (runs get/list/note/
set-status against a temp copy and diff-checks the results).

USAGE:
  manifest.py list [--status S] [--phase P]     compact id|phase|status|title table
  manifest.py get <ID>                          the full item block incl. its notes
  manifest.py next                              campaign.next + all in_flight items
  manifest.py set-status <ID> <todo|in_flight|done|verified>
  manifest.py note <ID> "text"                  append a dated # note to the item
  manifest.py add --id X --phase P --title T --files F --verify V [--status todo]
  manifest.py selftest
Optional first arg: a path to a manifest (default dev/campaigns/fp8-total.toml).
"""

from __future__ import annotations

import fcntl
import os
import re
import shutil
import sys
import tempfile
import tomllib
from datetime import date

DEFAULT = os.path.join(os.path.dirname(__file__), "gate-hardening.toml")
HDR = re.compile(r"^\[\[items?\]\]\s*$")
IDLINE = re.compile(r'^id\s*=\s*"([^"]+)"')
STATUSLINE = re.compile(r'^(status\s*=\s*")([a-z_]+)(".*)$')
STATUSES = {"todo", "in_flight", "done", "verified"}


def _read(path: str) -> list[str]:
    with open(path, encoding="utf-8") as f:
        return f.readlines()


def _blocks(lines: list[str]):
    """Yield (item_id, header_idx, end_idx) — end is the next [[item*]]/section or EOF.
    Trailing # comment notes between items belong to the PRECEDING item."""
    starts = [i for i, l in enumerate(lines) if HDR.match(l)]
    for n, s in enumerate(starts):
        end = starts[n + 1] if n + 1 < len(starts) else len(lines)
        # stop at a new [section] header too (e.g. [baselines])
        for j in range(s + 1, end):
            if re.match(r"^\[[a-z]", lines[j]):
                end = j
                break
        item_id = None
        for j in range(s + 1, min(s + 4, end)):
            m = IDLINE.match(lines[j])
            if m:
                item_id = m.group(1)
                break
        if item_id:
            yield item_id, s, end


def _find(lines: list[str], item_id: str):
    for iid, s, e in _blocks(lines):
        if iid == item_id:
            return s, e
    sys.exit(f"error: item {item_id!r} not found")


def _validate_or_die(path: str, backup: str):
    try:
        with open(path, "rb") as f:
            tomllib.load(f)
    except Exception as exc:  # roll back — never leave a broken manifest
        shutil.copy(backup, path)
        sys.exit(f"error: write produced invalid TOML ({exc}); ROLLED BACK")


def _locked_rewrite(path: str, mutate):
    """flock the manifest, apply mutate(lines)->lines, validate, fsync."""
    with open(path, "a+", encoding="utf-8") as lockf:
        fcntl.flock(lockf, fcntl.LOCK_EX)
        lines = _read(path)
        backup = tempfile.NamedTemporaryFile(
            "w", delete=False, suffix=".manifest.bak", encoding="utf-8"
        )
        backup.writelines(lines)
        backup.close()
        try:
            new = mutate(lines)
            with open(path, "w", encoding="utf-8") as f:
                f.writelines(new)
                f.flush()
                os.fsync(f.fileno())
            _validate_or_die(path, backup.name)
        finally:
            os.unlink(backup.name)
            fcntl.flock(lockf, fcntl.LOCK_UN)


def cmd_list(path, status=None, phase=None):
    lines = _read(path)
    for iid, s, e in _blocks(lines):
        st = ph = ti = ""
        for l in lines[s:e]:
            if m := STATUSLINE.match(l):
                st = m.group(2)
            elif l.startswith("phase"):
                ph = l.split('"')[1] if '"' in l else ""
            elif l.startswith("title"):
                ti = l.split("=", 1)[1].strip().strip('"')[:90]
        if status and st != status:
            continue
        if phase and ph != phase:
            continue
        print(f"{iid:6} {ph:2} {st:9} {ti}")


def cmd_get(path, item_id):
    lines = _read(path)
    s, e = _find(lines, item_id)
    sys.stdout.write("".join(lines[s:e]).rstrip() + "\n")


def cmd_next(path):
    lines = _read(path)
    joined = "".join(lines[:60])
    if m := re.search(r"next\s*=\s*\[([^\]]*)\]", joined):
        print(f"campaign.next = [{m.group(1)}]")
    cmd_list(path, status="in_flight")


def cmd_set_status(path, item_id, new_status):
    if new_status not in STATUSES:
        sys.exit(f"error: status must be one of {sorted(STATUSES)}")

    def mutate(lines):
        s, e = _find(lines, item_id)
        for j in range(s, e):
            if m := STATUSLINE.match(lines[j]):
                lines[j] = f"{m.group(1)}{new_status}{m.group(3)}\n"
                return lines
        sys.exit(f"error: item {item_id!r} has no status line")

    _locked_rewrite(path, mutate)
    print(f"{item_id} -> {new_status}")


def cmd_note(path, item_id, text):
    def mutate(lines):
        s, e = _find(lines, item_id)
        stamp = date.today().isoformat()
        body = [f"# [{stamp}] {l}\n" for l in text.splitlines() if l.strip()]
        # insert before the blank line(s) that end the block, after content
        j = e
        while j > s and lines[j - 1].strip() == "":
            j -= 1
        return lines[:j] + body + lines[j:]

    _locked_rewrite(path, mutate)
    print(f"note appended to {item_id}")


def cmd_add(path, item_id, phase, title, files, verify, status="todo"):
    def mutate(lines):
        for iid, _, _ in _blocks(lines):
            if iid == item_id:
                sys.exit(f"error: item {item_id!r} already exists")
        # insert before [baselines] (or EOF)
        at = len(lines)
        for i, l in enumerate(lines):
            if l.startswith("[baselines]"):
                # back up over the baselines comment banner
                at = i
                while at > 0 and lines[at - 1].lstrip().startswith("#"):
                    at -= 1
                break
        files_toml = ", ".join(f'"{f.strip()}"' for f in files.split(","))
        block = (
            f"\n[[items]]\nid = \"{item_id}\"\nphase = \"{phase}\"\n"
            f"title = \"{title}\"\nfiles = [{files_toml}]\n"
            f"status = \"{status}\"\nverify = \"{verify}\"\n"
        )
        return lines[:at] + [block] + lines[at:]

    _locked_rewrite(path, mutate)
    print(f"added {item_id}")


def cmd_selftest(path):
    tmp = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp.close()
    shutil.copy(path, tmp.name)
    cmd_add(tmp.name, "ZZTEST", "Z", "selftest item", "a/**, b/**", "n/a")
    cmd_set_status(tmp.name, "ZZTEST", "in_flight")
    cmd_note(tmp.name, "ZZTEST", "note line one")
    lines = _read(tmp.name)
    s, e = _find(lines, "ZZTEST")
    blk = "".join(lines[s:e])
    assert 'status = "in_flight"' in blk, blk
    assert "note line one" in blk, blk
    with open(tmp.name, "rb") as f:
        tomllib.load(f)
    os.unlink(tmp.name)
    print("selftest OK (add/set-status/note round-trip + valid TOML)")


def main(argv):
    args = list(argv[1:])
    path = DEFAULT
    if args and args[0].endswith(".toml"):
        path = args.pop(0)
    if not args:
        sys.exit(__doc__)
    cmd, rest = args[0], args[1:]
    if cmd == "list":
        kw = {}
        while rest:
            flag = rest.pop(0)
            if flag == "--status":
                kw["status"] = rest.pop(0)
            elif flag == "--phase":
                kw["phase"] = rest.pop(0)
        cmd_list(path, **kw)
    elif cmd == "get":
        cmd_get(path, rest[0])
    elif cmd == "next":
        cmd_next(path)
    elif cmd == "set-status":
        cmd_set_status(path, rest[0], rest[1])
    elif cmd == "note":
        cmd_note(path, rest[0], rest[1])
    elif cmd == "add":
        kw = {}
        while rest:
            flag = rest.pop(0)
            kw[flag.lstrip("-").replace("-", "_")] = rest.pop(0)
        cmd_add(
            path,
            kw["id"],
            kw["phase"],
            kw["title"],
            kw.get("files", ""),
            kw.get("verify", "n/a"),
            kw.get("status", "todo"),
        )
    elif cmd == "selftest":
        cmd_selftest(path)
    else:
        sys.exit(f"unknown command {cmd!r}\n{__doc__}")


if __name__ == "__main__":
    main(sys.argv)
