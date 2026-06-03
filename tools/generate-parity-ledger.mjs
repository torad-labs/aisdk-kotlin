#!/usr/bin/env node

import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';

const repoRoot = resolve(new URL('..', import.meta.url).pathname);
const referenceRoot = join(repoRoot, '.reference', 'vercel-ai-sdk-ai-6.0.195', 'packages');
const outputRoot = join(repoRoot, 'docs', 'parity');
const checkOnly = process.argv.includes('--check');

const packageStatus = new Map([
  ['ai', 'in-progress: current root module covers the core KMP surface; full package ecosystem parity remains open'],
  ['@ai-sdk/provider', 'in-progress: provider contracts are currently folded into the root module'],
  ['@ai-sdk/provider-utils', 'in-progress: provider utility subset is currently folded into the root module'],
  ['@ai-sdk/devtools', 'in-progress: devToolsMiddleware is represented as a Kotlin-native recorder-backed middleware folded into the root module; DB viewer/server storage remains a platform/tooling module concern'],
  ['@ai-sdk/cerebras', 'ported: createCerebras/cerebras, chat provider settings, CerebrasErrorData, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as CEREBRAS_VERSION until package modules are split'],
  ['@ai-sdk/deepinfra', 'ported: createDeepInfra/deepinfra, provider settings, chat/completion/embedding/image routing, DeepInfraErrorData, and DeepInfra usage correction are represented as a Kotlin facade folded into the root module; VERSION is exposed as DEEPINFRA_VERSION until package modules are split'],
  ['@ai-sdk/deepseek', 'ported: createDeepSeek/deepseek, provider settings, language options, error data alias, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as DEEPSEEK_VERSION until package modules are split'],
  ['@ai-sdk/fireworks', 'ported: createFireworks/fireworks, provider settings, language/embedding option surfaces, FireworksErrorData, FireworksImageModel, chat/completion/embedding routing, and Fireworks image backend routing are represented as a Kotlin facade folded into the root module; VERSION is exposed as FIREWORKS_VERSION until package modules are split'],
  ['@ai-sdk/gateway', 'in-progress: gateway facade and Ktor transport are currently folded into the root module'],
  ['@ai-sdk/groq', 'ported: createGroq/groq, Groq tools, chat/transcription routing, provider settings, and option surfaces are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as GROQ_VERSION until package modules are split'],
  ['@ai-sdk/langchain', 'ported: toBaseMessages, convertModelMessages, toUIMessageStream, StreamCallbacks, and LangSmithDeploymentTransport are represented as Kotlin-native UI/Flow adapters folded into the root module'],
  ['@ai-sdk/llamaindex', 'ported: toUIMessageStream is represented as a Kotlin Flow adapter over LlamaIndexEngineResponse, with callback lifecycle support folded into the root module'],
  ['@ai-sdk/mcp', 'in-progress: MCP JSON-RPC contracts, client handshake, capability-gated APIs, dynamic tool conversion, elicitation, OAuth type surface, and stdio API shape are currently folded into the root module; HTTP/SSE/stdio platform transports remain open'],
  ['@ai-sdk/moonshotai', 'ported: createMoonshotAI/moonshotai, provider settings, model id/options aliases, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module'],
  ['@ai-sdk/open-responses', 'in-progress: createOpenResponses, OpenResponsesOptions, generate/stream response mapping, and fake HTTP tests are folded into the root module; VERSION is exposed as OPEN_RESPONSES_VERSION until package modules are split'],
  ['@ai-sdk/openai', 'in-progress: createOpenAI/openai facade and hosted OpenAI tool descriptors are folded into the root module; Responses-specific model transport remains open'],
  ['@ai-sdk/openai-compatible', 'in-progress: OpenAI-compatible Ktor adapter is currently folded into the root module'],
  ['@ai-sdk/perplexity', 'ported: createPerplexity/perplexity and provider settings are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as PERPLEXITY_VERSION until package modules are split'],
  ['@ai-sdk/test-server', 'ported: createTestServer, TestResponseController, UrlResponse, UrlHandler, and UrlHandlers are represented as a Kotlin-native in-memory server with a Ktor MockEngine bridge folded into the root module'],
  ['@ai-sdk/togetherai', 'ported: createTogetherAI/togetherai, provider settings, image/reranking option surfaces, TogetherAIErrorData, chat/completion/embedding/image routing, and TogetherAI reranking are represented as a Kotlin facade folded into the root module; VERSION is exposed as TOGETHERAI_VERSION until package modules are split'],
  ['@ai-sdk/valibot', 'ported: valibotSchema is represented as a Kotlin-native Schema adapter folded into the root module'],
  ['@ai-sdk/vercel', 'ported: createVercel/vercel, VercelProviderSettings, VercelErrorData, and chat-only routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as VERCEL_VERSION until package modules are split'],
  ['@ai-sdk/voyage', 'ported: createVoyage/voyage, VoyageProviderSettings, embedding/reranking option surfaces, embedding routing, reranking routing, usage parsing, and embedding input limits are represented as a Kotlin facade folded into the root module; VERSION is exposed as VOYAGE_VERSION until package modules are split'],
]);

