## Executive read

This repo is **already much more hardened than a normal alpha SDK**: `explicitApi()`, Kotlin ABI dumps, Linux Native tests, Apple CI, custom detekt rules, Konsist architecture tests, ast-grep CI gate, Maven local publication checks, 2,323 passing tests across root + detekt rules, and a prior 26-finding robustness audit that appears fixed.

But I would **not call this first-beta ready yet**. The remaining risk is not “no tests”; it is **the wrong gaps are still allowed to pass green**:

- Coverage is measured but **not gated**.
- Detekt has a **3,687-entry baseline** and the cancellation rule is documented as **inert**.
- `publishToMavenLocal` succeeds but emits **large Dokka unresolved-link warnings** and a **Gradle 10 deprecation warning**.
- README quickstart and `INTERFACE_CONTRACT.md` are stale enough to mislead first users: README imports lowercase APIs and instantiates `ToolLoopAgent`, but the code now exposes PascalCase factories and `ToolLoopAgent` is abstract.
- Tool execution can still create unbounded child coroutines because `maxParallelToolCalls` defaults to `Int.MAX_VALUE` and one coroutine is launched per tool call before permit acquisition.
- Telemetry and logging still have raw-content/secret-leak surfaces: `LoggingMiddleware` logs raw tool args, and telemetry integration events carry full messages/call params while `recordInputs`/`recordOutputs` are documented as not yet honored.
- `RetryPolicy` has retry-after parsing and cancellation rethrow, but lacks full jitter, total deadline, random/clock injection, and a safe default retry predicate.
- Release supply-chain posture is not Square-grade yet: no Gradle dependency verification metadata, GitHub Actions are tag-pinned not SHA-pinned, and CI installs `@ast-grep/cli` globally at runtime from npm.
- The public API is huge and evolvability-hostile: I counted **242 public data classes** in `commonMain`.

Below is the plan I would execute. I’ve broken it into independent blocks so they can be implemented with minimal collision.

---

## Implementation status

This section is maintained while hardening lands. A block is checked when its beta acceptance is either implemented directly or guarded by an executable ratchet that prevents regression/new debt.

- [x] Block 1 — Public examples/docs compile
  - README quickstart now uses current PascalCase API and a concrete `ToolLoopAgent` subclass.
  - `ReadmeQuickStartTest` compiles/runs the quickstart shape.
  - Dokka unresolved-link warnings are cleared in `publishToMavenLocal`.
- [x] Block 2 — Coverage is release-gated
  - `koverVerify` is wired under `check` with line/instruction/branch ratchets.
- [x] Block 3 — Detekt baseline ratchet + cancellation detection
  - Detekt baseline budget is enforced under `check`.
  - Added custom `NoRunCatchingInSuspendFunction` detekt rule + tests to cover the cancellation hole while built-in typed detection remains unavailable for KMP.
- [x] Block 4 — ToolLoopAgent bounded execution policy
  - `ToolExecutionPolicy` bounds per-step parallelism, per-step total tool calls, progress buffering, and optional per-tool timeout.
  - Parallel execution now uses bounded workers rather than one coroutine per tool call.
  - Large-file/decomposition debt is now guarded by `architecture-budget.json` / `tools/check-architecture-budget.mjs` so it cannot grow silently before the deeper split.
- [x] Block 5 — Retry/timeout/transport resilience
  - `RetryPolicy` now defaults to typed retryable errors, full jitter, injected clock/delay generator, and total/per-attempt deadlines.
- [x] Block 6 — Provider conformance matrix/golden tests
  - Provider capability matrix plus freshness check covers 45 public provider classes.
  - Provider golden/conformance coverage manifest tracks request/stream coverage for every provider and is enforced by `tools/check-provider-golden-coverage.mjs`.
- [x] Block 7 — Telemetry/logging redaction defaults
  - Telemetry integration events are metadata-only by default; `LoggingMiddleware` avoids raw payloads unless explicitly opted in.
- [x] Block 8 — API surface freeze discipline
  - API-review gate requires changelog + interface contract updates for ABI dump changes.
  - Public API `@since` debt is budgeted by `api-since-budget.json`; new public declarations cannot increase the missing-`@since` count.
- [x] Block 9 — Supply-chain/release hardening
  - Dependency verification metadata, SHA-pinned Actions, no global npm install, workflow timeouts, local-staging smoke, and macOS Swift smoke hook are in place.
