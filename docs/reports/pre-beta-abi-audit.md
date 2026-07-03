# Pre-tag public-API evolvability audit — `0.3.0-beta01`

**Date:** 2026-07-03 · **HEAD:** `a67eec0` (branch `release/v0.3.0-beta01`)

**Purpose.** The entire public surface of this library is currently
`@since 0.3.0-beta01` and unreleased — every declaration is a free
rename/reshape today. The instant the tag lands, that window closes: every
missed evolvability wart becomes a `NoSuchMethodError` for a consumer, a
forced deprecation cycle, or a permanent maintenance scar. This is one
systematic sweep of the whole ABI surface against the Kotlin-library-
evolvability canon (Jake Wharton's "Public API challenges in Kotlin", the
JetBrains library-author backward-compatibility guide, and this repo's own
`CLAUDE.md` "Public value types" section), so every remaining issue becomes a
**conscious fix-or-accept decision** before the freeze instead of a default
inherited by silence. It supersedes nothing already decided in
`docs/data-class-audit.md` — those calls are respected and, where relevant,
cross-referenced for execution status only.

All findings are **recommendations**. The `Decision` column is left blank for
the owner.

## Executive summary

| Priority | Count |
|---|---:|
| P0 — fix before tag, near-certain regret if missed | 3 |
| P1 — should fix before tag | 10 |
| P2 — fix-or-consciously-accept | 9 |
| P3 — note only | 4 |
| **Total findings** | **26** |

**Single highest-leverage finding (F1):** the `@Poko` migration recorded in
`docs/data-class-audit.md` removed `copy()`/`componentN()` from ~289 produced
types, but **170 of those 289 (59%) still expose a public, directly-callable,
multi-parameter positional constructor with no `internal constructor` +
factory guard** — meaning the *other* half of the ABI-evolvability contract
(adding a field ever again) is still frozen for a majority of the "already
migrated" surface, including core types like `MCPToolDefinition` and every
`Lifecycle.AgentEvent` payload. This is bigger blast-radius than any single
interface or enum finding below and is not something `docs/data-class-audit.md`
tracked at all — it audited `data class` vs `@Poko`, not constructor
visibility once demoted.

## Ranked findings table