function main() {
  if (!existsSync(referenceRoot)) {
    fail(`Missing upstream reference root: ${relative(repoRoot, referenceRoot)}`);
  }

  const packages = listPackages().map(readPackageLedger);
  const files = new Map();
  files.set(join(outputRoot, 'README.md'), renderIndex(packages));
  for (const pkg of packages) {
    files.set(join(outputRoot, `${slugForPackage(pkg.name)}.md`), renderPackage(pkg));
  }

  if (checkOnly) {
    const failures = [];
    for (const [file, content] of files) {
      if (!existsSync(file)) {
        failures.push(`missing ${relative(repoRoot, file)}`);
        continue;
      }
      const actual = readFileSync(file, 'utf8');
      if (actual !== content) {
        failures.push(`stale ${relative(repoRoot, file)}`);
      }
    }
    if (failures.length > 0) {
      fail(`Parity ledgers are not current:\n${failures.map(item => `- ${item}`).join('\n')}`);
    }
    console.log(`Parity ledgers are current (${files.size} files).`);
    return;
  }

  mkdirSync(outputRoot, { recursive: true });
  for (const [file, content] of files) {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
  }
  console.log(`Wrote ${files.size} parity ledger files.`);
}

function listPackages() {
  return readdirSync(referenceRoot, { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .map(entry => join(referenceRoot, entry.name))
    .filter(dir => existsSync(join(dir, 'package.json')))
    .sort((left, right) => {
      const leftName = JSON.parse(readFileSync(join(left, 'package.json'), 'utf8')).name ?? '';
      const rightName = JSON.parse(readFileSync(join(right, 'package.json'), 'utf8')).name ?? '';
      return leftName.localeCompare(rightName);
    });
}

function readPackageLedger(packageDir) {
  const packageJson = JSON.parse(readFileSync(join(packageDir, 'package.json'), 'utf8'));
  const name = packageJson.name ?? relative(referenceRoot, packageDir);
  const entrypoints = resolveEntrypoints(packageDir, packageJson);
  const publicExports = new Map();
  const externalExports = new Set();
  const unresolvedExports = [];

  for (const entrypoint of entrypoints) {
    if (!entrypoint.sourcePath) {
      unresolvedExports.push(`${entrypoint.subpath}: no source entry found`);
      continue;
    }
    const parsed = parseExportSurface(entrypoint.sourcePath, packageDir);
    for (const item of parsed.exports) {
      const existing = publicExports.get(item.name);
      if (!existing) {
        publicExports.set(item.name, { ...item, entrypoints: [entrypoint.subpath] });
      } else if (!existing.entrypoints.includes(entrypoint.subpath)) {
        existing.entrypoints.push(entrypoint.subpath);
      }
    }
    for (const item of parsed.external) {
      externalExports.add(item);
    }
    for (const item of parsed.unresolved) {
      unresolvedExports.push(`${entrypoint.subpath}: ${item}`);
    }
  }

  return {
    dir: packageDir,
    name,
    version: packageJson.version ?? '',
    status: packageStatus.get(name) ?? 'missing: no Kotlin module or parity mapping exists yet',
    targetModule: targetModuleFor(name),
    entrypoints,
    exports: [...publicExports.values()].sort((left, right) => left.name.localeCompare(right.name)),
    externalExports: [...externalExports].sort(),
    unresolvedExports: [...new Set(unresolvedExports)].sort(),
  };
}

function resolveEntrypoints(packageDir, packageJson) {
  const exportsField = packageJson.exports;
  if (!exportsField || typeof exportsField !== 'object') {
    const binEntrypoints = Object.entries(packageJson.bin ?? {}).map(([name, target]) => ({
      subpath: `bin:${name}`,
      sourcePath: sourcePathForDistTarget(packageDir, target),
    }));
    if (binEntrypoints.length > 0) {
      return binEntrypoints;
    }
    return [
      {
        subpath: '.',
        sourcePath: firstExisting([
          join(packageDir, 'src', 'index.ts'),
          join(packageDir, 'index.ts'),
        ]),
      },
    ];
  }

  return Object.entries(exportsField)
    .filter(([subpath]) => subpath !== './package.json')
    .map(([subpath, value]) => {
      const typesPath = typeof value === 'string' ? value : value?.types ?? value?.import ?? value?.require;
      return {
        subpath,
        sourcePath: sourcePathForExport(packageDir, subpath, typesPath),
      };
    });
}

function sourcePathForExport(packageDir, subpath, typesPath) {
  const candidates = [];
  if (subpath === '.') {
    candidates.push(join(packageDir, 'src', 'index.ts'), join(packageDir, 'index.ts'));
  } else {
    const cleanSubpath = subpath.replace(/^\.\//, '');
    candidates.push(
      join(packageDir, 'src', cleanSubpath, 'index.ts'),
      join(packageDir, cleanSubpath, 'index.ts'),
      join(packageDir, 'src', `${cleanSubpath}.ts`),
      join(packageDir, `${cleanSubpath}.ts`),
    );
  }

  if (typeof typesPath === 'string') {
    const suffix = distTargetToSourceSuffix(typesPath);
    candidates.push(join(packageDir, 'src', suffix), join(packageDir, suffix));
    const basenameMatches = findFiles(packageDir, file => file.endsWith(suffix));
    candidates.push(...basenameMatches);
  }

  return firstExisting(candidates);
}

function sourcePathForDistTarget(packageDir, target) {
  if (typeof target !== 'string') return undefined;
  const suffix = distTargetToSourceSuffix(target);
  return firstExisting([
    join(packageDir, 'src', suffix),
    join(packageDir, suffix),
    ...findFiles(packageDir, file => file.endsWith(suffix)),
  ]);
}

function distTargetToSourceSuffix(target) {
  return target
    .replace(/^\.\//, '')
    .replace(/^dist\//, '')
    .replace(/\.d\.ts$/, '.ts')
    .replace(/\.js$/, '.ts');
}

function parseExportSurface(entryFile, packageDir, seen = new Set()) {
  const exports = new Map();
  const external = new Set();
  const unresolved = [];

  function addExport(item) {
    if (!exports.has(item.name)) {
      exports.set(item.name, item);
    }
  }

  function visit(file) {
    if (!file || seen.has(file)) return;
    seen.add(file);
    const source = stripComments(readFileSync(file, 'utf8'));

    for (const match of source.matchAll(/export\s+(?:type\s+)?\*\s+from\s+['"]([^'"]+)['"]/g)) {
      const target = match[1];
      if (!target.startsWith('.')) {
        external.add(`* from ${target}`);
        continue;
      }
      const resolved = resolveRelativeExport(file, target);
      if (resolved) {
        visit(resolved);
      } else {
        unresolved.push(`${relative(packageDir, file)} re-exports unresolved ${target}`);
      }
    }

    for (const match of source.matchAll(/export\s+(type\s+)?\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g)) {
      const exportKind = match[1] ? 'type' : 'value';
      const names = parseExportNames(match[2], exportKind);
      const target = match[3];
      if (!target.startsWith('.')) {
        for (const name of names) {
          addExport({ ...name, source: target });
        }
        continue;
      }
      const resolved = resolveRelativeExport(file, target);
      for (const name of names) {
        addExport({ ...name, source: resolved ? relative(packageDir, resolved) : target });
      }
      if (!resolved) {
        unresolved.push(`${relative(packageDir, file)} named export source unresolved ${target}`);
      }
    }

    for (const match of source.matchAll(/export\s+(?:declare\s+)?(?:async\s+)?(class|function|interface|type|const|let|var|enum)\s+([A-Za-z_$][\w$]*)/g)) {
      addExport({
        name: match[2],
        kind: declarationKind(match[1]),
        source: relative(packageDir, file),
      });
    }
  }

  visit(entryFile);
  return {
    exports: [...exports.values()],
    external: [...external],
    unresolved,
  };
}

function parseExportNames(rawNames, defaultKind) {
  return rawNames
    .split(',')
    .map(item => item.trim())
    .filter(Boolean)
    .map(item => item.replace(/\s+/g, ' '))
    .map(item => {
      let kind = defaultKind;
      let text = item;
      if (text.startsWith('type ')) {
        kind = 'type';
        text = text.slice('type '.length).trim();
      }
      const aliasMatch = text.match(/^([A-Za-z_$][\w$]*)\s+as\s+([A-Za-z_$][\w$]*)$/);
      const name = aliasMatch ? aliasMatch[2] : text;
      const original = aliasMatch ? aliasMatch[1] : text;
      return { name, original, kind };
    })
    .filter(item => /^[A-Za-z_$][\w$]*$/.test(item.name));
}

function resolveRelativeExport(fromFile, target) {
  const targetWithoutRuntimeExtension = target.replace(/\.js$/, '');
  const base = resolve(dirname(fromFile), targetWithoutRuntimeExtension);
  return firstExisting([
    `${base}.ts`,
    `${base}.tsx`,
    `${base}.svelte.ts`,
    `${base}.d.ts`,
    join(base, 'index.ts'),
    join(base, 'index.tsx'),
    join(base, 'index.d.ts'),
  ]);
}

function findFiles(root, predicate) {
  const result = [];
  const stack = [root];
  while (stack.length > 0) {
    const dir = stack.pop();
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const fullPath = join(dir, entry.name);
      if (entry.isDirectory()) {
        stack.push(fullPath);
      } else if (predicate(relative(root, fullPath).replaceAll('\\', '/'))) {
        result.push(fullPath);
      }
    }
  }
  return result;
}

function firstExisting(paths) {
  return paths.find(path => path && existsSync(path));
}

function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');
}

function declarationKind(kind) {
  return kind === 'interface' || kind === 'type' ? 'type' : 'value';
}

function targetModuleFor(packageName) {
  if (packageName === 'ai') return ':aisdk-core';
  if (packageName === '@ai-sdk/provider') return ':aisdk-provider';
  if (packageName === '@ai-sdk/provider-utils') return ':aisdk-provider-utils';
  if (packageName === '@ai-sdk/gateway') return ':aisdk-gateway';
  if (packageName === '@ai-sdk/openai-compatible') return ':aisdk-openai-compatible';
  if (packageName === '@ai-sdk/openai') return ':aisdk-openai';
  if (packageName === '@ai-sdk/open-responses') return ':aisdk-open-responses';
  if (packageName === '@ai-sdk/react') return ':aisdk-ui';
  if (packageName === '@ai-sdk/vue') return ':aisdk-ui';
  if (packageName === '@ai-sdk/svelte') return ':aisdk-ui';
  if (packageName === '@ai-sdk/angular') return ':aisdk-ui';
  if (packageName === '@ai-sdk/rsc') return ':aisdk-server';
  if (packageName === '@ai-sdk/mcp') return ':aisdk-mcp';
  if (packageName === '@ai-sdk/langchain') return ':aisdk-langchain';
  if (packageName === '@ai-sdk/llamaindex') return ':aisdk-llamaindex';
  if (packageName === '@ai-sdk/valibot') return ':aisdk-validation';
  if (packageName === '@ai-sdk/devtools') return ':aisdk-devtools';
  if (packageName === '@ai-sdk/test-server') return ':aisdk-test-server';
  if (packageName === '@ai-sdk/codemod') return ':aisdk-codemod';
  return `:aisdk-provider-${packageName.replace(/^@ai-sdk\//, '')}`;
}

function slugForPackage(packageName) {
  return packageName
    .replace(/^@/, '')
    .replace(/\//g, '-')
    .replace(/[^A-Za-z0-9._-]/g, '-');
}

function renderIndex(packages) {
  const totalExports = packages.reduce((sum, pkg) => sum + pkg.exports.length, 0);
  return [
    '# Vercel AI SDK v6 Parity Ledgers',
    '',
    'Generated from `.reference/vercel-ai-sdk-ai-6.0.195/packages`.',
    'Run `node tools/generate-parity-ledger.mjs` after refreshing the upstream reference.',
    'Run `node tools/generate-parity-ledger.mjs --check` in CI to verify this directory is current.',
    '',
    `- Packages: ${packages.length}`,
    `- Public exports discovered: ${totalExports}`,
    '',
    '| Package | Version | Target module | Upstream exports | Status |',
    '|---|---:|---|---:|---|',
    ...packages.map(pkg => {
      const link = `${slugForPackage(pkg.name)}.md`;
      return `| [${pkg.name}](${link}) | ${pkg.version} | \`${pkg.targetModule}\` | ${pkg.exports.length} | ${pkg.status} |`;
    }),
    '',
  ].join('\n');
}

function renderPackage(pkg) {
  const lines = [
    `# ${pkg.name}`,
    '',
    `- Version: ${pkg.version}`,
    `- Upstream path: \`${relative(repoRoot, pkg.dir)}\``,
    `- Target Kotlin module: \`${pkg.targetModule}\``,
    `- Current parity status: ${pkg.status}`,
    '',
    '## Entrypoints',
    '',
    '| Subpath | Source | Export count |',
    '|---|---|---:|',
  ];

  for (const entrypoint of pkg.entrypoints) {
    const source = entrypoint.sourcePath ? `\`${relative(repoRoot, entrypoint.sourcePath)}\`` : 'unresolved';
    const count = pkg.exports.filter(item => item.entrypoints?.includes(entrypoint.subpath)).length;
    lines.push(`| \`${entrypoint.subpath}\` | ${source} | ${count} |`);
  }

  lines.push('', '## Public Exports', '', '| Export | Kind | Source | Entrypoints |', '|---|---|---|---|');
  for (const item of pkg.exports) {
    const source = item.source ? `\`${item.source}\`` : '';
    const entrypoints = item.entrypoints?.map(name => `\`${name}\``).join(', ') ?? '';
    lines.push(`| \`${item.name}\` | ${item.kind} | ${source} | ${entrypoints} |`);
  }

  if (pkg.externalExports.length > 0) {
    lines.push('', '## External Star Re-exports', '');
    for (const item of pkg.externalExports) {
      lines.push(`- \`${item}\``);
    }
  }

  if (pkg.unresolvedExports.length > 0) {
    lines.push('', '## Unresolved Export Sources', '');
    for (const item of pkg.unresolvedExports) {
      lines.push(`- ${item}`);
    }
  }

  lines.push('');
  return lines.join('\n');
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

main();
