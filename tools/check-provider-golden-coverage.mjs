#!/usr/bin/env node
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve, relative } from 'node:path';
const root = resolve(new URL('..', import.meta.url).pathname);
const providersDir = join(root, 'src/commonMain/kotlin/ai/torad/aisdk/providers');
const manifestPath = join(root, 'docs/provider-golden-coverage.json');
function providerClasses() {
  const out = new Set();
  for (const entry of readdirSync(providersDir, { withFileTypes: true })) {
    if (!entry.isFile() || !entry.name.endsWith('.kt')) continue;
    const text = readFileSync(join(providersDir, entry.name), 'utf8');
    for (const match of text.matchAll(/^public\s+(?:class|interface)\s+(\w+Provider)\b/gm)) {
      if (!match[1].startsWith('Mock')) out.add(match[1]);
    }
  }
  return [...out].sort();
}
function testFiles() {
  const base = join(root, 'src/commonTest/kotlin/ai/torad/aisdk');
  return readdirSync(base, { withFileTypes: true }).filter(e => e.isFile() && e.name.endsWith('Test.kt')).map(e => e.name);
}
if (!existsSync(manifestPath)) {
  const tests = testFiles();
  const entries = Object.fromEntries(providerClasses().map(cls => {
    const stem = cls.replace(/Provider$/, '');
    return [cls, { requestGolden: tests.find(t => t.includes(stem)) || null, streamGolden: tests.find(t => t.includes(stem) && /Provider|Defensive|Streaming/.test(t)) || null }];
  }));
  writeFileSync(manifestPath, JSON.stringify({ schemaVersion: 1, providers: entries }, null, 2) + '\n');
  console.log(`created ${relative(root, manifestPath)}`);
  process.exit(0);
}
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')).providers || {};
const expected = providerClasses();
const failures = [];
for (const provider of expected) {
  const entry = manifest[provider];
  if (!entry) failures.push(`${provider}: missing manifest entry`);
  else if (!entry.requestGolden && !entry.streamGolden) failures.push(`${provider}: missing request/stream golden coverage reference`);
}
for (const provider of Object.keys(manifest)) if (!expected.includes(provider)) failures.push(`${provider}: manifest entry has no public provider class`);
if (failures.length) {
  console.error('provider golden coverage failed:');
  failures.forEach(f => console.error(`  - ${f}`));
  process.exit(1);
}
console.log(`provider golden coverage manifest OK: ${expected.length} providers tracked`);
