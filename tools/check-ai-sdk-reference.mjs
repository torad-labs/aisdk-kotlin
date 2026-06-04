#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const repoRoot = resolve(new URL('..', import.meta.url).pathname);
const expectedVersion = process.env.AI_SDK_REFERENCE_VERSION ?? '6.0.196';
const referenceRoot = join(repoRoot, '.reference', `vercel-ai-sdk-ai-${expectedVersion}`);
const packageJsonPath = join(referenceRoot, 'packages', 'ai', 'package.json');

function main() {
  const latest = JSON.parse(
    execFileSync('npm', ['view', 'ai', 'version', '--json'], {
      cwd: repoRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'inherit'],
    }),
  );

  if (latest !== expectedVersion) {
    fail(
      `npm latest ai version is ${latest}, but this port targets ${expectedVersion}. ` +
        'Refresh .reference, regenerate parity ledgers, and close the new delta before release.',
    );
  }

  if (!existsSync(packageJsonPath)) {
    fail(`Missing reference package file: ${packageJsonPath}`);
  }

  const referencePackage = JSON.parse(readFileSync(packageJsonPath, 'utf8'));
  if (referencePackage.version !== expectedVersion) {
    fail(
      `Reference package version is ${referencePackage.version}, expected ${expectedVersion}: ${packageJsonPath}`,
    );
  }

  console.log(`AI SDK reference is current: ai@${expectedVersion}`);
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

main();
