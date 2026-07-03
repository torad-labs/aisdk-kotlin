#!/usr/bin/env node
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

const repoRoot = process.cwd();
const fixturesRoot = resolve(repoRoot, process.argv[2] ?? "tools/gate-fixtures");

if (!existsSync(fixturesRoot)) {
  console.log("gate fixture harness OK: no fixtures configured");
  process.exit(0);
}

const gateDirs = readdirSync(fixturesRoot)
  .map((name) => ({ name, path: resolve(fixturesRoot, name) }))
  .filter(({ path }) => statSync(path).isDirectory())
  .sort((a, b) => a.name.localeCompare(b.name));

if (gateDirs.length === 0) {
  console.log("gate fixture harness OK: no fixtures configured");
  process.exit(0);
}

const failures = [];
let checks = 0;

for (const gate of gateDirs) {
  const commandPath = resolve(gate.path, "cmd.txt");
  if (!existsSync(commandPath)) {
    failures.push(`${gate.name}: missing cmd.txt`);
    continue;
  }
  const command = readFileSync(commandPath, "utf8").trim();
  if (!command) {
    failures.push(`${gate.name}: empty cmd.txt`);
    continue;
  }

  for (const [caseKind, shouldPass] of [["compliant", true], ["violation", false]]) {
    const caseDir = resolve(gate.path, caseKind);
    if (!existsSync(caseDir) || !statSync(caseDir).isDirectory()) {
      failures.push(`${gate.name}: missing ${caseKind}/ fixture directory`);
      continue;
    }

    checks += 1;
    const result = spawnSync("bash", ["-lc", command], {
      cwd: caseDir,
      env: {
        ...process.env,
        REPO_ROOT: repoRoot,
        GATE_NAME: gate.name,
        CASE_KIND: caseKind,
        FIXTURE_DIR: caseDir,
        GATE_FIXTURE_DIR: gate.path,
      },
      encoding: "utf8",
    });
    const passed = result.status === 0;
    if (passed !== shouldPass) {
      failures.push(
        [
          `${gate.name}/${caseKind}: expected exit ${shouldPass ? 0 : "nonzero"}, got ${result.status ?? "signal"}`,
          result.stdout ? `stdout:\n${result.stdout.trimEnd()}` : "",
          result.stderr ? `stderr:\n${result.stderr.trimEnd()}` : "",
        ].filter(Boolean).join("\n"),
      );
    }
  }
}

if (failures.length > 0) {
  console.error(`GATE FIXTURE FAIL: ${failures.length} problem(s) across ${gateDirs.length} gate(s)`);
  for (const failure of failures) {
    console.error(`\n- ${failure}`);
  }
  process.exit(1);
}

console.log(`gate fixture harness OK: ${checks} fixture checks across ${gateDirs.length} gate(s)`);