| ID | Priority | Category | Symbol | file:line | Risk (one line) | Recommendation (one line) | Decision |
|---|---|---|---|---|---|---|---|
| F1 | P0 | C5/C6 | 170 `@Poko` produced types (see detail) | e.g. `McpProtocol.kt:102`, `Lifecycle.kt:24` | Public positional ctor freezes field-count forever even though `copy()`/`componentN()` were removed | Add `internal constructor` + a factory/builder (or at minimum an `@JvmOverloads`-free append-only contract audit) to every produced `@Poko` type before tag | ACCEPTED — produced read-only types keep public ctors (policy clarified in CLAUDE.md); see F1 note |
| F2 | P0 | C1 | `MCPClient` | `MCP.kt:282` | 12-member interface, sole impl is `private class DefaultMCPClient`, produced only via `CreateMCPClient()` — adding a method breaks nothing today but the interface itself invites external implementation it was never designed for | Make `sealed interface MCPClient` (impl stays in-module) to reclaim member-growth freedom | APPLIED — sealed as `sealed class` (not `sealed interface`) to comply with the repo's `no-sealed-interface` gate; see MCP.kt:282 |
| F3 | P0 | C2 | `MessageRole` | `ModelMessage.kt:67` | 4-variant enum (`System,User,Assistant,Tool`), no catch-all; core wire type touched by every provider integration; direct precedent — OpenAI added a `developer` role after `system` was deprecated for reasoning models | Add a documented forward-compat contract (reserve an `Unknown`/`Other` variant now, or convert to sealed hierarchy with a default branch requirement) before tag | DOCUMENTED — forward-compat KDoc contract added; no new variant (owner-scoped to docs-only this pass) |
| F4 | P1 | C2 | `UIMessageRole` | `ui/UIMessage.kt:42` | 3-variant enum (`System,User,Assistant` — no `Tool`), same role-growth precedent as F3, scoped to the Kotlin-host UI layer | Same fix as F3, scoped to UI package | DOCUMENTED — forward-compat KDoc contract added; no new variant |
| F5 | P1 | C2 | `ToolCallState` | `ui/ToolCallState.kt:32` | 7-variant tool-call lifecycle enum, no catch-all; Vercel's own `ToolUIPart` state has grown release-over-release | Reserve an escape-hatch variant or document the exhaustive-`when` contract | DOCUMENTED — forward-compat KDoc contract added; no new variant |
| F6 | P1 | C2 | `ChatStatus` | `ui/Chat.kt:108` | 4-variant (`Ready,Submitted,Streaming,Error`) mirrors Vercel AI SDK's `ChatStatus` shape exactly — that upstream enum is a plausible growth template (e.g. reconnect states) | Same fix as F3 | DOCUMENTED — forward-compat KDoc contract added; no new variant |
| F7 | P1 | C2 | `FinishReason` | `Usage.kt:278` | Already has an `Other` catch-all (good) but **has already grown once** (`ToolApprovalRequested` added post-hoc per inline comment) — the mitigation only protects consumers who route unknowns through `Other`, not a genuinely-new named variant | Document explicitly: "exhaustive `when` on `FinishReason` MUST branch through `Other`" in the KDoc; consider whether future growth always goes through `Other` by policy | DOCUMENTED — KDoc contract added stating new variants route through `Other` |
| F8 | P1 | C2 | `RetryErrorReason` | `AiSdkError.kt:365` | 3-variant retry-failure category, no catch-all; error taxonomies are a canonical growth case | Add an `Unknown`/`Other` variant or document the contract | DOCUMENTED — forward-compat KDoc contract added; no new variant |
| F9 | P1 | C2 | `GatewayModelType` | `GatewayTypes.kt:39` | 5 variants (`Embedding,Image,Language,Reranking,Video`) — **concrete evidence of near-term growth**: the SDK already has 7 model kinds elsewhere (`SpeechModel`, `TranscriptionModel` exist as first-class model interfaces but have no `GatewayModelType` entry) | Add `Speech`/`Transcription` now while free, or document the gap as deliberate and add an escape-hatch variant | APPLIED — added `Speech` and `Transcription` variants + wire mapping |
| F10 | P1 | C3 | `ContentPart.metadata`, `StreamEvent.metadata` | `TypedJson.kt:38`, `TypedJson.kt:53` | Last 2 public top-level extension declarations after the object-member-extension migration; owner's stated direction is "no public extensions in this SDK" | Migrate to members on `ContentPart`/`StreamEvent` (or their sealed-leaf classes) to complete the stated policy before tag | APPLIED — migrated to member `val metadata` on the sealed base classes |
| F11 | P1 | C4 | `AgentEvent.Finished.output` | `Lifecycle.kt:172` | Always `null` today by design (documented "step-2 dispatch concern"); a permanently-nullable public field is exactly the kind of wart that's awkward to tighten later, and it carries no `@ExperimentalAiSdkApi` marker despite being explicitly unstable | Either wire the real value before tag, or annotate `@ExperimentalAiSdkApi` so it can still move post-tag | APPLIED — annotated `@ExperimentalAiSdkApi`; real value still not wired (out of scope) |
| F12 | P1 | C7 | `Schema<T>.validate`, `MiddlewareCallContext`, `EmbeddingMiddlewareCallContext`, `ImageMiddlewareCallContext` | `Tool.kt:558`, `Middleware.kt:104`, `Embedding.kt:306`, `ImageModels.kt:339` | All 4 are `@Poko` classes holding a closure field (`validate`, `doGenerate`, `doStream`, `doEmbed`) and/or a provider-instance field (`model: LanguageModel`/`EmbeddingModel`/`ImageModel`) — `@Poko`-generated `equals`/`hashCode`/`toString` on a lambda or provider handle is meaningless per CLAUDE.md's own rule | Convert all 4 to plain regular classes (remove `@Poko`), keep `internal constructor` | APPLIED — removed `@Poko` from all 4; constructor visibility unchanged (all were already public, as spec'd) |
| F13 | P1 | C10 | `AbortSignalFromJob` vs `AbortSignals.from(job)` | `AbortSignal.kt:200`, `AbortSignal.kt:212` | Two public spellings of the identical operation — `AbortSignals.from(job)` literally delegates to `AbortSignalFromJob(job)` — introduced by the *just-landed* member-extension migration (commit `a67eec0`) that added the `AbortSignals` object without removing the old top-level function | Deprecate/remove `AbortSignalFromJob` (and audit `CombineAbortSignals` for the same pattern) now, before either spelling has consumers | APPLIED — removed `AbortSignalFromJob`; body folded into `AbortSignals.from(job)`; `CombineAbortSignals` left as-is (no duplication found) |
| F14 | P2 | C1 | `AnthropicAwsProvider`, `BlackForestLabsProvider`, `ByteDanceProvider`, `OpenAICompatibleProvider`, `OpenResponsesProvider`, `GatewayProvider` | `providers/AnthropicAwsProvider.kt:184`, `providers/BlackForestLabsProvider.kt:372`, `providers/ByteDanceProvider.kt:226`, `providers/OpenAICompatibleProvider.kt:243`, `providers/OpenResponsesProvider.kt:441`, `Gateway.kt:247` | 6 branded per-provider facade interfaces, each `: Provider` with exactly one internal implementation (`Default*Provider` class or an `object :` singleton); no plausible consumer-implementation scenario | `sealed interface` each (or document "not for external implementation") to reclaim freedom to add provider-specific accessor methods | APPLIED — all 6 sealed as `sealed class` (not `sealed interface`) to comply with the repo's `no-sealed-interface` gate; anonymous singleton implementers converted to named `private object` (a sealed class forbids anonymous-object subtypes) |
| F15 | P2 | C1 | `UIMessageStreamWriter`, `ToolStreamWriter` | `ui/Streams.kt:67`, `Tool.kt:789` | Both are constructed by the SDK and handed to a *consumer-supplied lambda* as receiver (`execute: suspend UIMessageStreamWriter.() -> Unit`) or context field (`ToolPredicateOptions.writer`); zero named implementers anywhere including tests | Consider sealing, but note a legitimate consumer-testability use case (faking the writer in unit tests) argues for leaving open — flag for owner judgment rather than a hard recommendation | DEFERRED — left open; consumer testability (faking the writer) outweighs sealing |
| F16 | P2 | C2 | `MCPTransportKind` | `MCP.kt:1017` | 2 variants (`Http,Sse`); MCP spec transport surface is still evolving (e.g. WebSocket proposals) | Document forward-compat contract or reserve escape hatch | ACCEPTED — note-only per audit; low blast radius |
| F17 | P2 | C2 | `AgentSessionStatus` | `AgentSession.kt:19` | 5-variant session lifecycle enum; plausible growth (e.g. a `Paused` state distinct from `AwaitingApproval`) | Note only / accept | ACCEPTED — note-only per audit; low blast radius |
| F18 | P2 | C2 | `CompletionStreamProtocol` | `CompletionApi.kt:14` | 2-variant wire protocol enum (`Data,Text`), legacy-completion-API scoped (lower traffic than the core Agent/Stream surface) | Note only / accept | ACCEPTED — note-only per audit; low blast radius |
| F19 | P2 | C2 | `StreamEvent.SourcePart.SourceType` | `Streaming.kt:242` | 2 variants (`Url,Document`); could grow (e.g. `Image` source) | Note only / accept | ACCEPTED — note-only per audit; low blast radius |
| F20 | P2 | C2/C10 | `PruneReasoning` vs `PruneToolCalls` | `PruneMessages.kt:4`, `PruneMessages.kt:17` | Same semantic 3-value shape (`All`/`BeforeLastMessage`/`None`) modeled as an `enum class` in one case and a `sealed class` with `data object` leaves in the other, in the same file | Pick one shape for both (sealed class is the more ABI-evolvable and is already the pattern the file demonstrates) for consistency | APPLIED — `PruneReasoning` converted to `sealed class` + `data object` leaves, matching `PruneToolCalls` |
| F21 | P2 | C9 | DevTools surface (`DevToolsRecorder`, `DevToolsStep`, `DevToolsStepResult`) | `DevTools.kt:13,24,57` | Newer subsystem (added after the initial port, per `git log`), zero `@ExperimentalAiSdkApi` coverage despite being the kind of surface still being shaped | Consider `@ExperimentalAiSdkApi` on the DevTools package until the shape is proven in a release | APPLIED — annotated `DevToolsStep`, `DevToolsStepResult`, `DevToolsRecorder`, `InMemoryDevToolsRecorder`, `DevToolsMiddleware` with `@ExperimentalAiSdkApi` |
| F22 | P2 | C10 | `ProviderId`/`ModelId` construction | `ModelRef.kt:9-22`, `ModelRef.kt:73-78` | 3 equivalent public spellings for the same construction: the public value-class constructor `ProviderId(value)`, the companion factory `ProviderId.of(value)`, and `ModelIdentifiers.providerId(value)` (which just calls the constructor) | Pick one canonical spelling and consider deprecating the redundant ones (cheap now, a deprecation cycle later) | DEFERRED — `ModelIdentifiers` is a deliberate PascalCase-ctor-conflict workaround; consolidation is a non-mechanical decision |
| F23 | P3 | C2 | `GatewayAuthMethod`, `GatewayCredentialType`, `GatewaySpendReportDatePart`, `GatewaySpendReportGroupBy` | `Gateway.kt:16`, `GatewayTypes.kt:109,103,93` | 4 narrow Gateway-API enums (2-6 variants each), bounded blast radius (Gateway consumers only) | Note only / accept as-is | ACCEPTED — note-only per audit; low blast radius |
| F24 | P3 | C1 | `AbortSignal` | `AbortSignal.kt:26` | Not sealed; KDoc explains the *design rationale* (wraps `Job.cancel()`) but never states whether consumer implementations (e.g. bridging a platform cancellation token) are supported or discouraged | Add one sentence to the KDoc stating the intended implementability | DOCUMENTED — KDoc states consumer implementations are supported |
| F25 | P3 | C10 | `Create*` factory family vs bare-`Noun(...)` convention | `KtorGatewayTransport.kt:23`, `MCP.kt:1174`, `ui/Streams.kt:53,60,74` | 6 top-level functions use a `Create<Noun>(...)` verb-prefix while ~245 other factories use bare `<Noun>(...)` (the dominant convention, e.g. `CallSettings{}`, `Gateway(settings)`) | Cosmetic; rename the 6 to bare-noun form only if convenient, otherwise accept the family as a recognizable "explicit construction" idiom | ACCEPTED — note-only per audit; low blast radius |
| F26 | P3 | C2 | `TextUIPartState` | `ui/UIMessagePart.kt:53` | 2-variant (`Streaming,Done`) UI part state, no catch-all; `ToolCallState` (F5) shows this family does grow | Note only / accept | ACCEPTED — note-only per audit; low blast radius |

