#!/usr/bin/env node

import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const root = resolve(new URL('..', import.meta.url).pathname);
const sourceRoot = join(root, 'src/commonMain/kotlin');
const sinceVersion = '0.3.0-beta01';
const declaration = /^\s*public\s+(?:(?:data|sealed|abstract|open|final)\s+)*(?:class|interface|object|enum\s+class|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)/;

function main() {
  if (!existsSync(sourceRoot)) {
    fail(`Missing source root: ${relative(root, sourceRoot)}`);
  }

  let inserted = 0;
  for (const file of files(sourceRoot)) {
    const original = readFileSync(file, 'utf8');
    const hadTrailingNewline = original.endsWith('\n');
    const lines = original.split(/\r?\n/);
    if (hadTrailingNewline) lines.pop();

    const targets = [];
    lines.forEach((line, index) => {
      if (declaration.test(line) && !hasSince(lines, index)) {
        targets.push(index);
      }
    });

    if (targets.length === 0) continue;
    for (const index of targets.reverse()) {
      inserted += addSince(lines, index);
    }

    writeFileSync(file, `${lines.join('\n')}${hadTrailingNewline ? '\n' : ''}`, 'utf8');
  }

  console.log(`Inserted @since ${sinceVersion} on ${inserted} public declarations.`);
}

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

function addSince(lines, declarationIndex) {
  const kdoc = findKdoc(lines, declarationIndex);
  if (kdoc) {
    insertSinceIntoKdoc(lines, kdoc);
  } else {
    const indent = lines[declarationIndex].match(/^\s*/)?.[0] ?? '';
    lines.splice(declarationIndex, 0, `${indent}/** @since ${sinceVersion} */`);
  }
  return 1;
}

function findKdoc(lines, declarationIndex) {
  const start = Math.max(0, declarationIndex - 12);
  for (let end = declarationIndex - 1; end >= start; end--) {
    if (!lines[end].includes('*/')) continue;
    for (let begin = end; begin >= start; begin--) {
      if (lines[begin].includes('/**')) {
        return isAttachedKdoc(lines, end, declarationIndex) ? { begin, end } : null;
      }
    }
    return null;
  }
  return null;
}

function isAttachedKdoc(lines, end, declarationIndex) {
  return lines.slice(end + 1, declarationIndex).every(line => {
    const trimmed = line.trim();
    return trimmed === '' ||
      trimmed.startsWith('@') ||
      trimmed === ')' ||
      trimmed === '),' ||
      trimmed.startsWith('//');
  });
}

function insertSinceIntoKdoc(lines, { begin, end }) {
  if (begin === end) {
    const line = lines[begin];
    const indent = line.match(/^\s*/)?.[0] ?? '';
    const body = line
      .slice(line.indexOf('/**') + 3, line.lastIndexOf('*/'))
      .trim();
    const replacement = [`${indent}/**`];
    if (body) replacement.push(`${indent} * ${body}`);
    replacement.push(`${indent} * @since ${sinceVersion}`);
    replacement.push(`${indent} */`);
    lines.splice(begin, 1, ...replacement);
    return;
  }

  const indent = lines[end].match(/^\s*/)?.[0] ?? '';
  lines.splice(end, 0, `${indent} * @since ${sinceVersion}`);
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

main();
