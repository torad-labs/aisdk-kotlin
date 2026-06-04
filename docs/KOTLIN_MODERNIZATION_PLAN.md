# aisdk-kotlin — Kotlin Modernization Plan

> Refactor plan produced by a 12-agent workflow (5 web-grounded research tracks on
> latest 2026 Kotlin → 4 code-audit areas → synthesize → adversarial critique →
> finalize) against the post-refactor `kotlin-idioms` branch. The critique pass
> re-read the tree and verified the load-bearing file:line claims. Excludes the
> HIGH/MEDIUM/LOW findings already implemented this session (see
> KOTLIN_SDK_CONVENTIONS.md). Kotlin reference: 2.4.0 (released 2026-06-03).

---

## 1. Executive summary

`aisdk-kotlin` (Kotlin 2.3.21, KMP, AGP 9.2.0, Gradle 9.5.1) has a strong core: cold `Flow<StreamEvent>` as the streaming contract, `MutableStateFlow` + `.update {}` state holders, disciplined `CancellationException` rethrow in most direct catches, sealed `@SerialName`/`@JsonClassDiscriminator` polymorphism, value classes, a two-codec `TypedJson`, and an injected `engineContext`. The session's prior work (value classes, `@DslMarker` core builders, atomic `AbortController`, `AgentSession` guards, `StreamTextResult` commit-on-success replay semantics, the `!!`-removal cluster, typed `StreamEvent.Error.cause`) is solid and is **not** re-litigated here.

What remains clusters into five themes, ordered by leverage:

1. **One genuine concurrency defect.** `StreamTextResult` holds a `Mutex` across the *entire* upstream collection (`Generate.kt:243`), so a second collector serializes behind the live run instead of replaying — directly contradicting its own KDoc (`Generate.kt:236-241`).
2. **Library-grade discipline is entirely absent.** No `explicitApi()`, no ABI validation, no detekt/Kover/Dokka, and only 33 `internal` markers across ~700 public declarations. For a signed, published library this is the 1.0 gate.
3. **Massive plumbing duplication in the provider layer.** 28 near-identical `Json{}` codecs, 25 `parse*Json` extensions, two verbatim copies of SHA-256/HMAC, six copies of `urlEncode`, and a rich `APICallError` type that is *used zero times* — providers throw flattened strings instead.
4. **A real CI correctness bug.** iOS targets are published but built/tested **nowhere** — `allTests` runs on `ubuntu-latest` only (`ci.yml:38`, `ci.yml:18`), and `kotlin.native.ignoreDisabledTargets=true` (`gradle.properties:7`) makes the gap silent.
5. **Cheap, stable-feature idiom wins.** `data object` singletons, guard conditions, residual `!!`, a few `CancellationException`-swallowing `runCatching` sites, and dispatcher-injection gaps in the MCP transports.

Two currency facts shape phasing: BCV is now **built into KGP** (`checkKotlinAbi`/`updateKotlinAbi`, runs under `check`; the DSL is still Experimental and requires `@OptIn`), and Kotlin **2.4.0 shipped 2026-06-03** (Gradle-9.5 compatible — the repo is already on 9.5.1). The 2.4.0 bump (Phase 4 fork) is the gating decision: it promotes **context parameters, explicit backing fields, and `kotlin.uuid.Uuid` to Stable**, which turns three former watch-list items into actionable work. Still genuinely deferred: multi-field value classes (keep `ModelRef` a `data class` per KEEP-0454), name-based destructuring, and `@MustUseReturnValue`/unused-return-value checking (all still Experimental at 2.4.0).

---

## 2. Phased plan

### Phase 1 — Quick wins (low risk / high value, no flags)

Zero-flag changes: stable language features, mechanical dedup, and the CI bug. Each is independently shippable and verifiable.

