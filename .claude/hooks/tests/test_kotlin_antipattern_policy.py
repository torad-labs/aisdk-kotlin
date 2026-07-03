#!/usr/bin/env python3
"""Tests for the repo-local Kotlin anti-pattern policy.

Target architecture = decision C (Kotlin-native, class-based). The defining rule is
no-camelcase-top-level-function: logic lives on types / in cohesive units, so the ONLY
legal top-level callable is a PascalCase factory faux-constructor. Carriers below are
class members / factories so each case isolates the rule under test.
"""
from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HOOKS_ROOT = ROOT / ".claude" / "hooks"
ADAPTER = ROOT / ".codex" / "hooks" / "claude_compat.py"
sys.dont_write_bytecode = True
sys.path.insert(0, str(HOOKS_ROOT))

failures: list[str] = []
ran = 0


def check(name: str, condition: bool) -> None:
    global ran
    ran += 1
    if not condition:
        failures.append(name)


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


policy = load_module(
    "kotlin_antipattern_policy",
    HOOKS_ROOT / "modules" / "pretooluse" / "kotlin_antipattern_policy.py",
)


def kind_of(content: str, path: str):
    r = policy.run({"tool_name": "Write", "tool_input": {"file_path": path, "content": content}})
    return None if r is None else r.kind


with tempfile.TemporaryDirectory() as tmp:
    kt = str(Path(tmp) / "Sample.kt")
    P = "package x\n\n"

    # === BLOCK (severity: error) — carriers isolate each rule ===
    blocks = {
        "camelCase top-level function (C)": P + "public fun generateText(): Unit = Unit\n",
        "internal camelCase top-level function (C)": P + "internal fun helper(): Int = 1\n",
        "camelCase top-level extension function (C)": P + "public fun String.titleCase(): String = this\n",
        "nullable prompt/messages bag": P + "public class C {\n    fun chk(p: String?, m: List<Int>) {\n        require(p != null || m.isNotEmpty()) { \"x\" }\n    }\n}\n",
        "throw-in-stream-error-fn": P + "public class C {\n    public suspend fun error(t: Throwable) {\n        throw t\n    }\n}\n",
        "secondary constructor": P + "public class C(val a: Int) {\n    constructor(b: String) : this(b.length)\n}\n",
        "implicitly-public mutable var": P + "public class C {\n    var loading: Boolean = false\n}\n",
        "top-level mutable var": P + "var counter: Int = 0\n",
        "mutable companion state": P + "public class C {\n    public companion object {\n        var cache: Int = 0\n    }\n}\n",
        "lateinit var": P + "public class C {\n    lateinit var late: String\n}\n",
        "sealed interface (bodied)": P + "public sealed interface Shape {\n    public val x: Int\n}\n",
        "sealed interface (bodyless)": P + "public sealed interface Marker\n",
        "not-null assertion": P + "public class C {\n    fun f(s: String?): String = s!!\n}\n",
        "deferred-wiring comment (xray root cause)": P + "public class C {\n    // The accumulated steps. Loop-side population is staged in as a follow-up.\n    val priorSteps: List<Int> = emptyList()\n}\n",
    }
    for name, content in blocks.items():
        check(f"BLOCK: {name}", kind_of(content, kt) == "block")

    # === WARN (severity: warning) — member carriers so camelCase rule doesn't preempt ===
    warns = {
        "String typealias": P + "public typealias FooId = String\n",
        "JsonNull sentinel": P + "public class C {\n    fun f(i: JsonElement?): JsonElement = i ?: JsonNull\n}\n",
        "empty-string sentinel": P + "public class C {\n    fun f(s: String?): String = s ?: \"\"\n}\n",
        "providerOptions JsonObject cast": P + "public class C {\n    fun f(providerOptions: Map<String, JsonElement>) = providerOptions[\"k\"] as? JsonObject\n}\n",
    }
    for name, content in warns.items():
        check(f"WARN: {name}", kind_of(content, kt) == "warn")

    # === PASS — the C-world idioms ===
    passes = {
        "PascalCase factory faux-constructor": P + "public fun TextGenerator(model: String): String = model\n",
        "public member function": P + "public class C {\n    public fun m(): Int = 1\n}\n",
        "private member function": P + "public class C {\n    private fun m(): Int = 1\n}\n",
        "member extension function": P + "public class C {\n    public fun String.ext(): String = this\n}\n",
        "private member var": P + "public class C {\n    private var secret: Int = 0\n}\n",
        "val property": P + "public class C {\n    val identity: String = \"x\"\n}\n",
        "non-sealed interface": P + "public interface Transport {\n    public fun send(): Int\n}\n",
        "sealed class": P + "public sealed class Outcome\n",
    }
    for name, content in passes.items():
        check(f"PASS: {name}", kind_of(content, kt) is None)

    # === incremental: pre-existing block pattern is grandfathered ===
    legacy = P + "public class C {\n    fun chk(p: String?, m: List<Int>) {\n        require(p != null || m.isNotEmpty()) { \"x\" }\n        val y = 1\n    }\n}\n"
    Path(kt).write_text(legacy, encoding="utf-8")
    unrelated = policy.run({"tool_name": "Edit", "tool_input": {"file_path": kt, "old_string": "val y = 1", "new_string": "val y = 2"}})
    check("incremental: edit not touching pre-existing require-bag PASSES", unrelated is None)

    # === incremental: editing the BODY of a grandfathered loose top-level fn PASSES (signature-keyed diff) ===
    legacy_fn = P + "internal fun helper(): Int {\n    return 1\n}\n"
    Path(kt).write_text(legacy_fn, encoding="utf-8")
    body_edit = policy.run({"tool_name": "Edit", "tool_input": {"file_path": kt, "old_string": "return 1", "new_string": "return 2"}})
    check("incremental: body edit of grandfathered camelCase top-level fn PASSES", body_edit is None)
    Path(kt).write_text(legacy_fn, encoding="utf-8")
    add_fn = policy.run({"tool_name": "Edit", "tool_input": {"file_path": kt, "old_string": "internal fun helper(): Int {\n    return 1\n}\n", "new_string": "internal fun helper(): Int {\n    return 1\n}\n\ninternal fun helper2(): Int = 9\n"}})
    check("incremental: ADDING a new camelCase top-level fn still BLOCKS", bool(add_fn and add_fn.kind == "block"))


