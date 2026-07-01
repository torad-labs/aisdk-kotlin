#!/usr/bin/env node

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { basename, join, relative, resolve } from 'node:path';

const repoRoot = resolve(new URL('..', import.meta.url).pathname);
const parityDir = join(repoRoot, 'docs', 'parity');
const apiDumpPaths = [
  join(repoRoot, 'api', 'torad-aisdk.klib.api'),
  join(repoRoot, 'api', 'jvm', 'torad-aisdk.api'),
];

const trackedSymbols = [
  'toBaseMessages',
  'convertModelMessages',
  'toUIMessageStream',
  'StreamCallbacks',
  'LangSmithDeploymentTransport',
  'LlamaIndexEngineResponse',
  'valibotSchema',
  'zodSchema',
  'createTestServer',
  'TestResponseController',
  'UrlResponse',
  'UrlHandler',
  'UrlHandlers',
];

const explicitNonRuntimeLabels = [
  'not-ported',
  'not-applicable',
  'not-applicable-runtime',
  'test-only',
  'test-runtime-only',
];

function main() {
  const apiText = readApiDumps();
  const statusLines = readParityStatusLines();
  if (process.env.PARITY_CLAIMS_INJECT_BROKEN === '1') {
    statusLines.push({
      file: '<injected>',
      line: 'Current parity status: ported: valibotSchema is represented as a Kotlin public API',
    });
  }

  const failures = [];
  for (const symbol of trackedSymbols) {
    if (hasApiSymbol(apiText, symbol)) continue;
    for (const { file, line } of statusLines) {
      if (!line.includes(symbol)) continue;
      if (hasExplicitNonRuntimeLabel(line)) continue;
      failures.push(`${file}: claims absent API symbol ${symbol}: ${line.trim()}`);
    }
  }

  if (failures.length > 0) {
    fail(`Parity ledger has public API claims that are not present in API dumps:\n${failures.map(item => `- ${item}`).join('\n')}`);
  }

  console.log(`Parity claim check OK: ${trackedSymbols.length} tracked symbols checked against API dumps.`);
}

function readApiDumps() {
  return apiDumpPaths.map(path => {
    if (!existsSync(path)) {
      fail(`Missing API dump: ${relative(repoRoot, path)}`);
    }
    return readFileSync(path, 'utf8');
  }).join('\n');
}

function readParityStatusLines() {
  if (!existsSync(parityDir)) {
    fail(`Missing parity docs directory: ${relative(repoRoot, parityDir)}`);
  }

  const lines = [];
  for (const entry of readdirSync(parityDir, { withFileTypes: true })) {
    if (!entry.isFile() || !entry.name.endsWith('.md')) continue;
    const file = join(parityDir, entry.name);
    const relativeFile = relative(repoRoot, file);
    for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
      if (line.includes('Current parity status:')) {
        lines.push({ file: relativeFile, line });
      } else if (basename(file) === 'README.md' && line.startsWith('| [') && line.includes('|')) {
        lines.push({ file: relativeFile, line });
      }
    }
  }
  return lines;
}

function hasApiSymbol(apiText, symbol) {
  const escaped = symbol.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[^A-Za-z0-9_])${escaped}([^A-Za-z0-9_]|$)`).test(apiText);
}

function hasExplicitNonRuntimeLabel(line) {
  const lower = line.toLowerCase();
  return explicitNonRuntimeLabels.some(label => lower.includes(label));
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

main();
