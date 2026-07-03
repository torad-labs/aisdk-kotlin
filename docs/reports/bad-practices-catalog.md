# torad-aisdk Anti-Pattern Catalog (TS→Kotlin residue audit)

## Executive summary

This catalog consolidates 26 adversarially-verified findings across 6 dimensions in the published KMP library `ai.torad:torad-aisdk` (0.3.0-alpha01, `explicitApi()` ON, ABI tracked in `api/*.api`, targets jvm/ios/linux/wasm). The unifying root cause is **mechanical TypeScript→Kotlin translation**: option-bags (`Partial<T>`), `Record<string,unknown>` (`Map<String,JsonElement>`), npm default-exports (top-level throwing singletons), React/Vue/Svelte hook topology, and string-union types (`typealias X = String`) were preserved verbatim instead of being re-expressed with Kotlin's type system.

The findings violate Marcos's tenets in three recurring ways:
- **T5 (make illegal states unrepresentable):** nullable option-bags with cross-field `require()` invariants, sentinel fabrication (`?: JsonNull`, `?: ""`, `base64 = ""`), enum-gated nullable payload bags, `null` meaning both "absent" and "don't override".
- **T1/T2 (sealed source of truth, wire ≠ domain):** flat discriminated unions, `Any?` erasure where generics already exist, `Map<String,JsonElement>` as the public provider-config and result-metadata surface, `sealed interface` where a `sealed class` with shared payload is correct.
- **No mutable global/singleton state:** ~48 throw-on-use provider singletons, a process-global mutable telemetry registry, and a shared `gateway` singleton with a non-synchronized metadata cache (a genuine data race).

### Prioritized fix order

**Phase 0 — correctness / behavior (do first, mostly ABI-safe):**
1. `GLOBAL-3` gateway metadata-cache data race → `Mutex` + single-flight (ABI-safe).
2. `FW-4` `StreamableValueController.error()` throws in the producer instead of propagating to collectors (subsumed by the rsc deletion in `FW-1/FW-2`, or fixed via a sealed event type if rsc survives).

**Phase 1 — ABI-additive deprecations & doc clarifications (ship now, no ABI break):**
3. `NOB-1` add `GenerationInput`-first required-param overloads to `generateText/streamText/Agent.*`; `@Deprecated` the nullable pair.
4. `PO-2` `@Deprecated` the 85 `typealias …ModelId = String` **and** the `invoke(String)` entrypoints; add `ModelId` overloads.
5. `GLOBAL-1` Phase 1 `@Deprecated(WARNING)` every throwing provider singleton.
6. `GLOBAL-2` Phase 1 inject a `TelemetryRegistry` into `resolveTelemetry`/Agent; `@Deprecated` the globals.
7. `NOB-4` items 1 & 3 (doc the intentional default-application; KDoc the metadata merge).

**Phase 2 — API boundary cleanups, batched as ONE ABI rebaseline before the 1.0 freeze** (regenerate `api/jvm/torad-aisdk.api` + `api/torad-aisdk.klib.api` once):
8. Framework-package removal: `FW-1`/`FW-2`/`FW-3`/`FW-6` delete `react`/`rsc`/`svelte`/`vue`/`angular`, the parity test, and the `rewriteFrameworkImports` codemod; relocate the Flow-native streamable primitives into `ui` if a consumer exists.
9. `PO-4` delete dead `QuiverAIOperation = String`, add the `enum`.
10. Sealed-modeling batch (touch `UIMessagePart`, `ModelMessage`, `ToolLoopAgentEngine`): `SEAL-01` ToolUI sealed `State`, `SEAL-05` unify `ToolUI`/`DynamicToolUI`, `SEAL-04` `ToolLoopAgentState` `Phase`, `SEAL-06` `File/Image/Source` data-shape sealing, `SEAL-03` `sealed interface`→`sealed class`.
11. Primitive-obsession batch: `PO-1` `ToolCallId`/`ToolName`/`ApprovalId` value classes; `PO-3` `ModelId`/`ProviderId` on the core abstraction.
12. `SEAL-02` generify lifecycle event payloads off `Any?`.
13. Nullable-bag finishers: `NOB-2` make `Input` the sole source on `TextGenerationRequest`; `NOB-3` `Override` sealed type for force-clearable settings; `NOB-4` item 2 (`providerOptions` non-null).
14. Raw-JSON batch (additive where possible, but type changes break ABI): `raw-json-03` typed `ProviderToolInfo`, `raw-json-02` typed hosted-tool config, `raw-json-01`/`raw-json-04` typed provider options/metadata via per-provider serializers + `ProviderMetadata` value class.

GLOBAL-2/raw-json-* are read/write boundary work — schedule after the structural sealing lands so types settle once.

---

## Dimension 1 — TS framework packages in a KMP library

### FW-1 — Five web-framework packages as public KMP API (high) — T8/T6/T2
- Occurrences: 5 · Evidence: `react/React.kt:75`, `angular/Angular.kt:5`, `svelte/Svelte.kt:7`
- **Bad:** packages named `react/rsc/svelte/vue/angular` holding React/Vue hook vocabulary (`useChat`, `useCompletion`, `experimental_useObject`, `useAIState`) as first-class API of a jvm/ios/linux/wasm library. The hook naming is meaningless without a render cycle; `svelte/vue/angular` are typealias-only re-exports, `react/rsc` are full classes in the ABI.
```kotlin
public fun useChat(options: UseChatOptions = UseChatOptions()): UseChatHelpers // thin wrapper over ui.Chat
public typealias Chat = ai.torad.aisdk.ui.Chat // entire angular package
```
- **Solution:** collapse to one idiomatic `ai.torad.aisdk.ui` surface. Delete `svelte/vue/angular` wholesale; delete the React free functions + `*Helpers`; handle `rsc` surgically (delete the RSC state vocabulary, relocate the Flow-native streamable primitives into `ui` if used); delete `FrameworkFacadeParityTest`; delete the `rewriteFrameworkImports` codemod (the orphan your change creates). Expose `ui.Chat` state as `StateFlow` (FW-5) so consumers get Flow-native reactivity directly; ship any TS-name familiarity in a separate `torad-aisdk-compat` artifact excluded from the stable ABI.
- **ABI:** breaks (sanctioned — surface is `@ExperimentalAiSdkApi`). Regenerate both dumps.

### FW-2 — `rsc/AIProvider` mutable React-state container (high) — no-mutable-shared-state/T4/T3
- Occurrences: 1 · Evidence: `rsc/Rsc.kt:117`, `rsc/Rsc.kt:142`, `api/jvm/torad-aisdk.api:12854`
- **Bad:** a plain-`var` container with public mutators, no synchronization, no Flow; plus seven free functions (`getAIState`/`getMutableAIState`/`useActions`/`useSyncUIState`…) that are pure identity aliases of the provider.
```kotlin
public var aiState: AI_STATE = initialAIState
    private set
public fun setAIState(value: AI_STATE) { aiState = value; onSetAIState?.invoke(value) }
```
- **Solution:** delete `Rsc.kt:111-163` (AIProvider + createAI/getAIState/getMutableAIState/useAIState/useUIState/useActions/useSyncUIState). The streaming primitive already exists as `Flow<UIMessage>`. A host that needs observable state owns its own `MutableStateFlow`. Drop the `createAI` use in `FrameworkFacadeParityTest`.
- **ABI:** breaks (experimental, clean removal).

### FW-3 — vue/svelte/angular duplicate react verbatim (medium) — DRY/T6
- Occurrences: 3 · Evidence: `vue/Vue.kt:27`, `api/jvm/torad-aisdk.api:13631`, `svelte/Svelte.kt:25`
- **Bad:** `vue.useCompletion`/`experimental_useObject` are byte-for-byte copies of react's and even return react's helper classes; svelte/angular are typealias re-exports; svelte's `AIContext` exposes raw public `MutableMap`s.
- **Solution:** delete all three files; bundle with FW-1. If a keyed registry is ever genuinely needed it belongs in `ui` as a concurrency-safe typed holder shipped when a consumer exists.
- **ABI:** breaks (experimental).

