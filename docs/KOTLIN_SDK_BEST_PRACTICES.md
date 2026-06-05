---
name: kotlin-sdk-best-practices
description: >-
  Engineering standard and anti-pattern rejection framework for building a
  best-in-class, binary-stable Kotlin Multiplatform SDK (published library,
  streaming HTTP/LLM client, iOS-bridged). Apply when writing or reviewing
  Kotlin/KMP library code: public API surface and binary compatibility,
  coroutines and Flow, kotlinx.serialization wire contracts, KMP source-set
  structure, Maven Central publishing and iOS interop, runtime/build
  performance, and error handling / retry / resilience. Use for any PR against
  aisdk-kotlin. Triggers: "review this Kotlin/KMP code", "is this idiomatic
  Kotlin", "will this break binary compat", "best practice for <Flow / suspend /
  Json / expect-actual / value class / retry / cancellation>", "kotlin sdk
  standard".
---

# Kotlin Multiplatform SDK — Engineering Best Practices

**Module:** `ai.torad.aisdk` (Kotlin Multiplatform AI/LLM client, AI SDK v6 parity port)
**Status:** Definitive engineering standard. This is the permanent review reference for `aisdk-kotlin`.

> **Skill-shaped.** The YAML header above lets this file be promoted to an agent
> skill (drop into `.claude/skills/kotlin-sdk-best-practices/SKILL.md`) so a
> reviewer agent loads it automatically on a Kotlin/KMP PR. Read as a document
> today; install as a skill when you want it to fire on the triggers above.

## Purpose

This document is the standard a contributor's code is held to, and the checklist a reviewer applies. It exists because a published, binary-stable, multiplatform SDK fails differently from an app: a mistake here is a `NoSuchMethodError` in a downstream consumer six months later, an iOS build that compiles nowhere until the worst moment, or a swallowed `CancellationException` that leaks coroutines in someone else's production app. None of those show up in a green local build.

**How to use it:**

