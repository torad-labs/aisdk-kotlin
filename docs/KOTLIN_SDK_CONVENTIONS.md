# Kotlin SDK Conventions & TypeScript→Kotlin Port Map

> Research artifact for the `kotlin-idioms` branch. Produced by a 9-agent
> dynamic workflow (6 parallel research tracks → synthesize → adversarial
> critique → finalize) on commit `1b27683`, AI SDK reference v6.0.196.
> The critique pass re-read the working tree and verified every HIGH finding
> with file:line citations before this document was finalized.

---

All critique points are now verified against the working tree:

- **WireDecoder**: `ignoreUnknownKeys=false`, `isLenient=false`, `encodeDefaults=true`, `explicitNulls=false` (strict reader) — confirms the flag-matrix divergence.
- **Producer-side error loss**: `StreamEvent.Error(val message: String)` (Streaming.kt:287) carries only a String; emits at ToolLoopAgent.kt:351/415/538 all drop the `Throwable` (`t.message ?: ...`). Confirmed — the critique's "producer-side typed-error loss" gap is valid.
- **modelVisible**: `val modelVisible: JsonElement = output` (ModelMessage.kt:99) — defaulted to another constructor arg. Confirmed.

Every critique gap and inaccuracy I tested is valid. The off-base items I'll handle silently: the verdict itself confirms the priority ordering is sound and the HIGH findings hold, so I keep the structure and only fix the GeneratedFile framing, the two mis-citations, the codec-flag matrix, and weave in the concurrency siblings (currentJob, combineAbortSignals re-entrancy, cancellation-cooperation, producer-side error payload) plus the @PublishedApi-internal-as-ABI, explicitApi blast-radius, value-class serialization implication, and provider-facade ABI topics. Now writing the final document.

Below is the FINAL document.

# Kotlin SDK Conventions and TypeScript-to-Kotlin Port Map

> Scope: `aisdk-kotlin` at `1b27683` (branch `main` == `kotlin-idioms`), package `ai.torad.aisdk`, KMP targets jvm/android/iosX64/iosArm64/iosSimulatorArm64, version `0.1.0-SNAPSHOT`. Every current-port claim below was verified against the working tree. This document merges six research tracks, dedupes overlapping findings, and reconciles the two places tracks disagreed (noted inline in §5/§6).

---

## 1. Executive summary — the 7 highest-leverage moves

The port is already strong: sealed hierarchies + exhaustive `when`, the cold-`Flow`/`suspend` split, injected-scope `AgentSession`, the reified-inline + `KSerializer` pairing, and minimal `expect`/`actual` are all done well and four tracks independently confirmed them. The leverage is concentrated in a small set of moves that are either *correctness bugs* or *one-time pre-1.0 hardening that gets 10x harder after publish*.

1. **Replace sentinel-default settings with nullable-unset.** `CallSettings.responseFormat = ResponseFormat.Text` and `CallSettings.abortSignal = AbortSignalNever` (`KotlinApi.kt:25,28`) are non-nullable sentinels while the other ten fields are `T? = null`. `merge` compares by `==`/`===` against the sentinel (`KotlinApi.kt:324,327`), so an explicit `ResponseFormat.Text` to *force* prose over inherited JSON is silently swallowed. This is a real correctness bug, it is the most un-Kotlin construct in the new surface, and the fix is mechanical. **Highest single-leverage change.**

2. **Make the cross-thread concurrency surfaces memory-model-safe — as a *cluster*, not three isolated fixes.** Three distinct hazards live on types explicitly designed to cross coroutines/threads, and they do **not** all close with one fix:
   - `AbortController.callbacks = mutableListOf` (`AbortSignal.kt:54`) is mutated unsynchronized from `register`/`abort`. UB on Kotlin/Native; `ConcurrentModificationException` or dropped registration on JVM.
   - `combineAbortSignals` (`AbortSignal.kt:111-124`) has a **re-entrancy/ordering** bug *independent of the list race*: if any source signal is already aborted at combine time, `signal.register { controller.abort() }` fires synchronously, so the cleanup `controller.signal.register { … }` at `:120` runs against an already-fired controller and the child registrations may never be cancelled. An atomicfu list does not address this — the ordering must be made abort-before-register safe.
   - `AgentSession.currentJob` is a plain `var` (`AgentSession.kt:39`) written from `submit`/`submitStreaming`/`cancel`/`reset`/`launchSession` (`:50,92,185,186,191,192,237`) with no synchronization — the same class of hazard as the callbacks list, on the session's own job handle. The §H-2 job-identity guard *reads* `currentJob` and therefore races its assignment unless the handle is made atomic (atomicfu `AtomicRef`) at the same time.
   Required before native targets ship; treat as one workstream.

3. **Fix the `AgentSession` stale-job state race + streaming part-drop.** `submit`/`submitStreaming` (`AgentSession.kt:50,70,117–159`) write `mutableState.value = state.value.copy(...)` — a non-atomic read-modify-write — with no check that the writing job is still current; a lingering cancelled job A can clobber job B's state. The streaming `when` falls through to `else -> Unit` (`AgentSession.kt:155`), silently discarding `ToolCall`/`ToolResult` events, so the UI loses tool cards mid-stream. `ToolLoopAgent` already uses `update {}` — copy it. The guard must read an *atomic* `currentJob` (see move 2).

4. **Resolve `StreamTextResult` cold-vs-memoised ambiguity, and make the long collect loops cancellation-cooperative.** `fullStream` (`Generate.kt:234`) wraps the *entire* `upstream.collect` in `mutex.withLock` and only sets `collected = true` after the collect completes. A first collector cancelled mid-stream releases the lock with `collected == false` and a half-captured `capturedEvents`, which the next collector replays as if complete — truncated data presented as a real stream. The same library has `streamText` (cold, re-bills) and `StreamTextResult.fullStream` (memoised) with opposite semantics and no doc distinguishing them. Adjacent and distinct: the capture-then-emit loops here and in the `ToolLoopAgent` stream pipeline must `ensureActive()`/yield at event granularity, or a cancellation arriving between events is swallowed.

5. **Turn on the ABI guardrails before 1.0:** enable `explicitApi()` and add `binary-compatibility-validator` (commit `.api`/`.klib.api` golden files). The build has neither (verified — no match in `*.kts`). These are the *enforcement mechanism* that makes every data-class/envelope-evolution convention in §6 real rather than aspirational, and they cost almost nothing while still at `0.1.0-SNAPSHOT`. Scope honestly: flipping `explicitApi()` is a **multi-hundred-symbol triage** across ~90 commonMain files (every provider facade, every builder, `TestServer`, the four codecs) — stage with `explicitApiWarning()` first so the public-vs-`internal` decisions land incrementally rather than as one unreviewable diff.

