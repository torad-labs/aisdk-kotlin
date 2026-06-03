# Complete Vercel AI SDK v6 Port Plan

This plan defines "complete" as the full stable Vercel AI SDK v6 package
surface, adapted to Kotlin Multiplatform and Kotlin/JVM where the upstream
package is JavaScript, browser, Node, or framework-specific.

Reference locked for this pass:

- npm `ai@latest`: `6.0.195`
- upstream tag/reference directory: `.reference/vercel-ai-sdk-ai-6.0.195`
- package source root: `.reference/vercel-ai-sdk-ai-6.0.195/packages`

There is no stable `ai@6.1.x` release in npm metadata at verification time.
The stable target is therefore v6.0.195. Beta and canary dist-tags point at v7.

## Completion Rule

A package is complete only when it satisfies all of these gates:

- Every public export in the upstream package is represented in Kotlin, or has
  a documented Kotlin-native equivalent in the package's parity ledger.
- Request construction, headers, query params, body encoding, response parsing,
  stream parsing, warnings, and error mapping are covered by fake-transport
  golden tests.
- Model-family support is explicit: language, embedding, image, speech,
  transcription, video, reranking, gateway, or utility-only.
- Default tests require no live provider credentials.
- Optional live smoke tests are isolated behind environment variables and are
  not required for normal CI.
- Published artifacts expose stable package/module names and valid metadata.

No package remains "deferred" in the complete port. JS-only or framework-only
packages become Kotlin-native modules, adapters, or tools with tests.

## Upstream Package Inventory

Core and shared contracts:

- `ai`
- `@ai-sdk/provider`
- `@ai-sdk/provider-utils`
- `@ai-sdk/gateway`
- `@ai-sdk/openai-compatible`
- `@ai-sdk/openai`
- `@ai-sdk/open-responses`

Provider packages:

- `@ai-sdk/alibaba`
- `@ai-sdk/amazon-bedrock`
- `@ai-sdk/anthropic`
- `@ai-sdk/anthropic-aws`
- `@ai-sdk/assemblyai`
- `@ai-sdk/azure`
- `@ai-sdk/baseten`
- `@ai-sdk/black-forest-labs`
- `@ai-sdk/bytedance`
- `@ai-sdk/cerebras`
- `@ai-sdk/cohere`
- `@ai-sdk/deepgram`
- `@ai-sdk/deepinfra`
- `@ai-sdk/deepseek`
- `@ai-sdk/elevenlabs`
- `@ai-sdk/fal`
- `@ai-sdk/fireworks`
- `@ai-sdk/gladia`
- `@ai-sdk/google`
- `@ai-sdk/google-vertex`
- `@ai-sdk/groq`
- `@ai-sdk/huggingface`
- `@ai-sdk/hume`
- `@ai-sdk/klingai`
- `@ai-sdk/lmnt`
- `@ai-sdk/luma`
- `@ai-sdk/mistral`
- `@ai-sdk/moonshotai`
- `@ai-sdk/perplexity`
- `@ai-sdk/prodia`
- `@ai-sdk/quiverai`
- `@ai-sdk/replicate`
- `@ai-sdk/revai`
- `@ai-sdk/togetherai`
- `@ai-sdk/vercel`
- `@ai-sdk/voyage`
- `@ai-sdk/xai`

Framework, UI, interop, and tooling packages:

- `@ai-sdk/angular`
- `@ai-sdk/react`
- `@ai-sdk/rsc`
- `@ai-sdk/svelte`
- `@ai-sdk/vue`
- `@ai-sdk/mcp`
- `@ai-sdk/langchain`
- `@ai-sdk/llamaindex`
- `@ai-sdk/valibot`
- `@ai-sdk/devtools`
- `@ai-sdk/test-server`
- `@ai-sdk/codemod`

## Module Layout

Use a Gradle multi-module build. Keep the existing root module as the core
artifact until the split is complete, then make it an aggregate convenience
artifact if needed.

Core modules:

- `:aisdk-core`
- `:aisdk-provider`
- `:aisdk-provider-utils`
- `:aisdk-gateway`
- `:aisdk-openai-compatible`
- `:aisdk-openai`
- `:aisdk-open-responses`

Provider modules:

- `:aisdk-provider-alibaba`
- `:aisdk-provider-amazon-bedrock`
- `:aisdk-provider-anthropic`
- `:aisdk-provider-anthropic-aws`
- `:aisdk-provider-assemblyai`
- `:aisdk-provider-azure`
- `:aisdk-provider-baseten`
- `:aisdk-provider-black-forest-labs`
- `:aisdk-provider-bytedance`
- `:aisdk-provider-cerebras`
- `:aisdk-provider-cohere`
- `:aisdk-provider-deepgram`
- `:aisdk-provider-deepinfra`
- `:aisdk-provider-deepseek`
- `:aisdk-provider-elevenlabs`
- `:aisdk-provider-fal`
- `:aisdk-provider-fireworks`
- `:aisdk-provider-gladia`
- `:aisdk-provider-google`
- `:aisdk-provider-google-vertex`
- `:aisdk-provider-groq`
- `:aisdk-provider-huggingface`
- `:aisdk-provider-hume`
- `:aisdk-provider-klingai`
- `:aisdk-provider-lmnt`
- `:aisdk-provider-luma`
- `:aisdk-provider-mistral`
- `:aisdk-provider-moonshotai`
- `:aisdk-provider-perplexity`
- `:aisdk-provider-prodia`
- `:aisdk-provider-quiverai`
- `:aisdk-provider-replicate`
- `:aisdk-provider-revai`
- `:aisdk-provider-togetherai`
- `:aisdk-provider-vercel`
- `:aisdk-provider-voyage`
- `:aisdk-provider-xai`