- [x] Block 10 — Real consumer artifact validation
  - JVM, KMP, and Android release local-staging consumers compile against published coordinates.
  - iOS Swift import smoke is wired into macOS CI via `tools/run-ios-swift-smoke` and skips only on non-Darwin local hosts.
- [x] Block 11 — Protocol/MCP chaos tests
  - Added SSE parser and JSON-RPC/MCP late/duplicate/malformed-message regression tests.
- [x] Block 12 — Architecture boundary enforcement
  - Konsist boundary checks enforce provider/UI/protocol layering.
  - Large-file budgets prevent load-bearing files from growing while deeper module/file splits are staged.
- [x] Block 13 — Error taxonomy consistency
  - API review gate budgets generic throw patterns; new tool-policy errors are typed.
  - Error taxonomy budget now runs in the beta readiness gate.
- [x] Block 14 — Beta release checklist gate
  - `tools/beta-readiness-check --strict-readme` passes with no warnings and runs all added budget/conformance gates.

---

## Verification I ran

Current branch: `refactor/ts-residue-cleanup`, head `c14d586 Harden tool occurrence identity`.

Commands run:

- `./gradlew check --no-configuration-cache` → **green**
- `bash .claude/hooks/rules/ci-gate.sh` → **green**
- `./gradlew publishToMavenLocal --no-configuration-cache` → **green**, **0 Dokka unresolved-link warnings**; Gradle still emits deprecation warnings
- `./gradlew koverXmlReport` → generated coverage report
- Test XML count:
  - root build: **2,306 tests, 0 failures**
  - detekt-rules: **17 tests, 0 failures**
- Kover aggregate:
  - line coverage: `[meas: coverage_line_percent]`
  - instruction coverage: `[meas: coverage_instruction_percent]`
  - branch coverage: `[meas: coverage_branch_percent]`
- Detekt baseline:
  - **3,687 entries**
  - biggest buckets: `ArgumentListWrapping`, `MaxLineLength`, `MagicNumber`, `FunctionNaming`, `NoNotNullAssertion`, `LongMethod`, `LongParameterList`
- Public data classes:
  - **242** in `src/commonMain/kotlin`

Post-hardening gates additionally run green:

- `tools/beta-readiness-check --strict-readme`
- `tools/check-provider-capabilities.mjs`
- `tools/check-provider-golden-coverage.mjs`
- `tools/check-public-api-since-budget.mjs`
- `tools/check-architecture-budget.mjs`
- `tools/check-api-review.mjs`
- `API_REVIEW_BASE=HEAD tools/check-api-review.mjs`
- `tools/run-local-staging-smoke --no-publish --staging-repo "$HOME/.m2/repository"`
- `tools/run-ios-swift-smoke` (skips on non-Darwin; wired to macOS CI)

---

# Plan

## Block 1 — Make public examples and docs compile, or delete them

**Risk addressed:** first beta users copy README/docs and fail immediately. This is reputation-damaging and creates false bug reports.

**Evidence found:**

- README examples must use PascalCase factories such as `StepCountIs`, `Tool`, and `ToolSet`; the beta quickstart now follows that shape.
- README instantiates `ToolLoopAgent`, but `ToolLoopAgent` is now `public abstract class`.
- `INTERFACE_CONTRACT.md` still says `class ToolLoopAgent(...) — default impl`, while the source says `public abstract class ToolLoopAgent`.

**Files owned:**

- `README.md`
- `INTERFACE_CONTRACT.md`
- `docs/wiki/**`
- `build.gradle.kts`
- new `samples/**` or `src/commonTest/kotlin/.../samples/**`

**Tasks:**

1. Replace README quickstart with a compiled sample.
   - Define a tiny concrete subclass:
     ```kotlin
     class HelloAgent(model: LanguageModel, tools: ToolSet<Unit>) :
         ToolLoopAgent<Unit, String>(
             model = model,
             instructions = "Be brief.",
             tools = tools,
             stopWhen = StepCountIs(3),
         )
     ```
   - Use current API names: `Tool`, `ToolSet`, `StepCountIs`.

2. Add compiled samples.
   - Add a `samples` source set or compile sample functions under tests.
   - Reference them from KDoc with `@sample`.
   - For README, either generate snippets from those sample files or add a CI check that compiles README fenced Kotlin snippets.