1. **For new code** — read the theme section that covers what you're touching. Each rule is imperative, has a one-line *why*, and (where the repo already does it right) points at an in-repo exemplar you can copy.
2. **For review** — the [Rejection Framework](#rejection-framework) is the heart of this doc. It is a grep-able / detekt-able / mechanically-checkable list of smells that **block a PR**. The [PR Review Checklist](#pr-review-checklist) is the one-page distillation.
3. **For evolution** — when a rule needs to bend, say so explicitly in the PR and update [Deliberately Deferred / Non-Goals](#deliberately-deferred--non-goals). Silent deviation is the failure mode this doc prevents.

The bar is: **a green merge, not a green diff.** The ABI dump, the Apple CI leg, and the cancellation-rethrow rules are all there because the diff looked fine.

> **A standard that doesn't run is a comment.** Several rules below name a *mechanical* detector (detekt rule, ABI dump, grep). If the detector exists but isn't wired into the gate, the rule is silently off — that is itself a finding. The cancellation detector ([§9.2](#9-error-handling--resilience)) is the worked example, and a deliberately *unfinished* one: detekt ships `SuspendFunSwallowedCancellation` and this repo sets it `active: true`, **yet it still matches nothing** — it needs type resolution our classpath-less KMP detekt run doesn't provide (detekt#5961), so enabling the rule was necessary but not sufficient. Wiring a typed, classpath-aware detekt task is the open item ([§7.4](#7-build-tooling--testing), [Non-Goals](#deliberately-deferred--non-goals)); until then the synchronous `SwallowedException` guard plus `runTest` cancellation tests hold the line.

---

## 1. API Design & Backward Compatibility

The published surface is a contract. Kotlin's own [API guidelines](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html) distinguish binary, source, and behavioral compatibility — all three are in scope, and binary breaks are the ones invisible in source review.

1. **Keep `explicitApi()` strict on and never suppress it.** Every public/protected declaration must carry an explicit visibility modifier and an explicit return/property type or the build fails. An inferred return type pins the compiled signature to whatever inference produced, so a body edit (`List`→`Collection`) silently changes the published descriptor. *Exemplar:* `build.gradle.kts:32` (`explicitApi()`). ([KEEP-0045](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0045-explicit-api-mode.md))

2. **Write the explicit type on every public function return and property — never an expression body that infers it.** Same reason: the bytecode encodes the declared type. `fun parse(): JsonElement`, not `fun parse() = ...`.

3. **Gate the public surface on the committed ABI dump.** `checkKotlinAbi` runs under `check` and fails on any unreviewed change to the JVM ABI (`api/jvm/aisdk-kotlin.api`, 13239 lines) or the merged klib ABI (`api/aisdk-kotlin.klib.api`, 13798 lines). Treat a dump diff as a reviewable API change, never a formality. *Exemplar:* `build.gradle.kts:56` (`abiValidation { ... }`), `keepLocallyUnsupportedTargets.set(true)` lets Linux CI infer the iOS klib ABI. ([KGP ABI validation](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html))

4. **Add optional parameters via a new overload, never by appending to an existing signature — even with a default.** Any parameter change alters the JVM method descriptor → `NoSuchMethodError` for already-compiled Kotlin callers. Source still compiles, hiding it from review; the ABI dump catches it. `@JvmOverloads` does **not** fix this for Kotlin callers: a Kotlin call site with omitted defaults binds to the `$default` synthetic method plus an arg-presence bitmask, not to a telescoping overload; `@JvmOverloads` only generates Java-facing telescoping overloads, so the `$default` descriptor a recompiled Kotlin caller needs is still the one that moved. Only a hand-written overload preserves the old descriptor.

5. **Do not let a public `data class` grow.** Adding a property changes the synthesized constructor + `copy(...)` descriptors (binary break); reordering remaps `componentN()` (behavioral break). Where a public value type is expected to gain fields, model it as a regular class or interface. `ModelRef` is a deliberately-frozen exception — see [Non-Goals](#deliberately-deferred--non-goals).

6. **Seal closed domain hierarchies.** `sealed interface`/`sealed class` blocks illegal external implementations and makes consumer `when` exhaustive without `else`. *Exemplar:* `StreamEvent` and the wire content-part hierarchy in `ModelMessage.kt:67`.

7. **Treat `@PublishedApi internal` as frozen public ABI.** It is inlined into client bytecode, so changing it breaks binary compatibility exactly like a public change — the `internal` keyword fools reviewers. Subject every `@PublishedApi` declaration to the same compatibility review as public API. *Exemplar:* `aiSdkJson` is `@PublishedApi internal` precisely because the reified inline `decodeAs`/`encodeJsonElement` twins inline it — `TypedJson.kt:11`.

8. **Accept and return read-only collections; never expose arrays or `Mutable*`.** Callers must not be able to mutate library-held state, and arrays force defensive copies everywhere. Public signatures use `List`/`Set`/`Map`; back internal mutation with a private `_xs` exposed as the read-only supertype.

9. **Evolve, don't remove.** Retire API with `@Deprecated(message, replaceWith = ReplaceWith(...))` escalating WARNING → ERROR → HIDDEN across minor releases; remove only in a major. Do **not** abuse `@RequiresOptIn` to retire existing declarations — opt-in is for staging new surface, not killing old.

10. **Pin the JVM default-methods mode explicitly; treat it as an ABI decision.** Since Kotlin 2.2 interface functions with bodies compile to real JVM default methods by default, controlled by the now-**stable** `-jvm-default` option (it replaced the experimental `-Xjvm-default`). The mode is binary-affecting and must be a deliberate choice, not a compiler default you inherited on a Kotlin bump:
    - `-jvm-default=enable` (the 2.2+ default) generates the default method **and** keeps the `DefaultImpls` compatibility stubs — pick this for a published library so consumers compiled against older bytecode still link. (Equivalent to the old `-Xjvm-default=all-compatibility`.)
    - `-jvm-default=no-compatibility` drops `DefaultImpls` for cleaner bytecode — only safe before 1.0 / when you control every consumer. (Equivalent to the old `-Xjvm-default=all`.)
    Switching `enable`→`no-compatibility` after release removes `DefaultImpls` from the ABI and is a classic invisible binary break; the ABI dump (§1.3) catches it. Use `@JvmDefaultWithoutCompatibility` to opt individual interfaces out of the stubs under `enable`. ([Kotlin 2.2 — `-jvm-default`](https://kotlinlang.org/docs/whatsnew22.html), [compatibility guide](https://kotlinlang.org/docs/compatibility-guide-22.html))

---

## 2. KMP Structure

Rules here keep platform code out of `commonMain` and prevent the silent single-target trap, where common code happily references a JVM symbol that vanishes the instant iOS is added. ([Discover your project](https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html))

1. **Rely on the default hierarchy template; never hand-roll `dependsOn` for sets it already provides.** Since 1.9.20 the template auto-creates `iosMain`/`appleMain`/`nativeMain`/`jvmMain` with type-safe accessors. A manual `.dependsOn()` edge **disables automatic application of the default template** for the affected source sets (you get a build warning and lose the generated accessors) — it does not silently delete sets you didn't touch. If you genuinely need an extra set, call `applyDefaultHierarchyTemplate()` explicitly first, then add only the additive edge so the template still applies. ([Hierarchy](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html))

2. **Keep `commonMain` free of platform symbols.** No `java.io.File`, `java.time.*`, `System.*`, `platform.Foundation.*`, `android.*`. It compiles to every target including Native where those don't exist. Hide them behind `expect`/`actual` or an interface.

3. **Prefer a common interface + `expect fun` factory over `expect class`.** Interfaces allow fakes in tests and multiple implementations; `expect class` is still Beta (needs `-Xexpect-actual-classes`). Reach for `expect class` only for genuine platform-inheritance cases. ([expect/actual](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html))

4. **Keep `expect` declarations minimal and implementation-free.** They are templates; the compiler rejects bodies. When a factory accretes platform branches, graduate it to an interface.

5. **Confine single-platform dependencies to their source set.** `commonMain` may depend only on libraries shipping artifacts for every declared target (Coroutines, kotlinx-serialization, Ktor client). OkHttp → `jvmMain`/`androidMain`; Darwin engine → `iosMain`. A JVM-only dep in `commonMain` breaks the iOS build.

6. **Put `actual` implementations in the highest shared intermediate set.** For `iosArm64` + `iosSimulatorArm64`, the single `actual` goes in `iosMain`; leaf sets stay empty unless a leaf genuinely differs. No copy-pasted device/simulator `actual`s.

7. **Write shared tests in `commonTest` against `kotlin.test` only.** `@Test`/`assertEquals`/`assertTrue` compile to all targets; keep JUnit/XCTest specifics in `jvmTest`/`iosTest`.

8. **Build all targets in CI from day one.** A single-target project lets common code reach that target's symbols; a second target makes the leak a compile error at the worst time. *Exemplar:* the `verify-apple` CI leg builds + tests `iosSimulatorArm64` on `macos-latest` (`.github/workflows/ci.yml:48`), closing the gap `kotlin.native.ignoreDisabledTargets` would otherwise hide.

---

## 3. Publishing & iOS Interop

A KMP library publishes one umbrella module plus per-target artifacts, all of which Maven Central wants in **one** deployment from **one** macOS host.

1. **Publish all artifacts from a single macOS runner.** Apple `.klib`/XCFramework artifacts only build on macOS, and Central forbids duplicate root publications. One `publish...` task, `runs-on: macOS-latest`. Splitting the publish across hosts produces duplicate `kotlinMultiplatform` modules and fails the deployment. ([Publish to Maven](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-libraries-to-maven.html))

2. **Populate required POM metadata and gate on the pre-publish guards.** Central rejects deployments missing license/developer/SCM or a PGP signature. *Exemplar:* `build.gradle.kts:243` already refuses to publish a `SNAPSHOT`, refuses unsigned artifacts, and requires credentials — keep those guards; they fail before upload, not after.

3. **Ship a real Dokka 2.x (V2) HTML `-javadoc` jar, never an empty stub.** Dokka's Javadoc format cannot render KMP, so HTML-in-javadoc.jar is the correct way to satisfy Central and stay browsable on javadoc.io. An empty javadoc jar passes validation and gives consumers nothing. ([Dokka javadoc](https://kotlinlang.org/docs/dokka-javadoc.html))

4. **Package Apple output as an XCFramework with a stable `baseName` and explicit `bundleId`.** `baseName` is the Swift `import` name; `bundleId` sets a unique `CFBundleIdentifier`. Never ship a fat/universal framework — App Store rejects simulator slices. *Exemplar:* the XCFramework aggregation with `bundleId` already in `build.gradle.kts`.

5. **Declare any dependency whose types appear in the public ABI as `api()`, not `implementation()`.** Coroutines, kotlinx-serialization, and Ktor types leak into public signatures (a returned `Flow`, a `@Serializable` param, an `HttpClientEngine` seam); if those are declared `implementation()`, the generated POM scopes them `runtime`, the type is invisible to consumers at compile time, and the consumer's build fails to resolve a symbol that is plainly in your API. This is the canonical "fails differently from an app" defect — an app never publishes a POM. Gate it: cross-check the ABI dump against the POM's `compile`-scope dependencies; any type in the dump whose artifact is only `runtime`-scoped is a finding. ([Gradle api vs implementation](https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_separation))

6. **Wire actual Maven Central, not just GitHub Packages.** *Gap:* today only GitHub Packages is configured; the POM and javadoc exist but the umbrella publication isn't finalized for Central (a `vanniktech`-style convention over the hand-rolled signing/POM block is the standard path). Until then the artifact is not Central-consumable. ([vanniktech central](https://vanniktech.github.io/gradle-maven-publish-plugin/central/))

7. **For iOS DX, the surface needs SKIE — the raw ObjC bridge degrades it.** *Gap:* with 100+ `Flow`, ~400 `suspend` funcs, and 19 sealed hierarchies, the bare bridge turns `Flow` into an opaque object (not `AsyncSequence`), `suspend` into completion handlers (not `async`), and sealed types into non-exhaustive Swift. Apply `co.touchlab.skie` **in the framework-producing module only** (the one with `framework {}` / `native.cocoapods`); applied elsewhere it silently does nothing. Pin SKIE and Kotlin versions together (SKIE supports Kotlin 2.0.0–2.3.10 today). ([SKIE](https://skie.touchlab.co/intro))

8. **Do not rely on JetBrains Swift export for the production iOS surface yet.** It is **Alpha as of Kotlin 2.4.0** (Experimental in 2.2.20–2.3.0). Even at Alpha it supports only final classes directly inheriting `Any`, type-erases generics, and breaks on sealed/open hierarchies — i.e. most of this SDK. (Kotlin 2.4 does add out-of-the-box `Flow`→`AsyncSequence` and `suspend`→`async` export, which narrows the gap with SKIE, but the class-shape limits still rule it out here.) Default to ObjC bridge + SKIE; track Swift export behind a flag. ([Swift export](https://kotlinlang.org/docs/native-swift-export.html), [What's new in Kotlin 2.4.0](https://kotlinlang.org/docs/whatsnew24.html))

---

## 4. Coroutines & Flow

The SDK is a streaming client; cancellation correctness and main-safety are load-bearing, not polish. ([Android coroutine best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices))

1. **One-shot = `suspend fun`; streaming = a non-suspend function returning a cold `Flow`.** A `Flow`-returning function must never itself be `suspend` (detekt `SuspendFunWithFlowReturnType`) — that does hidden eager work and breaks lazy cancellation. *Exemplar:* `StreamTextResult.fullStream` is a cold `flow { }` (`Generate.kt:249`).

2. **Make every `suspend` function main-safe.** Wrap blocking I/O in `withContext(ioDispatcher)` and heavy CPU in `withContext(defaultDispatcher)`. Callers must be able to invoke from `Dispatchers.Main` without freezing. The class doing the work owns the dispatcher choice.

3. **Inject dispatchers; never hardcode `Dispatchers.X` in library code.** Constructor params with production defaults let tests substitute a `TestDispatcher` on one scheduler. *Exemplar:* `ToolLoopAgent` takes `engineContext = Dispatchers.Default` and all three MCP transports take an injected `engineContext` (`MCP.kt:1540,1705,1884`).

4. **Bound shared I/O concurrency with a single `Dispatchers.IO.limitedParallelism(n)` view; never invent a `newFixedThreadPoolContext`.** A streaming client funneling N providers can otherwise open unbounded concurrent connections under load. `Dispatchers.IO` is elastic: `limitedParallelism(n)` returns a *view* that caps parallelism to `n` while sharing the IO thread pool — the modern replacement for a dedicated fixed pool. Create the view **once** into a stable `val` and reuse it (each call makes an independent view; they don't share the cap). It is a parallelism limit, not a mutex. ([limitedParallelism](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/limited-parallelism.html))

5. **For a suspend-friendly critical section, use `Mutex.withLock`; never `synchronized`/`ReentrantLock` in `commonMain`.** A coroutine holding a JVM monitor across a suspension point blocks a pooled thread, and `synchronized`/`ReentrantLock` have no `actual` on Native/JS so they won't even compile in common. For lock-free shared state prefer atomicfu CAS ([§6.9](#6-runtime-performance)); reach for `Mutex` only when the critical section must `suspend`. ([Mutex](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/))

6. **Set a Flow's dispatcher with `.flowOn(...)` as the last operator — never `withContext`/`launch` around `emit()`.** Emitting from a different context violates Flow's context-preservation invariant and throws `IllegalStateException("Flow invariant is violated")` at runtime. ([Flow](https://kotlinlang.org/docs/flow.html))

7. **Rethrow `CancellationException` first in every broad catch.** It is the structured-concurrency cancellation signal; swallowing it leaves coroutines running after their scope is gone, defeats `withTimeout`, and leaks work. *Exemplar:* `ToolLoopAgent.kt:364` catches `ce: CancellationException` and rethrows before the generic `Throwable` branch. detekt `SwallowedException` is configured to **not** ignore `CancellationException` (`detekt.yml:13`).

8. **Make CPU-bound loops cooperatively cancellable.** Call `ensureActive()`/`yield()` per iteration in any loop that doesn't already call a cancellable suspend function. The `flow { }` builder adds these per emission; raw loops do not. *Exemplar:* the replay driver in `Generate.kt:249` checks `ensureActive()` at event granularity.

9. **Bridge callback transports with `callbackFlow` and `awaitClose` as the final statement.** Emit via `trySend`; unregister in `awaitClose { ... }`. Omitting `awaitClose` throws `IllegalStateException` or leaks the callback. Single-shot callbacks → `suspendCancellableCoroutine` + `invokeOnCancellation`.

10. **Call `shareIn`/`stateIn` exactly once into a stable `val`.** Each call spins a new upstream coroutine and connection; calling from a getter or per-request function multiplies and leaks. Prefer `SharingStarted.WhileSubscribed(5000)`. Apply `catch`/`retry`/`onStart` *before* `shareIn`.

11. **Expose immutable `Flow`/`StateFlow`/`SharedFlow`, backed by private `Mutable*`.** Centralizes mutation in the owning class; never leak `Mutable*` out of the SDK.

12. **Never use `GlobalScope`; inject a `SupervisorJob`-backed scope for work that outlives a call.** `GlobalScope` has no parent, no cancellation, no test seam. *Exemplar:* `ToolLoopAgent` owns its scope and exposes `close()` to cancel it (`ToolLoopAgent.kt:132`). Use `coroutineScope`/`supervisorScope` for call-scoped fan-out.

13. **Choose `supervisorScope` vs `coroutineScope` deliberately for fan-out.** `supervisorScope` = one child fails, siblings survive (partial results). `coroutineScope` = one child fails, all cancel (transaction). Getting it wrong silently kills or orphans work.

---

## 5. Serialization (kotlinx.serialization)

Wire stability is an API contract too: a renamed class or a dropped null is a breaking change to consumers' stored data. ([json.md](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md))

1. **Build each `Json` once into a top-level/`object` `val`; reuse it.** Formats cache per-class descriptor analysis; per-call `Json { }` discards that cache on every streamed chunk. *Exemplar:* the two named codecs `aiSdkJson` (inbound, lenient) and `aiSdkOutputJson` (outbound, `encodeDefaults=true`) in `TypedJson.kt:11`.

2. **Pin every polymorphic subtype's wire value with `@SerialName`.** The default discriminator is the FQ class name, so a rename/move silently breaks the wire. *Exemplar:* the sealed wire types carry `@JsonClassDiscriminator("type")` + kebab `@SerialName` (`"tool-call"`, `"tool-result"`) to round-trip v6's exact JSON (`ModelMessage.kt:67`).

3. **Set the discriminator once on the base via `@JsonClassDiscriminator`.** It is `@InheritableSerialInfo`; you cannot vary it per branch. Choose it at the root of the hierarchy.

4. **Encode/decode polymorphic values through the static base type.** `encodeToString<Base>(value)` — the discriminator is only written when the compile-time type is the base. Serializing through a concrete subtype omits `type` and produces un-decodable payloads.

5. **Use `@JsonIgnoreUnknownKeys` per-response-class for forward compat — not the global flag.** New server fields then don't crash older builds, while request/strict models still reject typos. The annotation does **not** propagate into nested classes — annotate each. Prefer this over global `ignoreUnknownKeys`. ([@JsonIgnoreUnknownKeys](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-ignore-unknown-keys/))

6. **Register a polymorphic `defaultDeserializer` fallback that captures the raw discriminator.** An unknown subtype otherwise throws `Serializer for subclass not found`. LLM providers add message/content types constantly; degrade gracefully.

7. **Keep `explicitNulls = true` unless you've proven no nullable field has a non-null default.** With it off, a nullable+defaulted field decodes back to the default, not `null` — encode-then-decode is no longer idempotent. The inbound `aiSdkJson` sets `explicitNulls = false` deliberately for inbound leniency, which is safe **only because it is decode-only**: the encode side uses the separate `aiSdkOutputJson` codec (`TypedJson.kt:11`). Make that boundary enforceable — the `explicitNulls = false` codec is a **decode-only** instance; any call site that both `encodeToString`s and `decodeFromString`s through the *same* `false` codec is a finding (a non-idempotent round-trip). Route encode through the `explicitNulls = true` codec.

8. **Give every custom `KSerializer` a unique, package-qualified descriptor name matching its encode/decode calls.** `PrimitiveSerialDescriptor("ai.torad.aisdk.X", ...)`. A mismatched descriptor is documented unspecified behavior. Prefer the surrogate pattern (private `@Serializable` surrogate + delegated serializer) over hand-rolled `encodeStructure`/`decodeStructure`.

9. **Pin the serialization runtime to the Kotlin version.** The compiler plugin and runtime ship in tandem; a mismatch breaks codegen.

---

## 6. Runtime Performance

The streaming path runs per token on memory-constrained devices; allocation discipline there matters more than anywhere else. ([Inline classes](https://kotlinlang.org/docs/inline-classes.html))

1. **Wrap single-field domain identifiers in `@JvmInline value class` with `init` validation.** Zero heap cost, type safety, centralized invariants. *Exemplar:* `ProviderId`/`ModelId` in `ModelRef.kt:5`.

2. **Keep value classes unboxed on hot paths.** Used as a generic element (`List<ModelId>` per token), interface type, or nullable (`ModelId?`), a value class **boxes** — silently undoing the win. Extract `.value` at the boundary before crossing into generic/nullable/interface code in a loop. **There is no static lint for this** — boxing is a backend codegen decision, not a source pattern, so `-Xverify-ir` and the ABI dump won't surface it. Two real options: (a) reason about it at review by reading the boxing rules (generic arg, nullable, supertype) against the hot files; (b) for the streaming path specifically, pin it down empirically — add a `kotlinx-benchmark` (JMH) allocation/`-prof gc` test over the per-token decode loop and fail on an allocation-rate regression. Treat this as **manual review backed by an allocation benchmark**, not a grep. ([kotlinx-benchmark](https://github.com/Kotlin/kotlinx-benchmark))

3. **Reserve `inline` for functions with a lambda or `reified` param; keep bodies small.** Otherwise it just duplicates bytecode (the `NOTHING_TO_INLINE` warning). *Exemplar:* the reified `decodeAs<T>()`/`encodeJsonElement<T>()` inline twins paired with explicit non-reified `KSerializer` overloads — the reified door for Kotlin, the erased door for iOS/Swift where reified inline doesn't cross (`TypedJson.kt:26`).

4. **Construct each codec/regex once; reuse for the SDK lifetime.** Same rule as serialization §1 — never inside a per-chunk function.

5. **Decode SSE incrementally over one retained buffer; never re-materialize the whole stream.** Frame on byte boundaries (`indexOf(0x0A)` / `\n\n`), consume complete frames, leave the partial tail in the buffer. `decodeToString()` / `bodyAsText()` / `accumulated.split("\n")` per chunk is O(n²). *Exemplar:* `streamSse` keeps the body a live channel via `prepareRequest{}.execute{}` and emits each wire line as it arrives (`HttpTransport.kt:179`).

6. **Accumulate streamed text in a reused `StringBuilder`, not `acc += delta`.** String is immutable; `+=` allocates a fresh full-length string per delta (O(n²) over a stream). Key a `StringBuilder` by part id; materialize once at part end.

7. **Default to `List`; reach for `asSequence()` only on large or early-terminating multi-step chains.** `asSequence()` trades per-element iterator overhead for laziness, so it pays its way only when the collection is large or a step can short-circuit (`first`/`take`/`find`); on a few headers or content parts the eager `List` operators are faster and allocate less. Don't add `.asSequence()` reflexively. ([sequences](https://kotlinlang.org/docs/sequences.html))

8. **Use `lazy(LazyThreadSafetyMode.NONE)` only for thread-confined heavy state.** Default `SYNCHRONIZED` pays a lock on every access; `NONE` skips it. But `NONE` is a data race the moment the lazy value is touched from more than one thread (it has no happens-before guarantee on JVM/Native), so use it **only when the value is provably confined to one thread** — computed at construction time, or guarded by the SDK's own single-threaded confinement. Never apply it to state a public getter can hand to arbitrary caller threads.

9. **Mutate shared state with `atomicfu` `update { }` CAS loops; keep the closure cheap and side-effect-free** (it re-runs on CAS retry). *Exemplar:* `AbortController` keeps its callback list as a copy-on-write `AtomicReference<List<...>>` updated via CAS, correct on Native not just JVM (`AbortSignal.kt:61`). `combineAbortSignals` handles the abort-before-register hazard by attaching teardown before forwarding registrations (`AbortSignal.kt:151`).

10. **Keep `kotlin-reflect` off the dependency graph.** Resolve serializers via the plugin + `reified` helpers. Reflection defeats R8 tree-shaking, forces consumer keep-rules, and is unsupported on Native. ([R8 guidance](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html))

---

## 7. Build, Tooling & Testing

The gates are what make the other sections enforceable rather than aspirational.

1. **Commit `org.gradle.caching=true` and `org.gradle.configuration-cache=true` in `gradle.properties`.** They are independent (output reuse vs configured-graph reuse) and committing them gives the whole team + IDE-delegated builds the speedup. *Exemplar:* config-cache + parallel + build-cache are already on.

2. **Cache `~/.konan` and `~/.gradle` in CI.** Kotlin/Native re-downloads its toolchain into `~/.konan` per fresh container otherwise. *Exemplar:* the Linux CI leg caches `~/.konan` (`.github/workflows/ci.yml`).

3. **Gate CI on `checkKotlinAbi` (under `check`); run `updateKotlinAbi` only on intentional change.** Reflexively running `updateKotlinAbi` to go green silently blesses a real ABI break — that's a blocking review failure. Fail the PR if `api/*.api` changed without a changelog entry. **Caveat:** the ABI dump's member ordering and shape are produced by a specific Kotlin compiler version, so a Kotlin bump can legitimately re-emit the dump (reordered members, new synthetic accessors) with no real source change. Pin the Kotlin version, and on a deliberate bump regenerate the dump in the same PR and call the churn out as *expected, compiler-driven* — distinct from a real API change. Don't let expected bump-churn train reviewers to rubber-stamp dump diffs.

4. **Run detekt as a blocking gate with `buildUponDefaultConfig = true`, `autoCorrect = false`.** `autoCorrect` does nothing without `buildUponDefaultConfig` and can fix-then-still-fail; corrections belong in local/pre-commit, not the gate. The cancellation rule `SuspendFunSwallowedCancellation` must be `active: true` *and* run with type resolution (the typed `detektMain`-style tasks with a classpath, not a classpath-less source scan) or it silently matches nothing. *Exemplar:* `detekt.yml` keeps `SwallowedException` active with `CancellationException` deliberately *not* ignored, and turns on `coroutines.SuspendFunSwallowedCancellation`. **Baseline = migration scaffold, not standard.** A detekt baseline is acceptable *only* as a temporary suppression ledger with a committed burn-down target; it ratchets new code clean while the backlog is paid down. A baseline carried indefinitely is the anti-pattern this section exists to catch, not an exemplar of it — see [Non-Goals](#deliberately-deferred--non-goals) for the current count and the burn-down commitment.

5. **Inject `Clock`, `Random`, dispatcher, and `HttpClientEngine` as constructor seams.** No `System.currentTimeMillis()`, `Random.Default`, hardcoded `Dispatchers.IO`, or real engine inside a class. *Exemplar:* `IdGenerator`/`generateId(random: Random = Random.Default)` (`Util.kt:186`). *Gap:* several providers still read `Clock.System.now()` directly (AWS SigV4 `amzDate`, KlingAI JWT `exp`), so signing isn't deterministic under test — inject a `Clock`/`TimeSource` there too.

6. **Test the HTTP layer with Ktor `MockEngine` sharing the production client config.** No real network in unit tests (slow, flaky, doesn't exercise error/retry paths deterministically). Pass `MockEngine` whose handler asserts request shape and returns canned responses. ([Ktor testing](https://ktor.io/docs/client-testing.html))

7. **Wrap coroutine/Flow tests in `runTest`; collect hot/infinite flows in `backgroundScope`.** `runTest` skips delays (retry/backoff tests run instantly); `backgroundScope` auto-cancels infinite collectors so `runTest` doesn't hang. Never `toList()` a `StateFlow` under `runTest` — it never completes and the test hangs to timeout.

8. **Guard the JSON wire contract with golden/approval tests through the real `Json.encodeToString()` path.** Hand-asserting fields misses drift in defaults, nullability, discriminators, enum encoding. Serialize through the SDK's actual codec, not Gson/Jackson defaults.

9. **Enforce a Kover coverage floor under `check`.** *Gap:* Kover measures but `koverVerify` is not gated and there's no `minBound`, so coverage can silently regress. Add a modest floor with generated/sample code excluded. ([Kover](https://kotlin.github.io/kotlinx-kover/gradle-plugin/))

10. **Use `linkDebug*`/per-target test tasks while iterating one platform.** `build`/`assemble` recompile common code once per target.

---

## 8. Ergonomics & Type Safety

1. **Annotate one shared builder supertype with `@DslMarker`, not individual builders.** It stops an inner block (`tool {}` inside `agent {}`) from silently configuring an outer scope. *Exemplar:* `@AiSdkDsl` is defined and applied to `CallSettingsBuilder`, `ToolSetBuilder`, and the nested `ui` builder (`KotlinApi.kt:18`).

2. **Resolve unset settings with `?:` over `T? = null`, never sentinels.** A nullable type is the first-class "absent" signal; `-1`/`""`/`"AUTO"` are indistinguishable from real values and defeat null-checking. *Exemplar:* `CallSettings` uses `T? = null` + `?:` merge, explicitly fixing the prior `AbortSignalNever`/`ResponseFormat.Text` sentinel bug (`KotlinApi.kt:21`).

3. **Replace behavior-switching `Boolean` params with named functions or enums.** `generate(prompt, true, false)` is unreadable and can't grow a third mode without a breaking change. `streamText` vs `generateText`; an `enum` mode otherwise. If a Boolean is unavoidable, require named-argument calling.

4. **Gate every unstable surface behind a tiered `@RequiresOptIn` marker.** `Level.ERROR` for surfaces that may break binary compat; markers need BINARY/RUNTIME retention and no `EXPRESSION`/`FILE`/`TYPE` target. Propagate opt-ins you consume upward. *Exemplar:* `@ExperimentalAiSdkApi`/`@InternalAiSdkApi` with BINARY retention, wired into the ABI exclusion filter (`OptIn.kt:24`).

5. **Use `@SubclassOptInRequired` for client-extensible contracts you intend to grow.** It requires opt-in at the inheritance site only, signalling "you may extend, but new abstract members can appear" — the right middle ground between fully sealed and freely `open`.

6. **Model finite outcome spaces as sealed; write `when` without `else` in your own handling.** Adding a variant becomes a compile error at every consumer. *Exemplar:* `AgentError` (tool-loop failure taxonomy), `StreamEvent` consumed by an exhaustive `when` with no `else` (`TypedJson.kt:108`).

7. **Pick the error channel by failure kind, library-wide:** nullable for absent data; typed sealed exception for genuine failure; a `…Catching` Result-returning twin at I/O boundaries. Consistency lets callers predict the channel without reading each signature.

8. **Make collection-typed config immutable; evolve with `copy`/`merge`, not mutation.** Safe to share across coroutines in a streaming client; per-call overrides stay composable without aliasing. Use the private `_messages` exposed as `List` pattern for internal mutation.

9. **Follow Kotlin naming idioms and KDoc every public member.** `sort` vs `sorted`, `…OrNull`/`…Catching` suffixes, cheap-stable computations as `val` properties, work as functions. KDoc on the public surface is a library-authoring requirement, not optional.

---

## 9. Error Handling & Resilience

The SDK talks to flaky, rate-limited, paid APIs; the retry and error model is product-critical. ([OpenAI error/retry](https://platform.openai.com/docs/guides/error-codes))

1. **Rethrow `CancellationException` as the first statement of any broad catch** (`coroutineContext.ensureActive()` or `if (e is CancellationException) throw e`). A retry loop or error-mapper that swallows it breaks cancellation. *Exemplar:* `ToolLoopAgent.kt:364`.

2. **Never wrap a suspend call in stdlib `runCatching`.** It catches `Throwable` and never rethrows → it captures `CancellationException` into `Result.failure`, faking a recoverable error. Use a cancellation-aware `runSuspendCatching` (catch-all that rethrows `CancellationException`) or explicit try/catch with `ensureActive()` first. *Mechanical detector:* detekt's built-in `SuspendFunSwallowedCancellation` (coroutines rule set, shipped in 1.23.0) flags a `suspend` call inside a `runCatching` lambda via type resolution. It ships `active: false` — turn it on (`detekt.yml`) **and** run detekt with a configured classpath, or it finds nothing. ([detekt coroutines ruleset](https://detekt.dev/docs/rules/coroutines/), [PR #5666](https://github.com/detekt/detekt/pull/5666))

3. **Carry `statusCode`, `responseHeaders`, `responseBody`, `url`, `cause`, and `isRetryable` on every transport error.** Let the retry layer branch on machine-readable fields, never substring-match a message. *Exemplar:* `APICallError` and the single shared `requestJson`/`streamSse` pipeline every provider funnels through (`HttpTransport.kt:91`; consumer `AlibabaProvider.kt:315`).

4. **Classify retryable vs terminal by status, not by retrying everything.** Retryable: 408, 409, 425, 429, plus 5xx and transport errors. Terminal: 400/401/403/404/422 — fail fast. Note that a blanket `statusCode >= 500` rule also retries `501 Not Implemented` and `505 HTTP Version Not Supported`, which are terminal in practice — this SDK treats **all 5xx as retryable by design**, leaning on the max-attempt cap + jittered backoff as the backstop rather than carving out the two terminal 5xx codes. If you tighten this, exclude `501`/`505` explicitly. *Exemplar:* `APICallError.isRetryable` (`408 || 409 || 429 || statusCode >= 500`) is the single source of truth — and is the place to add `425` and any 5xx carve-out.

5. **Honor `Retry-After` as a floor before exponential backoff.** Parse delta-seconds, HTTP-date, and `retry-after-ms`; fall back to backoff only when absent; clamp to a ceiling. The server knows its window better than any client algorithm.

6. **Use capped exponential backoff *with* full jitter, plus a max-attempt cap and overall deadline.** `random(0, min(maxDelay, base * 2^n))`. Jitter-free backoff synchronizes failed clients into retry spikes (thundering herd). ([AWS retry](https://docs.aws.amazon.com/sdkref/latest/guide/feature-retry-behavior.html))

7. **Let the caller own the idempotency key; reuse it across retries.** A retried non-idempotent POST that succeeded server-side but failed in transit double-executes without a stable `Idempotency-Key`. The retry layer must never mint a fresh key per attempt.

8. **Validate non-2xx *and* suspicious 2xx bodies at the client boundary.** `expectSuccess = true` + `HttpResponseValidator` to map Ktor exceptions into typed `APICallError`; `validateResponse {}` to reject 200-OK responses carrying an error code. Many LLM APIs return 200 with an error payload.

9. **Set a per-attempt timeout distinct from the overall deadline.** Explicit `requestTimeoutMillis`/`connectTimeoutMillis`/`socketTimeoutMillis`; remember N retries can take (N+1)×timeout + backoff, so bound the whole op with a separate `withTimeout`.

10. **Preserve the cause chain; surface all collected failures on retry exhaustion.** Always pass `cause = original` when wrapping; expose the full `errors` list (and a `RetryErrorReason`) rather than only the last. *Exemplar:* `JSONParseError`/`ToolCallRepairError`/`RetryError` pass `cause`. `StreamEvent.Error` carries a `@Transient cause: Throwable?` populated at the emit site so in-process consumers branch on the typed cause while the wire stays a stable `String` (`Streaming.kt:287`). `AbortError extends CancellationException` so abort participates in structured cancellation natively (`AbortSignal.kt:124`).

11. **Don't return a mock/default on failure.** A canned fallback converts an outage into fake success — monitoring stays green, users get wrong output. Propagate a typed error unless the fallback is an explicit, documented product decision.

12. **Don't rely on a global `CoroutineExceptionHandler` as the error path.** It only fires for uncaught root failures, can't recover, and is ignored by `async`. Handle expected failures at the call site; always `await()` `async` results.

---

## Rejection Framework

These are blocking review findings. Each row: the smell, why it's rejected, what to do instead, and how to catch it mechanically. Where no static check exists, the row says so plainly — a grep that can't actually catch the smell is worse than an honest "manual review."

### API Design & Compatibility

| Smell | Why rejected | Do instead | How to detect |
|---|---|---|---|
| Public fn/prop with **inferred** type (`fun build() = Impl()`) | Compiled signature pins to the inferred type; a body edit silently breaks compiled clients (`NoSuchMethodError`) | Explicit return/property type on every public decl | `explicitApi()` strict errors on it; detekt `LibraryCodeMustSpecifyReturnType` |
| New public `data class` for an evolvable model | Added/reordered property breaks constructor/`copy()`/`componentN()` (binary + behavioral) | Regular class/interface; or freeze + add via overloads | `grep -nE 'public data class' src/commonMain`; ABI dump diff on `copy`/ctor |
| Param added to an existing public fn (even defaulted), or `@JvmOverloads` as the "fix" | JVM descriptor changes → `NoSuchMethodError` for compiled Kotlin callers (they bind to the moved `$default` synthetic) | New overload, keep original signature | `checkKotlinAbi` fails; review any edit to a public param list |
| Switching `-jvm-default=enable`→`no-compatibility` after release | Drops `DefaultImpls` from the ABI → `NoSuchMethodError`/`AbstractMethodError` for old consumers; invisible in source | Keep `enable` post-1.0; opt out per-interface with `@JvmDefaultWithoutCompatibility` | ABI dump diff shows `DefaultImpls` disappearing; review any change to the `jvmDefault`/`-jvm-default` setting |
| Removing/narrowing/`@RequiresOptIn`-hiding a public decl in a minor | Source+binary break, no migration path; opt-in is for staging not retiring | `@Deprecated(replaceWith=...)` WARNING→ERROR→HIDDEN over minors; remove in major | ABI dump diff fails on disappearance without a prior `@Deprecated` release |
| `@PublishedApi internal` treated as private and freely changed | Inlined into client bytecode = public ABI | Freeze its signature; ABI-review it like public | `grep -rn '@PublishedApi' src` and audit each against the dump |
| Mutable/array type across the public boundary (`MutableList`, `Array<String>`) | Callers mutate library state; not thread-safe | Read-only `List`/`Set`/`Map`; defensive copy if an array is unavoidable | `grep -rnE ': (Mutable(List|Set|Map)|Array)<' src/commonMain` (catches explicit mutable types; `explicitApi()` does **not** flag these once the type is written) |
| Public-ABI type whose dependency is declared `implementation()` not `api()` | POM scopes the dep `runtime`; consumer can't resolve the type at compile time → broken downstream build | Declare the dep `api()` | Cross-check ABI-dump types against POM `compile`-scope deps |

### KMP Structure

| Smell | Why rejected | Do instead | How to detect |
|---|---|---|---|
| Manual `iosMain.dependsOn(commonMain)` etc. | Disables automatic default-hierarchy-template application for those sets (warning + lost accessors) | Use generated accessors; call `applyDefaultHierarchyTemplate()` before any additive edge | `grep -rn 'dependsOn(' **/build.gradle.kts`; CI greps the build log for "Default Kotlin Hierarchy Template was not applied" |
| Platform symbol in `commonMain` (`java.*`, `android.*`, `platform.Foundation.*`, `System.*`) | Fails on Native/JS; in a single-JVM module it compiles now and breaks when iOS is added | `expect`/`actual` or an interface | `grep -rnE 'java\.|android\.|platform\.(Foundation|UIKit)|System\.(getProperty|currentTimeMillis)' src/commonMain` |
| `expect class` where an interface + factory suffices | One impl per platform, no test fakes, relies on Beta | interface in common + `expect fun buildX(): TheInterface` | `grep -rn 'expect class' src/commonMain` |
| Runtime OS branching in common (`if (Platform.isIOS)`) | Defeats compile-time separation; dead branches per target | `expect`/`actual` or injected interface | `grep -rnE 'Platform\.(is|current)' src/commonMain` |
| JVM/Apple-only dep in `commonMain { dependencies }` | Breaks non-JVM target resolution | Move to `jvmMain`/`androidMain`/`appleMain` | Review block vs each lib's KMP targets; build a Native target in CI |
| Duplicated `actual` in both `iosArm64Main` and `iosSimulatorArm64Main` | Doubles maintenance, risks device/sim drift | Single `actual` in `iosMain` | `grep -rn 'actual ' src/iosArm64Main src/iosSimulatorArm64Main` |

### Publishing & iOS Interop

| Smell | Why rejected | Do instead | How to detect |
|---|---|---|---|
| Empty/stub `-javadoc` jar | Passes Central, gives consumers zero docs, breaks javadoc.io | Dokka 2.x (V2) HTML javadoc jar | `grep -rn 'archiveClassifier.*javadoc'`; confirm `org.jetbrains.dokka` applied |
| Publishing targets from different CI hosts | Duplicate root publications; Central rejects | One `publish` on `macOS-latest` | Review workflow for >1 job running a `publish` task |
| Public-ABI dep declared `implementation()` | Consumer POM omits it from compile scope → unresolvable type | `api()` for any dep in the public signature | ABI-dump type cross-check vs POM scopes |
| SKIE applied in a non-framework module | Silently no-ops; Flow/suspend/sealed never reach Swift | Apply in the `framework {}`/`native.cocoapods` module | `grep -L 'framework\|native.cocoapods'` on any file applying `co.touchlab.skie` |
| Fat/universal framework for distribution | App Store rejects simulator slices | `XCFramework(name)` | `grep` for `binaries { framework` without surrounding `XCFramework(...)`; `lipo`/strip steps |
| Swift export as the sole iOS surface with sealed/generics/open types | Alpha (2.4); unsupported/type-erased → wrong Swift API | ObjC bridge + SKIE; Swift export behind a flag | `grep 'swiftExport {'` on a stable module |

### Coroutines & Flow

| Smell | Why rejected | Do instead | How to detect |
|---|---|---|---|
| `withContext(ctx){ emit(x) }` / `launch(ctx){ emit }` in a `flow {}` | Violates context preservation → `IllegalStateException` at runtime | `.flowOn(ctx)` as the last operator | **No reliable static check** — `grep emit(` can't tell a legal top-level emit from a nested one and noises on every flow. Focused review of `emit(` appearing inside a `withContext(`/`launch(`/`async(` lambda within a `flow{}`; the real guard is a `runTest` collection test that exercises the flow |
| `catch (e: Exception/Throwable)` without rethrowing `CancellationException` | Semi-cancelled coroutine, leaked work, broken propagation | `ensureActive()`/`throw e` first, or catch narrow types | detekt `SwallowedException`/`TooGenericExceptionCaught` (CancellationException not ignored) |
| `runCatching { someSuspendCall() }` | Captures `CancellationException` into `Result.failure` | `runSuspendCatching` or try/catch + `ensureActive()` | detekt `SuspendFunSwallowedCancellation` (set `active: true`; needs type resolution) — see [§9.2](#9-error-handling--resilience) |
| `callbackFlow {}` / `channelFlow {}` without `awaitClose` | Channel closes early (`IllegalStateException`) or callback leaks | `awaitClose { api.unregister(cb) }` as the final statement | Every `callbackFlow {`/`channelFlow {` must contain `awaitClose(` |
| `GlobalScope.launch/async` for SDK work | No parent, no cancellation, untestable, lives forever | Inject a `SupervisorJob`-backed scope tied to `close()` | detekt `GlobalCoroutineUsage`; `grep 'GlobalScope\.'` |
| `suspend fun foo(): Flow<T>` or eager I/O before returning the Flow | Breaks cold-Flow contract; hidden work at call time | Non-suspend fn returning `flow { }` | detekt `SuspendFunWithFlowReturnType`; `grep 'suspend fun .*: *Flow<'` |
| `Thread.sleep` / blocking call / `runBlocking` inside a suspend fn | Freezes `Main`, starves shared pools | `delay()`; `withContext(ioDispatcher)`; never `runBlocking` in lib code | detekt `SleepInsteadOfDelay`; `grep 'runBlocking'` in `*Main` |
| `synchronized`/`ReentrantLock` for a critical section that suspends, or in `commonMain` | Blocks a pooled thread across suspension; no `actual` on Native/JS | `Mutex.withLock` (suspend section) or atomicfu CAS (lock-free) | `grep -rn 'synchronized(\|ReentrantLock' src/commonMain` |
| Unbounded provider I/O concurrency; `newFixedThreadPoolContext` for a pool | Connection blow-up under load; dedicated pool wastes idle threads | One reused `Dispatchers.IO.limitedParallelism(n)` view in a `val` | `grep 'newFixedThreadPoolContext'`; `grep 'limitedParallelism(' ` called per-request rather than once |
| `shareIn`/`stateIn` in a getter or per-request fn | New upstream coroutine + connection each call | Call once into a stable `val` | `grep -E '(shareIn|stateIn)\(' ` inside `get()`/non-init bodies |
| Detaching from parent via `withContext(Job())` / `launch(Job())` | Severs cancellation; child outlives caller | Don't pass a `Job`; new scopes use `SupervisorJob() + dispatcher` | `grep 'withContext(\s*Job()'`, `'launch(\s*Job()'` |

### Serialization

| Smell | Why rejected | Do instead | How to detect |
|---|---|---|---|
| `Json { }` inline at a call site (response handler) | Discards per-class descriptor cache on every chunk | One top-level/`object` `val`, injected | ast-grep `Json { $$$ }` not assigned to a top-level property |
| Polymorphic subtype with no `@SerialName` (default FQCN discriminator) | Wire coupled to package/class names; rename = breaking | Explicit unique `@SerialName` per subclass | Lint/review: every `@Serializable` sealed subtype carries `@SerialName` |
| Global `isLenient`/`coerceInputValues = true` | Accepts non-RFC JSON / silently substitutes defaults, masking contract bugs | Fix the payload; model fields explicitly; scope tolerance narrowly | `grep 'isLenient = true'`, `'coerceInputValues = true'` |
| `explicitNulls = false` codec used on an encode→decode round-trip path | Null → default decode makes the round-trip non-idempotent | `explicitNulls=false` codec is decode-only; route encode through the `explicitNulls=true` codec | `grep 'explicitNulls = false'` to find the codec, then grep its identifier for any `encodeToString(`/`encodeToJsonElement(` call site (decode-only is the invariant) |
| Global `ignoreUnknownKeys = true` (incl. request/strict models) | Typos + drift silently dropped everywhere | `@JsonIgnoreUnknownKeys` per inbound class (and each nested class) | `grep 'ignoreUnknownKeys = true'` at builder level |
| Custom `KSerializer` reusing a delegate's `.descriptor` or with kind/elements ≠ encode/decode calls | Misleads schema tooling; unspecified behavior | `SerialDescriptor("ai.torad.…", delegate.descriptor)`; mirror calls | Review every `KSerializer` `descriptor` assignment |

### Runtime Performance

| Smell | Why rejected | Do instead | How to detect |
|---|---|---|---|
| `data class Id(val v: String)` / bare `String` for high-frequency ids | Heap allocation + no type safety | `@JvmInline value class` with `init` | `grep -E 'data class \w+\((val\|public val) \w+: (String\|Int\|Long)\)$'` |
| Value class as `List<Id>` element / `<T>` arg / `Id?` on a hot path | Boxes back to an object — undoes the win | Pass concrete type or extract `.value` at the boundary | **No static rule** (boxing is codegen, not source). Manual review of the boxing rules against hot files, backed by a `kotlinx-benchmark` allocation test on the streaming path |
| Re-decoding the whole stream per chunk (`decodeToString`/`bodyAsText`/`split("\n")`) | O(n²) re-scan + transient garbage | Incremental framing over a retained buffer | `grep -rn 'decodeToString(\|bodyAsText(\|.split("\\n")'` in transport |
| `acc += delta` string concat in the per-token loop | O(n²) allocation, GC pressure | per-part `StringBuilder`, materialize once | `grep` `'\w+ += '` on a `String` accumulator inside a `collect{}`/loop |
| Reflexive `.asSequence()` on small/single-step collections | Iterator overhead exceeds the laziness benefit | Plain `List`/`Iterable` operators | `grep -rn '.asSequence()'`; review collection size + chain length |
| `lazy(LazyThreadSafetyMode.NONE)` on state a public getter exposes | Data race across caller threads (no happens-before) | `NONE` only for thread-confined/construction-time state | `grep -rn 'LazyThreadSafetyMode.NONE'`; review the lazy value's thread confinement |
| `kotlin-reflect` dep or runtime reflection in the request path | Defeats R8, forces consumer keep-rules, unsupported on Native | Plugin + `reified` helpers | `grep` build files for `kotlin-reflect`; source for `::class.members\|Class.forName` |

### Build / Tooling / Testing

| Smell | Why rejected | Do instead | How to detect |
|---|---|---|---|
| `System.currentTimeMillis()`/`Clock.System.now()`/`Random.Default`/`Dispatchers.IO` inside an SDK class | Nondeterministic, untestable, delays don't skip | Inject `Clock`/`Random`/dispatcher with prod defaults | `grep -RnE 'currentTimeMillis\(\|Random.Default\|Dispatchers.(IO\|Default\|Main)'` in non-DI code |
| `.toList()`/`.collect {}` on a hot/infinite flow under `runTest` | `runTest` hangs then times out | Collect in `backgroundScope` / Turbine `testIn(backgroundScope)` | `grep` for `.toList()`/`.collect {` inside `runTest` on non-finite flows |
| Real network engine (`HttpClient(CIO/OkHttp/Darwin)`) in unit tests | Slow, flaky, doesn't exercise error/retry deterministically | Ktor `MockEngine` sharing prod config | `grep -RnE 'HttpClient\((CIO\|OkHttp\|Java\|Darwin)'` in test sources |
| `updateKotlinAbi`/`apiDump` run reflexively to go green | Silently blesses a real ABI break | Read the diff; update only on intentional, versioned change (compiler-bump churn excepted, called out in the PR) | CI: fail PR if `api/*.api` changed without a changelog entry; never auto-run in CI |
| detekt `autoCorrect = true` / `--auto-correct` in the CI gate | No-op without `buildUponDefaultConfig`; can fix-then-fail | `autoCorrect = false` in CI; auto-correct local only | `grep -RnE 'autoCorrect\s*=\s*true'`; check CI detekt invocation |
| `SuspendFunSwallowedCancellation` left `active: false`, or detekt run without type resolution | The flagship cancellation detector silently matches nothing | `active: true` + run the typed `detektMain`-style tasks with a classpath | Confirm `coroutines.SuspendFunSwallowedCancellation.active: true` in `detekt.yml`; confirm CI runs the classpath-aware task |
| detekt baseline carried with no burn-down target | Suppression ledger masquerading as a standard | Treat baseline as a temporary scaffold with a committed burn-down | Baseline entry count must trend down release-over-release |
| Expensive/I/O work in the Gradle configuration phase | Breaks config-cache reuse | Move into task actions; model inputs as task props / Build Service | `./gradlew --configuration-cache` with problems=fail |
| Hand-asserting individual JSON fields for serialized models | Verbose; misses default/nullability/discriminator drift | Golden/approval test through real `encodeToString()` | Review: serialization tests reference committed golden files |

### Ergonomics & Type Safety

| Smell | Why rejected | Do instead | How to detect |
|---|---|---|---|
| Nested receiver builders with no `@DslMarker` | Inner block can silently call an outer receiver → wrong config that compiles | One marker on the shared supertype | Every `Builder.() -> Unit` entry point traces to a `@DslMarker` supertype |
| Sentinel values for "absent"/"default" (`-1`, `""`, `"AUTO"`) | Indistinguishable from real values; defeats null-checking | Nullable type + default param | `grep -rn '= -1\|Int.MIN_VALUE\|"AUTO"\|"NONE"'`; review "pass X to mean unset" |
| Positional behavior-switching `Boolean` params | Unreadable at call site; can't grow a third mode without a break | Named functions or enum mode | detekt `BooleanPropertyNaming`; `grep` public fns with ≥2 `: Boolean` |
| `throw IllegalStateException/RuntimeException(msg)` for expected failures; exceptions as control flow | Callers substring-match messages; fragments the error channel | Sealed error/result hierarchy; null/Result for recoverable | `grep -rn 'throw IllegalStateException\|throw RuntimeException('` in commonMain |
| `sealed` on a hierarchy you'll keep extending, or a growable extension point left plain `open` | Added variant/member breaks clients silently | `@SubclassOptInRequired` for client-extensible; sealed only for truly closed | Review each public `sealed`/`open`: "will I add post-1.0?" |
| `===`/`AtomicReference` over a value class, or multi-field value classes treated as stable | Boxing makes identity meaningless; multi-field value classes are still an unstable design (KEEP discussion #472) | Compare with `==`; `data class` for composite values until the design stabilizes | `grep -rn '=== '` near value-class types; `AtomicReference<` of a value class |

### Error Handling & Resilience

| Smell | Why rejected | Do instead | How to detect |
|---|---|---|---|
| Empty / log-only catch (`catch (e) {}`) | Real error looks like success; may swallow cancellation | Map to a typed error and return/throw; `ensureActive()` first | detekt `EmptyCatchBlock`/`SwallowedException`; `grep` catches with no `throw`/typed return |
| `runCatching { someSuspendCall() }` in a suspend fn | Captures `CancellationException` into `Result.failure` | `runSuspendCatching` or try/catch + `ensureActive()` | detekt `SuspendFunSwallowedCancellation` (set `active: true`; needs type resolution) |
| Returning a mock/default on network/model failure | Masks outages; users get wrong output | Propagate a typed error | Review catch blocks wrapping `client.*`/model calls that `return` a non-error value |
| Retrying on every exception/status (4xx, validation, cancellation) | Burns quota, amplifies load, defeats cancellation | Gate on `isRetryable`; short-circuit `is CancellationException` | Retry predicate must reference `isRetryable` + rethrow cancellation |
| Fixed-delay / jitter-free backoff, no per-sleep or attempt cap | Thundering herd; unbounded wait | Full-jitter `random(0, min(maxDelay, base*2^n))` + caps + deadline | Backoff fn must include a random term, `min(...,cap)`, `maxRetries`, `withTimeout` |
| Wrapping an exception while dropping `cause` | Root failure vanishes from traces and `when` | Always `cause = original`; surface full `errors` on exhaustion | detekt `ThrowingExceptionsWithoutMessageOrCause`; review SDK-error ctor calls |
| Relying on global `CoroutineExceptionHandler`; un-awaited `async` | Only fires for uncaught root failures; `async` ignores it | Handle at call site; always `await()` | `grep 'async {'` whose result is never awaited |

---

## PR Review Checklist

Distilled, mechanical. A PR that touches the relevant area must satisfy these.

**Compatibility**
- [ ] Build passes `explicitApi()` strict; every new public decl has explicit visibility + return/property type.
- [ ] `checkKotlinAbi` is green; if `api/*.api` changed, the diff is intentional and noted in the PR (not silently `updateKotlinAbi`'d). Compiler-bump churn is called out as such.
- [ ] No new public `data class` for an evolvable model; no param added to an existing public fn.
- [ ] Public collections are read-only; no `Mutable*`/`Array` across the boundary.
- [ ] Any `@PublishedApi internal` change is treated as a public ABI change.
- [ ] `-jvm-default` stays `enable` (or per-interface `@JvmDefaultWithoutCompatibility`); no silent `no-compatibility` switch.
- [ ] Every dependency type in the public ABI is declared `api()`, not `implementation()`.

**KMP**
- [ ] No platform symbols in `commonMain`; no manual `dependsOn` edges (or template re-applied first); no single-platform dep in common.
- [ ] No `synchronized`/`ReentrantLock` in `commonMain`.
- [ ] CI builds + tests Apple targets (`verify-apple` leg green).

**Coroutines / Errors**
- [ ] Every broad catch rethrows `CancellationException` first; no `runCatching` around a suspend call (detekt `SuspendFunSwallowedCancellation` green).
- [ ] Streaming returns a non-suspend cold `Flow`; `.flowOn` (not `withContext`) sets emission context; flow exercised by a `runTest` collection test.
- [ ] No `GlobalScope`; injected scope/dispatcher; `callbackFlow` has `awaitClose`; shared I/O bounded by a reused `limitedParallelism(n)` view.
- [ ] No blocking call / `Thread.sleep` / `runBlocking` in a suspend fn.
- [ ] Retry gates on `isRetryable`, honors `Retry-After`, uses jittered capped backoff + deadline; errors carry `cause` + status/headers/body.

**Serialization**
- [ ] `Json` instances are reused, not per-call; polymorphic subtypes carry `@SerialName`; forward-compat via per-class `@JsonIgnoreUnknownKeys`; the `explicitNulls=false` codec is decode-only.

**Performance**
- [ ] Single-field ids are `@JvmInline value class`, kept unboxed on hot paths (allocation benchmark green if the streaming path changed); streamed text uses `StringBuilder`; SSE framed incrementally; no `kotlin-reflect`.

**Tooling / Testing**
- [ ] `Clock`/`Random`/dispatcher/engine injected; HTTP tested with `MockEngine`; coroutine tests use `runTest` + `backgroundScope`; wire contract covered by golden tests.
- [ ] detekt baseline not grown for avoidable findings and trending down; new code carries no new suppressions.

**Ergonomics**
- [ ] Builders are `@DslMarker`-scoped; optional config is nullable+default, not sentinel; no positional behavior `Boolean`; unstable surface gated by `@RequiresOptIn`.

---

## Deliberately Deferred / Non-Goals

These are conscious exceptions or known gaps, recorded so they aren't re-litigated silently in review or mistaken for oversights.

- **`ModelRef` stays a `data class`.** It has two fields and a frozen shape; the convenience (`copy`, destructuring) is worth it and the [data-class evolution rule](#1-api-design--backward-compatibility) §5 is waived *for this type only* on the condition that its property set never grows. New evolvable value types do not get this exception. (`ModelRef.kt`)
- **`@InternalAiSdkApi` exclusion filter is forward-looking.** Nothing carries the annotation today — internal plumbing uses the `internal` keyword (already absent from the ABI). The filter is a guard for future `@PublishedApi`-style internal-but-public declarations (`build.gradle.kts`).
- **`kotlin.uuid.Uuid` (Stable in 2.4.0) not yet adopted.** `generateId` still samples a hand-rolled alphabet with injected `Random`. Migrating to the cross-platform `Uuid` is planned but not gating.
- **No `wasmJs`/`js` target yet.** For a port of a JS-native SDK the browser is the most defensible next target and the streaming/Flow core is platform-agnostic; it's a roadmap item, not a regression. Current targets: `jvm`/`android`/`ios`.
- **iOS interop is raw ObjC bridge.** SKIE is the recommended next step (§3.7); until it lands, the iOS DX is knowingly below the Android DX.
- **Maven Central not wired** (only GitHub Packages). The POM/javadoc exist; the umbrella publication + a `vanniktech`-style convention are the remaining work (§3.6).
- **Framework-adapter shims** (react/vue/svelte/angular/rsc) ship as `@ExperimentalAiSdkApi`-gated public Kotlin on all targets. Whether a Kotlin SDK should carry JS-ecosystem adapters at all is an open product decision — they add surface and a maintenance tail with no Kotlin-native consumer.
- **Coverage measured, not enforced** (§7.9); **detekt baseline (~3133 entries)** ratchets new code only. Both are tracked debt with a burn-down commitment, **not** accepted steady state — the baseline is a migration scaffold (§7.4), and a release that lets the count climb is itself a finding.
- **`SuspendFunSwallowedCancellation` is enabled but inert.** `detekt.yml` sets it `active: true`, but our detekt runs classpath-less (detekt 1.23.x can't type-resolve KMP non-JVM source sets, detekt#5961), and this rule needs type resolution — so it currently matches nothing. The synchronous `SwallowedException` guard (`CancellationException` not in `ignoredExceptionTypes`) + the `runTest` cancellation tests + review hold the line. Wiring a typed, classpath-aware detekt task to make the rule actually fire is the open item (§7.4) — kept enabled so it activates for free once that lands.
- **Multi-field value classes** are avoided on purpose: the design is still in flux — the ["Value Classes 2.0 / Better Immutability" KEEP discussion #472](https://github.com/Kotlin/KEEP/discussions/472) (with [Multi-field value classes — Issue #340](https://github.com/Kotlin/KEEP/issues/340), umbrella tracker [KT-23338](https://youtrack.jetbrains.com/issue/KT-23338)) is open as of mid-2026, so composite value-like types stay `data class` until it stabilizes.
- **No published `aisdk-testing` fixtures artifact.** Fakes like `MockLanguageModel` live in the main source tree; a supported test-doubles artifact for downstream consumers is a future addition.