6. **Decide the wire-discriminator strategy for the sealed types.** `ContentPart`, `StreamEvent`, `UIMessagePart` have `@Serializable` but **zero** `@SerialName`/`@JsonClassDiscriminator` (verified: 0 matches). kotlinx's default discriminator is the fully-qualified class name, so `aiSdkJson.encodeToString(part)` emits `"ai.torad.aisdk.ContentPart.ToolCall"`, not v6's `"tool-call"`. The port sidesteps this with hand-written wire mapping in transports, leaving the `@Serializable` annotations dead on the wire path. Pick (a) annotate + let serialization own the wire (deletes hand-written mappers) or (b) formally document `ContentPart` as internal-only — not both half-wired.

7. **Convert `ProviderId`/`ModelId` to `@JvmInline value class`** (`ModelRef.kt:3,12`). Textbook single-`String`-wrapper value-class case; as `data class` they heap-allocate per construction for zero benefit and expose unwanted `copy()`/`componentN()` ABI. Keep the `init` validation and the existing top-level `providerId(...)`/`modelId(...)` factories as the Java/Swift-facing door (value-class constructors are mangled / not Java-callable). One implication to bake in, not discover later: because `ModelRef` is wire-adjacent, the value class must carry `@Serializable` so kotlinx emits/reads the underlying `String` via its inline serializer rather than an object wrapper — and Native `Map`-key/equality behavior should be covered by a contract test (§4.7).

Two cross-cutting hygiene findings surfaced during verification that no single track isolated, both **high**: `ktor-client-mock` is an `implementation` dependency of **`commonMain`** (`build.gradle.kts:51`), and `TestServer.kt` lives in **`commonMain`** with no production consumers — test scaffolding is being shipped in the published artifact (§6).

---

## 2. Kotlin idioms to ADOPT (with snippets)

These are the conventions to converge the rest of the codebase onto. Several are already exemplified in the port; the snippet shows the canonical form.

### 2.1 Sealed hierarchies + exhaustive `when`, **no `else ->` over a sealed type**
The whole value of sealed-over-discriminated-union is that adding a variant is a compile error at every consumer. An `else ->` re-imports the exact TS bug (silent fallthrough) the sealed type was meant to eliminate.
```kotlin
// GOOD — exhaustive, breaks on new variant (TypedJson.kt:100-126 does this)
when (event) {
    is StreamEvent.TextDelta  -> ...
    is StreamEvent.ToolCall   -> ...
    StreamEvent.Abort         -> ...
    // no else: a new StreamEvent variant fails compilation here
}
```
Convention: a `when` over an AI-SDK sealed type must be exhaustive *without* `else` unless a catch-all is genuinely semantic. (Directly contradicts the current `AgentSession.kt:155` `else -> Unit` — see §6 H-2.)

### 2.2 `@JvmInline value class` for single-field typed wrappers
```kotlin
@Serializable                          // wire-adjacent → keep the inline serializer
@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProviderId must not be blank." }
        require(':' !in value) { "ProviderId must not contain `:`." }
    }
    override fun toString(): String = value
}
// stays unboxed even as ProviderId? (underlying type is a reference type),
// so ModelRef.providerId: ProviderId? = null keeps working with no boxing.
```

### 2.3 Nullable-unset, never sentinel-default
Every optional setting is `T? = null` and resolution is `?:` chaining. No `== Default` / `=== Sentinel` comparisons.
```kotlin
data class CallSettings(
    val responseFormat: ResponseFormat? = null,   // was: = ResponseFormat.Text
    val abortSignal: AbortSignal? = null,          // was: = AbortSignalNever
    // ...the other ten fields already follow this
)
private fun CallSettings.merge(other: CallSettings) = copy(
    responseFormat = other.responseFormat ?: responseFormat,   // no == sentinel
    abortSignal    = other.abortSignal ?: abortSignal,         // no === sentinel
)
```
This unifies `CallSettings` with the *already-correct* `AgentSettings.responseFormat: ResponseFormat? = null` / `StepSettings.responseFormat: ResponseFormat? = null` (`Context.kt:58,100`).

### 2.4 Inject the non-deterministic environment (dispatcher, clock, id/RNG, network)
All four are constructor parameters with production defaults — never inlined globals. This is simultaneously the threading-control idiom and the testability seam.
```kotlin
class ToolLoopAgent<TContext, TOutput>(
    /* ... */,
    private val engineContext: CoroutineContext = Dispatchers.Default,
) {
    private val engineScope = CoroutineScope(SupervisorJob() + engineContext)
    fun close() { engineScope.cancel() }   // give the self-owned scope an owner
}
```
`AgentSession` already injects the scope (`AgentSession.kt:34`) — this extends the same rule to `ToolLoopAgent.kt:117` and the three `MCP.kt` SSE scopes (1541/1697/1875).

### 2.5 Reified-inline + non-reified `KSerializer` twin
The reified overload is the ergonomic door; the explicit-serializer overload is the iOS-facing / erased-call-site door (reified inline does not cross to Swift). Every reified convenience must keep its non-reified twin `public`.
```kotlin
fun <T> encodeJsonElement(value: T, serializer: KSerializer<T>): JsonElement = ...
inline fun <reified T> encodeJsonElement(value: T): JsonElement =
    encodeJsonElement(value, serializer())     // TypedJson.kt:18-22 — already the template
```
**Convention coupling with `@PublishedApi internal` codecs:** because the reified twin inlines into caller bytecode, the codec it references (`@PublishedApi internal val aiSdkJson`, `TypedJson.kt:11`) is itself part of the binary ABI — it leaks through the public inline body. Under `explicitApi()` + BCV this becomes a frozen-forever surface. Rule: a `@PublishedApi internal` codec is treated as public ABI for evolution purposes (no field-shape or flag changes that alter emitted bytes once published), and only the *minimum* codec needed by an inline twin is marked `@PublishedApi` — everything else stays plain `internal`.

### 2.6 Cold `Flow` for streams, `suspend` for one-shots, `StateFlow` for state
`stream(...): Flow<StreamEvent>` is **not** `suspend` (returns instantly, collection drives work); `generate` is `suspend`. Reactive session state is `MutableStateFlow(...).asStateFlow()`. Both are done correctly (`LanguageModel.kt:50-53`, `AgentSession.kt:38-41`); the convention is to keep externally-visible side effects *out* of cold `flow { }` bodies, and to keep long collect loops cancellation-cooperative — call `ensureActive()` (or `coroutineContext.ensureActive()`) at event granularity so a cancellation landing between emissions is honored, not swallowed (the `StreamTextResult`/`Chat.sendMessage` violations in §3.5 are the counterexample).