### FW-4 — `StreamableValueController.error()` bypasses the stream (medium) — T5/T4
- Occurrences: 1 · Evidence: `rsc/Rsc.kt:43` vs sibling `rsc/Rsc.kt:86`
- **Bad:** `error()` throws synchronously in the producer coroutine; collectors of `readStreamableValue(value)` never observe it. The sibling `StreamableUIController.error()` does the opposite (emits an Error part) — contradictory semantics from the same template.
```kotlin
public suspend fun error(throwable: Throwable) { throw throwable } // never reaches subscribers
```
- **Solution:** preferred — delete the rsc package (subsumes this). If it must survive, model the terminal signal as data (don't swap to a bare Channel — it breaks `replay=64` fan-out):
```kotlin
public sealed class StreamableValueEvent<out T> {
    public data class Update<T>(val value: T) : StreamableValueEvent<T>()
    public data class Error(val throwable: Throwable) : StreamableValueEvent<Nothing>()
    public data object Done : StreamableValueEvent<Nothing>()
}
// error() -> updates.emit(StreamableValueEvent.Error(throwable))
```
Add a test asserting a collector observes the error.
- **ABI:** breaks (experimental).

### FW-5 — react facades expose state as snapshot getters, not StateFlow (medium) — T4/T3
- Occurrences: 3 · Evidence: `react/React.kt:88`, `CompletionApi.kt:117`, `react/React.kt:113`
- **Bad:** `isLoading`/`error`/`completion` are val-getters reading a backing `var` (React useState port). A getter snapshot can never notify a collector.
```kotlin
public var loading: Boolean = false
    private set
public val isLoading: Boolean get() = completionState.loading // not observable
```
- **Solution:** replace the four independent vars on `Completion`/`StructuredObject` with one `StateFlow` over a sealed `CompletionState { Idle; Streaming(text); Done(text); Failed(text,cause) }`; emit per delta in `callCompletionApi`. These holders are **stable**, so add the new `StateFlow` additively and keep old vars as `@Deprecated` computed shims for one release.
- **ABI:** stable surface — do additively then remove next cycle.

### FW-6 — `UseChatHelpers` is a 1:1 passthrough of `ui.Chat` (low) — T6
- Occurrences: 1 · Evidence: `react/React.kt:47`, `react/React.kt:62`
- **Bad:** every method forwards verbatim to `ui.Chat`; even re-carries a `@Deprecated addToolResult` alias that never shipped on a stable surface.
- **Solution:** delete the react package per FW-1; provide the integration value the facade pretended to add by exposing `ui.Chat` state as `StateFlow` (FW-5). Do NOT migrate the `addToolResult` alias. Leave the `middleware` package untouched — it is legitimate `LanguageModelMiddleware`, not residue.
- **ABI:** breaks (experimental).

---

## Dimension 2 — Nullable option-bags & Partial<T> semantics

### NOB-1 — generation entry points are TS option-bags (high) — T5/T1
- Occurrences: 11 · Evidence: `Generate.kt:114`, `Generate.kt:20`, `Generate.kt:396`
- **Bad:** `prompt: String? = null, messages: List<ModelMessage> = emptyList()` with the cross-field "exactly one present" invariant enforced only by a runtime `require()`. `generateText(model)` type-checks and throws at runtime.
```kotlin
require(prompt != null || messages.isNotEmpty()) { "generateText: must provide either `prompt` or `messages`" }
```
- **Solution:** promote KotlinApi's existing `Input` design to a top-level `sealed class GenerationInput` (PromptText/MessageHistory/MessageHistoryWithPrompt + `NonEmptyMessages`). Add `input: GenerationInput` (non-defaulted, first) overloads to `generateText`/`streamText`/`Agent.generate`/`Agent.stream`; exhaustive `when`, no `require()`. `@Deprecated(WARNING)` the nullable overloads delegating via `GenerationInput.from(...)`; the `require` stays only in the shims. Place the new param before optionals to avoid resolution ambiguity.
- **ABI:** additive only (GAINS symbols, deprecated remain) → **breaksAbi=false**.

### NOB-2 — `TextGenerationRequest` is a half-finished fix with a throwing getter (high) — T5/T1
- Occurrences: 4 · Evidence: `KotlinApi.kt:125`, `KotlinApi.kt:142`, `KotlinApi.kt:207`
- **Bad:** the sealed `Input` was added but sits alongside the legacy nullable primary constructor; `TextGenerationRequest()` is constructable and `request.input` calls `Input.from(null, emptyList())` → throws.
```kotlin
public val input: Input get() = Input.from(prompt = prompt, messages = messages) // throws on empty
```
- **Solution:** make `input: Input` a **stored** val the canonical primary constructor; promote `Input` to a `sealed class`; the builder resolves to exactly one `Input` at `build()` (explicit `require`, never an accessor). Add the `Input`-primary ctor now; `@Deprecated(WARNING)` the nullable ctor for one cycle; schedule the data-class primary-ctor flip (changes `component1`/`copy`) as the deliberate break.
- **ABI:** breaks (`componentN`/`copy` signature change) — additive now, flip later.

### NOB-3 — `AgentSettings`/`StepSettings` are `Partial<Settings>` patches (high) — T5/T3
- Occurrences: 2 · Evidence: `Context.kt:36`, `Context.kt:85`, `ToolLoopAgent.kt:629`
- **Bad:** every field nullable, merged via `step ?: agent ?: default` chain; `null` means "inherit", so there is no representable "clear back to provider default" — if the agent set `temperature = 0.0`, a step cannot request the native default.
```kotlin
temperature = stepSettings.temperature ?: resolvedSettings.temperature ?: temperature
```
- **Solution:** add an `Override` sealed type (`Unchanged`/`Clear`/`Set(value)`) **only** on fields where forcing the provider default matters; keep the rest nullable-means-inherit with a doc. Replace the merge chains (629-649, 1861-1864) with an exhaustive `when`. Test that `Clear` forces `temperature` to `null`.
- **ABI:** breaks — additive or deliberate alpha break.

### NOB-4 — `CallSettings` flat nullable patch + sentinel unspool (medium) — T5/T3
- Occurrences: 3 · Evidence: `KotlinApi.kt:21`, `KotlinApi.kt:409`, `KotlinApi.kt:316`
- **Bad:** all-nullable fields, `merge` picks `other.x ?: this.x`, and the call boundary fabricates sentinels (`stopSequences ?: emptyList()`, `abortSignal ?: AbortSignalNever`, `responseFormat ?: ResponseFormat.Text`).
- **Solution:** items 1 & 3 now (ABI-safe): rewrite the apologetic comment into an intentional contract (null = provider default; no clear semantics for a one-shot call; merge is override-only) and mark the sentinels as **genuine SDK-wide defaults** (not T5 fabrication); add KDoc to `LanguageModelResponseMetadata.merge`. Item 2 (standardize `providerOptions` on non-null `emptyMap()`, migrate `Context.kt:41/:91`) gate behind the NOB-3 breaking window. Do NOT introduce `Override<T>` here.
- **ABI:** items 1&3 **breaksAbi=false**; item 2 deferred to the break.

---

## Dimension 3 — Sealed modeling: interface-vs-class, flat unions, Any? erasure

### SEAL-01 — `UIMessagePart.ToolUI` flat discriminated union (high) — T1/T5
- Occurrences: 2 · Evidence: `ui/UIMessagePart.kt:72`, `ui/ToolCallState.kt:31`, `ui/ConvertToModelMessages.kt:251`
- **Bad:** a 7-state enum gates 5 independently-nullable payload fields. `ToolUI(state=OutputAvailable, output=null)` and `ToolUI(state=InputStreaming, output=set)` both compile; consumers paper the gap with `output ?: JsonNull`.
```kotlin
ToolCallState.OutputAvailable -> ContentPart.ToolResult(toolCallId, toolName, output = output ?: JsonNull)
```
- **Solution:** replace the flat fields on `ToolUI`/`DynamicToolUI` with one shared nested `sealed class State` mirroring `OnToolCallFinishEvent.Outcome` (variants 1:1 with the 7 states); exhaustive `when`; drop the `JsonNull` sentinels at 244/251/263. Write a custom `KSerializer` to preserve the v6 flat wire shape or document the wire break.
- **ABI:** breaks — batch with SEAL-05.

### SEAL-02 — lifecycle hook events erase to `Any?` (high) — T1/T5
- Occurrences: 4 · Evidence: `Lifecycle.kt:17`, `Lifecycle.kt:57`, `Lifecycle.kt:152`
- **Bad:** `options: Any?`, `finalOutput: Any?`, `experimental_context: Any?`, `StepResult.experimentalContext: Any?` with a "consumers cast to their TContext" comment, despite `Agent<TContext,TOutput>` already carrying the exact types.
- **Solution:** generify the event payloads with `out` variance (`OnFinishEvent<out TOutput, out TContext>`); keep the telemetry sink erased via star-projection (`*`). Removes the cast instruction.
- **ABI:** breaks — batch with the sealed-modeling rebaseline.

### SEAL-03 — core unions are `sealed interface`, not `sealed class` (medium) — T1
- Occurrences: 3 · Evidence: `ModelMessage.kt:68`, `Streaming.kt:48`, `ui/UIMessagePart.kt:51`
- **Bad:** top-level unions declared `sealed interface` (Marcos prefers `sealed class`), no multi-hierarchy need, and `providerMetadata` is copy-pasted on ~30 variants.
- **Solution:** convert each to `sealed class`, keeping all `@SerialName`/`@JsonClassDiscriminator` tags byte-stable. **Do NOT blanket-hoist `providerMetadata`** — it is intentionally absent on control/terminal variants (Abort/Error/Raw/StreamStart/Finish…); forcing an abstract base property fabricates a meaningless null (T5). Ship the interface→class change alone; if polymorphic access is needed use an intermediate `WithMetadata` subclass that only metadata-carrying variants extend.
- **ABI:** breaks (supertype kind) — surface the binary-compat cost to Marcos before doing even this style-only change.

### SEAL-04 — `ToolLoopAgentState` boolean-flag phase (medium) — T1/T3/T5
- Occurrences: 1 · Evidence: `ToolLoopAgentEngine.kt:29/31/33`
- **Bad:** `isStreaming`/`isModelLoading` orthogonal booleans + nullable `error` admit contradictory states; a ViewModel must reconstruct the phase from flag arithmetic.
- **Solution:** replace with a `sealed class Phase { Idle; Streaming; Paused; Completed; Failed(error) }`, fold each variant's data in; keep only `messages`, `totalSteps`, `phase` flat. `isModelLoading` is dead — drop it. Also flag `ToolLoopAgentAction` (line 45) is a `sealed interface` against T1.
- **ABI:** breaks — acceptable at alpha.

### SEAL-05 — `DynamicToolUI` duplicates the entire `ToolUI` flat union (medium) — T1/T6/T5
- Occurrences: 1 · Evidence: `ui/UIMessagePart.kt:199`, `ui/UIMessagePart.kt:246`
- **Bad:** two full nullable-flat copies differing only by a `dynamic` axis + approval fields, each with its own `outputAs/inputAs` (4 near-identical helpers).
- **Solution:** sealify, don't flatten — `sealed class ToolUI` with `Static(…, approvalId, signature)` and `Dynamic(…)` subclasses carrying the shared base payload; one pair of helpers on the base; delete `dynamicOutputAs`/`dynamicInputAs`. `approvalId`/`signature` stay unrepresentable on `Dynamic`. Do in the SAME PR as SEAL-01. Do NOT ship a `dynamic: Boolean` flag.
- **ABI:** breaks (ToolUI no longer a constructible data class) — clean break + CHANGELOG migration snippet.

### SEAL-06 — `File/Image/Source` encode data-shape via sentinel + nullable bag (medium) — T5/T1
- Occurrences: 3 · Evidence: `ModelMessage.kt:184`, `ModelMessage.kt:212`, `ModelMessage.kt:163`
- **Bad:** `base64: String = ""` (empty-string = absent) alongside `url: String? = null`, so `File(base64="", url=null)` and `File(base64=set, url=set)` both compile; `Source` gates url/mediaType/filename by a `sourceType` enum with all fields nullable.
```kotlin
public data class File(val mediaType: String, val base64: String = "", val url: String? = null) : ContentPart
```
- **Solution:** model the data-shape as a nested closed polymorphic `sealed class FileData { Inline(base64); Remote(url) }` reused by `File`/`Image`; split `Source` into `Url`/`Document` subclasses (deletes the enum; `@SerialName` carries the discriminator). Keep `@Deprecated` computed shims (`base64`/`url`) + a `@Deprecated` secondary ctor for one release; bump the wire version with a deserialization migration. Ride `StreamEvent.SourcePart` along in the same PR.
- **ABI:** breaks (binary + wire) — soften with shims.

---

## Dimension 4 — String typealiases & primitive obsession

### PO-1 — tool-correlation ids are raw swappable Strings (high) — T5/value-class-over-typealias
- Occurrences: 118 · Evidence: `ModelMessage.kt:31`, `ModelMessage.kt:33`, `ModelMessage.kt:113`
- **Bad:** 54 `toolCallId: String`, 44 `toolName: String`, 20 `approvalId: String`; factories pass `(toolCallId, toolName)` positionally — swapping id and name compiles and corrupts correlation.
- **Solution:** introduce three `@JvmInline @Serializable value class` ids (`ToolCallId`/`ToolName`/`ApprovalId`) with `init { require(value.isNotBlank()) }`; thread through every id site in one pass. Add `@Serializable` **explicitly** (unlike `ModelRef`'s value classes, these cross the wire — a serializable single-String value class encodes to a bare JSON string; lock with a round-trip test). Convert `ToolExecutionContext.toolCallId` too. Do all three together (they co-occur). Follow-up: the `approvalId ?: toolCallId` default is a T5 null-means-two-things smell.
- **ABI:** breaks (mangled getters) — gate behind a bump; no shim feasible (field types change).

### PO-2 — 85 `typealias …ModelId = String` coexist with the existing `ModelId` value class (medium) — value-class-over-typealias/T6
- Occurrences: 85 · Evidence: `ModelRef.kt:16`, `Gateway.kt:9`, `providers/AnthropicProvider.kt:32`
- **Bad:** a validated `@JvmInline value class ModelId` exists but every provider's `invoke`/`chat`/`languageModel` uses a `typealias …ModelId = String` that adds zero type separation.
- **Solution:** `@Deprecated(ReplaceWith("ModelId"))` the aliases AND, crucially, the `invoke(String)` entrypoints (bare-literal calls resolve to the String overload, never the alias — the deprecation must sit on the overload that drives migration); add `invoke(ModelId)`/`chat(ModelId)` overloads delegating to `languageModel(modelId.value)`. Open-valued aliases (`ElevenLabsSpeechVoiceId`, `GoogleInteractionsAgentName`) become their own value classes, not `ModelId`. Set a concrete removal target.
- **ABI:** additive (aliases absent from ABI dumps; new overloads only gain) → **breaksAbi=false**.

### PO-3 — core abstraction uses raw `String` identity (medium) — value-class/T3
- Occurrences: 4 · Evidence: `LanguageModel.kt:25`, `LanguageModel.kt:36`, `Provider.kt:4`
- **Bad:** `LanguageModel.modelId/provider: String` (`provider` defaults to `"unknown"`), `Provider.providerId: String`, and the 8 `<kind>Model(modelId: String)` factories erase the SDK's own `ModelId`/`ProviderId` at the abstraction seam.
- **Solution:** clean break now at pre-1.0: make the value classes the abstract members; drop the `"unknown"` sentinel default (T5) forcing each impl to declare its real id; keep `@Deprecated` String shims for source migration; prune `ModelRef.kt` overloads that only unwrapped `.value`; pass `.value` at `NoSuchModelError` throw sites. One coordinated commit.
- **ABI:** breaks (getter + value-class param mangling) — before 1.0.

### PO-4 — `QuiverAIOperation = String` is a closed two-value set (low) — T1/T5
- Occurrences: 1 · Evidence: `providers/QuiverAIProvider.kt:27/86/255`
- **Bad:** aliased to `String`, branched on a binary set read from a map with `?: "generate"`; a typo silently routes to vectorization.
- **Solution:** delete the dead typealias (zero ABI impact); add an `enum class QuiverAIOperation(wireValue, path)` with `fromWire` that throws on unknown; parse once at the boundary (absent→`Generate` is a legitimate default); `quiverAIRequestBody` switches exhaustively; path collapses to `operation.path`.
- **ABI:** additive (new enum) + removal of an untracked alias → effectively non-breaking.

---

## Dimension 5 — Raw JSON / `Map<String,JsonElement>` leakage (`Record<string,unknown>`)

### raw-json-01 — `providerOptions: Map<String,JsonElement>` is the public request-side config (high) — T2/T1
- Occurrences: 63 · Evidence: `LanguageModel.kt:82`, `KotlinApi.kt:31`, `providers/AnthropicProvider.kt:688`
- **Bad:** two-level untyped bag (outer provider-name → inner `JsonObject` consumed via `as? JsonObject` + string-literal keys); no compile check, no discoverability, typos no-op.
- **Solution:** keep the bag keyed by the (load-bearing) provider name, but type the **value** via kotlinx.serialization. Each provider slice defines its own `@Serializable …ProviderOptions` data class and decodes with `json.decodeFromJsonElement(...)`. Add provider-slice extension builders (`ProviderOptionsBuilder.anthropic { … }`) for compile-checked construction; keep the existing `provider(name, JsonObject)` raw escape hatch for the long tail. Do NOT model 35 providers as a sealed class in core (god file + reverse dependency). Migrate additively — new builder extensions write into the existing map; `@Deprecated` raw `put`.
- **ABI:** type changes break — additive builder layer is ABI-safe; run `apiCheck`.

### raw-json-02 — hosted provider tools are `Tool<JsonElement,JsonElement,Any?>` (high) — T2/T5
- Occurrences: 75 · Evidence: `providers/OpenAIProvider.kt:177/190`, `providers/XaiProvider.kt:127`
- **Bad:** every hosted tool typed `Tool<JsonElement,JsonElement,Any?>`; config passed as free-form `args: JsonElement` stuffed into `metadata["providerToolArgs"]`. Tools with real config (file_search vector_store_ids, web_search search_context_size) lose all shape.
- **Solution:** type the **config**, not TInput. Keep `TInput = JsonElement` (model runtime input); add a typed `@Serializable` config default arg encoded into metadata at construction (`metadata.providerToolArgs = aiSdkJson.encodeToJsonElement(config)`) — same wire slot `OpenResponsesProvider.kt:673` reads, so no consumer change. Add a `rawProviderTool(args: JsonObject)` escape hatch. Add config-typed factories alongside the JsonElement ones; `@Deprecated(ReplaceWith)` the latter. Prioritize config-carrying tools.
- **ABI:** additive factories are binary-compatible (`breaksAbi=true` only if descriptors change — keep them frozen).

### raw-json-03 — `Tool.metadata` smuggles typed concepts via magic keys (medium) — T2/T5
- Occurrences: 26 · Evidence: `Tool.kt:97`, `providers/OpenAIProvider.kt:196`, `providers/AnthropicProvider.kt:1159`
- **Bad:** `providerToolId`/`providerToolArgs` are always-present concepts stored as stringly-keyed `JsonPrimitive` entries in a generic `metadata` map and re-read by literal.
- **Solution:** promote to a typed `ProviderToolInfo(providerToolId, args, inputSchema, outputSchema, supportsDeferredResults)` on both `Tool` and the wire `LanguageModelTool` (nullable, defaulted); a generic `ProviderExecutedTool<TInput,TOutput,TContext>` carries it; reserve `metadata` for open-ended annotations. Map `providerTool` through `ToolSet.descriptors` so `OpenResponsesProvider.kt:674-680`/`GoogleProvider.kt:811` read it directly. Keep old metadata reads as a `@Deprecated` fallback one release → additive.
- **ABI:** additive (new types/fields only) → binary-compatible.

### raw-json-04 — `providerMetadata: Map<String,JsonElement>` forces stringly read-back (medium) — T2/T3
- Occurrences: 60 · Evidence: `LanguageModel.kt:131`, `Lifecycle.kt:147`, `TypedJson.kt:63`
- **Bad:** results expose provider response metadata as the same bag; the shipped `decodeProviderMetadata` helper is the tell that the API hands back untyped JSON for every consumer to re-decode by hand (violates T3).
- **Solution:** keep the wire bag as transport but wrap in a `@JvmInline value class ProviderMetadata(raw)` with a `decode(provider, serializer)` primitive; each provider slice owns its typed accessor (`GenerateTextResult<*>.anthropicMetadata(): AnthropicMetadata?`). Core never depends on provider schemas. `@Deprecated(WARNING)` raw exposure one cycle. Read-side → lower priority; do NOT bundle with the request-side bag.
- **ABI:** breaks (property type) — additive accessor first, regenerate dumps with the type swap.

---

## Dimension 6 — Mutable global/singleton state & JS default-export disguise

### GLOBAL-1 — ~48 throw-on-use provider singletons (npm default-exports) (high) — no-singleton/explicit-DI/T3
- Occurrences: 48 · Evidence: `providers/OpenAIProvider.kt:56/75`, `providers/OpenAICompatibleProviderFacades.kt:72`
- **Bad:** `public val <provider>: XProvider = <object that throws on every method>` paired with an existing `createX(client, settings)`. The vals compile and throw `AiSdkException` at first use; the facades add a reflective `when (T::class) … as T` stub factory.
```kotlin
public val openai: OpenAIProvider = OpenAIProviderNotConfigured
private fun missing(): Nothing = throw OpenAIProviderNotConfiguredError()
```
- **Solution:** Phase 1 (ABI-safe, now): `@Deprecated(WARNING, message="…construct explicitly with createX(client, settings).")` every throwing val; WARNING-level does not alter the ABI dump; use message-only or a `ReplaceWith` naming only the factory (avoid an unresolved IDE rewrite). Phase 2 (break): delete the throwing vals, `*NotConfigured` objects, `missing()` helpers, `*NotConfiguredError` classes, and the reflective `providerNotConfigured` factory. Add a per-provider test asserting `createX(client)` returns a usable provider.
- **ABI:** Phase 1 breaksAbi=false; Phase 2 breaks.

### GLOBAL-2 — `globalTelemetry` mutable process-global registry on the hot path (medium) — no-singleton/explicit-DI
- Occurrences: 1 · Evidence: `Telemetry.kt:146/149/167`
- **Bad:** `resolveTelemetry()` consults a mutable top-level registry when no per-call integrations are passed; registration is process-wide startup mutation, so behavior depends on whoever called `registerTelemetry` first (test-isolation hazard). The registry is internally COW-atomic, but it is still global mutable state.
- **Solution:** inject a `TelemetryRegistry` into `resolveTelemetry` and the Agent constructor (the per-call `TelemetrySettings.integrations` seam already exists); `@Deprecated` the globals additively; remove on a bump.
- **ABI:** Phase 1 additive injection is ABI-safe; removal breaks.

### GLOBAL-3 — shared `gateway` singleton holds a non-synchronized mutable cache (high, CORRECTNESS) — no-singleton/structured-concurrency
- Occurrences: 1 · Evidence: `Gateway.kt:254`, `Gateway.kt:261`, `Gateway.kt:279`
- **Bad:** a process-wide `gateway` singleton caches fetch-metadata in two plain `var`s read-then-written inside `suspend getAvailableModels()` with no mutex — concurrent coroutines race (torn reads, lost writes, double-fetch).
- **Solution:** split into two changes. (1) **The bug (ABI-safe, ship now):** guard a single nullable `MetadataCache` data class (collapses the correlated `pendingMetadata`/`lastFetchTime` pair, T5) behind a `kotlinx.coroutines.sync.Mutex` — prefer Mutex over AtomicReference because it also de-dupes the concurrent fetch (single-flight), which a CAS does not.
```kotlin
private val metadataMutex = Mutex()
private var metadataCache: MetadataCache? = null
override suspend fun getAvailableModels(): GatewayFetchMetadataResponse = metadataMutex.withLock {
    val now = settings.nowMillis()
    metadataCache?.takeIf { settings.metadataCacheRefreshMillis > 0 && now - it.fetchedAtMillis <= settings.metadataCacheRefreshMillis }?.response
        ?: settings.transport.getAvailableModels(requestContext()).also { metadataCache = MetadataCache(it, now) }
}
```
(2) The `gateway` throw-on-use singleton is GLOBAL-1's pattern — handle there.
- **ABI:** the cache fix is purely internal to a private impl → **breaksAbi=false**.


---

## Gap-fill addendum

Net-new findings from six dimensions not covered by FIX-001..FIX-016. Task IDs below are renumbered into a single FIX-017+ sequence (the source findings collided at FIX-017 across dimensions). All ABI notes refer to `api/jvm/torad-aisdk.api` + `api/torad-aisdk.klib.api`; project is `0.3.0-alpha01`, so breaking changes are acceptable but should be additive + `@Deprecated` where cheap.

### Dimension: Error hierarchy not sealed (largest T1 gap)

#### FIX-017 — Seal the `AiSdkException` root (`open class` with ~40 leaves) — high, T1
- Evidence: `src/commonMain/kotlin/ai/torad/aisdk/AiSdkError.kt:3` (`public open class AiSdkException : RuntimeException`), `providers/OpenAIProvider.kt:58`.
- **Bad:** one `open` root with ~40 `public class X : AiSdkException` leaves across AiSdkError.kt, Gateway.kt, Util.kt, MCP.kt, WireDecoder.kt, OpenAIProvider.kt. `open` (not `sealed`) defeats exhaustive `when`; every catch site needs a non-exhaustive `is`-chain + `else`, and external subtypes the SDK never produces are representable. Pure TS residue (TS has no sealed exceptions); all leaves live in commonMain so a sealed root is mechanically feasible.
- **Solution:** (1) add typed `ProviderNotConfiguredError(provider)` and migrate ~30 provider not-configured throws; (2) map ad-hoc `throw AiSdkException(...)` (e.g. WireDecoder.kt:25) to existing typed leaves or one explicit `GenericAiSdkError(message, cause)` escape hatch — do not leave the base instantiable; (3) flip `open` → `sealed`, keeping `(message, cause)` as base payload; (4) seal `GatewayError` (FIX-018) too or the subtree stays non-exhaustive. Gate on `compileKotlinJvm` (~171 sites surface), then regenerate api dumps. Breaking change for the alpha.
- **ABI:** breaks.

#### FIX-018 — Seal `GatewayError` sub-root; drop stringly-typed `type` discriminator — high, T1/T2
- Evidence: `Gateway.kt:423` (`public open class GatewayError(... public val type: String = "gateway_error" ...)`), `Gateway.kt:468`.
- **Bad:** `GatewayError` is itself `open` with 7 leaf subtypes, and its discriminant is a fabricated `type: String` tag duplicating the class identity the language already provides.
- **Solution:** `open` → `sealed` (subtypes already in-file; transport never constructs base — `KtorGatewayTransport.kt:791` `else -> GatewayResponseError`). Remove `type: String` from the public ctor; keep the wire↔subtype mapping solely in `KtorGatewayTransport.gatewayErrorFromResponse` (already owns inbound mapping at 785-797) per T2. Keep `statusCode`/`isRetryable`/`generationId` as typed base fields. Sealable independently of FIX-017. Leave `GatewayTransportNotConfiguredError` under AiSdkException (config error, not HTTP).
- **ABI:** breaks (callers reading `type` / subclassing affected).

#### FIX-019 — Replace ~11 `Any?` error payload fields with real types — high, T2/T5
- Evidence: `AiSdkError.kt:19` (`requestBodyValues: Any?`, `data: Any?`), `:31` (`prompt: Any?`), `:73` (`value: Any?`).
- **Bad:** `Any?` across APICallError, InvalidPromptError, InvalidResponseDataError, TooManyEmbeddingValuesForCallError, TypeValidationError, InvalidStreamPartError, InvalidDataContentError, MessageConversionError — uninspectable without a cast, leaks raw provider/JSON shapes (T2), and means both absent and present-but-untyped (T5).
- **Solution:** per-site, verified by construction site — NOT a blanket `JsonElement?`: APICallError.requestBodyValues/data → `JsonElement?`; InvalidResponseDataError.data → `JsonElement?`; TypeValidationError.value → `JsonElement?` (NOT generic — would break non-generic `ValidationResult.Failure` at Tool.kt:758); TooManyEmbeddingValuesForCallError.values → `List<String>`; MessageConversionError.originalMessage → domain `ModelMessage`/`UIMessage`; InvalidPromptError.prompt → domain prompt type; InvalidDataContentError.content → binary `ByteArray?`/sealed DataContent, never JsonElement. Fix `GatewayAndProviderUtilsParityTest` assertions that pass raw Strings.
- **ABI:** breaks.

#### FIX-020 — Unify the two tool-error hierarchies (AgentError under AiSdkException) — medium, T1
- Evidence: `AgentError.kt:43` (`sealed class AgentError : RuntimeException`), `:48` (`NoSuchTool` dup of `AiSdkError.kt:215` `NoSuchToolError`).
- **Bad:** `AgentError` extends `RuntimeException` directly, outside the SDK root, so `catch(AiSdkException)` misses every AgentError; its leaves duplicate AiSdkException leaves one-for-one (half-finished migration, two taxonomies).
- **Solution:** change `AgentError.kt:46` parent to `: AiSdkException(message, cause)` (one breaking line; AiSdkException already has the `(String, Throwable?)` ctor). `@Deprecated(ReplaceWith)` the four flat dup leaves (NoSuchToolError→AgentError.NoSuchTool, etc; note InvalidToolApprovalError maps imperfectly — document). Migrate the lone consumer `GatewayAndProviderUtilsParityTest.kt:445-476`. Add a test asserting `AgentError.NoSuchTool(...) is AiSdkException`. Does NOT seal AiSdkException (that is FIX-017).
- **ABI:** breaks (supertype narrows; still IS-A RuntimeException).

#### FIX-021 — Drop `data` from the 8 AgentError leaves — low, T1
- Evidence: `AgentError.kt:48` (`data class NoSuchTool`), `:65` (`data class ToolExecution`).
- **Bad:** `data class` on a Throwable generates equals/hashCode over props (ignoring stack/cause) and a `copy()` that fabricates an exception without re-capturing a stack trace — wrong semantics; exceptions are identity objects.
- **Solution:** remove `data` from all 8 leaves, keep fields as `public val`. No test/commonMain changes needed (only field access + `is`/`assertIs`, no `==`/destructuring/`.copy()` — verified). Leave `StreamEvent.ToolError` data class alone. Reasonable to bundle with FIX-020 (same file, single ABI break).
- **ABI:** breaks (removes componentN/copy/equals/hashCode entries).

#### FIX-022 — Remove the dead `AISDKError` typealias — low, T2
- Evidence: `AiSdkError.kt:8` (`public typealias AISDKError = AiSdkException`).
- **Bad:** unused TS-name-parity alias; a second name for the same type inviting wrong binding.
- **Solution:** delete line 8 (confirmed absent from both api dumps — source-only change, `apiCheck` shows no diff). Alpha needs no deprecation cycle.
- **ABI:** none.

### Dimension: Non-text model surface never audited (embedding/rerank/media)

#### FIX-023 — Wire the 42 dead typed `*ModelOptions` classes (stringly-typed JsonObject digging) — high, T2/typed-models
- Evidence: `providers/VoyageProvider.kt:26` (typed `VoyageEmbeddingModelOptions`), `:99` (`options["inputType"]`), `:192` (`voyageOptions(...)` returns raw bag).
- **Bad:** a `@Serializable` typed options class is declared public but never decoded; a private `xxxOptions(): JsonObject` hands back the raw bag and code reads `options["camelKey"]` unchecked across 19 providers.
- **Solution (two stages):** Stage 1 (additive, per provider): reconcile every read key against the typed class (add missing fields first), then replace the raw helper with `params.providerOptions.decodeProviderMetadata<T>("voyage") ?: T()` (helpers already in TypedJson.kt); pull client-side poll/control keys (pollIntervalMs/pollTimeoutMs) into a separate control struct (T2); add per-provider round-trip wire tests. Note several classes are `*ProviderOptions` not `*ModelOptions` — derive name per file. Stage 2 (additive + `@Deprecated`): expose the typed options as a first-class param channel, keep Map path for compat. Stage 2 is the actual T2 fix.
- **ABI:** none (Stage 1 additive; Stage 2 additive + deprecate).

#### FIX-024 — `GeneratedFile`: sealed Inline/Remote, kill `base64=""` sentinel — high, T5/T1
- Evidence: `MediaModels.kt:8` (flat record, non-null `base64`, nullable `url`), `:76` (`base64 = ""` sentinel for URL-backed), `:114` (`bytes()` runtime `check`).
- **Bad:** two disjoint reps (inline base64 XOR remote url) in one flat record with `base64=""` overloaded to mean absent; `bytes()` defends the invariant at runtime — canonical T5 nullable-pair sentinel (FileData next door is already sealed).
- **Solution:** `sealed class GeneratedFile` with shared `mediaType/filename/providerMetadata` on base + `Inline(base64)` / `Remote(url)`. Deprecated `operator fun invoke(...)` companion shim + deprecated `base64`/`url` accessor extensions for one release; rewrite `bytes()`/`bytesOrNull()`/`fileData()` as exhaustive `when`, delete the runtime `check`. Migrate provider sentinel sites (`XaiProvider.kt:367`, `AlibabaProvider.kt:193`, ProdiaProvider) to `Remote(url=...)`. `Inline(base64="")` now unambiguously = genuinely empty inline file.
- **ABI:** breaks (source-compat for one release via shims).

#### FIX-025 — `ImageGenerationFile` all-nullable bag → `List<FileData>` — medium, T5/T1
- Evidence: `MediaModels.kt:201` (4 nullable fields), `:84` (`imageGenerationFile()` downgrades sealed FileData), `FalProvider.kt:500` (`file.url ?: "...${file.base64.orEmpty()}"`).
- **Bad:** four independently-nullable fields re-encode the inline-XOR-url choice FileData already models; every consumer re-derives the invariant with `?:`/`.orEmpty()`, and the helper actively downgrades a precise sealed value.
- **Solution:** delete `ImageGenerationFile` + the `imageGenerationFile(FileData)` converter; change `ImageGenerationParams.files`/mask to `List<FileData>`/`FileData?`; rewrite each provider encoder (Fal, Luma, BlackForestLabs, Replicate, Xai, QuiverAI, OpenAICompatible, AmazonBedrock, Google) as exhaustive `when(file){ Base64/Bytes/Url }`; update the 7 provider test files. No `@Deprecated` bridge at alpha.
- **ABI:** breaks.

#### FIX-026 — Remove `provider = "unknown"` sentinel on all 6 non-text models + LanguageModel — medium, T5
- Evidence: `Embedding.kt:11-13`, `Rerank.kt:7`, `MediaModels.kt:173/372/459/558`, `LanguageModel.kt:36-37` (all `get() = "unknown"`).
- **Bad:** a required identity field defaulted to a fabricated placeholder so a misconfigured impl silently reports `provider="unknown"`.
- **Solution:** make `provider` ABSTRACT (delete the getter) on EmbeddingModel, RerankingModel, ImageModel, SpeechModel, TranscriptionModel, VideoModel AND LanguageModel (else the primary surface keeps the bad pattern). All built-in providers already override; Wrapped*/Gateway delegate `inner.provider`. Do NOT migrate `provider: String` → `ProviderId` here (it is a granular telemetry tag like `"alibaba.embedding"`, not the registry id; would model illegal states wrongly) — track `modelId: String → ModelId` / a new `ProviderTag` value class as a separate cross-surface task.
- **ABI:** breaks.

#### FIX-027 — Rerank result fabricates sentinels (`getOrElse{""}`, `?:0f`, `?:0`) — medium, T5
- Evidence: `VoyageProvider.kt:150`, `CohereProvider.kt:235`; same pattern in `KtorGatewayTransport.kt:260-264`, `OpenAICompatibleProviderFacades.kt:1317-1321`, `AmazonBedrockProvider.kt:378-384` (5 paths total).
- **Bad:** missing index/score on the wire is mapped to index=0 (first doc), document="", score=0f — all indistinguishable from real data (0f is a legal relevance).
- **Solution:** in all 5 paths throw `APICallError` when `index`/`relevance_score` absent or `getOrNull(index)` out of range; remove the `getOrElse{""}`/`?:0`/`?:0f` defaults (manual-parse throwing, not a new DTO; `NoOutputGeneratedError` is the wrong type — protocol violation). Add per-provider tests asserting a missing score/bad index throws. Public `RerankedItem`/`RerankingModelResult` unchanged.
- **ABI:** none.

### Dimension: God files / structural concentration (T8)

> All god-file splits below are same-package moves. With `@file:JvmName(...)` + `@file:JvmMultifileClass` on any file receiving previously-top-level public funcs/vals, the JVM facade is reconstituted and the klib ABI is package-keyed — so each is **ABI-neutral**; the acceptance gate is `apiCheck` producing a byte-identical diff. Move declarations VERBATIM (no signature edits).

#### FIX-028 — Split `MCP.kt` (2221 lines, 5 concerns) — high, T8
- Evidence: `MCP.kt:92` (JSON-RPC), `:399` (MCP client), `:1056` (OAuth/PKCE). Also hand-rolled SHA-256 + 3 transports.
- **Solution:** same-package `mcp/` dir → MCPJsonRpc.kt, MCPModels.kt, MCPClientImpl.kt, MCPOAuth.kt, MCPCrypto.kt, MCPTransports.kt. Files receiving top-level publics (MCP_PACKAGE_VERSION, createMCPClient, experimental_createMCPClient, createMcpTransport — keep LATEST_PROTOCOL_VERSION/SUPPORTED with them) need `@file:JvmName("MCPKt") @file:JvmMultifileClass`; preserve existing `@file:Suppress`. Out of scope: sealing JSONRPCMessage; replacing SHA-256.
- **ABI:** neutral (caveat: any missing JvmMultifileClass header breaks it — gate on zero apiCheck diff).

#### FIX-029 — Decompose `ToolLoopAgent.kt` (1814-line god class, 663-line private `streamInternal`) — high, T7/T8
- Evidence: `ToolLoopAgent.kt:60` (class spans 60-1873), `:467` (streamInternal flow 467-1130), `:1331` (executeTool; 29 private funs).
- **Solution:** ToolLoopExecutor.kt (resolveCall/tryRepairToolInput/decodeToolInput/executeTool/collectFinalToolOutput → typed `ResolvedCall` not Triple/Pair, constructed with immutable deps, testable against a fake Tool with no model); ToolLoopApproval.kt (callNeedsApproval/applyToolApprovalResponses receiving collector + message list as params); ToolLoopAgent.kt keeps public surface byte-identical, decomposes streamInternal into `runModelStep`/`dispatchTools`/`applyResults` threading a `TurnState` holder. Keep extracted symbols `internal`. Add commonTest units for tryRepairToolInput + callNeedsApproval.
- **ABI:** neutral.

#### FIX-030 — Split `OpenAICompatibleProviderFacades.kt` (1505 lines, 11 providers) — medium, T8
- Evidence: `:47` (DeepSeek settings), `:728` (deepSeek transforms), `:1147` (FireworksImageModel).
- **Solution:** per-provider files under `providers/openaicompat/` (package unchanged): DeepSeekFacade.kt … BasetenFacade.kt + FacadeShared.kt. Each file carries its settings/options/error-data, interface, `createX`, the `xxx` val, private `toCompatible`, and its private transform/messages/usage helpers. Public model classes (FireworksImageModel, TogetherAIRerankingModel) move with their facade. **Promote shared private helpers (compatibleSettings, usageFromParts, providerNotConfigured, postFacadeJson/Binary, providerFacadeHeaders, intField/nestedIntField/textFromContentParts) to `internal`** so they resolve across files. Add `@file:JvmName("OpenAICompatibleProviderFacadesKt") @file:JvmMultifileClass` to every file with former top-level publics.
- **ABI:** neutral (private→internal is excluded from the published surface).

#### FIX-031 — Split `GoogleProvider.kt` (2044 lines, two Google APIs) — medium, T8
- Evidence: `:436` (Interactions model), `:1284` (generateContent body), `:170` (5 model classes in header).
- **Solution:** keep ABI-bearing surface in GoogleProvider.kt (GOOGLE_VERSION, typealiases, sealed GoogleInteractionsModelInput, settings/interface/factory/`google` val, DefaultGoogleGenerativeAIProvider, GoogleTools + `googleTools`). Move only private decls: GoogleModels.kt (embedding/image/video + bodies), GoogleGenerateContent.kt, GoogleInteractions.kt, GoogleShared.kt. Every moved symbol is private → no `@JvmMultifileClass` needed. Carve GoogleInteractions.kt first.
- **ABI:** neutral.

#### FIX-032 — Extract Bedrock Smithy event-stream codec to a testable unit — medium, T7
- Evidence: `AmazonBedrockProvider.kt:1314` (decodeBedrockEventStream), `:1380` (bedrockHeaders), `:206` (model classes precede codec).
- **Solution:** extract ONE internal file BedrockEventStream.kt: decodeBedrockEventStream, readSmithyHeaders, readInt32BE/readUInt16BE, BedrockEventStreamMessage, bedrockMessagePayload, readBedrockFrame/sendBedrockEventStreamFrames — flip private→internal. Add commonTest golden-byte fixtures (header type tags 0-9, string-type 7 extraction, length-bound break, truncated trailing frame; event-type wrap vs passthrough). Do NOT create BedrockSigning.kt — SigV4 is already in AwsSigV4.kt with AwsSigV4Test.kt; bedrockHeaders/baseURL/clock-skew are thin wiring, leave them. Optionally revisit the `.orEmpty()` sentinels at ~1328-1330 (T5).
- **ABI:** neutral (codec confirmed absent from api dumps).

#### FIX-033 — Split `Tool.kt` (1247 lines: abstraction + schema/validation + provider-tool factory) — medium, T8
- Evidence: `:721` (Schema/validation slice), `:886` (ProviderToolFactory slice), `:70` (Tool core).
- **Solution:** Tool.kt (Tool/StreamingTool/builders/contexts/stream writer), ToolSet.kt, Schema.kt (Schema/LazySchema/ValidationResult/safeValidateTypes/validateTypes/parseProviderOptions/schemaFallbackValue), ProviderToolFactory.kt (+ ExecuteToolResult/executeTool), ToolResult.kt (ToolChoice/ToolResultOutput/converters). Add `@file:JvmName("ToolKt") @file:JvmMultifileClass` to EVERY split file (incl. trimmed Tool.kt). Do NOT convert ValidationResult/ToolChoice interface→class here (separate T1 finding; Surgical Changes).
- **ABI:** neutral.

### Dimension: Core streaming / backpressure

#### FIX-034 — SSE response headers smuggled across coroutines via shared mutable `var` (8 sites) — medium, no-shared-mutable-state/T4
- Evidence: `AnthropicProvider.kt:125` (`var sseHeaders ... { sseHeaders = it }` then `{ sseHeaders }`), `HttpTransport.kt:294/333`. 8 sites: Anthropic, OpenResponses, OpenAICompatible×2, HuggingFace, Gateway×2, Bedrock (verified).
- **Bad:** `streamSse` exposes 2xx headers only via an `onResponse` callback running in the producer coroutine; each caller declares a local `var sseHeaders`, mutates it from the producer lambda, then reads it from the collector coroutine — correct today only because the channel send/receive happens-before edge holds; one refactor (prefetch/conflation/empty stream) from a stale read.
- **Solution:** replace `onResponse` + every `var sseHeaders` with a write-once `CompletableDeferred`; `streamSse` completes it when the 2xx head arrives; `forwardSseEvents.capturedHeaders` becomes a suspend lambda that `await()`s; Bedrock resolves once into a local val. Error path never awaits (no hang); empty stream stays correct (complete runs before producer ends). All wrappers internal/private.
- **ABI:** none.

#### FIX-035 — `pipeUiMessageStreamToResponse` default encoder emits Kotlin `toString()` to the SSE wire — medium, T2
- Evidence: `ui/Streams.kt:66` (`encoder = { it.toString() }`), `:72` (`response.write(encoder(it))`).
- **Bad:** default ships data-class debug text as the SSE chunk under a `text/event-stream` content type meant to feed a JS `useChat` client.
- **Solution:** change default to `{ "data: " + aiSdkJson.encodeToString(UIMessage.serializer(), it) + "\n\n" }` (framing lives in the encoder since the fn does none). But do NOT advertise as useChat-ready — it streams whole-UIMessage snapshots, not v6 UIMessageChunk frames. Route the genuine path through `toUIMessageStream(events): Flow<JsonObject>` framed `data: {json}\n\n` + `[DONE]`; add/document a `Flow<JsonObject>` overload as the supported chunk wire input; KDoc the snapshot variant as "renderable snapshots, not the v6 chunk protocol". Tests: snapshot encoder round-trips to UIMessage; chunk path each line parses to a JsonObject with a v6 `type`.
- **ABI:** none (default-value change only).

#### FIX-036 — `SmoothStream` `.buffer()` comment claims unbounded/no-loss but buffer is bounded — low, doc correctness
- Evidence: `SmoothStream.kt:111` ("unbounded-ish ... keep producing"), `:115` (`upstream.buffer()`).
- **Bad:** `buffer()` with no capacity = `Channel.BUFFERED` (default ~64, SUSPEND) — bounded backpressure, the opposite of the comment; a future maintainer might "fix" it toward `Channel.UNLIMITED` and reintroduce OOM risk.
- **Solution:** rewrite the comment to state bounded suspending buffer / producer suspends (no loss) / do-not-switch-to-UNLIMITED. Behavior already correct — no code change required. If a larger window is wanted, add `bufferCapacity: Int = 64` (additive).
- **ABI:** none.

### Dimension: LangChain/LlamaIndex adapters (TS-ecosystem residue)

#### FIX-037 — Deprecate or extract the non-functional LangChain/LlamaIndex adapters — high, T2/T8/scope
- Evidence: `LangChain.kt:269` (graphStream default `throw UnsupportedOperationException`), `:280` (reconnectToStream throws unconditionally), `LlamaIndex.kt:11` (bare String-delta wrapper).
- **Bad:** 1:1 ports of `@ai-sdk/langchain` / `@ai-sdk/llamaindex` with no Kotlin runtime to bind to; the transport is inert (throwing defaults). Same category as the removed react/rsc/svelte/vue/angular packages, yet these survived carrying heavy tenet debt for zero working integration.
- **Solution:** decide consistently across all adapters. Preferred: move react/svelte/vue/angular/langchain/llamaindex into an opt-in `:interop` artifact with a uniform deprecation cycle. If kept in core, fix defects instead of deprecating (reconnectToStream → null, fabricated ids → caller ids, StreamCallbacks → Flow operators, JsonElement → typed). If deprecating now, WARNING-level changes no api dump.
- **ABI:** none (WARNING deprecation path); breaks if types are moved/removed.

#### FIX-038 — Delete the 7-lambda `StreamCallbacks` bag — high, T4
- Evidence: `LlamaIndex.kt:15` (7 nullable suspend lambdas), `LangChain.kt:145/247` (`callbacks?.onX?.invoke(...)` scattered).
- **Bad:** direct port of the JS callback option bag; every lambda is redundant with a Flow operator on the `Flow<UIMessage>` these fns already return (onStart→onStart{}, onToken/onText→onEach{}, onFinal/onFinish→onCompletion{}, onError→catch{}, onAbort→cancellation). Scatters side-effect notification through the suspend body.
- **Solution:** delete StreamCallbacks and the param from all four functions; model finalState as a typed terminal `UIMessagePart` sealed subtype (not a nullable side channel); keep typed Error emission. Rewrite adapter tests to assert via Flow operators.
- **ABI:** breaks.

#### FIX-039 — Fabricated default/sentinel message & tool ids — medium, T5
- Evidence: `LangChain.kt:148` (`assistantMessageId = "langchain-msg-1"`), `:183` (`runId ?: ... ?: "tool-call"`, `toolName ?: ... ?: "tool"`), `:418` (`id ?: "tool-call-$index"`).
- **Bad:** constant id fallbacks collide across concurrent streams and corrupt UIMessage de-dup/merge — the sentinel T5 forbids.
- **Solution:** change the two `assistantMessageId` defaults to `generateId("msg")` (keeps `String = ...` signature → no ABI break); replace constant tool-id/name fallbacks at LangChain.kt:183/202/418 with per-call `generateId("call")` so start/end stay correlated. Do NOT touch derived ids at :228/:259 (correct deterministic derivations). Subsumed if FIX-037 deletes the adapters — confirm that decision first.
- **ABI:** none (defaults keep signatures).

#### FIX-040 — Raw provider JSON leaks into public adapter domain types — medium, T2
- Evidence: `LangChain.kt:47` (`content: JsonElement`, `additionalKwargs: Map<String,JsonElement>`), `:64` (`StreamEventsEvent` free `event: String` + `data: JsonObject?`), `:71` (`LangGraphEvent.type: String`, `data: JsonElement = JsonNull`).
- **Bad:** the public adapter types ARE the JS wire shapes; discriminators are free Strings decoded via if/when on literals.
- **Solution:** gate on FIX-037 — if adapters are removed/extracted, do nothing in core. If kept (ideally in `:interop`): content → `List<ContentPart>` (reuse existing sealed type); drop additionalKwargs map → explicit typed `toolName`/`isError` fields; convert StreamEventsEvent/LangGraphEvent into a sealed hierarchy under LangChainStreamItem (kills the JsonNull default); keep ONLY the opaque LangGraph user-defined final state as documented JsonElement pass-through.
- **ABI:** breaks (field-type changes can't be additive).

#### FIX-041 — `LangChainStreamItem` sealed interface → class; drop redundant `toUIMessageStream` overloads + `@JvmName` hack — low, T1
- Evidence: `LangChain.kt:56` (sealed interface), `:138` (`@JvmName("langChainToUIMessageStreamExport")` pass-through), `LlamaIndex.kt:25` (third overload).
- **Bad:** sealed interface (T1 prefers class); three unrelated `toUIMessageStream` overloads collide hard enough to need `@JvmName` to compile on JVM; both adapter wrappers are pure pass-throughs to the `*ToUIMessageStream` named fns for JS-name parity.
- **Solution:** `sealed interface` → `sealed class`; delete the two redundant overloads + the `@JvmName`/import, keep only `langChainToUIMessageStream`/`llamaIndexToUIMessageStream`. Update tests. Leave StreamEventsEvent.event/LangGraphEvent.type as strings (wire values, T2).
- **ABI:** breaks.

### Dimension: TOP LEVERAGE — one typed provider-data boundary (epic, sequences with FIX-015/016)

#### FIX-042 — EPIC seam: introduce `ProviderOptions` / `ProviderMetadata` value types — high, T2/T5/typed-models
- Evidence: `KotlinApi.kt:31` (`providerOptions: Map<String,JsonElement>`), `TypedJson.kt:60` (`.providerMetadata(provider)`), `AnthropicProvider.kt:688` (`providerOptions["anthropic"] as? JsonObject ?: empty`). The same provider-id-keyed-JSON-object concept is spelled `Map<String,JsonElement>` on ~40 request + ~56 result fields across CallSettings, call options, Embedding, Rerank, Media, Tool, Context, ModelMessage, ContentPart, StreamEvent, UIMessagePart, Lifecycle.
- **Solution:** reuse the existing `ProviderId` value class (ModelRef.kt:6). Make both `ProviderOptions` (inbound) and `ProviderMetadata` (outbound) `@JvmInline value class`es wrapping `Map<ProviderId, JsonObject>`, each `@Serializable(with=...)` routed through ONE internal `providerSliceMapSerializer` (object-of-objects, ProviderId.value as key). Deserializer lenient on shape (skip non-object values, consistent with `aiSdkJson` ignoreUnknownKeys/isLenient). Expose `slice(ProviderId): JsonObject?`, `decode<T>(...)` (+reified), `raw` getter, `ofRaw`/`of`/`Empty` factories, a builder reusing ProviderOptionsBuilder/putJson. Tests assert wire round-trip equals the current Map JSON exactly. This task introduces ONLY the type/serializer/interop/round-trip tests — no call-site migration. This is the additive seam FIX-043..045 apply. Sequence: FIX-015/016 type the TEXT surface first; this epic generalizes the same type across all surfaces.
- **ABI:** breaks (new public types; migration is in FIX-043/044).

#### FIX-043 — Migrate all INBOUND provider-config fields (40 sites) to `ProviderOptions` — high, T2
- Evidence: `Embedding.kt:38`, `Rerank.kt:17`, `MediaModels.kt:194` (+ Tool.kt:104/209, LanguageModel.kt:119, Context.kt:41/91, DevTools.kt:21, middleware/DefaultSettings.kt:34).
- **Solution:** gate on FIX-016 (text) + FIX-042; retype the non-text sites to `ProviderOptions` with deprecated raw-map overloads; switch provider extraction to `slice(...)`.
- **ABI:** breaks (additive overloads + deprecate).

#### FIX-044 — Migrate all OUTBOUND provider-metadata fields (56 sites) to `ProviderMetadata` — high, T2
- Evidence: `Streaming.kt:75` (×21 StreamEvent subtypes), `ModelMessage.kt:74` (×8), `ui/UIMessagePart.kt:57` (×8) + Embedding/Rerank/Media results, ContentPart, Lifecycle StepResult, LanguageModelResult.
- **Solution:** fold FIX-015 into one PR/one api regen; retype all 56 domain sites to `ProviderMetadata` default `Empty` (collapse nullable→Empty, T5); leave the ~4 provider/wire sites raw (T2); rewrite TypedJson.kt metadata getters to return ProviderMetadata; update provider write sites to `ProviderMetadata.of(...)`; round-trip tests.
- **ABI:** breaks.

#### FIX-045 — Typed slice accessors eliminate ~30 `providerOptions["x"] as? JsonObject ?: empty` extractions — medium, T5
- Evidence: `AnthropicProvider.kt:688`, `AmazonBedrockProvider.kt:299`, `HuggingFaceProvider.kt:368` (`["huggingface"] ?: ["hugging-face"]`).
- **Bad:** every provider re-implements the unsafe cast + `?: JsonObject(emptyMap())` sentinel (T5); a non-object slice is silently swallowed.
- **Solution:** SCOPE to the ~30 genuine option-slice reads (drop the inflated "119" — it conflates transport-JSON casts). Add on the FIX-042 type: `slice(id): JsonObject?` (single key, refactor downstream to accept null so the empty sentinel disappears) and `sliceFallback(vararg ids)` (HuggingFace alias case). Anthropic.kt:688-691 needs a MERGE (`mergeJsonObjects(slice(anthropicId), slice(customId))`, custom name computed dynamically) — NOT a vararg fallback. ABI-neutral only AFTER FIX-043 replaces the public Map field.
- **ABI:** none (depends on FIX-043).