3. Fix `INTERFACE_CONTRACT.md`.
   - Mark `ToolLoopAgent` as abstract/extend-only.
   - Replace lowercase tool factories with current names.
   - Confirm every listed type still exists.

4. Make Dokka unresolved links fail CI.
   - Current `publishToMavenLocal` produced many unresolved links.
   - Add a Dokka log validation step or Dokka config that treats warnings as build failures if supported by the used Dokka version.
   - Fix links like `generateText`, `streamText`, `streamSse`, `registerTelemetry`, `embedMany`, etc.

**Acceptance gate:**

- `./gradlew check publishToMavenLocal` produces no unresolved Dokka links.
- README quickstart compiles in CI.
- `INTERFACE_CONTRACT.md` is generated or checked against public API names.

---

## Block 2 — Turn coverage from “measurement” into a release gate

**Risk addressed:** coverage can silently regress while CI stays green.

**Evidence found:**

- `build.gradle.kts` explicitly says Kover has “Measurement only for now — no enforced threshold”.
- Current coverage is usable as a starting point:
  - line: `[meas: coverage_line_percent]`
  - branch: `[meas: coverage_branch_percent]`
- Low-covered/high-risk areas include:
  - `GatewayContentDecoder`
  - `GatewayContentEncoder`
  - `Completion`
  - `StructuredObject`
  - several provider request/stream state classes

**Files owned:**

- `build.gradle.kts`
- `src/commonTest/**`
- possibly new `src/jvmTest/**` coverage/golden tests

**Tasks:**

1. Add global coverage gates:
   - line minimum
   - branch minimum
   - instruction minimum

2. Add package/class-specific gates for load-bearing code:
   - `ToolLoopAgent`
   - `ToolApprovalCoordinator`
   - `RetryPolicy`
   - `HttpTransport`
   - `EventStreamParser`
   - protocol codecs under `protocol/`
   - provider request builders and stream decoders

3. Use differential ratcheting.
   - Store current baseline coverage numbers in a checked-in script/config.
   - Fail if coverage drops.
   - Raise only when new tests increase it.

4. Exclude only generated/uninteresting classes.
   - Keep provider encoders/decoders in coverage.
   - Do not exclude hard code just to make gates pass.

**Acceptance gate:**

- `./gradlew check` fails on coverage regression.
- Branch coverage for `ToolLoopAgent`/transport/protocol code is explicitly visible.
- No coverage baseline can be lowered without a visible config diff.

---

## Block 3 — Burn down the detekt baseline and make cancellation detection real

**Risk addressed:** lints are present, but thousands of issues are still suppressed; the most important coroutine cancellation rule is documented as inert.

**Evidence found:**

- `detekt-baseline.xml` has **3,687 entries**.
- `build.gradle.kts` runs detekt classpath-less.
- `detekt.yml` explicitly documents `SuspendFunSwallowedCancellation` as currently inert because type resolution is missing.

**Files owned:**

- `detekt.yml`
- `build.gradle.kts`
- `detekt-baseline.xml`
- `detekt-rules/**`
- `.claude/hooks/rules/**`

**Tasks:**

1. Add a baseline budget checker.
   - Count entries by rule.
   - Fail CI if the count increases.
   - Store counts in `detekt-baseline-budget.json`.

2. Prioritize semantic buckets before style buckets:
   - `NoNotNullAssertion`
   - typed error rules
   - broad catch / swallowed cancellation
   - mutable public state
   - long method / long parameter list in load-bearing files

3. Add type-aware cancellation checks.
   - Introduce a JVM/classpath-aware detekt task for `jvmMain`/`commonMain` where possible.
   - If detekt cannot cover KMP correctly, add a custom detekt rule or compiler-test fixture that flags `runCatching` around known suspend call shapes.
   - Keep the existing classpath-less detekt task for KMP-wide style/structural checks.

4. Add a `runSuspendCatching` primitive only if needed.
   - It must rethrow `CancellationException`.
   - Then ban stdlib `runCatching` in suspend contexts via rule/test.

5. Make custom rules cover the repo, not just new edits.
   - Current custom detekt rules are good, but only a subset of ast-grep rules are mirrored.
   - Mirror highest-value ast-grep rules into detekt/Konsist where feasible.

**Acceptance gate:**

- Baseline count cannot increase.
- A fake `runCatching { suspendCall() }` test fails the gate.
- Broad catch without cancellation rethrow fails.
- No new `!!` or raw generic throw can land.