| Item | Why (latest best practice) | Where (file:line) | Effort | Risk |
|---|---|---|---|---|
| Fix CI iOS coverage bug | A Linux host cannot compile/test Apple targets; `kotlin.native.ignoreDisabledTargets=true` (`gradle.properties:7`) makes the gap silent. Add a `macos-latest` leg running `iosSimulatorArm64Test` + iOS link tasks, and switch the command from `allTests` to `check`. | `.github/workflows/ci.yml:38`, `ci.yml:18` | S | low |
| Drive remaining `commonMain` `!!` to zero | `?.let`/smart-cast and bound-locals eliminate the assertion. `FalProvider` does the dict lookup twice and asserts on the second; `MCP.kt` re-reads `scope` two lines after assigning it. (Confirmed complete set of 5 `!!` in commonMain.) | `FalProvider.kt:618`, `MCP.kt:1880`, `FixJson.kt:161,282` | S | low |
| `data object` for the 5 residual stateless singletons | `data object` is already the house style across the ADTs (`StreamEvent.Abort`, `SmoothStream.Word/Line`, `ToolChoice.*`, `PruneToolCalls.*`, etc.). These 5 are the only stragglers still plain `object`: the load-bearing ADT marker `GatewayTransportNotConfigured` and the four pure-behavior `Noop*`. Aligning them gets correct `equals` (classloader/deserialization-safe) and a useful `toString()`. | `Gateway.kt:217`, `Tool.kt:1020`, `Telemetry.kt:39,224`, `Logger.kt:35` | S | low |
| Guard conditions in `when` (Stable 2.2.0) | Collapse `is X -> if (cond) {…} else {…}` into `is X if cond -> {…}`, flattening a branch level while keeping the `when` exhaustive. Pays off in provider message-mappers. | `AnthropicProvider.kt:489`, `CohereProvider.kt:330,336`, `OpenResponsesProvider.kt:407`, `ProdiaProvider.kt:313` | S | low |
| Single `Crypto.kt` (SHA-256 + HMAC-SHA256) | Two complete, line-for-line identical copies of a crypto primitive is a correctness-divergence hazard — a fix to one won't reach the other. Pure functions with deterministic test vectors → low risk. | `AwsSigV4.kt:151`, `KlingAIProvider.kt:421,448` | M | low |
| Single `percentEncode` in `Util.kt` | The identical encode-byte-loop is copy-pasted 6×. One copy, not six. (Keep `AwsSigV4.kt:118`'s stricter RFC-3986 variant.) | `KtorGatewayTransport.kt:798`, `OpenAICompatibleProvider.kt:1102`, `AzureProvider.kt:175`, `ElevenLabsProvider.kt:312`, `OpenAIProvider.kt:212`, `DeepgramProvider.kt:548` | S | low |
| Collapse the 28 provider `Json{}` codecs → `aiSdkJson`/`aiSdkOutputJson` | Of the ~28 named provider/transport `Json{}` codecs, the verified bodies (alibaba/luma/anthropic/deepgram) are byte-identical `{ignoreUnknownKeys; isLenient; explicitNulls=false}` to the canonical `aiSdkJson` (`TypedJson.kt:12`). Keep only `openResponsesJson` (real `encodeDefaults` quirk, `OpenResponsesProvider.kt:1024`) — the criterion `TypedJson.kt:22` already states. **Before collapsing:** grep each provider body to confirm no silent per-codec quirk (only `openResponsesJson` is expected to differ), and decide whether the inbound decoder wants `coerceInputValues` / `decodeEnumsCaseInsensitive` (both Stable) for any provider. | `OpenAICompatibleProvider.kt:572`, `KtorGatewayTransport.kt:482`, `AlibabaProvider.kt:201`, +25 more | L | low |
| Multi-dollar interpolation for literal `$` | Stable since 2.2.0; removes the `"\$schema"` escape class in schema emitters. Minor but free. | `Output.kt:67,114` | S | low |
| Remove dead imports / shadowed typealias imports | A published library should carry no dead-import noise; a detekt `UnusedImports` rule (Phase 3) holds the line. | `rsc/Rsc.kt:7`, `react/React.kt:12`, `vue/Vue.kt:4` | S | low |

### Phase 2 — Idioms + API surface (reviewed mechanical passes + the concurrency fix + correctness)

The two highest-leverage behavioral items (the `Mutex` defect and `APICallError` adoption), plus the reviewed mechanical passes that need judgment per-site and a guardrail to prevent regression.

| Item | Why (latest best practice) | Where (file:line) | Effort | Risk |
|---|---|---|---|---|
| Fix `StreamTextResult` `Mutex`-across-collect | The lock is held for the whole stream, so a second collector suspends on `withLock` for the entire first run instead of replaying — contradicting the KDoc (`Generate.kt:236-241`). Replace with a one-shot `CompletableDeferred<List<StreamEvent>>` + CAS guard (or `shareIn(replay=Int.MAX_VALUE)`), preserving commit-on-success via `try/finally` that completes only on normal completion and resets the guard on cancellation. **First grep `mutex.withLock { … .collect` across the module to confirm `Generate.kt:243` is the only instance** rather than assuming it. | `Generate.kt:243`, `Generate.kt:282` | M | med |
| Shared `requestJson`/`HttpJsonResponse` transport helper | 25 `parse*Json` extensions, 33 identical header-flatten blocks, 44 `status.value !in 200..299` checks, and a per-provider `XxxJsonResponse` class all re-derive the same request→read→check→parse→error pipeline. One internal Ktor helper collapses them — and **throws `APICallError`** (folds in the row below). | `AlibabaProvider.kt:327`, `KlingAIProvider.kt:345`, `KtorGatewayTransport.kt:381`, `OpenAICompatibleProvider.kt:139` | L | med |
| Throw the rich `APICallError`, not bare strings | `APICallError(url, statusCode, responseHeaders, responseBody, cause, isRetryable, …)` exists (`AiSdkError.kt:16`) but is used 0× — every HTTP error flattens status into a message string, discarding headers/body/retryability so callers and the retry layer can't branch on `429`/`isRetryable`. | `OpenAICompatibleProvider.kt:1089`, `AlibabaProvider.kt:357`, `KlingAIProvider.kt:366`, `AnthropicProvider.kt:917`, `FalProvider.kt:600` | M | med |
| Rethrow `CancellationException` in suspend `runCatching`/`catch(Throwable)` | These wrap *suspend* bodies, so a cooperative cancel is swallowed and misreported. The MCP sites are the genuine high-severity instances (they wrap real suspend calls — incl. `MCP.kt:1556`'s suspend `close()`, not just the originally-listed set). The `ToolLoopAgent` callback sites are **lower-severity**: `runHook` (`ToolLoopAgent.kt:1191-1194`) already does `throw ce` before the `catch(Throwable)`, so the only residual is a cancellation thrown by the `onError` callback body itself. (`runCatching` over *synchronous* parse bodies is safe and stays.) | MCP: `MCP.kt:1655,1728,1890,1001,1556`; lower-priority: `ToolLoopAgent.kt:1161,1194`, `ui/Streams.kt:111` | S | low |
| Inject `CoroutineContext` into the 3 MCP transports | A library must never hardcode a dispatcher — each transport hardcodes `CoroutineScope(SupervisorJob()+Dispatchers.Default)`. Inject defaulting to `Dispatchers.Default`, mirroring the existing `ToolLoopAgent(engineContext=…)` pattern. Makes the inbound-SSE retry loop testable under `StandardTestDispatcher`/virtual time. | `MCP.kt:1545,1701,1879` | M | low |
| `?.let`-as-control-flow → `if`, gated by a detekt rule | `?.let` is for transforming a non-null value, not null-guarded control flow. The statement-shaped subset reads clearer as `if (x != null) …`; chained transforms stay. A reviewed IntelliJ "Convert to if" pass + detekt rule to hold the line. | concrete: `ui/ConvertToModelMessages.kt:56`, `rsc/Rsc.kt:20,50` | L | low |
| Remove redundant `else` from sealed `when`s | A redundant `else` on sealed dispatch *suppresses the `NO_ELSE_IN_WHEN` exhaustiveness error you want* when a new variant is added (this is the long-standing exhaustiveness check, independent of 2.3.0's DFA-based exhaustiveness for negative data-flow). Drop `else` only where intent is "handle every variant"; keep it for open subjects (`String`/`Int`/`JsonElement`). | audit per-site (note: core-engine `ToolLoopAgent`/`Tool`/`Output` `else`s were verified *correct* and must stay) | M | med |
| Make `Rsc.kt` `SharedFlow` overflow policy explicit | `MutableSharedFlow(replay=64)` with default `SUSPEND` and no `extraBufferCapacity` lets `emit` suspend on a stalled subscriber; the `init` `tryEmit` return is discarded. Choose `replay=1` + `extraBufferCapacity` + `DROP_OLDEST` (or `Channel.receiveAsFlow`), or keep 64 with a memory-cost comment. | `rsc/Rsc.kt:16,46,20,50` | M | med |
| `@AiSdkDsl` on the `ToolPartHandlerRegistry.Builder` DSL | The project's `@DslMarker` (`KotlinApi.kt:18`) is applied to core builders but not this nested receiver builder; without it, inner lambdas can implicitly call outer receivers (scope leakage). | `ui/InferAgentMessage.kt:67` | S | low |
| Shared `buildProviderHeaders(...)` | `alibabaHeaders`/`klingAIHeaders`/`falHeaders`/`commonHeaders` repeat `linkedMapOf → auth → putAll(settings) → putAll(call) → withUserAgentSuffix`; only the auth scheme varies. | `AlibabaProvider.kt:365`, `KlingAIProvider.kt:374`, `FalProvider.kt:608`, `OpenAICompatibleProvider.kt:124` | S | low |

### Phase 3 — Tooling + ABI (the 1.0 gate)

The library-grade layer that is entirely missing today. Ordering matters: decompose + `internal`-ize **before** snapshotting any ABI, so you don't freeze an accidental surface.

| Item | Why (latest best practice) | Where (file:line) | Effort | Risk |
|---|---|---|---|---|
| Package decomposition + `internal` discipline | 77 of 100 files (all 32 `*Provider*.kt`) sit in flat `ai.torad.aisdk`; only 33 `internal` markers across ~339 data classes / ~363 funcs. Push providers/wire decoders into sub-packages and `internal`-ize non-entry-points so the public surface is the intended SDK API. | `build.gradle.kts:16` (project-wide) | L | med |
| `explicitApi()` (warning → strict) | Forces explicit visibility + explicit return types on every public decl — compile-time-only (KEEP-0045: no bytecode/semantic change). The prerequisite that makes the whole surface intentional. Start `explicitApiWarning()` to size the backlog, then flip to strict. | `build.gradle.kts:16` | L | med |
| Built-in KGP ABI validation | DSL is **Experimental** — requires the opt-in: `@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)` then `kotlin { abiValidation { enabled.set(true); klib { keepUnsupportedTargets.set(true) } } }`. No separate plugin; `keepUnsupportedTargets` lets Linux CI infer the iOS klib ABI. Seed with `updateKotlinAbi`, commit the `.api`/`.klib.api` dump, gate via `checkKotlinAbi` (runs under `check`). Do **after** decomposition. | `build.gradle.kts:16` (no `api/` dir today) | M | low |
| detekt 1.23.x + detekt-formatting (ktlint) | No static analysis/formatter exists; `.editorconfig` is correct but unenforced. Use **1.23.x not 2.0-alpha** (2.0 is config-cache-incompatible — conflicts with `gradle.properties:3` `org.gradle.configuration-cache=true` — and has a KMP variant-explosion regression). Point `source.setFrom` at the real KMP source sets, align `jvmTarget` to `JVM_17`, baseline the existing surface, then ratchet. | `gradle/libs.versions.toml:1` | M | low |
| Custom detekt cancellation-rethrow rule + `SwallowedException` | The Phase-2 `CancellationException` pass is one-time; a lint rule holds the line. There are ~10 `catch(Throwable)` sites in `MCP.kt` alone, and cancellation-swallowing is flagged as the canonical structured-concurrency hazard. Add a custom rule (or `SwallowedException`) that flags any `catch(Throwable)`/`runCatching` in a `suspend` context lacking a `CancellationException` rethrow. | `gradle/libs.versions.toml:1` | M | low |
| Kover 0.9.8 | Config-cache-compatible; measures the JVM target (commonMain runs through `jvm`). Exclude generated serializers (`classes("*\$\$serializer")`). Wire `koverVerify` under `check`. | `gradle/libs.versions.toml:1` | S | low |
| Dokka 2.2.0 | Required for a published library (and the `dokkaJavadocJar` is required by Maven Central). New top-level `dokka{}` DSL; pairs with `explicitApi()` strict's missing-KDoc warnings. Rich KDoc already exists. | `gradle/libs.versions.toml:1` | S | low |
| `@ExperimentalAiSdkApi` / `@InternalAiSdkApi` opt-in markers | Different guarantees for the stable core vs the churning framework-adapter shims and half-ported surface. Feed `InternalAiSdkApi` into the ABI `excluded{annotatedWith}` filter so the core can reach 1.0 while edges stay honest-experimental. | applied to `react/`, `svelte/`, `vue/`, `angular/`, `rsc/`, `codemod/` | M | low |
| Dependency-currency audit (runtime libs) | Plan grounds Kotlin *language* currency well but is silent on the three pinned runtime libraries: coroutines 1.11.0, serialization 1.11.0, ktor 3.5.0 (`libs.versions.toml:6,7,9`). Verify each against latest stable (serialization 1.11.x `@KeepGeneratedSerializer`/sealed-interface support; ktor 3.x client changes) and bump where clean. | `gradle/libs.versions.toml:6,7,9` | S | low |
| Inject `Random` (and finish `Clock`) for determinism | `IdGenerator.generate()` calls global `Random.nextInt` (`Util.kt:171`) → non-reproducible agent trajectories. Thread `random: Random = Random.Default`. After the 2.4.0 bump, prefer the now-Stable cross-platform `kotlin.uuid.Uuid` as the ID primitive over the hand-rolled alphabet sampler. Several providers read `Clock.System.now()` directly; the `awsSigV4SignedHeaders(amzDate=…)` seam exists but callers omit it — propagate the `Gateway.nowMillis` pattern. | `Util.kt:171`, `KlingAIProvider.kt:130`, `AlibabaProvider.kt:163`, `AwsSigV4.kt:79`, callers `AmazonBedrockProvider.kt:1257`, `AnthropicAwsProvider.kt:133` | M | low |
| JVM-shared intermediate source set for MCP stdio | `MCPStdioProcess.jvm.kt` and `.android.kt` are byte-identical except an unexplained divergence: Android drops `destroyForcibly()` (present `jvm.kt:36`, absent `android.kt:35`). `applyDefaultHierarchyTemplate()` + one manual `dependsOn` collapses them to one source of truth and reconciles the divergence. Note the broader `actual-typealias`/single-source-set opportunity across the other `expect` declarations once the hierarchy template is in place. | `MCPStdioProcess.jvm.kt`, `MCPStdioProcess.android.kt:35` | M | low |
| Opt-in leak audit | 5 `@OptIn(ExperimentalSerializationApi)` sites are legitimate, but verify none leaks into a public signature (which would force the annotation on consumers). Also delete now-dead opt-ins where the wrapped stdlib API stabilized (`Base64` Stable 2.2 → `Util.kt:485,489`; `kotlin.time.Clock` Stable 2.3). | `ModelMessage.kt:65`, `Util.kt:5,485,489` | S | low |

### Phase 4 — Platform / KMP + Swift interop (reach & DX; some items are forks for the user)

Improving the consumable artifacts and the watch-list. Build-config items are safe now; the rest are deferred-but-now-located.

| Item | Why (latest best practice) | Where (file:line) | Effort | Risk |
|---|---|---|---|---|
| `XCFramework` aggregation + `bundleId` | Plain per-target `framework{}` produces no unified iOS artifact. `XCFramework("AiSdk")` + `binaryOption("bundleId", "ai.torad.aisdk")` is pure build config and strictly improves the consumable. | `build.gradle.kts:35` | S | low |
| `withSourcesJar()` + `dokkaJavadocJar` in the publication | GitHub Packages tolerates their absence; Maven Central requires both + the umbrella `kotlinMultiplatform` publication. | `build.gradle.kts:61` | S | low |
| `.buffer()` between model stream and `smoothStream` | The `delay()`s that pace UI chunks (`SmoothStream.kt:80,99`) currently back-pressure the provider while collecting upstream; a `.buffer()` decouples the artificial UI delay from token production — a throughput win for fast on-device models (buffer = no loss). | `SmoothStream.kt:52` (signature; insert at the collect seam) | S | low |
| Reconcile + document the `Flow` context-preservation contract | The core `LanguageModel.stream` carries an **inconsistent in-tree doc pair**: the terse KDoc reads "Hot until collected" (`LanguageModel.kt:53`) while the richer overload's KDoc directly below reads "implementations must keep the returned stream cold" (`LanguageModel.kt:56-58`). With ~15 provider implementors, fix the contradiction (it is cold) and state "implementations must not `flowOn`/emit from a foreign context; collection context is the caller's choice." | `LanguageModel.kt:53`, `LanguageModel.kt:56-58` | S | low |
| **Fork:** Bump to Kotlin 2.4.0 | Shipped 2026-06-03, Gradle-9.5-compatible (repo already on 9.5.1). Unlocks the most-robust ABI toolchain (KT-86268), **Stable `kotlin.uuid.Uuid`, Stable context parameters (sans context arguments/callable refs), and Stable explicit backing fields** — the latter directly cleans up the many `private val _x = MutableStateFlow(...)` / `val x: StateFlow = _x` holder pairs (`AgentSession`, `Chat`, `Rsc` controllers). **Resolve this first**: it gates the watch-list items below and may constrain the SKIE decision. Verify via `allTests`/`check` + `publishToMavenLocal`. | `gradle/libs.versions.toml:2` | S | low |
| **Fork:** SKIE vs JetBrains Swift export | The library exposes 104 `Flow` + ~397 `suspend` funcs + 28 sealed hierarchies that degrade badly across the raw ObjC bridge. SKIE gives the best DX today but currently lags Kotlin's release ceiling (may pin the 2.4.0 bump); Swift export is JetBrains-native but Alpha. Genuine human call. | `build.gradle.kts:35` | M | med |
| **Fork:** `wasmJs { browser() }` / `js` target | This is a JS-SDK port; web is its native ecosystem and the streaming/Flow core is platform-agnostic. The most defensible *new* target — but a real scope decision. | `build.gradle.kts:16` | M | med |
| **Fork:** framework-adapter shims — keep or drop | `angular/vue/svelte/react/rsc/codemod` are 1-file JS-ecosystem concepts shipped as public Kotlin on all 3 platforms. Decide whether they belong in a Kotlin SDK at all; if kept, gate behind `@ExperimentalAiSdkApi` (Phase 3). | `react/React.kt`, `vue/Vue.kt`, `svelte/Svelte.kt`, `angular/Angular.kt`, `rsc/Rsc.kt`, `codemod/` | M | low |
| **Watch list — no work now** | Items still genuinely Experimental at 2.4.0: name-based destructuring; `@MustUseReturnValue`/unused-return-value checker. Deferred by deliberate design choice: MFVC for `ModelRef` (KEEP-0454 public-discussion only — keep it a `data class`). *(Context parameters, explicit backing fields, and `Uuid` were promoted to Stable in 2.4.0 and are now folded into the bump fork + Phase-3 determinism/holder-pair items above — they are no longer watch-list.)* | `ModelRef.kt:24` (keep as-is) | — | — |

---

## 3. Explicitly excluded (already implemented — this plan does NOT touch)

- **Value classes** — `ProviderId`/`ModelId` are `@JvmInline value class` (`ModelRef.kt:5,15`); `ModelRef`/`LoopState`/`TypeValidationContext` are correctly `data class` (2+ fields, `ModelRef.kt:24`).
- **`@DslMarker`** — `@AiSdkDsl` defined (`KotlinApi.kt:18`) and applied to the core builders. (Phase 2 only *extends* it to one missed nested builder.)
- **Sealed `@SerialName` + `@JsonClassDiscriminator`** — the `ContentPart` hierarchy (`ModelMessage.kt:65`) is textbook-current.
- **Two-codec `TypedJson`** — `aiSdkJson` (inbound, lenient, `TypedJson.kt:12`) / `aiSdkOutputJson` (outbound, `encodeDefaults`, `TypedJson.kt:21`). (Phase 1 *routes the duplicates to it*; the codec itself is done.)
- **`data object` as house style** — already adopted across the ADTs: `StreamEvent.Abort` (`Streaming.kt:283`), `SmoothStream.Word/Line`, `ToolChoice.Auto/None/Required`, `PruneToolCalls.*`, `ToolLoopAgentAction.Cancel/Reset`, `TelemetrySpanStatus.Ok`, `BedrockStreamBlock.*`. (Phase 1 only converts the 5 remaining stragglers.)
- **Nullable-unset `CallSettings` + `?:` merge** — `CallSettings.merge` relies on `copy` (`KotlinApi.kt:326`).
- **Atomic `AbortController`** — COW-atomic via `kotlin.concurrent.atomics` (`AbortSignal.kt`).
- **`AgentSession` `MutableStateFlow` guards** — the model the Phase-2 `Chat`/`ChatSession` refactor should mirror.
- **`StreamTextResult` commit-on-success replay semantics** — the *intent* is correct; Phase 2 fixes only the `Mutex` *mechanism*.
- **Throwing duplicate-tool policy; typed `StreamEvent.Error.cause`; injected `engineContext` + `close()`.**
- **Modern build skeleton** — version catalog, config-cache/parallel/caching on (`gradle.properties:3`), `com.android.kotlin.multiplatform.library` + `android{}` block (the correct modern Android-KMP shape — verify, do not migrate), signed publishing with release guards, pinned-SHA CI. Gradle 9.5.1 already current.
- **Stable `kotlin.time.Clock`/`Instant`** already adopted (`Gateway.kt`, `AwsSigV4.kt`, `KlingAIProvider.kt`) — no `kotlinx-datetime` needed. Phase 3 only adds *injection seams*, not new time APIs.
- **Core-engine `else` branches** verified correct — `ToolLoopAgent.kt:263,310,545`, `Tool.kt:552,863,884,1131`, `Output.kt:97,235` are partial dispatch or open subjects and must keep their `else`. The Phase-2 redundant-`else` pass excludes these.
- **`runCatching` over synchronous parse bodies** (e.g. error-message parsers) — no cancellation hazard; left as-is. The `ToolLoopAgent.runHook` outer path is already cancellation-safe (`throw ce` precedes `catch(Throwable)`, `ToolLoopAgent.kt:1191-1194`).
- **Convention plugins / `build-logic`** — explicitly **not** adopted: this is a single-module library; `buildSrc` would be premature abstraction.

---

## 4. Open decisions needing a human

1. **Bump to Kotlin 2.4.0 now, or hold at 2.3.21? (resolve FIRST — it gates the rest.)** 2.4.0 (shipped 2026-06-03) is low-risk and promotes context parameters, explicit backing fields, and `Uuid` to Stable — turning three watch-list items into actionable work (Phase 3 determinism + holder-pair cleanup). But if SKIE is chosen (decision #2) the version may need to be pinned to whatever SKIE supports. Resolve this before #2, or bump now and accept SKIE as a later gate.
2. **iOS Swift interop strategy — SKIE vs Swift export.** The 104 `Flow` + ~397 `suspend` + 28 sealed surface degrades across the raw ObjC bridge. SKIE (`Flow`→`AsyncSequence`, `suspend`→`async`, sealed→exhaustive Swift enums) is the best DX today but historically trails Kotlin's latest x.y.z; JetBrains Swift export is native and the long-term direction but Alpha. Picking SKIE may constrain the 2.4.0 bump (decision #1). *Default if unanswered:* ship `XCFramework` + `bundleId` now (Phase 4), defer the bridge-enhancer choice.
3. **Add a `wasmJs`/`js` target?** Highest-value *new* target for a JS-SDK port, but a real scope/maintenance expansion (new test matrix, new CI leg). Needs a product call on whether web consumers are in scope for 1.0.
4. **Maven Central vs GitHub Packages.** Today publishing is GitHub Packages only. Central needs source + javadoc jars, the umbrella publication, and likely `vanniktech-maven-publish` over the hand-rolled signing/POM block (`build.gradle.kts:61`). This determines whether the Phase-4 publishing items are required or optional.
5. **Framework-adapter shims — keep, isolate, or drop?** `angular/vue/svelte/react/rsc/codemod` are JS-ecosystem concepts in a Kotlin SDK. Keeping them means `@ExperimentalAiSdkApi`-gating (Phase 3); dropping them shrinks the 1.0 surface materially. Product/scope call.
6. **`Chat`/`ChatSession` to a single `StateFlow` holder.** The Phase-2 fix mirrors `AgentSession`, but it changes the public concurrency contract of `Chat` (`ui/Chat.kt:130`). Confirm whether `Chat` should be made concurrency-safe for 1.0 or merely *documented* as single-collector — the former is the right work, the latter is the cheap work.

---

## Appendix — Sources (85)

- https://kotlinlang.org/docs/whatsnew23.html
- https://kotlinlang.org/docs/whatsnew2320.html
- https://kotlinlang.org/docs/whatsnew22.html
- https://kotlinlang.org/docs/whatsnew24.html
- https://kotlinlang.org/docs/releases.html
- https://kotlinlang.org/docs/context-parameters.html
- https://kotlinlang.org/docs/object-declarations.html
- https://kotlinlang.org/docs/sealed-classes.html
- https://kotlinlang.org/docs/components-stability.html
- https://github.com/Kotlin/KEEP/discussions/473
- https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0453-better-immutability-value-classes-motivation.md
- https://blog.jetbrains.com/kotlin/2026/05/kotlinconf26-keynote-highlights/
- https://blog.jetbrains.com/kotlin/2025/12/kotlin-2-3-0-released/
- https://github.com/Kotlin/kotlinx.serialization/pull/2991
- https://github.com/Kotlin/KEEP/blob/master/proposals/data-objects.md
- https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0
- https://github.com/Kotlin/kotlinx.coroutines/blob/master/CHANGES.md
- https://kotlinlang.org/docs/coroutines-basics.html
- https://github.com/Kotlin/kotlinx.coroutines/blob/master/docs/topics/flow.md
- https://developer.android.com/kotlin/coroutines/coroutines-best-practices
- https://developer.android.com/kotlin/flow/stateflow-and-sharedflow
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-flow/
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/
- https://mvpfactory.io/blog/kotlin-coroutines-flow-patterns-senior-android/
- https://proandroiddev.com/how-emissions-work-in-kotlin-flows-and-channels-6cf683aba5ea
- https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html
- https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html
- https://kotlinlang.org/docs/multiplatform/multiplatform-advanced-project-structure.html
- https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/
- https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html
- https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html
- https://github.com/Kotlin/binary-compatibility-validator/releases
- https://kotlinlang.org/docs/native-swift-export.html
- https://kotlinlang.org/docs/multiplatform/multiplatform-spm-export.html
- https://skie.touchlab.co/intro
- https://www.kmpship.app/blog/kotlin-swift-export-ios-integration-2025
- https://developer.android.com/kotlin/multiplatform/plugin
- https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html
- https://blog.jetbrains.com/kotlin/2026/01/update-your-projects-for-agp9/
- https://github.com/Kotlin/kmp-agp9-migration/blob/master/kmp-agp9-migration/references/DSL-REFERENCE.md
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-clock/
- https://github.com/JetBrains/kotlin/blob/2.3.0/libraries/stdlib/src/kotlin/uuid/Uuid.kt
- https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html
- https://kotlinlang.org/docs/dokka-gradle.html
- https://github.com/techie-labs/kameleoon
- https://github.com/aryapreetam/cmp-lib-template/tree/v0.0.3
- https://blog.jetbrains.com/kotlin/2026/06/kotlin-2-4-0-released/
- https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html
- https://kotlinlang.org/docs/api-guidelines-simplicity.html
- https://kotlinlang.org/docs/api-guidelines-predictability.html
- https://kotlinlang.org/docs/api-guidelines-minimizing-mental-complexity.html
- https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0045-explicit-api-mode.md
- https://github.com/kotlin/binary-compatibility-validator
- https://kotlinlang.org/docs/dokka-migration.html
- https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/polymorphism.md
- https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization/-sealed-class-serializer/
- https://github.com/Kotlin/kotlinx.serialization/blob/dev/docs/serializers.md
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io.encoding/-base64/decode.html
- https://github.com/JetBrains/kotlin/blob/2.3.0/libraries/stdlib/src/kotlin/io/encoding/Base64.kt
- https://github.com/detekt/detekt/blob/main/website/docs/gettingstarted/gradle.mdx
- https://github.com/detekt/detekt/releases/tag/v2.0.0-alpha.3
- https://github.com/detekt/detekt/issues/8882
- https://itnext.io/adding-detekt-to-a-kotlin-multiplatform-project-66da8b6af8d6
- https://jadarma.github.io/blog/posts/2025/04/convenient-detekt-conventions/
- https://kotlin.github.io/kotlinx-kover/gradle-plugin/
- https://github.com/Kotlin/kotlinx-kover/releases/tag/v0.9.8
- https://chrisjenx.com/setting-up-kover-in-a-multi-module-kmp-project/
- https://github.com/Kotlin/binary-compatibility-validator
- https://kotlinlang.org/docs/gradle-best-practices.html
- https://docs.gradle.org/current/userguide/implementing_gradle_plugins_convention.html
- https://kotlinlang.org/docs/exception-handling.html
- https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md
- https://ktor.io/docs/client-requests.html
- https://ktor.io/docs/client-responses.html
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-mutable-shared-flow.html
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-cancellation-exception/
- https://kotlinlang.org/docs/coroutines-and-channels.html
- https://kotlinlang.org/docs/type-safe-builders.html
- https://kotlinlang.org/docs/coding-conventions.html
- https://kotlinlang.org/api/kotlin-gradle-plugin/kotlin-gradle-plugin-api/org.jetbrains.kotlin.gradle.dsl.abi/-abi-validation-extension/
- https://kotlinlang.org/api/kotlin-gradle-plugin/kotlin-gradle-plugin-api/org.jetbrains.kotlin.gradle.dsl.abi/-abi-validation-klib-kind-extension/keep-unsupported-targets.html
- https://github.com/JetBrains/kotlin/pull/6037
- https://kotlinlang.org/docs/native-target-support.html