Kotlin-native UI, framework, interop, and tooling modules:

- `:aisdk-ui`
- `:aisdk-compose`
- `:aisdk-server`
- `:aisdk-server-ktor`
- `:aisdk-mcp`
- `:aisdk-langchain`
- `:aisdk-llamaindex`
- `:aisdk-validation`
- `:aisdk-devtools`
- `:aisdk-test-server`
- `:aisdk-codemod`

## Execution Tracks

### 1. Mechanical Parity Ledger

- Generate an export list for every upstream package from the TypeScript source.
- Generate the current Kotlin public API list from source and binary metadata.
- Store one ledger per package under `docs/parity/`.
- Mark every export as `ported`, `renamed`, `kotlin-equivalent`, or `missing`.
- Block release while any package ledger has `missing`.

### 2. Core Exactness

- Remove the remaining rows from `docs/AISDK_PORT_GAPS.md` by implementing
  Kotlin equivalents for each row.
- Add tests for incremental `extractJsonMiddleware` streaming behavior.
- Add `StreamTextResult` metadata accessors for warnings and response metadata.
- Add structured tool-result stream output parity.
- Add agent-level active tool configuration if upstream behavior requires it.
- Add call-options schema validation where provider modules can consume it.

### 3. Shared Provider Infrastructure

- Normalize HTTP request building across providers.
- Provide fake transport, recording transport, and stream fixture transport.
- Centralize API key handling, header merging, retry policy, JSON event stream
  parsing, multipart handling, binary payload handling, and error mapping.
- Add provider conformance tests that every provider module must pass.

### 4. Provider Modules

For each provider module:

- Port settings, model factories, model ids, defaults, and provider options.
- Implement all model families supported by upstream.
- Implement request/response mapping with golden fixtures.
- Implement stream parsing with chunk-by-chunk fixtures.
- Implement provider-specific error mapping.
- Add compile samples for common Kotlin, Android, and JVM usage.

### 5. Framework And UI Modules

- Keep `Chat`, transports, UI messages, and stream readers in Kotlin-native
  form.
- Add Compose state helpers and renderer-neutral handlers.
- Add Ktor/server response adapters for text streams, UI message streams, and
  data streams.
- Represent React, Vue, Svelte, Angular, and RSC package semantics through
  Kotlin state holders, flows, adapters, and sample bindings.

### 6. MCP And Interop

- Port MCP tool loading and invocation into `:aisdk-mcp`.
- Add LangChain and LlamaIndex bridge modules for JVM callers.
- Map validation helper packages into Kotlin serialization and validation
  primitives in `:aisdk-validation`.

### 7. Tooling

- Add `:aisdk-test-server` for deterministic provider fixture tests.
- Add `:aisdk-devtools` for stream inspection and debugging.
- Add `:aisdk-codemod` as a JVM CLI for v5-to-v6 Kotlin migration patterns.

### 8. Test Suite

Required test groups:

- Export parity tests for every module.
- Golden request tests for every provider endpoint.
- Golden response tests for success, warning, and error payloads.
- Stream fixture tests with partial chunks, malformed chunks, and finish events.
- Tool-call, tool-result, tool-approval, and repair-loop tests.
- Schema generation tests for nested, polymorphic, nullable, recursive, dynamic,
  and provider-executed tools.
- UI aggregation tests for every message part and state transition.
- Server adapter tests for headers, status, body, cancellation, and errors.
- Publication tests for every module.
- Binary API validation after public API stabilization.

### 9. CI And Release Gates

- Run all unit tests on JVM and Android host.
- Compile all KMP targets configured for the repo.
- Publish every module to Maven local in CI.
- Run API parity ledgers and fail on missing entries.
- Run lint, dependency checks, and metadata checks.
- Keep live provider smoke tests separate from default CI.

## Failure Ownership Note

The `./gradlew jvmTest` failure observed at `2026-06-03T03:58:36Z` was traced
to recursive schema derivation for `JsonElement` descriptors in the tool schema
generator. Commit `bb63de6` fixed the cause by preserving explicit dynamic-tool
schemas, guarding recursive descriptors, special-casing kotlinx JSON tree
descriptors, and adding strict schema regression tests.

Verification after the fix:

- `./gradlew jvmTest --rerun-tasks --no-build-cache`: passed
- `FullPortFeatureParityTest[jvm]`: 15 tests, 0 failures, 0 errors
- `ToolTest[jvm]`: strict serializer schema coverage present
