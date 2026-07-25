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
    # Every public declaration carries `/** @since */`. That is not decoration: the policy
    # loads BOTH lanes (rules/ and rules-style/), so the opt-in `no-public-without-since`
    # rule sees these fixtures too and returns a WARN for any bare public declaration —
    # which makes `kind_of(...) is None` false and failed all 8 of these checks. The fixtures
    # were written when only the LAW lane was loaded, before e674eb5 split the package.
    # Adding the tag is the honest repair: it keeps the assertion at its strictest ("no
    # finding of ANY severity") while making the fixtures genuinely compliant with the rule
    # set they are run against, rather than relaxing the check to tolerate warnings.
    S = "/** @since 0.3.0-beta01 */\n"
    passes = {
        "PascalCase factory faux-constructor": P + S + "public fun TextGenerator(model: String): String = model\n",
        "public member function": P + S + "public class C {\n" + S + "    public fun m(): Int = 1\n}\n",
        "private member function": P + S + "public class C {\n    private fun m(): Int = 1\n}\n",
        "member extension function": P + S + "public class C {\n" + S + "    public fun String.ext(): String = this\n}\n",
        "private member var": P + S + "public class C {\n    private var secret: Int = 0\n}\n",
        "val property": P + S + "public class C {\n" + S + "    val identity: String = \"x\"\n}\n",
        "non-sealed interface": P + S + "public interface Transport {\n" + S + "    public fun send(): Int\n}\n",
        "sealed class": P + S + "public sealed class Outcome\n",
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
    # Drop a LAW-lane entry specifically. The semantic gate only audits rules/ (LAW) for
    # missing manifest coverage — a rules-style entry has no such requirement — so the old
    # `manifest_entries[:-1]` only worked while the manifest happened to END with a LAW rule.
    # It now ends with style rules, so the fixture silently stopped exercising the gate and
    # asserted a failure that could never happen. Selecting by lane removes the ordering
    # dependency entirely.
    law_lane = ROOT / ".rules" / "kotlin" / "ast-grep" / "rules"
    law_ids = {p.stem for p in law_lane.glob("*.yaml")}
    dropped = next(e for e in manifest_entries if e.get("id") in law_ids)
    with tempfile.TemporaryDirectory() as tmp:
        off_path_manifest = Path(tmp) / "manifest.json"
        off_path_manifest.write_text(
            json.dumps([e for e in manifest_entries if e is not dropped]),
            encoding="utf-8",
        )
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

    fix_rule = """id: test-replace-bad-name
language: kotlin
severity: error
rule:
  pattern: BadName()
fix: GoodName()
"""
    fix_manifest_entry = {
        "id": "test-replace-bad-name",
        "severity": "error",
        "yaml": fix_rule,
        "badExample": "val x = BadName()",
        "goodExample": "val x = GoodName()",
        "fixExamples": [
            {
                "input": "val x = BadName()",
                "output": "val x = GoodName()",
            }
        ],
    }

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        fix_manifest = tmp_path / "manifest.json"
        fix_registry = tmp_path / "autofix-registry.json"
        fix_manifest.write_text(json.dumps([*manifest_entries, fix_manifest_entry]), encoding="utf-8")
        fix_registry.write_text(json.dumps([{"id": "test-replace-bad-name"}]), encoding="utf-8")
        valid_fix_gate = subprocess.run(
            [
                sys.executable,
                str(HOOKS_ROOT / "rules" / "validate_rules.py"),
                "--manifest",
                str(fix_manifest),
                "--autofix-registry",
                str(fix_registry),
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        check("registered autofix rule with idempotent fixture passes", valid_fix_gate.returncode == 0)

        broken_entry = {
            **fix_manifest_entry,
            "fixExamples": [{"input": "val x = BadName()", "output": "val x = StillWrong()"}],
        }
        fix_manifest.write_text(json.dumps([*manifest_entries, broken_entry]), encoding="utf-8")
        broken_fix_gate = subprocess.run(
            [
                sys.executable,
                str(HOOKS_ROOT / "rules" / "validate_rules.py"),
                "--manifest",
                str(fix_manifest),
                "--autofix-registry",
                str(fix_registry),
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        check(
            "registered autofix rule with broken fixture fails",
            broken_fix_gate.returncode == 1 and "fixExamples[1]" in broken_fix_gate.stdout,
        )

        no_fixture_entry = {k: v for k, v in fix_manifest_entry.items() if k != "fixExamples"}
        fix_manifest.write_text(json.dumps([*manifest_entries, no_fixture_entry]), encoding="utf-8")
        no_fixture_gate = subprocess.run(
            [
                sys.executable,
                str(HOOKS_ROOT / "rules" / "validate_rules.py"),
                "--manifest",
                str(fix_manifest),
                "--autofix-registry",
                str(fix_registry),
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        check(
            "registered autofix rule without fixExamples fails",
            no_fixture_gate.returncode == 1 and "fixExamples" in no_fixture_gate.stdout,
        )

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        # Mirror the real layout the policy discovers: <root>/rules (LAW) + <root>/rules-style
        # (opt-in). e674eb5 moved the package from a flat .claude/hooks/rules/kotlin/ to
        # .rules/kotlin/ast-grep/{rules,rules-style}/ and renamed the module constant
        # RULES_DIR -> RULES_ROOT; this fixture was never updated, so it patched an attribute
        # that no longer exists and the test died on AttributeError before asserting anything.
        rules_root = tmp_path / "ast-grep"
        rules_dir = rules_root / "rules"
        rules_dir.mkdir(parents=True)
        # registry.json must sit ALONGSIDE rules/ — apply_autofix_mode resolves rule files
        # relative to the registry's own parent (registry_parent/rules, /rules-style, and the
        # legacy /kotlin). The old fixture put it one level above, so no rule was ever found
        # and the autofix checks asserted against a no-op run.
        registry = rules_root / "registry.json"
        sample_dir = tmp_path / "src"
        sample_dir.mkdir()
        rule_file = rules_dir / "test-replace-bad-name.yaml"
        sample = sample_dir / "Sample.kt"
        rule_file.write_text(fix_rule, encoding="utf-8")
        registry.write_text(json.dumps([{"id": "test-replace-bad-name"}]), encoding="utf-8")
        sample.write_text("package x\n\nval x = BadName()\n", encoding="utf-8")
        apply_gate = subprocess.run(
            [
                sys.executable,
                str(HOOKS_ROOT / "rules" / "validate_rules.py"),
                "--apply-autofix",
                str(registry),
                str(sample_dir),
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        check(
            "autofix pre-pass applies fixes then fails for review",
            apply_gate.returncode == 1
            and "fixed 1 site(s)" in apply_gate.stdout
            and "GoodName()" in sample.read_text(encoding="utf-8"),
        )
        apply_again = subprocess.run(
            [
                sys.executable,
                str(HOOKS_ROOT / "rules" / "validate_rules.py"),
                "--apply-autofix",
                str(registry),
                str(sample_dir),
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        check("autofix pre-pass is idempotent after review", apply_again.returncode == 0)

        original_rules_root = policy.RULES_ROOT
        original_registry = policy.AUTOFIX_REGISTRY
        try:
            policy.RULES_ROOT = rules_root
            policy.AUTOFIX_REGISTRY = registry
            fixed = policy.run({"tool_name": "Write", "tool_input": {
                "file_path": str(sample),
                "content": "package x\n\nval x = BadName()\n",
            }})
            check(
                "edit-time autofix fallback blocks with corrected snippet",
                bool(
                    fixed
                    and fixed.kind == "block"
                    and "AUTOFIX AVAILABLE" in fixed.payload
                    and "fixed 1 site(s)" in fixed.payload
                    and "GoodName()" in fixed.payload
                ),
            )
        finally:
            policy.RULES_ROOT = original_rules_root
            policy.AUTOFIX_REGISTRY = original_registry



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

# Test-source scoping (2026-07-03 misfire): ci-gate.sh never feeds src/commonTest or
# src/jvmTest to no-core-import-providers (its default `dirs` list has no Test
# directory), so a provider-under-test file legitimately importing
# ai.torad.aisdk.providers must not be blocked here either. commonMain stays guarded.
provider_import_kt = (
    "package ai.torad.aisdk\n\nimport ai.torad.aisdk.providers.OpenAIProvider\n\ninternal class Foo\n"
)
common_test_allowed = policy.run({"tool_name": "Write", "tool_input": {
    "file_path": str(ROOT / "src" / "commonTest" / "kotlin" / "ai" / "torad" / "aisdk" / "ScopeSample.kt"),
    "content": provider_import_kt,
}})
check(
    "commonTest provider import is exempt from no-core-import-providers",
    common_test_allowed is None or common_test_allowed.kind != "block",
)
jvm_test_allowed = policy.run({"tool_name": "Write", "tool_input": {
    "file_path": str(ROOT / "src" / "jvmTest" / "kotlin" / "ai" / "torad" / "aisdk" / "ScopeSample.kt"),
    "content": provider_import_kt,
}})
check(
    "jvmTest provider import is exempt (ci-gate.sh never scans jvmTest at all)",
    jvm_test_allowed is None or jvm_test_allowed.kind != "block",
)
common_main_blocked = policy.run({"tool_name": "Write", "tool_input": {
    "file_path": str(ROOT / "src" / "commonMain" / "kotlin" / "ai" / "torad" / "aisdk" / "ScopeSample.kt"),
    "content": provider_import_kt,
}})
check(
    "commonMain provider import is still blocked where ci-gate.sh actually scans",
    bool(common_main_blocked and common_main_blocked.kind == "block"),
)

if failures:
    print(f"FAILED {ran - len(failures)}/{ran}")
    for failure in failures:
        print(f"- {failure}")
    raise SystemExit(1)

print(f"ok {ran}")
