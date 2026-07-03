#!/usr/bin/env node

import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const repoRoot = resolve(new URL('..', import.meta.url).pathname);
const expectedPath = join(repoRoot, 'tools', 'expected-gates.txt');
const validStatuses = new Set(['success', 'failure', 'cancelled', 'skipped']);

function main() {
  const [context, ...rawEntries] = process.argv.slice(2);
  if (!context || rawEntries.length === 0) {
    fail('usage: node tools/assert-expected-gates.mjs <context> <gate=status>...');
  }

  const expected = expectedGatesFor(context);
  if (expected.size === 0) {
    fail(`no expected gates registered for context ${JSON.stringify(context)} in tools/expected-gates.txt`);
  }

  const actual = parseActualEntries(rawEntries);
  const failures = [];
  const missing = [...expected].filter((gate) => !actual.has(gate));
  const unexpected = [...actual.keys()].filter((gate) => !expected.has(gate));
  if (missing.length > 0) failures.push(`missing expected gate(s): ${missing.join(', ')}`);
  if (unexpected.length > 0) failures.push(`unexpected gate(s): ${unexpected.join(', ')}`);

  for (const [gate, status] of actual) {
    if (!validStatuses.has(status)) {
      failures.push(`${gate}: invalid or empty status ${JSON.stringify(status)}`);
    } else if (status === 'skipped') {
      failures.push(`${gate}: expected gate was skipped instead of running`);
    }
  }

  const ledger = [...actual].map(([gate, status]) => ({ context, gate, status }));
  for (const entry of ledger) {
    console.log(JSON.stringify(entry));
  }
  writeFileSync('gate-manifest.json', JSON.stringify(ledger, null, 2) + '\n');

  if (failures.length > 0) {
    fail(`gate ledger mismatch for ${context}:\n${failures.map((item) => `- ${item}`).join('\n')}`);
  }
  console.log(`gate ledger OK: ${context} (${expected.size} expected gate(s))`);
}

function expectedGatesFor(context) {
  const gates = new Set();
  for (const line of readFileSync(expectedPath, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const [lineContext, gate, extra] = trimmed.split(/\s+/);
    if (!lineContext || !gate || extra) {
      fail(`invalid expected gate line in tools/expected-gates.txt: ${line}`);
    }
    if (lineContext === context) gates.add(gate);
  }
  return gates;
}

function parseActualEntries(rawEntries) {
  const actual = new Map();
  for (const raw of rawEntries) {
    const separator = raw.indexOf('=');
    if (separator <= 0) {
      fail(`invalid gate status argument ${JSON.stringify(raw)}; expected gate=status`);
    }
    const gate = raw.slice(0, separator);
    const status = raw.slice(separator + 1);
    if (actual.has(gate)) fail(`duplicate gate status for ${gate}`);
    actual.set(gate, status);
  }
  return actual;
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

main();