---

## Block 4 — ToolLoopAgent resource safety and decomposition

**Risk addressed:** the most important runtime object is still too large and has one dangerous resource default.

**Evidence found:**

- `ToolLoopAgent.kt` is **1,688 LOC**.
- Constructor exposes many unrelated knobs.
- `maxParallelToolCalls` defaults to `Int.MAX_VALUE`.
- The parallel tool block launches one child coroutine per tool call, then uses `Semaphore(maxParallelToolCalls.coerceAtLeast(1))`; this bounds execution but not coroutine creation.

**Files owned:**

- `ToolLoopAgent.kt`
- `ToolLoopParallelExecution.kt`
- `ToolLoopAgentEngine.kt`
- `ToolLoopAgentInternals.kt`
- `ToolExecutionResult.kt`
- tests around parallel tool execution, abort, approvals

**Tasks:**

1. Replace unbounded default.
   - Introduce an explicit `ToolExecutionPolicy`.
   - Default should be bounded.
   - Include:
     - `maxParallelToolCalls`
     - `maxToolCallsPerStep`
     - `toolExecutionTimeout`
     - `progressBufferCapacity`
     - overflow behavior

2. Do not launch one coroutine per tool call.
   - Implement a bounded worker scheduler.
   - Launch at most `maxParallelToolCalls` workers.
   - Queue remaining calls.
   - Preserve deterministic application order.

3. Add hard tests:
   - model emits 10,000 tool calls → no 10,000 coroutines
   - one tool hangs → timeout path returns typed failure
   - one tool aborts → one terminal `Abort`, no deadlock
   - duplicate `toolCallId` with different occurrence → no collision
   - all categorization failures → model gets a retry step when appropriate
   - approval + duplicate IDs + denial + resume remain ordered

4. Split orchestration from execution.
   - Extract:
     - `ToolCallCategorizer`
     - `ToolExecutionScheduler`
     - `StepAccumulator`
     - `MessageAppender`
     - `LoopTerminationDecider`
   - Keep `ToolLoopAgent` as the public façade.

5. Move constructor knobs into config groups.
   - `AgentSamplerDefaults`
   - `AgentSecurityConfig`
   - `AgentExecutionPolicy`
   - `AgentTelemetryConfig`
   - `AgentEngineConfig`

**Acceptance gate:**

- No `ToolLoopAgent` method remains large enough to hide lifecycle logic.
- Resource policy is explicit and tested.
- Tool-loop tests include adversarial scale and cancellation tests.
- Public constructor stops growing whenever one setting is added.

---

## Block 5 — Retry, timeout, and transport resilience

**Risk addressed:** provider calls are paid, flaky, rate-limited, and security-sensitive; retry behavior must be deterministic and bounded.

**Evidence found:**

- `RetryPolicy` retries with fixed exponential delay, no full jitter.
- Default `shouldRetry = { true }`, so callers can accidentally retry terminal errors.
- It parses `Retry-After`, but uses `Clock.System.now()` directly.
- There is no total deadline in `RetryPolicy`.
- `APICallError` has `isRetryable`, but `RetryPolicy` does not default to it.

**Files owned:**

- `RetryPolicy.kt`
- `AiSdkError.kt`
- `HttpTransport.kt`
- provider HTTP call sites
- retry tests

**Tasks:**

1. Change retry default predicate.
   - Default should retry only:
     - `APICallError.isRetryable`
     - `GatewayError.isRetryable`
     - network/transport exceptions explicitly classified as retryable
   - Do not retry 400/401/403/404/422.
   - Include 425 if intended.

2. Add full jitter.
   - `random(0, min(maxDelay, base * 2^attempt))`
   - Honor `Retry-After` as a floor or server-directed delay, capped.

3. Inject clock and random.
   - No direct `Clock.System.now()` inside policy.
   - Tests should be deterministic.

4. Add total deadline and per-attempt timeout.
   - Avoid `maxRetries * timeout + backoff` growing without a bound.

5. Add `RetryError` detail.
   - Preserve all attempt errors.
   - Include retry decisions and delays for debugging.

6. Provider integration pass.
   - Ensure every provider uses the shared retry policy or explicitly opts out.
   - Add tests for 429, 500, 401, malformed body, retry-after-ms, HTTP-date retry-after.

**Acceptance gate:**

