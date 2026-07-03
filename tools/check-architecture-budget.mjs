#!/usr/bin/env node
import { existsSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
const root = resolve(new URL('..', import.meta.url).pathname);
const budgetPath = join(root, 'architecture-budget.json');
const roots = [join(root, 'src/commonMain/kotlin'), join(root, 'src/commonTest/kotlin')];
function files(dir) {
  if (!existsSync(dir)) return [];
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...files(path));
    else if (entry.isFile() && entry.name.endsWith('.kt')) out.push(path);
  }
  return out;
}
function lineCount(file) { return readFileSync(file, 'utf8').split(/\r?\n/).length; }
const counts = Object.fromEntries(roots.flatMap(files).map(f => [relative(root, f), lineCount(f)]));
if (!existsSync(budgetPath)) {
  const watched = Object.fromEntries(Object.entries(counts).filter(([, n]) => n >= 500));
  writeFileSync(budgetPath, JSON.stringify({ schemaVersion: 1, maxLinesByFile: watched }, null, 2) + '\n');
  console.log(`created ${relative(root, budgetPath)} with ${Object.keys(watched).length} large-file budgets`);
  process.exit(0);
}
const budget = JSON.parse(readFileSync(budgetPath, 'utf8')).maxLinesByFile || {};
const failures = [];
for (const [file, max] of Object.entries(budget)) {
  const actual = counts[file] ?? 0;
  if (actual > max) failures.push(`${file}: ${actual} > ${max}`);
}
const tooLargeUnbudgeted = Object.entries(counts)
  .filter(([file, n]) => n >= 500 && !(file in budget))
  .map(([file, n]) => `${file}: ${n} lines has no budget`);
failures.push(...tooLargeUnbudgeted);
if (failures.length) {
  console.error('architecture file-size budget failed:');
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`architecture file-size budget OK: ${Object.keys(budget).length} large files tracked`);