## Per-finding detail

### F1 — `@Poko` demotion left constructors unprotected (P0, C5/C6)

The `docs/data-class-audit.md` migration (batches D1–D14) correctly removed
`copy()`/`componentN()` from produced types by adding `@Poko`. It did **not**
uniformly add `internal constructor` + a factory. A repo-wide scan
(`@Poko` classes, python/regex-verified against source, not just the dump)
found:

- **289** public classes carry `@Poko`.
- **119** already use `internal constructor` (protected — e.g. `GenerateResult`
  at `Agent.kt:129`, which needed it anyway for an `internal val rawOutput`).
- **170 (59%)** have a plain public positional constructor with no
  `internal constructor` guard.

Representative unprotected examples (verified by direct read):

```kotlin
// McpProtocol.kt:102 — 1 required + 6 optional params, all public ctor
public class MCPToolDefinition(
    public val name: String,
    public val title: String? = null,
    // ... 5 more optional params
    @SerialName("_meta") public val meta: JsonObject? = null,
)

// Lifecycle.kt:24 — ALL params required, no defaults at all
public class Started<TContext>(
    public val prompt: String?,
    public val priorMessages: List<ModelMessage>,
    public val options: TContext?,
) : AgentEvent()
```

Per CLAUDE.md's own rule ("Adding a constructor field is a binary-incompatible
change... already-compiled callers hit `NoSuchMethodError` at link time"), this
risk is not specific to `data class` — it applies verbatim to any class with a
public positional constructor, `@Poko` or not. Only 9 uses of `@JvmOverloads`
exist repo-wide, so default-argument trailing-append is not a systematically
applied mitigation either. Any consumer who directly constructs one of these
170 types (a very common pattern for test doubles / fixtures around
`AgentEvent`, `StreamEvent`, `MCPToolDefinition`, media results, etc.) will
break the moment the SDK appends a field post-tag.