def run_local_hook(payload: dict) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(HOOKS_ROOT / "orchestrator" / "pretooluse.py")],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
        timeout=15,
    )


blocked = run_local_hook({"tool_name": "Write", "tool_input": {
    "file_path": "/tmp/Sample.kt",
    "content": "package x\n\npublic fun generateText(): Unit = Unit\n",
}})
check("orchestrator BLOCKS a camelCase top-level function", '"decision": "block"' in blocked.stdout)

allowed = run_local_hook({"tool_name": "Write", "tool_input": {
    "file_path": "/tmp/Sample.kt",
    "content": "package x\n\npublic fun TextGenerator(model: String): String = model\n",
}})
check("orchestrator does NOT block a PascalCase factory", '"decision": "block"' not in allowed.stdout)

with tempfile.TemporaryDirectory() as tmp:
    normal = Path(tmp) / "Normal.txt"
    kotlin = Path(tmp) / "Second.kt"
    normal.write_text("old\n", encoding="utf-8")
    kotlin.write_text("package x\n", encoding="utf-8")
    patch = f"""*** Begin Patch
*** Update File: {normal}
@@
-old
+new
*** Update File: {kotlin}
@@
 package x
+public fun generateText(): Unit = Unit
*** End Patch
"""
    env = dict(os.environ)
    env["CLAUDE_PROJECT_DIR"] = str(ROOT)
    adapter_blocked = subprocess.run(
        [sys.executable, str(ADAPTER), "pretooluse", str(HOOKS_ROOT / "orchestrator" / "pretooluse.py")],
        input=json.dumps({"tool_name": "apply_patch", "cwd": str(ROOT), "tool_input": {"command": patch}}),
        capture_output=True,
        text=True,
        timeout=15,
        env=env,
    )
    check(
        "adapter blocks Kotlin violation when Kotlin file is second",
        '"decision": "block"' in adapter_blocked.stdout,
    )