- Retry tests use virtual time and deterministic random.
- No terminal 4xx is retried by default.
- A stalled provider call cannot hang forever.
- Retry behavior is one shared policy, not duplicated per provider.

---

## Block 6 — Provider conformance matrix and golden wire tests

**Risk addressed:** most actual beta bugs will be provider request/response mismatches, not pure Kotlin mistakes.

**Evidence found:**

- The repo has many provider tests and parity ledgers.
- `docs/reports/robustness-audit.md` says provider subsystem verifiers were rate-limited and a scoped re-audit was queued.
- Coverage report shows multiple provider request/stream classes still have meaningful uncovered branches.

**Files owned:**

- `src/commonMain/kotlin/ai/torad/aisdk/providers/**`
- `src/commonTest/kotlin/ai/torad/aisdk/*ProviderTest.kt`
- `docs/parity/**`
- `tools/generate-parity-ledger.mjs`
- new `src/commonTest/resources/golden/**` if resources are added

**Tasks:**

1. Create a provider capability matrix.
   - language generate
   - language stream
   - tools
   - structured output
   - images/files
   - embeddings
   - speech/transcription/video
   - provider-executed tools
   - response metadata
   - usage fields
   - retry/error envelope

2. Golden request tests.
   - For each provider, serialize the actual request body through production code.
   - Compare to committed golden JSON.
   - Normalize field ordering.

3. Golden stream tests.
   - Feed provider-specific SSE/event chunks into production decoders.
   - Assert emitted `StreamEvent` sequence exactly.

4. Defensive parsing fuzz.
   - Unknown fields.
   - Wrong primitive type.
   - Missing optional fields.
   - Missing required fields.
   - Duplicate tool call IDs.
   - Non-string IDs where provider claims strings.
   - Empty SSE data.
   - Partial JSON.
   - Provider errors mid-stream.

5. Cross-provider tool-result contract.
   - Same `ContentPart.ToolResult` input must format correctly for OpenAI, Anthropic, Bedrock, Google, Cohere, OpenResponses, etc.
   - Include MCP content/image outputs.

6. Add parity drift reports to CI.
   - Current CI fetches pinned Vercel AI SDK reference and verifies ledgers.
   - Expand it so a provider parity ledger cannot be stale.

**Acceptance gate:**

- Every provider has request golden tests and stream golden tests.
- Capability matrix is generated or checked.
- Adding a provider requires passing the shared conformance suite.
- No provider can silently stringify structured tool output where native content blocks are required.

---

## Block 7 — Telemetry/logging redaction and privacy defaults

**Risk addressed:** SDKs get adopted in apps that process prompts, files, customer data, and API keys. Logs and telemetry are a common leak path.

**Evidence found:**

- `LoggingMiddleware` logs raw tool args: `args=${event.inputJson}`.
- `TelemetrySettings` says `recordInputs`/`recordOutputs` are not honored by the integration path.
- `AgentEvent.StepStarted` carries full messages and prepared call params.
- `AgentEvent.ModelCallStarted` carries full `LanguageModelCallParams`.

**Files owned:**

- `Telemetry.kt`
- `TelemetryTracing.kt`
- `AgentTelemetryDispatcher.kt`
- `Lifecycle.kt`
- `middleware/Logging.kt`
- tests for redaction

**Tasks:**

1. Add a `Redactor` interface.
   - Default redacts:
     - `authorization`
     - `api-key`
     - `x-api-key`
     - `x-goog-api-key`
     - `xi-api-key`
     - bearer/basic tokens
     - large prompt bodies
     - file/base64 payloads
   - Allow opt-in raw logging.

2. Change `LoggingMiddleware`.
   - Log tool name, ID, schema size, arg byte count.
   - Do not log raw args by default.
   - Preserve debug diagnostics through explicit `LoggingOptions(recordInputs = true)`.

3. Fix telemetry settings semantics.
   - If `recordInputs=false`, telemetry event projection must not expose messages/params/tool input.
   - If `recordOutputs=false`, do not expose output text/tool output.
   - Default should be metadata-only unless intentionally matching upstream behavior is a strict goal.

4. Add safe event projection.
   - Raw `AgentEvent` can remain for in-process lifecycle hooks.
   - Telemetry integrations should receive a redacted `TelemetryEvent` or redacted copy.

5. Tests:
   - Authorization header never appears in logs.
   - Tool args do not appear by default.
   - Prompt text does not appear when `recordInputs=false`.
   - Base64/file payloads are summarized.