**Cost of deferring:** every one of the 170 types becomes a silent
constructor freeze the day the tag lands; each future field addition to any
of them becomes either an ABI break or a forced new-type/deprecation cycle.
This is a **fix-before-tag** decision precisely because it is invisible today
(the ABI dumps look "clean" — they show a public constructor exactly as
intended) and expensive to discover later.

**Decision override (2026-07-03):** the owner does NOT apply this
recommendation as written — the 170 types keep their public positional
constructors. Rationale: this report's own C6 ledger shows every
consumer-*constructed* type already uses `internal constructor` + builder;
the 170 remaining types are produced, read-only types where a public
constructor is policy-consistent with the "Growable read-only types" rule and
preserves consumer testability (constructing fakes/fixtures for `AgentEvent`,
`StreamEvent`, `MCPToolDefinition`, and similar). The constructor-visibility
policy is now explicit in `CLAUDE.md`'s "Public value types" section so this
is a conscious, recorded default rather than a silent gap.

### F2 — `MCPClient` should be sealed (P0, C1)

```kotlin
// MCP.kt:282, 12 abstract members (serverInfo, instructions, tools,
// listTools, toolsFromDefinitions, listResources, readResource,
// listResourceTemplates, experimental_listPrompts, experimental_getPrompt,
// onElicitationRequest, close)
public interface MCPClient { ... }
```

