#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const repoRoot = resolve(new URL('..', import.meta.url).pathname);
const expectedVersion = process.env.AI_SDK_REFERENCE_VERSION ?? '6.0.208';
const expectedCommit =
  process.env.AI_SDK_REFERENCE_COMMIT ?? '3270146267b1d82f07972bc40f09e9b4ee8bb9d3';
const referenceRoot = join(repoRoot, '.reference', `vercel-ai-sdk-ai-${expectedVersion}`);
const packageJsonPath = join(referenceRoot, 'packages', 'ai', 'package.json');

function main() {
  const latest = readLatestPublishedVersion();

  if (latest !== null && latest !== expectedVersion) {
    warn(
      `npm latest ai version is ${latest}, but this port targets ${expectedVersion}. ` +
        'Refresh .reference, regenerate parity ledgers, and close the new delta before release.',
    );
  }

  if (!existsSync(packageJsonPath)) {
    warn(
      `AI SDK reference checkout is unavailable at ${referenceRoot}; ` +
        'skipping pin validation for this freshness check.',
    );
    return;
  }

  const actualCommit = readReferenceCommit();
  if (actualCommit !== expectedCommit) {
    fail(
      `Reference checkout commit is ${actualCommit}, expected ${expectedCommit}: ${referenceRoot}`,
    );
  }

  const referencePackage = JSON.parse(readFileSync(packageJsonPath, 'utf8'));
  if (referencePackage.version !== expectedVersion) {
    fail(
      `Reference package version is ${referencePackage.version}, expected ${expectedVersion}: ${packageJsonPath}`,
    );
  }

  console.log(`AI SDK reference pin is valid: ai@${expectedVersion} (${actualCommit})`);
}

function readLatestPublishedVersion() {
  try {
    return JSON.parse(
      execFileSync('npm', ['view', 'ai', 'version', '--json'], {
        cwd: repoRoot,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
      }),
    );
  } catch (error) {
    const detail = error.stderr?.toString().trim() || error.message;
    warn(`Unable to query npm for latest ai version; continuing freshness check: ${detail}`);
    return null;
  }
}

function readReferenceCommit() {
  try {
    return execFileSync('git', ['-C', referenceRoot, 'rev-parse', 'HEAD'], {
      cwd: repoRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();
  } catch (error) {
    const detail = error.stderr?.toString().trim() || error.message;
    fail(`Unable to read reference commit from ${referenceRoot}: ${detail}`);
  }
}

function warn(message) {
  console.warn(`::warning::${escapeGithubAnnotation(message)}`);
}

function escapeGithubAnnotation(value) {
  return value.replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

main();