### 2.7 `@DslMarker` on every builder receiver
```kotlin
@DslMarker annotation class AiSdkDsl

@AiSdkDsl class CallSettingsBuilder { /* ... */ }
@AiSdkDsl class TextGenerationRequestBuilder { /* ... */ }
@AiSdkDsl class ProviderOptionsBuilder { /* ... */ }
@AiSdkDsl class ToolSetBuilder { /* ... */ }
```
Currently absent (verified: 0 matches). Without it, nested receiver blocks (`textGenerationRequest { settings { providerOptions { ... } } }`) let an inner lambda silently call an outer builder's method. Low-risk, high-clarity.

### 2.8 `@RequiresOptIn` instead of `experimental_` name prefixes
```kotlin
@RequiresOptIn(message = "Experimental AI SDK API; may change.")
annotation class ExperimentalAiSdkApi

@ExperimentalAiSdkApi suspend fun generateImage(/* ... */): ImageResult
```
Replaces the four TS-literal twins `experimental_generateImage/Speech/transcribe/generateVideo` (`MediaModels.kt:213,343,432,526`), removing duplicate functions and propagating experimental status through `explicitApi()`.

### 2.9 Typed payloads on event types, not just at the consumption boundary
A typed-event hierarchy that carries a stringified error is a discriminated union with the discriminant thrown away. `StreamEvent.Error(val message: String)` (`Streaming.kt:287`) is constructed at the producer as `StreamEvent.Error(t.message ?: "…")` (`ToolLoopAgent.kt:351,415,538`) — the `Throwable` cause is lost *before* the event is ever emitted, so no consumer-side discipline can recover it. Convention: an error-bearing event carries a typed payload (`val cause: AiSdkException` or at minimum `val cause: Throwable?`) populated at the emit site; string-only error events are forbidden. This is the producer-side complement to §3.10 (don't rethrow as `RuntimeException` at the boundary).

---

## 3. Anti-patterns to AVOID (failure mode → fix)

### 3.1 Sentinel default masquerading as "unset"
**Failure mode:** `responseFormat = ResponseFormat.Text` / `abortSignal = AbortSignalNever` (`KotlinApi.kt:25,28`) are non-nullable; `merge` (`:324,327`) can't tell "caller explicitly set the default" from "caller left it unset," so an explicit value is dropped. **Fix:** §2.3 — make nullable, resolve with `?:`.

### 3.2 Shared mutable state mutated from a cold `flow {}` body / non-atomic `StateFlow` writes / unsynchronized job handle
**Failure mode:** `mutableState.value = state.value.copy(...)` (`AgentSession.kt:50,70,117,129,136,147,150,159`) is a read-modify-write; concurrent or stale-job writers lose updates (`StateFlow.value =` is atomic per-write but `.value.copy()` is not a CAS). Compounding it, the guard candidate `currentJob` is itself a plain `var` (`:39`) written without synchronization (`:50,92,185,186,191,192,237`), so a job-identity check can race the assignment it reads. **Fix:** `mutableState.update { it.copy(...) }` (as `ToolLoopAgent` does at `:142,156,203`) **and** make `currentJob` an atomicfu `AtomicRef<Job?>` so the captured-token compare-and-return is sound: `if (myJob !== currentJob.value) return@launch`.

### 3.3 Thread-unsafe callback list + abort-before-register re-entrancy on a cross-thread type
**Failure mode (two distinct bugs):** (a) `AbortController.callbacks` plain `mutableListOf` (`AbortSignal.kt:54`); `register` (`:82`) races `abort`'s snapshot+clear (`:62-64`). The `toList()` snapshot only guards re-entrant modification, not cross-thread concurrency; the check-then-act on `backing.isCancelled` is also racy. (b) **Independent of the list:** `combineAbortSignals` (`:111-124`) registers child→parent forwarding *then* registers the parent→deregister-children cleanup (`:120`); if any source is already aborted, the first `register` fires `controller.abort()` synchronously and the cleanup is registered against an already-fired controller, so children are never deregistered. **Fix:** (a) back the callback list with `kotlinx.atomicfu` atomic immutable-list (CAS add/remove/drain) or a `kotlinx.atomicfu.locks` lock (KMP-portable); (b) make `register` on an already-aborted controller fire-and-no-store, and order `combineAbortSignals` so the cleanup is wired before the forwarding registrations (or short-circuit when any source is already aborted). The atomicfu list does **not** close (b).

### 3.4 Hardcoded `Dispatchers.Default` + self-owned uncancellable scope
**Failure mode:** `CoroutineScope(SupervisorJob() + Dispatchers.Default)` at `ToolLoopAgent.kt:117` and `MCP.kt:1541/1697/1875` — (a) untestable (virtual time can't reach it; forces the real-wall-clock busy-poll in `ToolApprovalTest.kt:166-177`), and (b) `ToolLoopAgent`'s engine scope has no `close()`, so it leaks when the host dies (the MCP transports correctly expose `suspend fun close()` — verified at `MCP.kt:483/1550/1768/1899`; `ToolLoopAgent` has none). **Fix:** §2.4.

### 3.5 Externally-visible side effects inside a cold `flow {}` body
**Failure mode:** `StreamTextResult.fullStream` (`Generate.kt:234`) mutates `capturedEvents`/`collected`/`capturedResponse` inside the flow body and wraps the whole `upstream.collect` in `mutex.withLock`; a cancelled first collection leaves `collected == false` with a partial cache that the next collector replays as complete. `Chat.sendMessage` (`Chat.kt:130`) and `ChatSession.sendMessage` (`ui/ChatSession.kt:58`) append the user message *inside* the flow body, so double-collection double-appends and mutates an unsynchronized `MutableList`. **Fix:** make single-collection honest (throw on re-collect, like `consumeAsFlow`) or set the cache + `collected` in one guarded step that can't be observed half-done; for chat, make `sendMessage` a `suspend fun` or make re-collection idempotent. Pair with the §2.6 cancellation-cooperation rule so the capture loop yields between events.

### 3.6 Empty-string field for "no local bytes" — and a decode that returns a wrong answer
**Failure mode:** `GeneratedFile` already has a dedicated `url: String?` (`MediaModels.kt:10`); the remote-URL discriminator **is** that field, not the empty string. The actual defect is narrower: `base64: String` is non-null, and the `FileData.Url` conversion sets `base64 = ""` *alongside* `url = data.value` (`MediaModels.kt:72-79`) to mean "no local bytes." `GeneratedFile.bytes()` (`MediaModels.kt:103`) then base64-decodes that `""` to an empty `ByteArray` — a silent wrong answer for a URL-backed file. The shape recurs across **7 providers** (`ReplicateProvider`, `AlibabaProvider`, `XaiProvider`, `ByteDanceProvider`, `KlingAIProvider`, `GoogleProvider`, `FalProvider`) plus the conversion helper in `MediaModels.kt` itself, and is enshrined in test fixtures (`KotlinIdiomsTest.kt:133`). `UIMessagePart.File.base64` has the same shape. **Fix:** hold a `FileData` (the sealed `Base64`/`Bytes`/`Url` already in `MediaModels.kt:13-52`) so the URL case is `FileData.Url`, typed; `bytes()` then can't be called on a URL-only file.

### 3.7 `!!` relying on an invariant the compiler can't see
**Failure mode:** `reasoningId!!` ×5 (`middleware/ExtractReasoning.kt:47,74,81,87,89`) assumes `inReasoning ⇒ reasoningId != null`; a future state-machine edit turns a logic bug into a bare NPE mid-stream. **Fix:** capture a non-null local at the top of the branch: `val rid = reasoningId ?: error("reasoning block open without id")`.

### 3.8 `data class … val bytes: ByteArray` with default (reference) equality
**Failure mode:** auto-`equals`/`hashCode` use identity for the byte field (11 sites in commonMain alone; **more in provider/test code, where request/response bodies most plausibly become cache/dedup keys**), so value-identical instances are unequal and hash differently — silently breaking `Set`/`Map`/dedup and `assertEquals`. **Fix:** hand-write `equals`/`hashCode` with `contentEquals`/`contentHashCode` per the `FileData.Bytes` template (`MediaModels.kt:23-45`), or drop `data` where value-equality isn't needed. Audit the provider files explicitly when applying this — the highest-impact instances are likely outside the commonMain core (see §6 M-6).

### 3.9 Parse-on-every-access getters
**Failure mode:** `LanguageModelTool.parametersSchema` (`LanguageModel.kt:117`) and `Output.schema` (`Output.kt:45`) re-parse JSON on every read; `DefaultGeneratedFile` (`MediaModels.kt:111-145`) mutates `var` fields behind `val`-looking getters (non-thread-safe, likely dead parity scaffolding). **Fix:** `by lazy` or store the `JsonElement` and derive the string; remove `DefaultGeneratedFile` if unused.

### 3.10 `throw RuntimeException` from a typed event
**Failure mode:** `StreamEvent.Error → throw RuntimeException(event.message)` (`ToolLoopAgent.kt:292`) loses the typed error and forces string-matching at the boundary. **Fix:** throw the typed `AiSdkException`/`AgentError` already modeled elsewhere — and carry the typed cause on the event in the first place (§2.9).

### 3.11 Test scaffolding leaking into the published artifact
**Failure mode (verified, not in the tracks):** `ktor-client-mock` is an `implementation` dependency of `commonMain` (`build.gradle.kts:51`) and `TestServer.kt` lives in `commonMain` with no production consumer — both ship in the published library. **Fix:** move `ktor-client-mock` to `commonTest` only and relocate `TestServer.kt` to `commonTest` (or a published `aisdk-testing` artifact if downstream test doubles are intended). `explicitApi()` (§5) will also flag `TestServer` as accidental public ABI.

---

## 4. Testability playbook

The foundation is strong — 75/95 commonTest files use `runTest`, hand-written fakes (`MockLanguageModel`, `CapturingModel`, `Mock*` media models), Turbine via `drainAllItems`, Ktor `MockEngine`. The gaps are concentrated in a few seams.

**4.1 Default to `StandardTestDispatcher` (what `runTest {}` gives you); virtual time only reaches code on a TestDispatcher.** This is the load-bearing constraint behind the §3.4 finding: a `launch` onto `Dispatchers.Default` is invisible to `advanceUntilIdle()`. Once `ToolLoopAgent`/MCP take an injected `engineContext`, pass `StandardTestDispatcher(testScheduler)` and the `withTimeout(1_000){ while(...) delay(10) }` busy-poll (`ToolApprovalTest.kt:166-177`) collapses to one `advanceUntilIdle()`.

**4.2 Scheduler verbs:** `advanceUntilIdle()` (drain to quiescence — the default), `runCurrent()` (assert an intermediate state without firing downstream delays), `advanceTimeBy(ms)` (the *only* correct way to test `smoothStream` cadence and `retryWithExponentialBackoff` windows), `currentTime` (assert elapsed virtual time). Collect never-completing `StateFlow`/`SharedFlow` in `runTest`'s `backgroundScope`, never in `this`.

**4.3 Fakes over mocks — mandatory, not preference.** mockk/Mockito are JVM-only and *fail to link* on `iosX64Test`/`iosArm64Test`/`iosSimulatorArm64Test`. Keep the scripted-response fake (`MockLanguageModel.kt:27`) and capturing fake (`CapturingModel`, `KotlinApiTest.kt:24`) as the `verify {}` substitute; consolidate them (`CapturingModel` is currently duplicated privately in `KotlinApiTest`) into one fixtures home and add a reusable `FakeChatTransport` against `ui/Chat.kt:15`.

**4.4 Deterministic seams.** `IdGenerator.generate()` calls global `Random` with no injection point (the data class begins ~`Util.kt:155`; the `Random.nextInt` call is at `Util.kt:170`, body running past `:176`), so id *wiring* is unassertable. Add `random: Random = Random.Default` and thread an `idGenerator: () -> String = ::generateId` into `Chat`/`ToolLoopAgent`/`AgentSession`. Add a `Clock`/`TimeSource` seam now (default system) *before* any code inlines wall-clock time — it's the time analogue of the dispatcher defect.

**4.5 Cold-flow testing.** Keep `drainAllItems` (`testing/FlowDrain.kt:19`) for finite cold flows — it's strictly better than `toList()` (cancels on non-completion, surfaces the real terminal error). Two caveats to document: (a) it must **never** point at a `StateFlow`/`SharedFlow` (hangs to the Turbine timeout — assert `.value` or use bounded Turbine instead); (b) Turbine's 3s timeout is **wall-clock and ignores `runTest` virtual time**, which is why `SmoothStreamTest.kt` passes `delayMs = 0L` everywhere and the *delay behavior of `smoothStream` is therefore never tested*. Add one timing test that collects in `backgroundScope` and drives with `advanceTimeBy`.

**4.6 Concurrency-race tests — design the state race the same way as the abort race.** Current tests (`SubagentTest.kt:24,56`) only assert the signal object reaches the tool and that `throwIfAborted()` throws. Add three concrete race tests, all gated with `CompletableDeferred` (never `delay()`):
- **AbortController list + interruption (§3.3a):** a fake whose `stream` emits one event, signals a `CompletableDeferred`, then `awaitCancellation()`; `abort()`; `advanceUntilIdle()`; assert `isStreaming == false`. Also assert an aborted call is **not** retried by `retryWithExponentialBackoff` (`Util.kt:140`). The list race itself needs a multi-threaded jvm/native test (register-while-aborting) — virtual time hides it.
- **`combineAbortSignals` abort-before-register (§3.3b):** combine signals where one is *already aborted*; assert the combined signal is aborted **and** all child registrations were cancelled (no leaked forwarders).
- **`AgentSession` stale-job clobber (§H-2, HIGH — has no test today):** gate job A on a `CompletableDeferred` mid-`generate`; `submit` job B; release A; `advanceUntilIdle()`; assert A's terminal `mutableState` write did **not** overwrite B's state and `currentJob` reflects B. This is the higher-severity sibling of the abort race and gets the same recipe.

**4.7 Contract tests across targets.** Everything in `commonTest` already *is* a cross-target contract test. Harden it by **running `iosSimulatorArm64Test` on every PR**, not just `jvmTest`/`androidHostTest` — native runs catch the threading bugs (§3.2/§3.3) that the JVM never surfaces. Add a Native-specific equality/`Map`-key test for the §H-9 value classes (`@JvmInline` changes boxing and key identity subtly on Native). Keep all doubles pure Kotlin (no `expect`/`actual` fakes). Standardize HTTP-backed provider tests on the Ktor `MockEngine` path; no test reaches a real socket.

**4.8 Structured-output decode + codec-config pinning.** Keep synchronous decode tests *out* of `runTest` (`OutputTest.kt:25` is correct). Add malformed/partial-JSON decode-failure assertions and a `streamObject` partial-emission test drained via `drainAllItems`. Pin the codec configs in fixtures and assert the **flag matrix** directly (§6 M-3): a round-trip test that a default-valued field survives encode-by-`outputJsonCodec` → decode-by-`aiSdkJson` and vice-versa, so the `encodeDefaults`/`explicitNulls` divergence can't silently drift.

---

## 5. TypeScript-to-Kotlin convention port map

| TS convention (v6) | Kotlin approach | Verdict | Rationale |
|---|---|---|---|
| Discriminated union (`{type:'…'}`) | `@Serializable sealed interface` + `data class`/`data object` variants | **ADOPT** (safer) | Closure is a property of the *type*, not the call site — adding a variant is a compile error everywhere. Done: `StreamEvent`, `ContentPart`, `ToolChoice`, `ToolResultOutput`. |
| Wire `type` kebab strings (`"tool-call"`) | `@SerialName` + `@JsonClassDiscriminator("type")`, **or** hand-mapped in transports | **ADAPT — decision needed** | kotlinx defaults to FQCN discriminator. Currently hand-mapped; `@Serializable` is dead on the wire path. Option (a) deletes mappers and matches v6 bytes; (b) documents `ContentPart` as internal-only. Tracks 4 and 5 agree: pick one, don't half-wire both. |
| `dynamic?: false\|true` flag on tool call/result | sealed variants **or** explicit `dynamic: Boolean` + `invalid`/`toolMetadata` fields | **ADAPT** | The static/dynamic split is load-bearing for `StepResult.staticToolCalls/dynamicToolCalls/toolErrors`. `ContentPart.ToolCall` (`ModelMessage.kt:74`) lacks `dynamic`/`invalid`/`toolMetadata`/`providerExecuted` — add them. Audit too the existing `ToolResult.modelVisible: JsonElement = output` (`ModelMessage.kt:99`), whose default is *another constructor arg*: under a non-`encodeDefaults` codec it serializes inconsistently and is silently disambiguated only in the hand-written mapper. Make the dual-output split explicit, not defaulted. |
| Branded/template-literal types (`type Positive = number & {__brand}`) | `@JvmInline value class` | **ADOPT** (real nominal) | Kotlin has the real thing with zero allocation. `ProviderId`/`ModelId` are `data class` today — convert (§2.2), keep `@Serializable` for the inline serializer since `ModelRef` is wire-adjacent. |
| Zod / `FlexibleSchema` / `lazySchema` / Standard Schema | `KSerializer<T>` + `SerialDescriptor`→JSON-Schema walk | **DROP abstraction, KEEP schema gen** | In Kotlin the type *is* the schema, generated at compile time — the runtime-validator layer and `lazySchema`'s cold-start rationale don't exist. Keep the descriptor→JSON-Schema walk; the convention surface to preserve is **`jsonSchemaFor` (`Tool.kt:782`, `internal`)** — the recursive `descriptorToJsonSchema` (`Tool.kt:815`) is a `private` helper underneath it. Keep `Schema<JsonElement>` for the `dynamicTool`/MCP no-serializer path. |
| `zodSchema()` / `valibotSchema()` | — | **DROP (reconciled)** | Track 1/4 flagged for deletion; verification shows they ARE referenced — but only by `ValibotSchemaTest.kt` / `FullPortFeatureParityTest.kt`, and `valibotSchema(schema) = schema` is a tautology testing a no-op. They are parity-only API surface that confuses Kotlin callers (there is no Zod/Valibot). Remove the functions **and** their tautological tests together. |
| Options-object bag | default + named args + `@DslMarker` builder | **ADOPT** (improvement) | `generateText(model, prompt = "hi")` needs no bag. Done: `CallSettings`, `callSettings {}`, `toolSet {}`. Add the marker (§2.7) and promote builders as the headline (§6 M-8). |
| `AsyncIterable` / `ReadableStream` | `Flow` (cold) | **ADOPT** (1:1) | Cold, lazy, backpressure-by-suspension. Tool executor is always `Flow<TOutput>`. `ReadableStream`/`TextEncoder`/manual SSE framing are browser plumbing — **drop** from public API; at most an internal Ktor adapter. |
| `AbortSignal`/`AbortController` | coroutine `Job`/`CancellationException`, bridged | **ADAPT** (bridged) | `AbortError : CancellationException` so it participates in structured cancellation. Keep the controller for v6 parity + UI stop-button; steer Kotlin callers to `Job`/`withTimeout`. Don't replicate JS's "thread the signal to every leaf" tax — child coroutines inherit cancellation for free. (Fix the race *and* the combine-ordering bug, §3.3.) |
| `ValueOf` / `NeverOptional` / `NoInfer` conditional types | real generics + star projection | **DROP** | Pure structural-typing workarounds; the problem doesn't exist in nominal Kotlin. Correctly absent. |
| `AISDKError` + `Symbol.for(...)` + `isInstance` | `sealed`/`open class AiSdkException` + `is` checks | **DROP the Symbol** | Symbols only survive duplicate-package `instanceof` on JS; irrelevant on JVM/Native. Done: `AiSdkError.kt`. |
| `createIdGenerator({...})` returning closure | top-level `generateId(prefix)` + injectable `Random` | **ADAPT** (de-literalize) | `IdGenerator` data-class wrapper (`Util.kt:155`) is TS-shaped; a function suffices. Add `Random` injection (§4.4). |
| Barrel `index.ts`, declaration merging, tree-shaking | packages + `internal`/`public` | **DROP** (no analogue) | R8/DCE handle dead code at link time. Don't create an `Index.kt`. Correctly absent. |
| React `useChat`/`useCompletion` | `StateFlow`-backed `AgentSession`/`ChatSession` | **ADAPT** (done right) | `Flow`/`StateFlow` is the native KMP/Compose reactive primitive — not a port of `useState`. |
| `stopWhen` / `prepareStep` / middleware / registry | `fun interface` + factories / suspend lambda / decorators / value-class `ModelRef` | **ADOPT** | All idiomatic and faithful. `ModelRef` with `parse()` + `:`-rejecting `init` is *stronger* than v6's raw-string + template-literal-overload registry. |

---

## 6. Findings against the CURRENT port (prioritized, file:line)

### HIGH

- **H-1 — Sentinel-default settings swallow explicit values.** `CallSettings.responseFormat = ResponseFormat.Text` / `abortSignal = AbortSignalNever` (`KotlinApi.kt:25,28`); `merge` compares `== ResponseFormat.Text` / `=== AbortSignalNever` (`:324,327`); `ToolLoopAgent.kt:75` repeats the sentinel default while its loop resolution at `:458-460` uses the correct nullable `?:` chain — the same concept modeled two ways in one call graph. Fix: §2.3. *Tracks 1 & 6 agree this is the single highest-leverage change.*
- **H-2 — `AgentSession` stale-job state race + unsynchronized `currentJob` + streaming drops `ToolCall`/`ToolResult`.** `mutableState.value = state.value.copy(...)` non-atomic RMW at `AgentSession.kt:50,70,117,129,136,147,150,159`; the guard handle `currentJob` is a plain `var` (`:39`) written without synchronization from six sites (`:50,92,185,186,191,192,237`), so a job-identity check races its own assignment; streaming `when` `else -> Unit` at `:155` discards tool-call/result events so `streamingMessages` (`:248-266`) only ever emits Text + ApprovalRequest. Fix: §3.2 (`update {}` + atomicfu `currentJob`) + handle the two events. Untested (only `KotlinIdiomsTest` touches `.session()`, no rapid-resubmit case) — add the §4.6 stale-job clobber test.
- **H-3 — `AbortController` data race *and* `combineAbortSignals` abort-before-register bug.** (a) `callbacks` `mutableListOf` mutated unsynchronized from `register`/`abort` (`AbortSignal.kt:54,62,82`) on a cross-thread type; UB on Native. (b) `combineAbortSignals` (`:111-124`) wires forwarding before cleanup, so an already-aborted source fires synchronously and leaks child registrations. These are **two fixes**, not one (§3.3).
- **H-4 — `StreamTextResult.fullStream` half-captured-replay landmine + non-cooperative collect.** `mutex.withLock` wraps the entire collect; `collected = true` only after completion (`Generate.kt:234-246`) — cancelled first collection replays a truncated cache as a real stream; cold-vs-memoised contract undocumented vs. the cold top-level `streamText`; the capture loop never `ensureActive()`s between events. Fix: §3.5 + §2.6.
- **H-5 — No `explicitApi()`, no binary-compatibility-validator** (verified absent in `*.kts`). Everything defaults to `public`; helpers across `TypedJson.kt`/wire code are implicitly public ABI, and `@PublishedApi internal aiSdkJson` (`TypedJson.kt:11`) is already a frozen surface through the inline twins (§2.5). `rootProject.name` is set, so BCV's KLib prerequisite is met. Blast radius is large — a multi-hundred-symbol public/`internal` triage across ~90 commonMain files (providers, builders, `TestServer`, the four codecs); stage with `explicitApiWarning()`, then add BCV + commit `.api`/`.klib.api`. This is the enforcement mechanism for §6 M-1/M-2.
- **H-6 — Sealed wire types carry no `@SerialName`/`@JsonClassDiscriminator`** (`ModelMessage.kt`, `Streaming.kt`, `ui/UIMessagePart.kt` — 0 matches). Decision per §5 row 2. *Tracks 4 & 5 converge on this as the headline serialization recommendation.*
- **H-7 — `ContentPart.ToolCall/ToolResult/ToolApprovalRequest` lack `dynamic`/`invalid`/`toolMetadata`/`providerExecuted`** (`ModelMessage.kt:74,94,105`); and the existing `ToolResult.modelVisible: JsonElement = output` (`:99`) defaults to another constructor arg, which serializes inconsistently across the codec matrix (M-3). `StepResult.staticToolCalls/dynamicToolCalls/toolErrors` are not representable. Add the missing fields; make `modelVisible` explicit rather than self-defaulting.
- **H-8 — Divergent duplicate-tool policy across 4 sites.** `ToolSetBuilder.add` **throws** (`Tool.kt:286`); `toolSetOf` **silent last-wins** (`Tool.kt:280`); `ToolSet.plus` **silent last-wins** (`Tool.kt:264`); MCP import **silent last-wins** (`MCP.kt:528`). Add a private `requireUniqueNames` and apply the throwing policy everywhere — silent paths drop a tool.
- **H-9 — `ProviderId`/`ModelId` should be `@JvmInline value class`** (`ModelRef.kt:3,12`). §2.2. Keep `@Serializable` on the value class so kotlinx uses the inline serializer (the type is wire-adjacent), and cover Native `Map`-key/equality with a contract test (§4.7). Keep `ModelRef` a `data class` (two fields).
- **H-10 — Test scaffolding in the published artifact.** `ktor-client-mock` is `implementation` of `commonMain` (`build.gradle.kts:51`); `TestServer.kt` is in `commonMain` with no production consumer. Move both to `commonTest`. *(Surfaced during verification; not isolated by any single track.)*
- **H-11 — `GeneratedFile` empty-base64 yields a wrong decode for URL-backed files.** The remote-URL discriminator is the existing `url: String?` (`MediaModels.kt:10`), **not** the empty string; the `FileData.Url` conversion sets `base64 = ""` *and* `url = data.value` (`:72-79`). The defect is that `base64` is a non-null `String` whose `""` means "no local bytes," and `GeneratedFile.bytes()` (`:103`) decodes `""` to an empty `ByteArray` — a silent wrong answer. Shape recurs across 7 providers + the helper + `KotlinIdiomsTest.kt:133`. Model as `FileData`. §3.6.

### MEDIUM

- **M-1 — Data-class-in-public-API ABI strategy undocumented.** Nearly the whole wire surface is `data class` (`CallSettings`, `LanguageModelCallParams`, all `*Params`/`*Result`, `StreamEvent.*`, `UIMessagePart.*`). Adopt the reconciled rule: results stay `data class` (consumers rarely `copy()`); config envelopes grow *only* via the envelope, funnel construction through builders, freeze field order at 1.0, and let BCV (H-5) surface every break. *Reconciles Track 1 §6 (grow envelopes) vs §8 (growing a data class breaks `copy()` ABI): grow the **envelope**, not the **function signature**, and gate it behind a builder.* Open sub-question for the provider facades, see M-9.
- **M-2 — No `@DslMarker`** (verified absent). §2.7.
- **M-3 — Four divergent core `Json` instances *with a divergent flag matrix*.** Not just instance count — the flags conflict: `aiSdkJson` (`TypedJson.kt:11`) sets `ignoreUnknownKeys`, `isLenient`, `explicitNulls=false` and **no `encodeDefaults`**; `outputJsonCodec` (`Output.kt:189`) and `ToolLoopAgent.jsonCodec` (`ToolLoopAgent.kt:92`, a third literal of the same intent) both set `encodeDefaults=true` and leave `explicitNulls` default; `WireDecoder.json` (`:32`) is strict (`ignoreUnknownKeys=false`, `isLenient=false`, `encodeDefaults=true`, `explicitNulls=false`). The hazard is concrete: a default-valued field is **emitted by one codec and dropped by another** on round-trip. Collapse to two named, documented instances — `aiSdkJson` (inbound, lenient) and one shared `aiSdkOutputJson` (outbound, `encodeDefaults=true`) — fold `ToolLoopAgent.jsonCodec` into the latter, and assert the flag matrix in fixtures (§4.8). Provider-local codecs are fine where they encode real wire quirks.
- **M-4 — Hardcoded `Dispatchers.Default` + no `ToolLoopAgent.close()`.** `ToolLoopAgent.kt:117`, `MCP.kt:1541/1697/1875`. Inject `engineContext`; add `close()` (MCP transports already model `suspend fun close()`). §2.4 / §3.4.
- **M-5 — `!!` cluster in `ExtractReasoning.kt:47,74,81,87,89`.** §3.7. (Lower-risk siblings: `FixJson.kt`, `FalProvider.kt`, `MCP.kt`.)
- **M-6 — ~11+ `data class … ByteArray` with reference equality in commonMain — plus an unaudited provider tail.** §3.8, template `MediaModels.kt:23-45`. Spot-check the provider request/response bodies (AWS SigV4 / Bedrock / image providers) explicitly: those are the instances most likely to be used as cache/dedup or test-equality keys, and they sit outside the commonMain core the tracks examined.
- **M-7 — Parse-on-every-access getters** `LanguageModelTool.parametersSchema` (`LanguageModel.kt:117`), `Output.schema` (`Output.kt:45`); non-thread-safe `DefaultGeneratedFile` var-behind-getter (`MediaModels.kt:111-145`, likely dead). §3.9.
- **M-8 — Promote `CallSettings`/builder as the headline API.** 15-param `generateText` (`Generate.kt:17-33`) and 13–14-param `tool()`/`streamingTool()` (`Tool.kt:137,201,291`) with `@Suppress("LongParameterList")` are the TS options-bag re-expressed. Document them as v6-compat shims; make `callSettings {}` / `textGenerationRequest {}` / reified `ToolSetBuilder.tool {}` the documented Kotlin-first path. Add `@JvmOverloads` only to specific Java/Android entry points (note: it is *not* a binary-compat guarantee — the envelope pattern is).
- **M-9 — Provider-facade stability convention is unspecified.** `OpenAICompatibleProviderFacades.kt` and the ~40 provider files define the bulk of the *most-called* public API (top-level provider entry-point functions), yet the H-5/M-1 ABI discussion is scoped to data classes and wire envelopes only. State a convention: provider entry points evolve by added optional parameters with defaults guarded behind a settings envelope (never positional growth), are frozen by BCV like everything else, and Java/Android entry points that need overload stability get explicit `@JvmOverloads` — decided per entry point, not globally.

### LOW

- **L-1 — `experimental_*` prefixes** (`MediaModels.kt:213,343,432,526`; `experimental_context` `Tool.kt:1055`) → `@RequiresOptIn`. §2.8.
- **L-2 — `StreamEvent.Error` carries only a `String`, and the consumer rethrows `RuntimeException`** (`Streaming.kt:287` producer; emits at `ToolLoopAgent.kt:351,415,538`; rethrow at `:292`). The typed cause is lost at the *producer* before any consumer sees it. → typed cause on the event (§2.9) + typed throw at the boundary (§3.10).
- **L-3 — `ChatSession` optimistic-append is collection-timed** (`ui/ChatSession.kt:58-72`, append inside `flow {}`) while `AgentSession.submit` updates eagerly (`AgentSession.kt:43-83`). Align on eager update for predictable `StateFlow` UI.
- **L-4 — `zodSchema`/`valibotSchema` + tautological tests** (`Tool.kt:574-584`, `ValibotSchemaTest.kt`). Remove together (§5).
- **L-5 — `IdGenerator` data-class wrapper** (`Util.kt:155`, `Random` call at `:170`) is TS-shaped; a top-level `generateId` suffices (fold into the §4.4 `Random`-injection change).

### Already correct — keep as templates
Sealed + exhaustive `when` (`Streaming.kt`, `TypedJson.kt:100-126`); cold-`Flow`/`suspend` split (`LanguageModel.kt:50-53`); injected-scope `AgentSession` (`:34`); `CancellationException` caught-first-and-rethrown in both agent loops (`AgentSession.kt:223`, `ToolLoopAgent.kt:249`); `ToolLoopAgent` `update {}` CAS (`:142,156,203`); `ValidationResult` sealed return-over-throw (`Tool.kt:491`); `FileData.Bytes` hand-written `contentEquals`/`copyOf` (`MediaModels.kt:23-45`); reified + `KSerializer` pairing (everywhere); minimal `internal expect fun createMCPStdioProcess` (`MCP.kt:1857`); keyword-safe naming (`Output.Obj`/`Arr`); pure `isRunning`/`isStreaming` getters. (`@PublishedApi internal aiSdkJson`, `TypedJson.kt:11`, is correct *as written* but is **not** an inert template — it is frozen ABI through the inline twins; see §2.5 / H-5 for the evolution constraint it now carries.)

---

## 7. Open questions needing a human decision

1. **Wire-format ownership (H-6).** Adopt `@SerialName` + `@JsonClassDiscriminator("type")` so kotlinx round-trips v6's wire JSON directly and the hand-written transport mappers (`KtorGatewayTransport.kt`, `DevTools.kt`) are deleted — **or** formally declare `ContentPart`/`StreamEvent`/`UIMessagePart` internal-only persistence models with the wire shape living in transports? Option (a) removes a class of drift bugs but commits the public serial format; (b) keeps flexibility but accepts permanent dual-maintenance. *Recommend (a); needs sign-off because it freezes wire bytes — and once frozen, the M-3 codec-flag matrix becomes part of the contract.*

2. **`data class` vs builder-gated non-data class for evolvable config envelopes (M-1/M-9).** Pre-1.0, is the "grow the envelope, funnel through builders, freeze field order, lean on BCV" rule sufficient for both the config envelopes *and* the ~40 provider facades, or do `CallSettings`/`LanguageModelCallParams` need the heavier private-constructor-+-builder treatment for strict binary stability at 1.0? *Recommend deferring the heavy form to a 1.0 decision; adopt the lighter rule now and apply M-9's added-optional-parameter rule to the provider entry points.*

3. **Value-class JVM/Java-interop + serialization policy (H-9).** `@JvmInline value class` constructors are name-mangled and not directly Java-callable (and `@JvmExposeBoxed` is still evolving). Accept Kotlin-only construction for `ProviderId`/`ModelId` (routing Java through the existing `providerId(...)`/`modelId(...)` factories), or block on broader Java-interop guarantees for the JVM artifact? Confirm too that `@Serializable` on the value class (required so `ModelRef` serializes the underlying `String`, not an object wrapper) is acceptable as a committed wire detail.

4. **SKIE / Swift-interop constraints on the public sealed types.** SKIE generates exhaustive Swift enums only when all exposed children are classes (done), and sealed *interfaces* don't get `Hashable`. If any public sealed type (`StreamEvent`, `ToolChoice`, `ResponseFormat`, `UIMessagePart`) must be a Swift `Set`/`Map` key, it should be `sealed class`, not `sealed interface`. Is iOS `Hashable`-keying a requirement for any of these? *Needs the iOS consumer's input.*

5. **`explicitApi()` rollout staging (H-5).** Land `explicitApiWarning()` first (stage the `internal`-vs-`public` decisions across the multi-hundred-symbol surface — every provider facade, builder, `TestServer`, and the four codecs) then flip to strict, or go strict immediately and absorb the larger one-shot diff? Either way, confirm the reified-convenience non-reified twins **and** any `@PublishedApi internal` codec they inline stay correctly classified (they're the iOS-facing door and a frozen ABI surface respectively) before the pass demotes anything.

---

## Appendix — External Sources (61)

- https://kotlinlang.org/docs/api-guidelines-introduction.html
- https://kotlinlang.org/docs/api-guidelines-simplicity.html
- https://kotlinlang.org/docs/api-guidelines-consistency.html
- https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html
- https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0045-explicit-api-mode.md
- https://github.com/kotlin/binary-compatibility-validator
- https://kotlin.github.io/binary-compatibility-validator/
- https://kotlinlang.org/docs/inline-classes.html
- https://github.com/Kotlin/KEEP/blob/master/notes/value-classes.md
- https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0104-inline-classes.md
- https://github.com/Kotlin/KEEP/blob/jvm-expose-boxed/proposals/jvm-expose-boxed.md
- https://kt.academy/article/ek-value-classes
- https://kotlinlang.org/docs/flow.html
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-flow/
- https://elizarov.medium.com/cold-flows-hot-channels-d74769805f9
- https://developer.android.com/kotlin/coroutines/coroutines-best-practices
- https://developer.android.com/kotlin/flow
- https://skie.touchlab.co/features/flows
- https://skie.touchlab.co/features/sealed
- https://skie.touchlab.co/features/suspend
- https://touchlab.co/sealed-generics-and-skie
- https://kotlinlang.org/docs/cancellation-and-timeouts.html
- https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/
- https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/flow/Channels.kt
- https://detekt.dev/docs/rules/coroutines
- https://github.com/santimattius/structured-coroutines/blob/main/docs/DECISION_GUIDE.md
- https://github.com/santimattius/structured-coroutines/blob/main/docs/KOTLIN_COROUTINES_SKILL.md
- https://developer.android.com/kotlin/coroutines/test
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/run-test.html
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/-standard-test-dispatcher.html
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/-unconfined-test-dispatcher.html
- https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-test/MIGRATION.md
- https://github.com/cashapp/turbine
- https://developer.android.com/kotlin/flow/test
- https://kt.academy/article/cc-testing-flow
- https://programminghard.dev/dont-learn-coroutine-testing-with-turbine/
- https://www.kmpship.app/blog/kotlin-multiplatform-testing-guide-2025
- https://www.revenuecat.com/blog/engineering/testing-subscription-cmp/
- https://github.com/mockk/mockk/issues/950
- https://stackoverflow.com/questions/58320492/kotlin-multiplatform-how-to-mock-objects-in-a-unit-test-for-ios
- https://dev.to/rsicarelli/fakt-automating-the-fake-over-mock-pattern-amh
- https://akjaw.com/old/old-testing-on-kotlin-multiplatform-and-strategy-to-speed-up-development/
- https://dev.to/gabrielanhaia/your-kotlin-sealed-class-is-not-a-typescript-discriminated-union-22c9
- https://dev.to/gabrielanhaia/suspend-is-await-not-async-a-kotlin-to-typescript-bridge-5dcj
- https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/polymorphism.md
- https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization/-sealed-class-serializer/
- https://livefront.com/writing/intro-to-polymorphism-with-kotlinx-serialization/
- https://www.learningtypescript.com/articles/branded-types
- https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0453-better-immutability-value-classes-motivation.md
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/buffer.html
- https://kotlinlang.org/docs/whatsnew-eap.html
- https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md
- https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/
- https://ai-sdk.dev/docs/agents/loop-control
- https://ai-sdk.dev/docs/reference/ai-sdk-core/tool-loop-agent
- https://ai-sdk.dev/docs/reference/ai-sdk-core/step-count-is
- https://dev.to/gabrielanhaia/kotlin-coroutines-to-typescript-three-cancellation-patterns-that-arent-11-3aam
- https://katharina.damschen.net/post/2026-01-06-value-objects-in-kotlin/
- https://y9vad9.com/en/notes/semantic-typing
