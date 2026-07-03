#!/usr/bin/env node
import { existsSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const root = resolve(new URL('..', import.meta.url).pathname);
const sourceRoot = join(root, 'src/commonMain/kotlin');
const budgetPath = join(root, 'api-since-budget.json');
const printCurrent = process.argv.includes('--print-current');

const declaration = /^\s*public\s+(?:(?:data|sealed|abstract|open|final)\s+)*(?:class|interface|object|enum\s+class|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)/;

function files(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...files(path));
    else if (entry.isFile() && entry.name.endsWith('.kt')) out.push(path);
  }
  return out;
}

function hasSince(lines, index) {
  const start = Math.max(0, index - 12);
  return lines.slice(start, index).some(line => line.includes('@since'));
}

function current() {
  const missing = [];
  for (const file of files(sourceRoot)) {
    const lines = readFileSync(file, 'utf8').split(/\r?\n/);
    lines.forEach((line, index) => {
      const match = declaration.exec(line);
      if (match && !hasSince(lines, index)) missing.push(`${relative(root, file)}:${index + 1}:${match[1]}`);
    });
  }
  return missing.sort();
}

const missing = current();
if (printCurrent) {
  console.log(JSON.stringify({ schemaVersion: 1, totalMissingSince: missing.length }, null, 2));
  process.exit(0);
}
if (!existsSync(budgetPath)) {
  writeFileSync(budgetPath, JSON.stringify({ schemaVersion: 1, totalMissingSince: missing.length }, null, 2) + '\n');
  console.log(`created ${relative(root, budgetPath)} with budget ${missing.length}`);
  process.exit(0);
}
const budget = JSON.parse(readFileSync(budgetPath, 'utf8'));
const allowed = Number(budget.totalMissingSince);
if (missing.length > allowed) {
  console.error(`public API @since budget exceeded: ${missing.length} > ${allowed}`);
  console.error('New public declarations need @since KDoc, or intentionally lower/raise api-since-budget.json with review.');
  for (const item of missing.slice(0, 80)) console.error(`  ${item}`);
  process.exit(1);
}
console.log(`public API @since budget OK: ${missing.length}/${allowed} missing @since`);
