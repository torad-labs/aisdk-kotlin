#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const repoRoot = resolve(new URL('..', import.meta.url).pathname);

const errorBudgets = [
  {
    name: 'generic exception throws',
    budget: 0,
    pattern: /\bthrow\s+(?:Exception|RuntimeException|IllegalStateException)\s*\(/g,
    why: 'expected SDK/provider/protocol failures must use typed AiSdkException subclasses',
  },
  {
    name: 'IllegalArgumentException throws',
    budget: 10,
    pattern: /\bthrow\s+IllegalArgumentException\s*\(/g,
    why: 'caller-programmer errors are allowed, but the beta budget must not grow silently',
  },
  {
    name: 'kotlin error() calls',
    budget: 1,
    pattern: /^\s*error\s*\(/g,
    why: 'error() throws IllegalStateException and should be budgeted until migrated to typed errors',
  },
];

function main() {
  const changedFiles = changedFilesForReview();
  const apiDumpChanges = [...changedFiles].filter(isApiDump);
  const failures = [];

  if (apiDumpChanges.length > 0) {
    if (!changedFiles.has('CHANGELOG.md')) {
      failures.push('api/*.api changed but CHANGELOG.md did not change');
    }
    if (!changedFiles.has('INTERFACE_CONTRACT.md')) {
      failures.push('api/*.api changed but INTERFACE_CONTRACT.md did not change');
    }
  }

  failures.push(...checkErrorTaxonomyBudgets());

  if (failures.length > 0) {
    fail(`API review gate failed:\n${failures.map(item => `- ${item}`).join('\n')}`);
  }

  if (apiDumpChanges.length > 0) {
    console.log(`API review docs present for ${apiDumpChanges.length} API dump change(s).`);
  } else {
    console.log('No API dump changes detected.');
  }
  console.log('Error taxonomy budgets are within limits.');
}

function changedFilesForReview() {
  const files = new Set();
  const baseRequired = Boolean(process.env.API_REVIEW_BASE || process.env.GITHUB_BASE_REF || process.env.BASE_REF);
  const base = baseRequired ? reviewBase() : null;
  if (baseRequired && !base) {
    fail('Unable to resolve API review base. Set API_REVIEW_BASE to a fetched base commit/ref.');
  }
  if (base) {
    addGitNameOnly(files, ['diff', '--name-only', `${base}...HEAD`, '--']);
  }
  addGitNameOnly(files, ['diff', '--name-only', '--']);
  addGitNameOnly(files, ['diff', '--cached', '--name-only', '--']);
  return files;
}

function reviewBase() {
  if (process.env.API_REVIEW_BASE) return process.env.API_REVIEW_BASE;
  const baseRef = process.env.GITHUB_BASE_REF || process.env.BASE_REF;
  const candidates = baseRef
    ? [
        ['merge-base', 'HEAD', `origin/${baseRef}`],
        ['merge-base', 'HEAD', baseRef],
      ]
    : [
        ['merge-base', 'HEAD', 'origin/main'],
        ['merge-base', 'HEAD', 'origin/master'],
        ['rev-parse', 'HEAD~1'],
      ];
  for (const candidate of candidates) {
    try {
      return git(candidate).trim();
    } catch (_error) {
      // Try the next base strategy.
    }
  }
  return null;
}

function addGitNameOnly(target, args) {
  try {
    for (const file of git(args).split(/\r?\n/)) {
      if (file.trim()) target.add(file.trim());
    }
  } catch (_error) {
    // A fresh repo or shallow checkout may not support every diff mode. The base diff above
    // and the working-tree diffs are independent so CI can set API_REVIEW_BASE explicitly.
  }
}

function isApiDump(file) {
  return file.startsWith('api/') && file.endsWith('.api');
}

function checkErrorTaxonomyBudgets() {
  const sourceRoot = join(repoRoot, 'src', 'commonMain', 'kotlin');
  if (!existsSync(sourceRoot)) return [`missing source root ${relative(repoRoot, sourceRoot)}`];

  const files = listKotlinFiles(sourceRoot);
  const failures = [];
  for (const budget of errorBudgets) {
    const hits = [];
    for (const file of files) {
      const text = readFileSync(file, 'utf8');
      const lines = text.split(/\r?\n/);
      for (const [index, line] of lines.entries()) {
        budget.pattern.lastIndex = 0;
        if (budget.pattern.test(line)) {
          hits.push(`${relative(repoRoot, file)}:${index + 1}: ${line.trim()}`);
        }
      }
    }
    if (hits.length > budget.budget) {
      failures.push(
        `${budget.name} budget exceeded (${hits.length}/${budget.budget}): ${budget.why}\n` +
          hits.map(hit => `    ${hit}`).join('\n'),
      );
    }
  }
  return failures;
}

function listKotlinFiles(dir) {
  const files = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const fullPath = join(dir, entry.name);
    if (entry.isDirectory()) files.push(...listKotlinFiles(fullPath));
    if (entry.isFile() && entry.name.endsWith('.kt')) files.push(fullPath);
  }
  return files;
}

function git(args) {
  return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

main();
