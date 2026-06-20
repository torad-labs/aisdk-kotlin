#!/usr/bin/env python3
"""Tests for the repo-local Kotlin anti-pattern policy (14 catalog/tenet-derived ast-grep rules)."""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HOOKS_ROOT = ROOT / ".claude" / "hooks"
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

    # === BLOCK (severity: error) ===
    blocks = {
        "nullable prompt/messages bag": P + "public fun g(p: String?, m: List<Int>) {\n    require(p != null || m.isNotEmpty()) { \"x\" }\n}\n",
        "throw-in-stream-error-fn": P + "public suspend fun error(t: Throwable) {\n    throw t\n}\n",
        "secondary constructor": P + "public class C(val a: Int) {\n    constructor(b: String) : this(b.length)\n}\n",
        "implicitly-public mutable var": P + "public class C {\n    var loading: Boolean = false\n}\n",
        "top-level mutable var": P + "var counter: Int = 0\n",
        "mutable companion state": P + "public class C {\n    public companion object {\n        var cache: Int = 0\n    }\n}\n",
        "private top-level function": P + "private fun helper(): Int = 1\n",
        "lateinit var": P + "public class C {\n    lateinit var late: String\n}\n",
        "sealed interface (bodied)": P + "public sealed interface Shape {\n    public val x: Int\n}\n",
        "sealed interface (bodyless)": P + "public sealed interface Marker\n",
        "not-null assertion": P + "public fun f(s: String?): String = s!!\n",
    }
    for name, content in blocks.items():
        check(f"BLOCK: {name}", kind_of(content, kt) == "block")

    # === WARN (severity: warning) ===
    warns = {
        "String typealias": P + "public typealias FooId = String\n",
        "JsonNull sentinel": P + "public fun f(i: JsonElement?): JsonElement = i ?: JsonNull\n",
        "empty-string sentinel": P + "public fun f(s: String?): String = s ?: \"\"\n",
        "providerOptions JsonObject cast": P + "public fun f(providerOptions: Map<String, JsonElement>) = providerOptions[\"k\"] as? JsonObject\n",
    }
    for name, content in warns.items():
        check(f"WARN: {name}", kind_of(content, kt) == "warn")

    # === PASS — legitimate code & regression guards ===
    passes = {
        "public top-level function (functional API)": P + "public fun summarize(text: String): String = text.trim()\n",
        "internal top-level function (testable helper)": P + "internal fun helper(): Int = 1\n",
        "extension function": P + "public fun String.titleCase(): String = this\n",
        "public member function": P + "public class C {\n    public fun m(): Int = 1\n}\n",
        "private member function (testable via class)": P + "public class C {\n    private fun m(): Int = 1\n}\n",
        "private member var": P + "public class C {\n    private var secret: Int = 0\n}\n",
        "val property": P + "public class C {\n    val identity: String = \"x\"\n}\n",
        "non-sealed interface": P + "public interface Transport {\n    public fun send(): Int\n}\n",
        "sealed class": P + "public sealed class Outcome\n",
    }
    for name, content in passes.items():
        check(f"PASS: {name}", kind_of(content, kt) is None)

    # === incremental: pre-existing block pattern is grandfathered ===
    legacy = P + "public fun g(p: String?, m: List<Int>) {\n    require(p != null || m.isNotEmpty()) { \"x\" }\n    val y = 1\n}\n"
    Path(kt).write_text(legacy, encoding="utf-8")
    unrelated = policy.run({"tool_name": "Edit", "tool_input": {"file_path": kt, "old_string": "val y = 1", "new_string": "val y = 2"}})
    check("incremental: edit not touching pre-existing require-bag PASSES", unrelated is None)


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
    "content": "package x\n\npublic class C(val a: Int) {\n    constructor(b: String) : this(b.length)\n}\n",
}})
check("orchestrator BLOCKS a secondary constructor", '"decision": "block"' in blocked.stdout)

allowed = run_local_hook({"tool_name": "Write", "tool_input": {
    "file_path": "/tmp/Sample.kt",
    "content": "package x\n\npublic fun topLevelApi(): Unit = Unit\n",
}})
check("orchestrator does NOT block a public top-level function", '"decision": "block"' not in allowed.stdout)


if failures:
    print(f"FAILED {ran - len(failures)}/{ran}")
    for failure in failures:
        print(f"- {failure}")
    raise SystemExit(1)

print(f"ok {ran}")