The only implementation anywhere in the tree is
`private class DefaultMCPClient(config: MCPClientConfig) : MCPClient` (same
file), constructed exclusively via `CreateMCPClient(config): MCPClient` /
`Experimental_CreateMCPClient(config): MCPClient` (`MCP.kt:340,344`). No KDoc
invites external implementation, unlike `Agent<TContext,TOutput>`
(`Agent.kt:43`) which explicitly documents "other implementations exist...
application code should depend on this interface" — the contrast is the
signal. `MCPClient` is a 12-method surface every MCP consumer touches by
reading, never implementing.

**Recommendation:** `public sealed interface MCPClient` — the existing
private in-module implementation is unaffected; the SDK regains the freedom
to add new MCP-spec-driven members without breaking a third party that could
never legitimately have implemented it.

### F3/F4/F5/F6 — role/state/status enums without a forward-compat contract (P0/P1, C2)

`MessageRole` (`ModelMessage.kt:67`), `UIMessageRole` (`ui/UIMessage.kt:42`),
`ToolCallState` (`ui/ToolCallState.kt:32`), and `ChatStatus` (`ui/Chat.kt:108`)
are the four highest-likelihood-to-grow enums in the surface, matched against
the task's own prime-suspect list (event/part types, roles, error
categories). `MessageRole` is P0 because it is the single most load-bearing
enum in the library (every `ModelMessage`, every provider adapter, every
consumer integration touches it), with a concrete real-world precedent
(OpenAI's `developer` role). The other three are P1: same shape of risk, but
each is scoped to the `ui` package, which CLAUDE.md's own "Scope" section
already marks as a narrower, Kotlin-host-only surface than the core wire
model.

**Recommendation for all four:** reserve a catch-all variant now (`Unknown`
or `Other`, following the precedent already set by
`AgentEvent.Errored.ErrorSource` at `Lifecycle.kt:138`, which ships
`Hook, Tool, PrepareStep, PrepareCall, Model, Unknown` — the one enum in this
codebase that already does this correctly) — or explicitly document that
these are closed sets the team is confident will never grow, which is a
harder claim to defend for `MessageRole` given the OpenAI precedent.

### F7 — `FinishReason` has a catch-all but has already grown once (P1, C2)

`FinishReason` (`Usage.kt:278`) is the one enum that already ships the
"documented forward-compat contract" pattern the task asks about — it has an
`Other` variant and a `fromOpenAI` wire-mapping helper. But its own inline
comment ("v6: generation paused because tool(s) need approval") shows
`ToolApprovalRequested` was added as a genuinely new named variant during the
v6 port, not routed through `Other`. The mitigation only protects consumers
if all *future* growth is disciplined to go through `Other`; nothing enforces
that discipline today.

**Recommendation:** add an explicit KDoc contract ("new finish reasons are
added as named variants only for provider-agnostic outcomes; provider-
specific ones route through `Other`") so a future contributor doesn't
silently add a variant.

### F9 — `GatewayModelType` is missing two of the SDK's seven model kinds (P1, C2)

```kotlin
// GatewayTypes.kt:39
public enum class GatewayModelType {
    Embedding, Image, Language, Reranking, Video,
}
```

The SDK has 7 first-class model interfaces (`LanguageModel`, `EmbeddingModel`,
`ImageModel`, `VideoModel`, `RerankingModel`, `SpeechModel`,
`TranscriptionModel` — verified in the C1 interface sweep). `GatewayModelType`
only covers 5 of them. This is not speculative growth-risk reasoning — it is
direct structural evidence that this enum is *already* behind the rest of the
surface and will need `Speech`/`Transcription` variants as soon as the
Gateway proxies those model kinds, which is a near-certainty given the
pattern of the other 5.

### F10 — the last 2 public extensions (P1, C3)

```kotlin
// TypedJson.kt:38
public val ContentPart.metadata: ProviderMetadata get() = when (this) { ... }
// TypedJson.kt:53
public val StreamEvent.metadata: ProviderMetadata get() = when (this) { ... }
```

A full sweep of `src/commonMain/kotlin` (top-level `public fun/val X.y`, plus
member-extensions inside classes/objects across every source set) found
**exactly these two** and nothing else — confirming the task's stated
"known survivors" list is complete, not partial. Everything else that looked
like a member-extension (7 hits in `TypedJson.kt`'s `TypedJsonOps`) lives
inside an **`internal object`**, so it is not actually public ABI (matches
the exemption already written into the `no-public-member-extension-in-object`
gate rule's own comment). These 2 are genuinely public and are exactly the
kind of declaration the just-landed migration (7 objects, `no-public-member-
extension-in-object` gate) was designed to eliminate — they were simply
top-level, not inside an object, so the new gate doesn't catch them.

**Recommendation:** move both onto their sealed hierarchies as members (e.g.
an abstract/open `val metadata: ProviderMetadata` on `ContentPart` /
`StreamEvent` with each leaf overriding it), matching the member-not-
extension direction already executed for the object cases.

### F11 — `AgentEvent.Finished.output` (P1, C4)

Already documented in-source as the exemplar of this problem class:

```kotlin
// Lifecycle.kt:160-172
// Nullable for now: the base loop doesn't compute the typed output here (it flows via
// `generate(): TOutput`); `null` preserves the prior behavior. Wiring a real value is a
// step-2 dispatch concern. Typed (no `Any?`) regardless.
/**
 * Always `null` today (see the step-2 note above) — ...
 */
public val output: TOutput?,
```

This is not an `Any?`-typed field (the `no-any-typed-public-property` gate
correctly doesn't fire on it — it's properly typed `TOutput?`), so it's a
distinct problem from C4's `Any?` case: a **permanently-null-in-practice**
public field with no compiler or gate signal that it's unstable. It carries
no `@ExperimentalAiSdkApi` marker, so a consumer has no annotation-level
warning that this field's null-ness is a temporary implementation gap rather
than a documented contract.

### F12 — value-semantics violations on closure/provider-holder `@Poko` classes (P1, C7)

Four confirmed `@Poko` classes with function-typed or provider-instance
fields, verified by direct read of both the `@Poko` annotation and the field:

```kotlin
// Tool.kt:556-561
@Poko
public class Schema<T>(
    public val jsonSchema: JsonElement,
    public val validate: ((JsonElement) -> T)? = null,
)

// Middleware.kt:102-108
@Poko
public class MiddlewareCallContext(
    public val params: LanguageModelCallParams,
    public val model: LanguageModel,
    public val doGenerate: suspend (LanguageModelCallParams) -> LanguageModelResult,
    public val doStream: (LanguageModelCallParams) -> Flow<StreamEvent>,
)

// Embedding.kt:304-309 — same shape: model: EmbeddingModel + doEmbed: suspend (...) -> ...
// ImageModels.kt:337-342 — same shape: model: ImageModel + doGenerate: suspend (...) -> ...
```

Per CLAUDE.md's own rule: "Construct-types that hold a FUNCTION or other
non-value field... are NOT value types — value equality on a closure is
meaningless. Make these a plain regular class... Do NOT reach for
`@Poko.Skip` to force value semantics onto a closure-holder."
`@Poko`-generated `equals`/`hashCode`/`toString` on these 4 types will
compare lambda identity and call `toString()` on a `Function1`/`Function2`
instance, which is exactly the anti-pattern the rule names.

**Recommendation:** drop `@Poko` from all 4, make them plain regular classes
with `internal constructor` (they're already produced-only middleware-call
context objects, not consumer-constructed, so this is a pure quality fix with
no builder needed).

### F13 — `AbortSignalFromJob` / `AbortSignals.from` duplication (P1, C10)

```kotlin
// AbortSignal.kt:200
public fun AbortSignalFromJob(job: Job): AbortSignal { ... }

// AbortSignal.kt:219-212 (object AbortSignals)
public fun from(job: Job): AbortSignal = AbortSignalFromJob(job)
```

Confirmed by direct read: `AbortSignals.from(job)` literally delegates to
`AbortSignalFromJob(job)` — same operation, two public spellings, one calling
the other. This is freshly introduced by the working tree's own uncommitted
member-extension migration (`AbortSignals is now plain factory functions
instead of member-extensions`, per `CHANGELOG.md`) — the new `AbortSignals`
object was added without removing the pre-existing top-level function it now
wraps. Because this is literally the newest code in the diff, it is the
cheapest possible fix window: nothing has shipped depending on either name
yet.

**Recommendation:** pick one (the `AbortSignals.from(...)` member-factory
form is more consistent with the rest of C10's dominant idiom) and remove or
internalize the other before tag. Also audit `CombineAbortSignals`
(`AbortSignal.kt:230`) for the same duplication pattern — it doesn't
currently have one, but the naming precedent means it's worth a second look
before more `AbortSignal*` top-level functions accumulate.

## Per-category coverage ledger

| # | Category | Candidates checked | Flagged | Notes |
|---|---|---:|---:|---|
| C1 | Public interfaces | 43 | 9 (F2, F14 ×6, F15 ×2) | 3 already `sealed interface` (`JSONRPCMessage`, `ResponseFormat`, `UIMessagePart` — clean); 3 `fun interface`/SAM (`RetryDelayGenerator`, `StopCondition`, `LiteRTConversationFactory` — correctly open, no freeze risk by construction); ~27 verified consumer-implementable by design (real or test-double implementers found: `LanguageModel`, `Provider`, all 7 `*Model` interfaces, `*Middleware` family, `Logger`, `Redactor`, `Telemetry`/`TelemetryTracer`/`TelemetryActiveSpan`, `*Transport` family, `OAuthClientProvider`, `DevToolsRecorder`, `ServerResponseWriter`, `LiteRTConversation`, `Agent` — explicitly documented as the app-facing contract). `AbortSignal` (F24) and nested `AbortSignal.AbortRegistration` reviewed; the latter is clean (produced-only handle, 1 method). |
| C2 | Enums consumers exhaustively `when` over | 24 | 16 (F3-F9, F16-F20, F23 ×4, F26) | 8 confirmed clean: `AuthResult`, `MiddlewareOperation`, `PartialJsonState`, `PruneEmptyMessages`, `LiteRTMessageRole`, `LiteRTStreamTextMode` (narrow/closed sets, low blast radius), plus `AgentEvent.Errored.ErrorSource` (the one enum that already ships the `Unknown` catch-all pattern correctly — cite as the exemplar the others should copy). |
| C3 | Remaining public extension declarations | Full `src/commonMain` sweep (top-level + member-in-object + member-in-class, all source sets) | 2 (F10) | Confirmed exhaustive — no survivors beyond the 2 known `TypedJson.kt` properties; the 7 `TypedJsonOps` member-extensions are inside an `internal object` and are not public ABI. |
| C4 | Nullable/placeholder/`Any?` public fields | Repo-wide `Any?`/`Any` grep + "always null/TODO/step-2/gap" comment sweep | 1 (F11) | `no-any-typed-public-property` gate already owns the `Any?` case (1 documented exception: `Lifecycle.kt` context fields, `TContext?`/`TOutput?` generics — not double-reported). The "historical parity gap #NN" comments found throughout are narrative/rationale citations, not instability markers — only `Finished.output` is a genuine placeholder-nullable field. |
| C5 | `data class` budget reconciliation vs. the tag | 40 tracked (`data-class-budget.json`) cross-referenced against `docs/data-class-audit.md`'s DEMOTE(178)/BUILDER(139)/KEEP(34+7+2) classification | 0 gap in the 40; **1 systemic gap found outside the 40 (F1)** | The DEMOTE and BUILDER migration tracks are **already executed**, not merely planned — spot-checked `StreamStart` (D1 canary), `Lifecycle.Started` (D2), `ModelMessage` (D3), and every `*Settings`/`*Options`/`*Params`/`*Config`/`*Policy`/`*Credentials` class: all confirmed `@Poko` or `internal constructor` + builder as classified. The current 40 tracked data classes match the audit's KEEP floor exactly (verified by diffing `data-class-budget.json` against the KEEP/CONFIRMED-KEEP tables) — no KEEP-decided type is at risk of silent freeze, and `REVIEW (0)` in the audit doc is accurate (nothing pending a decision). The real gap the tag deadline should worry about is **F1**, which the data-class audit's scope never covered (it audited class-kind, not constructor visibility post-demotion). |
| C6 | Construct-types with frozen public positional constructors | 68 candidate files (`*Settings`/`*Options`/`*Params`/`*Config`/`*Policy`/`*Credentials`/`*Request` class declarations) | 0 | Clean — every consumer-constructed config/settings/options/params/policy/credentials type already uses `internal constructor` + builder + DSL factory (spot-verified: `CallConfig`, `RetryPolicy`, `AgentSettings`, `StepSettings`, and the full provider-settings family). The constructor-freeze risk in this codebase is concentrated instead in **produced** (not consumer-constructed) types — see F1/C5, not C6. |
| C7 | Value-semantics correctness on function/closure holders | 289 `@Poko` classes + all 40 tracked `data class`es scanned for `->`/`suspend`/`KSerializer`/provider-typed fields | 4 (F12) | The 40 tracked data classes are clean (none hold closures). `IdGenerator` (holds `Random`) and `CustomProvider` (holds a model-object map) were already correctly demoted to plain regular classes in the KEEP-floor audit — not re-flagged. |
| C8 | Accidental public surface (visibility leaks) | Repo-wide `Impl`/`Internal`/`Wire`/`Codec`/`Default`-prefix + generic-name (`Cache`/`Registry`/`Pool`/`Buffer`/`Manager`) sweep of both dumps | 0 | Clean — no `*Impl`/`*Internal`/`*Wire`/`*Codec`/`Default*` leaks in either dump. `ProviderRegistry`, `TelemetryRegistry`, `ToolPartHandlerRegistry` are deliberate, well-documented multi-provider dispatch types, not leaks. The `Mock*Model` family (7 types: `MockEmbeddingModel`…`MockVideoModel`, one per `*Model` interface) is a complete, symmetric, deliberate test-support export — matches the task's own "public by clear design" carve-out. `GoogleProvider.Agent`/`ManagedAgent`/`Model` and `MockLanguageModel` are already KEEP-decided in `docs/data-class-audit.md` — not re-litigated. |
| C9 | `@ExperimentalAiSdkApi` coverage gap | All 28 real application sites (12 files) + the always-null/TODO/gap-marker sweep from C4 | 2 (F11, F21) | Ground-truth correction: the annotation appears only ~1-2× in the *ABI dumps* (which only render the annotation-class declaration itself, not its application sites — dumps don't show `@RequiresOptIn`-style annotations on members), but **28 real call sites across 12 files** use it (MCP `experimental_listPrompts`/`experimental_getPrompt`/`experimental_MCPClient`, `experimental_generateImage`/`Video`/`Speech`/`transcribe`, `experimental_repairToolCall`, `experimental_toolApprovalSecret`, `experimental_context`) — coverage is broader and more deliberate than the dump alone suggests. The 2 flagged gaps (`Finished.output`, the DevTools package) are the genuine misses against the task's own candidate list (MCP surface: well-covered; UI message-stream types: reviewed, no comparable instability markers found). |
| C10 | Factory/conversion naming consistency | ~251 top-level PascalCase factories + all companion-object `of`/`from`/`parse`/`create` factories | 3 (F13, F22, F25) | Internally, `.of(...)` (simple wrap: `ToolCallId`, `ToolName`, `ApprovalId`, `Usage`, `ProviderId`, `ModelId`, `NonEmptyMessages`) and `.parse(...)` (fallible string parse: `DataUrl`, `ModelRef`) are each used consistently for their respective operation kind — no internal inconsistency within those two families. The `Wrap*`/`Combine*`/`Default*Middleware` imperative-verb functions are a distinct, correctly-different operation kind (transform an existing instance, not construct one) and are not flagged. |

## Explicitly out of scope / already-owned

- **Member-extension-in-object migration** — done (7 objects: `ProviderModels`,
  `GeneratedFiles`, `AgentSessions`, `ChatSessionFactory`, `ToolResultOutputs`,
  `UsageArithmetic`, `UIMessageMetadata`); the new
  `no-public-member-extension-in-object` gate rule enforces it going forward.
  Not re-audited beyond confirming its two residual survivors (F10).
- **The 4 KEEP state containers** (`AgentSessionState`, `ToolLoopAgentState`,
  `ChatState`, `CompletionState`) — D11 reclassification in
  `docs/data-class-audit.md` (2026-06-30) is respected as-is; `copy()` is
  contractual for their `MutableStateFlow.update { it.copy() }` idiom. Not
  re-opened.
- **`ModelMessage.ContentPart.ToolApprovalRequest`** (`ModelMessage.kt:217`)
  — the audit doc's "CONFIRMED KEEP after KEEP-floor audit" prose explicitly
  lists `ToolApprovalRequest` in the re-confirmed KEEP floor, overriding an
  earlier BUILDER classification in the same document's batch table. The
  current `data-class-budget.json` matches this KEEP decision exactly (it's
  one of the 40 tracked types). Confirmed executed, not re-litigated.
- **All 40 tracked `data class`es in `data-class-budget.json`** — cross-
  referenced 1:1 against the audit doc's KEEP / CONFIRMED-KEEP /
  owner-resolved tables; every entry matches a recorded owner decision. No
  gap between plan and executed state found (see C5 coverage ledger row).
- **The `no-any-typed-public-property` gate's documented `Lifecycle.kt`
  exception** — not double-reported (see C4 coverage ledger row).
