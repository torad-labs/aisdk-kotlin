#!/usr/bin/env node

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const repoRoot = resolve(new URL('..', import.meta.url).pathname);
const providersDir = join(repoRoot, 'src', 'commonMain', 'kotlin', 'ai', 'torad', 'aisdk', 'providers');
const matrixPath = join(repoRoot, 'docs', 'provider-capability-matrix.md');
const allowedCapabilityValues = new Set(['yes', 'partial', 'no', 'n/a']);
const capabilityColumns = [
  'Language generate/stream',
  'Tools',
  'Structured output',
  'Images/files',
  'Embeddings',
  'Speech/transcription/video',
  'Provider-executed tools',
  'Response metadata/usage',
  'Retry/error envelope',
];

function main() {
  if (!existsSync(matrixPath)) {
    fail(`Missing provider capability matrix: ${relative(repoRoot, matrixPath)}`);
  }

  const expectedProviders = discoverPublicProviderClasses();
  const matrix = readMatrix();
  const actualProviders = new Set(matrix.rows.map(row => row['Provider class']).filter(Boolean));

  const failures = [];
  for (const provider of expectedProviders) {
    if (!actualProviders.has(provider)) {
      failures.push(`missing matrix row for public provider class ${provider}`);
    }
  }

  for (const provider of actualProviders) {
    if (!expectedProviders.has(provider)) {
      failures.push(`matrix row has no matching public provider class: ${provider}`);
    }
  }

  for (const row of matrix.rows) {
    for (const column of capabilityColumns) {
      const value = (row[column] ?? '').trim();
      if (!allowedCapabilityValues.has(value)) {
        failures.push(`${row['Provider class'] || '<unknown>'}: ${column} must be one of ${[...allowedCapabilityValues].join(', ')} (got ${JSON.stringify(value)})`);
      }
    }
  }

  if (failures.length > 0) {
    fail(`Provider capability matrix is not current:\n${failures.map(item => `- ${item}`).join('\n')}`);
  }

  console.log(`Provider capability matrix covers ${expectedProviders.size} public provider classes.`);
}

function discoverPublicProviderClasses() {
  if (!existsSync(providersDir)) {
    fail(`Missing providers directory: ${relative(repoRoot, providersDir)}`);
  }

  const providers = new Set();
  for (const entry of readdirSync(providersDir, { withFileTypes: true })) {
    if (!entry.isFile() || !entry.name.endsWith('.kt')) continue;
    const text = readFileSync(join(providersDir, entry.name), 'utf8');
    const pattern = /^public\s+(?:class|interface)\s+(\w+Provider)\b/gm;
    let match;
    while ((match = pattern.exec(text)) !== null) {
      if (match[1].startsWith('Mock')) continue;
      providers.add(match[1]);
    }
  }
  return providers;
}

function readMatrix() {
  const lines = readFileSync(matrixPath, 'utf8').split(/\r?\n/);
  const tableLines = lines.filter(line => line.trim().startsWith('|'));
  if (tableLines.length < 3) {
    fail(`Provider capability matrix does not contain a markdown table: ${relative(repoRoot, matrixPath)}`);
  }

  const header = parseTableLine(tableLines[0]);
  const requiredHeaders = ['Provider class', 'Factory/package surface', ...capabilityColumns, 'Notes'];
  for (const required of requiredHeaders) {
    if (!header.includes(required)) {
      fail(`Provider capability matrix is missing required column ${JSON.stringify(required)}`);
    }
  }

  const rows = tableLines.slice(2).map(line => {
    const cells = parseTableLine(line);
    return Object.fromEntries(header.map((name, index) => [name, cells[index] ?? '']));
  }).filter(row => row['Provider class'] && row['Provider class'] !== '---');

  return { header, rows };
}

function parseTableLine(line) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim());
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

main();
