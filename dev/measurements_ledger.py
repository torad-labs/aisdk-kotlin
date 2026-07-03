#!/usr/bin/env python3
"""measurements_ledger.py — sanctioned CLI for dev/measurements.toml.

This is adapted from qgre's measurements ledger for this repo's provenance:
measurements carry date, HEAD, exact command, and tool_versions. There is no
GPU/clocks provenance here because these gates are repository/toolchain metrics.

Law: measured-only, supersede-never-delete. Raw edits make numbers drift and
erase history; this CLI preserves the history and validates TOML after every
write. Docs cite keys such as `[meas: coverage_branch_percent]` instead of
copying values.

Usage:
  measurements_ledger.py list [--status current|superseded]
  measurements_ledger.py get <KEY>
  measurements_ledger.py supersede <KEY>
  measurements_ledger.py add --key K --value V --unit U --date D --head H
      --command CMD --tool-versions VERSIONS --note N [--spread S]
  measurements_ledger.py selftest

Optional first arg: a path to a measurements ledger, default dev/measurements.toml.
"""

from __future__ import annotations

import fcntl
import json
import os
import re
import shutil
import sys
import tempfile
import tomllib
from collections.abc import Callable

DEFAULT = os.path.join(os.path.dirname(__file__), "measurements.toml")
HDR = re.compile(r"^\[\[measurement\]\]\s*$")
KEYLINE = re.compile(r'^key\s*=\s*"([^"]+)"')
STATUSLINE = re.compile(r'^(status\s*=\s*")([a-z]+)(".*)$')
STATUSES = {"current", "superseded"}
REQUIRED_FIELDS = ["value", "unit", "date", "head", "command", "tool_versions", "note"]


def _toml_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def _read(path: str) -> list[str]:
    with open(path, encoding="utf-8") as file:
        return file.readlines()


def _blocks(lines: list[str]):
    starts = [index for index, line in enumerate(lines) if HDR.match(line)]
    for ordinal, start in enumerate(starts):
        end = starts[ordinal + 1] if ordinal + 1 < len(starts) else len(lines)
        key = None
        for index in range(start + 1, min(start + 4, end)):
            match = KEYLINE.match(lines[index])
            if match:
                key = match.group(1)
                break
        if key:
            yield key, start, end


def _find_all(lines: list[str], key: str) -> list[tuple[int, int]]:
    hits = [(start, end) for found, start, end in _blocks(lines) if found == key]
    if not hits:
        sys.exit(f"error: key {key!r} not found")
    return hits


def _find_current(lines: list[str], key: str) -> tuple[int, int]:
    for start, end in _find_all(lines, key):
        for index in range(start, end):
            match = STATUSLINE.match(lines[index])
            if match and match.group(2) == "current":
                return start, end
    sys.exit(f"error: key {key!r} has no CURRENT block")


def _validate_or_die(path: str, backup: str) -> None:
    try:
        with open(path, "rb") as file:
            tomllib.load(file)
    except Exception as exc:
        shutil.copy(backup, path)
        sys.exit(f"error: write produced invalid TOML ({exc}); ROLLED BACK")


def _locked_rewrite(path: str, mutate: Callable[[list[str]], list[str]]) -> None:
    with open(path, "a+", encoding="utf-8") as lock_file:
        fcntl.flock(lock_file, fcntl.LOCK_EX)
        lines = _read(path)
        backup = tempfile.NamedTemporaryFile(
            "w",
            delete=False,
            suffix=".measurements.bak",
            encoding="utf-8",
        )
        backup.writelines(lines)
        backup.close()
        try:
            new_lines = mutate(lines)
            with open(path, "w", encoding="utf-8") as file:
                file.writelines(new_lines)
                file.flush()
                os.fsync(file.fileno())
            _validate_or_die(path, backup.name)
        finally:
            os.unlink(backup.name)
            fcntl.flock(lock_file, fcntl.LOCK_UN)


def cmd_list(path: str, status: str | None = None) -> None:
    lines = _read(path)
    for key, start, end in _blocks(lines):
        current_status = value = unit = ""
        for line in lines[start:end]:
            if match := STATUSLINE.match(line):
                current_status = match.group(2)
            elif line.startswith("value"):
                value = line.split("=", 1)[1].strip()
            elif line.startswith("unit"):
                unit = line.split("=", 1)[1].strip().strip('"')[:60]
        if status and current_status != status:
            continue
        print(f"{key:42} {value:12} {current_status:10} {unit}")


def cmd_get(path: str, key: str) -> None:
    lines = _read(path)
    blocks = [lines[start:end] for start, end in _find_all(lines, key)]
    sys.stdout.write("\n".join("".join(block).rstrip() for block in blocks) + "\n")