# === full rule set: parse gate + foundry semantic gate ===
parse_gate = subprocess.run(
    [sys.executable, str(HOOKS_ROOT / "rules" / "validate_rules.py")],
    capture_output=True, text=True, timeout=120,
)
check("all installed rule files parse (validate_rules.py)", parse_gate.returncode == 0)

manifest = HOOKS_ROOT / "rules" / "manifest.json"
if manifest.exists():
    sem_gate = subprocess.run(
        [sys.executable, str(HOOKS_ROOT / "rules" / "validate_rules.py"), "--manifest", str(manifest)],
        capture_output=True, text=True, timeout=180,
    )
    check("foundry rules pass semantic gate (match bad, skip good)", sem_gate.returncode == 0)

    manifest_entries = json.loads(manifest.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory() as tmp:
        off_path_manifest = Path(tmp) / "manifest.json"
        off_path_manifest.write_text(json.dumps(manifest_entries[:-1]), encoding="utf-8")
        missing_entry_gate = subprocess.run(
            [
                sys.executable,
                str(HOOKS_ROOT / "rules" / "validate_rules.py"),
                "--manifest",
                str(off_path_manifest),
            ],
            capture_output=True,
            text=True,
            timeout=180,
        )
        check(
            "off-path manifest missing a rule entry fails semantic gate",
            missing_entry_gate.returncode == 1 and "missing manifest entry" in missing_entry_gate.stdout,
        )

    with tempfile.TemporaryDirectory() as tmp:
        hunk_manifest = Path(tmp) / "manifest.json"
        hunk_entries = list(manifest_entries)
        hunk_entries[0] = {**hunk_entries[0], "hunkExpectation": "no-match"}
        hunk_manifest.write_text(json.dumps(hunk_entries), encoding="utf-8")
        hunk_gate = subprocess.run(
            [
                sys.executable,
                str(HOOKS_ROOT / "rules" / "validate_rules.py"),
                "--hunk-mode",
                str(hunk_manifest),
            ],
            capture_output=True,
            text=True,
            timeout=180,
        )
        check(
            "flipped hunkExpectation fails hunk-mode gate",
            hunk_gate.returncode == 1 and "hunkExpectation=no-match" in hunk_gate.stdout,
        )



# Consumer-tree exemption (2026-07-03 misfire): library rules must not bind
# samples/ or smoke-tests/ Kotlin; everywhere else stays guarded.
sample_allowed = policy.run({"tool_name": "Write", "tool_input": {
    "file_path": str(ROOT / "samples" / "jvm-chat-cli" / "src" / "main" / "kotlin" / "Main.kt"),
    "content": "public fun generateText(): Unit = Unit",
}})
check("samples/ Kotlin is exempt from library rules", sample_allowed is None or sample_allowed.kind != "block")
smoke_allowed = policy.run({"tool_name": "Write", "tool_input": {
    "file_path": str(ROOT / "smoke-tests" / "x" / "Main.kt"),
    "content": "public fun generateText(): Unit = Unit",
}})
check("smoke-tests/ Kotlin is exempt from library rules", smoke_allowed is None or smoke_allowed.kind != "block")

if failures:
    print(f"FAILED {ran - len(failures)}/{ran}")
    for failure in failures:
        print(f"- {failure}")
    raise SystemExit(1)

print(f"ok {ran}")