**Acceptance gate:**

- Grep-style tests prove common secret strings are absent from logs/telemetry by default.
- Users can opt into raw payloads deliberately.
- Docs clearly explain privacy behavior.

---

## Block 8 — API surface freeze and beta compatibility discipline

**Risk addressed:** once announced, users compile against this. Kotlin binary compatibility breaks are easy to create and hard to diagnose downstream.

**Evidence found:**

- `explicitApi()` and ABI validation are already present.
- API dumps are committed.
- There are **242 public data classes** in `commonMain`.
- Many public surfaces are alpha-vs-stable ambiguous.
- `@since` discipline appears absent.

**Files owned:**

- all public API files under `src/commonMain`
- `api/**`
- `CHANGELOG.md`
- `INTERFACE_CONTRACT.md`
- `OptIn.kt`
- new API review tooling/scripts

**Tasks:**

1. Classify every public symbol:
   - beta stable
   - experimental
   - internal-but-public-for-KMP
   - deprecated compatibility shim
   - provider-specific unstable

2. Apply opt-in markers.
   - `@ExperimentalAiSdkApi`
   - `@InternalAiSdkApi`
   - possibly provider-specific experimental markers

3. Public data class audit.
   - For evolvable request/result/config types, consider:
     - regular class with private constructor + builder
     - interface + implementation
     - sealed result hierarchy
     - explicit builder DSL
   - Keep `data class` only where fields are truly frozen.

4. Constructor/API evolution rules.
   - No appending params to public functions/classes without explicit ABI review.
   - Prefer new overloads or config types.
   - Avoid public mutable collections and arrays.

5. Add API review CI.
   - If `api/*.api` changes:
     - require `CHANGELOG.md` change
     - require `INTERFACE_CONTRACT.md` change
     - require API classification marker for new public symbols
   - Add script to fail missing `@since` KDoc on new public declarations.

6. Decide root artifact vs modules.
   - Current root includes many provider facades.
   - For beta, either:
     - commit to monolith temporarily and document it, or
     - split `core`, `provider-openai`, `provider-anthropic`, `provider-google`, etc.
   - The plan should avoid breaking the core API when provider surfaces churn.

**Acceptance gate:**

- Public API changes are impossible to merge silently.
- New public symbols are classified and documented.
- Changelog and API dump move together.
- First beta has a clear compatibility promise.

---

## Block 9 — Supply-chain and release hardening

**Risk addressed:** a professional open-source SDK has to defend the build/release path, not just runtime code.

**Evidence found:**

- No `gradle/verification-metadata.xml`.
- Workflows use tag-pinned actions like `actions/checkout@v6.0.3`, not commit SHA pins.
- CI installs `@ast-grep/cli@0.42.1` globally at runtime via npm.
- Release publishes from `ubuntu-latest` after separate Apple verification.
- GitHub Packages smoke verification is best-effort and `continue-on-error`.

**Files owned:**

- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`
- `gradle/verification-metadata.xml`
- `settings.gradle.kts`
- build scripts
- new release smoke project(s)

**Tasks:**

1. Enable Gradle dependency verification.
   - Generate `gradle/verification-metadata.xml`.
   - Review and commit it.
   - Fail CI on unverified artifacts.

2. Pin GitHub Actions by SHA.
   - Keep version comments for readability.
   - Use Dependabot/Renovate to update pins.

3. Remove global npm install from CI.
   - Vendor an ast-grep binary checksum, use a pinned action, or install via locked package manager with integrity.
   - Add cache with checksum validation.

4. Add workflow timeouts.
   - Each job should have `timeout-minutes`.
   - Long-hanging native/publish jobs should fail, not burn.

5. Strengthen release smoke.
   - GitHub Packages smoke cannot be best-effort if it is the only immediate resolution test.
   - Add local-staging smoke before upload:
     - create a temporary consumer project
     - resolve from `build/staging-deploy`
     - compile JVM consumer
     - compile Android consumer
     - compile KMP metadata consumer
   - Add Central status validation already exists; keep it.

6. Consider publishing from macOS for all KMP artifacts.
   - Current release publishes from Ubuntu and verifies Apple separately.
   - For maximum KMP publishing conservatism, publish all artifacts from one macOS runner.

7. Add release provenance.
   - Generate checksums/SBOM.
   - Attach signed release artifacts.
   - Use GitHub OIDC/trusted publishing if the ecosystem path supports it for Central.

**Acceptance gate:**

- CI can reproduce dependencies only from verified checksums.
- Action supply chain is SHA-pinned.
- Release fails if the staged artifact cannot be consumed by real sample projects.
- No “best effort” step is the only guard for published artifact resolution.

---

## Block 10 — Real consumer app validation

**Risk addressed:** the code can pass unit tests and still fail as a consumed SDK due to Gradle metadata, Android, iOS, R8, or Swift interop.

**Files owned:**

- new `smoke-tests/**`
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`
- publication config

**Tasks:**

1. Add local Maven consumer fixtures:
   - plain JVM app
   - Android library/app
   - KMP shared module
   - iOS/Swift integration fixture if practical

2. Test actual published coordinates from local staging.
   - Do not use project dependency.
   - Resolve `ai.torad:torad-aisdk:$VERSION_NAME`.

3. Android checks:
   - compile release variant
   - run R8/minify smoke if possible
   - ensure no unexpected keep-rules needed
   - verify no `kotlin-reflect`

4. iOS checks:
   - compile simulator target
   - import framework/module from Swift
   - instantiate key API surfaces
   - collect a simple `Flow` if exported path supports it

5. Runtime smoke:
   - create a mock model
   - create a tool
   - run one generate call
   - run one stream call
   - run approval pause/resume
   - run structured output

**Acceptance gate:**

- Beta artifacts are tested as artifacts, not only as source.
- Android release consumer compiles.
- KMP metadata consumer compiles.
- iOS simulator consumer compiles.
- Smoke tests cover ToolLoopAgent, Tools, middleware, and provider registry.

---

## Block 11 — Protocol and MCP chaos tests

**Risk addressed:** MCP/streaming/protocol clients fail on weird input, late responses, cancellation, and EOF behavior.

**Evidence found:**

- Prior robustness audit found many MCP/SSE issues and marks them fixed.
- That means this area is important enough to keep under chaos tests permanently.

**Files owned:**

- `MCP.kt`
- `McpProtocol.kt`
- `EventStreamParser.kt`
- `HttpTransport.kt`
- `KtorGatewayTransport.kt`
- protocol codec tests

**Tasks:**

1. Add property/fuzz tests for JSON-RPC IDs.
   - numeric ID echoed as string
   - string ID echoed as number
   - duplicate response
   - late response after timeout
   - unknown response ID
   - malformed notification

2. Add SSE parser chaos.
   - empty body
   - blank data
   - comments
   - multi-line data
   - invalid JSON frame
   - provider error frame
   - abrupt EOF
   - cancellation mid-frame

3. Add resource leak tests.
   - response body channel is cancelled on all exit paths.
   - child process stderr/stdout are drained/closed.
   - no pending request handler remains after timeout/abort.

4. Add Native runtime tests for flow invariants.
   - Linux Native already runs in `check`; add tests that would catch flow emission from wrong context.

**Acceptance gate:**

- Every previously fixed MCP/SSE audit issue has a regression test.
- Chaos tests run under JVM and at least Linux Native where possible.
- Cancellation/EOF/late-message paths are deterministic.

---

## Block 12 — Architecture boundaries and modularity

**Risk addressed:** large files and monolithic provider/core artifact make it easier to introduce bugs and harder to review.

**Evidence found:**

- `ToolLoopAgent.kt`: 1,688 LOC.
- `MCP.kt`: 1,620 LOC.
- `Tool.kt`: 919 LOC.
- Several provider files are 600–1,200 LOC.
- Root artifact contains core abstractions plus many provider facades.

**Files owned:**

- large core files
- provider package
- Gradle module structure if splitting

**Tasks:**

1. Define architecture layers:
   - `core-api`
   - `agent-runtime`
   - `tool-runtime`
   - `protocol`
   - `http-transport`
   - `providers`
   - `ui`
   - `testing`

2. Enforce dependencies.
   - Core types cannot depend on provider implementations.
   - Providers cannot depend on UI.
   - Protocol codecs should not depend on agent loop.
   - UI stream conversion should depend only on stream/message contracts.

3. Add Konsist architecture rules.
   - Package dependency constraints.
   - No provider imports in agent runtime.
   - No UI imports in providers.
   - No files over a chosen LOC threshold without an explicit suppression.
   - No public top-level functions except approved factories.

4. Split large files by cohesive responsibility.
   - `MCP.kt` into protocol/client/transports/oauth/tool-adapter.
   - `Tool.kt` into schema/factories/toolset/execution/output/tool-choice.
   - Provider monoliths into request builder, response decoder, stream decoder, model facade.

5. Use internal interfaces to make units testable.
   - Keep public surface stable.
   - Make internals swappable in tests.

**Acceptance gate:**

- Architecture tests fail on forbidden dependencies.
- Load-bearing files are small enough to review.
- Provider work can happen without touching agent runtime.
- Agent runtime can be tested without real providers.

---

## Block 13 — Error taxonomy consistency

**Risk addressed:** SDK users need typed failures they can recover from; generic messages force substring matching.

**Files owned:**

- `AiSdkError.kt`
- `AgentError.kt`
- provider error mapping
- tool error mapping
- retry errors

**Tasks:**

1. Audit every `throw`.
   - Replace generic `IllegalStateException`, `RuntimeException`, `Exception` on expected paths with typed SDK errors.
   - Keep programmer errors as `IllegalArgumentException`/`require` only where truly invalid caller usage.

2. Normalize provider errors.
   - Every HTTP error should carry:
     - provider
     - model id
     - operation
     - URL
     - status
     - response headers
     - capped body
     - retryability
     - cause

3. Normalize stream errors.
   - Avoid plain `StreamEvent.Error(message)` without cause where cause exists.
   - Preserve typed error in in-process paths; wire format can stay string-safe.

4. Document recovery.
   - Which errors are retryable.
   - Which errors indicate model/provider limits.
   - Which errors indicate invalid user input.
   - Which errors indicate SDK bug/invariant violation.

**Acceptance gate:**

- Tests can `when` on error types for common failure modes.
- No expected provider/tool failure is only recoverable via string matching.
- Retry uses typed fields only.

---

## Block 14 — Beta release checklist gate

**Risk addressed:** “we think it’s ready” needs to become a binary CI fact.

**Files owned:**

- new `tools/beta-readiness-check.*`
- `.github/workflows/ci.yml`
- release workflow
- docs

**Tasks:**

Create a single command:

```bash
./gradlew check publishToMavenLocal
bash .claude/hooks/rules/ci-gate.sh
tools/beta-readiness-check
```

It should verify:

- no Dokka unresolved links
- no Gradle deprecation warnings
- coverage gates pass
- detekt baseline count did not increase
- API dump changes have changelog + interface contract updates
- README snippets compile
- local artifact smoke projects compile
- dependency verification metadata exists
- actions are SHA-pinned
- no known generated build output is tracked
- no TODOs in source
- no unredacted logging rules are violated
- release version and changelog agree

**Acceptance gate:**

- Beta cannot be cut unless this command passes locally and in CI.

---

# Priority order

If the goal is “professional first beta, no embarrassing release bugs,” I would execute in this order:

1. **Docs compile + README fix** — users will hit this first.
2. **Release/supply-chain hardening** — prevents bad artifacts and untrusted build inputs.
3. **Coverage gate + detekt baseline ratchet** — converts quality into CI facts.
4. **ToolLoopAgent bounded execution policy** — highest runtime blast radius.
5. **Retry/deadline/jitter policy** — highest provider reliability blast radius.
6. **Provider golden/conformance tests** — most likely real app bug source.
7. **Consumer smoke apps** — proves artifact works outside the repo.
8. **Telemetry/logging redaction** — prevents privacy/security mistakes.
9. **API surface freeze/classification** — locks beta compatibility expectations.
10. **Architecture splits/Konsist dependency rules** — prevents regression as contributors arrive.

---

# Highest-leverage architectural move

The single biggest leverage point is:

> **Turn every “standard” currently expressed in docs/comments into an executable gate, then make the load-bearing runtime policies explicit types.**

Right now this repo has excellent standards, but several are still comments or partial gates:

- coverage measured but not enforced
- cancellation detector enabled but inert
- docs warnings emitted but non-fatal
- detekt baseline huge
- telemetry privacy contract documented but not enforced by event projection
- release verification present but not yet supply-chain-grade
- tool concurrency configurable but default-unbounded and not scheduler-bounded

If those become hard gates and explicit policy objects, the SDK stops relying on reviewer memory and starts behaving like a serious published library.