def cmd_supersede(path: str, key: str) -> None:
    def mutate(lines: list[str]) -> list[str]:
        start, end = _find_current(lines, key)
        for index in range(start, end):
            match = STATUSLINE.match(lines[index])
            if match:
                lines[index] = f"{match.group(1)}superseded{match.group(3)}\n"
                return lines
        sys.exit(f"error: key {key!r} has no status line")

    _locked_rewrite(path, mutate)
    print(f"{key} -> superseded")


def cmd_add(path: str, key: str, status: str = "current", spread: str | None = None, **fields: str) -> None:
    missing = [field for field in REQUIRED_FIELDS if not fields.get(field)]
    if missing:
        sys.exit(f"error: missing required field(s): {', '.join(missing)}")
    if status not in STATUSES:
        sys.exit(f"error: status must be one of {sorted(STATUSES)}")
    try:
        float(fields["value"])
    except ValueError:
        sys.exit("error: --value must be a TOML number")

    def mutate(lines: list[str]) -> list[str]:
        for found, start, end in _blocks(lines):
            if found != key:
                continue
            for index in range(start, end):
                match = STATUSLINE.match(lines[index])
                if match and match.group(2) == "current":
                    sys.exit(
                        f"error: key {key!r} already has a CURRENT entry; "
                        "supersede it first"
                    )
        block = [
            "\n[[measurement]]\n",
            f"key = {_toml_string(key)}\n",
            f"value = {fields['value']}\n",
            f"unit = {_toml_string(fields['unit'])}\n",
        ]
        if spread:
            block.append(f"spread = {_toml_string(spread)}\n")
        block += [
            f"date = {_toml_string(fields['date'])}\n",
            f"head = {_toml_string(fields['head'])}\n",
            f"command = {_toml_string(fields['command'])}\n",
            f"tool_versions = {_toml_string(fields['tool_versions'])}\n",
            f"status = {_toml_string(status)}\n",
            f"note = {_toml_string(fields['note'])}\n",
        ]
        return lines + block

    _locked_rewrite(path, mutate)
    print(f"added {key}")


def cmd_selftest(path: str) -> None:
    tmp = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp.close()
    shutil.copy(path, tmp.name)
    fields = {
        "value": "1.0",
        "unit": "unitless (selftest)",
        "date": "2026-07-03",
        "head": "0000000",
        "command": "measurements_ledger.py selftest",
        "tool_versions": "selftest",
        "note": "selftest round-trip entry",
    }
    cmd_add(tmp.name, "ZZTEST", **fields)
    try:
        cmd_add(tmp.name, "ZZTEST", **fields)
        raise AssertionError("duplicate current add should fail")
    except SystemExit as exc:
        assert "already has a CURRENT entry" in str(exc), exc
    cmd_supersede(tmp.name, "ZZTEST")
    cmd_add(tmp.name, "ZZTEST", **fields)
    lines = _read(tmp.name)
    blocks = _find_all(lines, "ZZTEST")
    assert len(blocks) == 2, f"expected 2 ZZTEST blocks, got {len(blocks)}"
    with open(tmp.name, "rb") as file:
        tomllib.load(file)
    os.unlink(tmp.name)
    print("selftest OK (add/supersede/re-add + duplicate-current rejection + valid TOML)")


def main(argv: list[str]) -> None:
    args = list(argv[1:])
    path = DEFAULT
    if args and args[0].endswith(".toml"):
        path = args.pop(0)
    if not args:
        sys.exit(__doc__)
    command, rest = args[0], args[1:]
    if command == "list":
        status = None
        while rest:
            flag = rest.pop(0)
            if flag == "--status":
                status = rest.pop(0)
            else:
                sys.exit(f"unknown list flag {flag!r}")
        cmd_list(path, status)
    elif command == "get":
        if len(rest) != 1:
            sys.exit("error: get requires KEY")
        cmd_get(path, rest[0])
    elif command == "supersede":
        if len(rest) != 1:
            sys.exit("error: supersede requires KEY")
        cmd_supersede(path, rest[0])
    elif command == "add":
        kwargs: dict[str, str] = {}
        while rest:
            flag = rest.pop(0)
            if not flag.startswith("--") or not rest:
                sys.exit(f"error: malformed add argument {flag!r}")
            kwargs[flag.removeprefix("--").replace("-", "_")] = rest.pop(0)
        key = kwargs.pop("key", None)
        if not key:
            sys.exit("error: --key is required")
        cmd_add(path, key, **kwargs)
    elif command == "selftest":
        cmd_selftest(path)
    else:
        sys.exit(f"unknown command {command!r}\n{__doc__}")


if __name__ == "__main__":
    main(sys.argv)
