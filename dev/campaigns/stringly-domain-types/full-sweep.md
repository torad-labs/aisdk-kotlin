# Stringly Domain Types: Full Codebase Sweep

**Date:** 2026-07-25  
**Scope:** All production Kotlin source sets in `aisdk-kotlin`  
**Mode:** Read-only architectural audit  
**Baseline:** Current worktree on `release/v0.3.0-beta01`

## Purpose

This document records a full sweep of stringly typed state, action, command, transport, provider-option, identifier, and wire-protocol concepts that might benefit from:

- sealed classes or interfaces with typed variants,
- enums for genuinely closed scalar domains,
- value classes for domain identifiers or provider values,
- typed parsing at open wire boundaries,
- or other models that make invalid states unrepresentable.

The central rule of the audit was that a raw `String` is not automatically a design defect. Every candidate was classified by who owns the vocabulary:

1. **Library-closed:** the SDK controls the complete domain and can safely model it exhaustively.
2. **Provider-open:** known values exist, but a provider may add more. These need known typed values plus a raw/custom escape hatch.
3. **Standards-open:** values such as MIME types are intentionally extensible and should remain strings or, at most, validated open wrappers.
4. **Identifier:** an opaque identity may warrant a value class if the SDK owns meaningful validation or if preventing accidental interchange outweighs migration cost.
5. **Display or diagnostic text:** prose and raw provider explanations should remain strings.

This distinction is load-bearing. Sealing an open provider or protocol vocabulary would reduce forward compatibility and make the SDK harder to evolve.

## Audit coverage

The read-only sweep covered every production source set:

- `src/commonMain/kotlin`
- `src/jvmAndAndroidMain/kotlin`
- `src/nativeMain/kotlin`

Structural inventory results:

- 263 `when` expressions in `commonMain` containing string literals.
- 375 named `String` fields associated with state, status, type, mode, operation, identifier, format, provider, and reason concepts.
- 54 existing sealed declarations inspected to avoid proposing models that already exist.
- The complete 6,460-line string-dispatch inventory was consumed and classified.
- `jvmAndAndroidMain` contained one benign stdio framing loop match.
- `nativeMain` contained no equivalent raw string dispatch.
- Candidate files and relevant recent history were read before recommendations were made.

No repository files were modified during the audit itself. Existing worktree changes were treated as active concurrent work and left untouched.

## Executive conclusion

The codebase is already substantially better typed than the raw string count initially suggests. Its central domains are correctly represented by types including:

- `StreamEvent`
- `ContentPart`
- `ToolResultOutput`
- `ToolLoopAgentAction`
- `ToolLoopAgentState.Phase`
- `CompletionPhase`
- `StructuredObjectPhase`
- `GenerationInput`
- `FileData`
- `GoogleInteractionsStreamState.OpenBlockState`
- `McpConnectionLifecycle.State`

The highest-value remaining work is **not** to add enums for every provider string. It is to:

1. replace nullable field bags with state variants that own only their valid data,
2. type local routing selectors that change endpoint or request topology,
3. parse repeated provider statuses into private `Known`/`Unknown(raw)` models,
4. expose named typed values for evolving provider options while retaining a custom wire escape hatch,
5. and repair ineffective options before expanding their public type surface.

For internal hierarchies, `data class` variants are appropriate. For public hierarchies, variants should use `@Poko` classes or `data object`s in accordance with the project’s ABI-evolution policy.

---

# High-impact migrations

## 1. Replace the UI tool lifecycle enum-plus-nullable-bag with sealed state variants

### Locations

- `src/commonMain/kotlin/ai/torad/aisdk/ui/UIMessagePart.kt:92`
- `src/commonMain/kotlin/ai/torad/aisdk/ui/UIMessagePart.kt:316`
- `src/commonMain/kotlin/ai/torad/aisdk/ui/InferAgentMessage.kt:53`
- `src/commonMain/kotlin/ai/torad/aisdk/ui/ToolCallState.kt:33`

### Current shape

`UIMessagePart.ToolUI`, `UIMessagePart.DynamicToolUI`, and `UIToolInvocation` combine:

- `ToolCallState`,
- nullable `input`,
- nullable `output`,
- nullable `error`,
- `preliminary`,
- nullable approval ID,
- and nullable approval signature.

That representation admits many invalid combinations:

- `OutputAvailable` with no output,
- `OutputError` with no error,
- `InputStreaming` with a final output,
- `ApprovalRequested` with no approval identity,
- `preliminary = true` in a non-output state,
- approval fields populated in unrelated states,
- and `DynamicToolUI` carrying approval-like enum states despite not carrying the full approval payload.

Methods such as `outputAs()` and `inputAs()` compensate at runtime by consulting the enum and returning `null` when the field bag does not line up with the selected state. The type itself should prevent those combinations.

### Recommended shape

```kotlin
@Serializable
public sealed interface ToolInvocationState {
    @Poko
    public class InputStreaming(
        public val partialInput: JsonElement? = null,
    ) : ToolInvocationState

    @Poko
    public class InputAvailable(
        public val input: JsonElement,
    ) : ToolInvocationState

    @Poko
    public class ApprovalRequested(
        public val input: JsonElement,
        public val approvalId: String,
        public val signature: String? = null,
    ) : ToolInvocationState

    @Poko
    public class OutputAvailable(
        public val input: JsonElement?,
        public val output: JsonElement,
        public val preliminary: Boolean = false,
    ) : ToolInvocationState

    @Poko
    public class OutputError(
        public val input: JsonElement?,
        public val message: String,
    ) : ToolInvocationState

    @Poko
    public class OutputDenied(
        public val input: JsonElement?,
        public val approvalId: String?,
        public val reason: String? = null,
    ) : ToolInvocationState
}
```

`ToolUI` and `DynamicToolUI` would carry one lifecycle value rather than seven independently combinable properties. `UIToolInvocation<TInput, TOutput>` should expose an equivalent generic sealed lifecycle instead of recreating the nullable bag after decoding.

The current flat JSON representation does not need to change. It can be preserved through a custom serializer or an internal wire DTO that maps to the public typed lifecycle.

### Quality impact

This is the strongest type-design improvement found in the sweep because it eliminates real invalid states across UI rendering, persistence, tool approval, dynamic-tool handling, and typed renderer dispatch.

### ABI and migration

This is a public ABI migration:

- If `0.3.0-beta01` has not shipped, it is worth considering before the surface freezes.
- If compatibility must already be preserved, first add an additive typed lifecycle view and validated factories, then deprecate direct construction of invalid combinations.
- Public variants should use `@Poko`, not public `data class`.
- Adding variants to a public sealed hierarchy can break exhaustive downstream `when` expressions, so the hierarchy must remain focused on SDK-owned states.

### Required tests

- Round trip each lifecycle state through serialization.
- Prove invalid field combinations cannot be constructed through the typed API.
- Cover `ToolUI` and `DynamicToolUI` rendering.
- Cover `UIToolInvocation` typed input/output extraction.
- Cover persisted-message rehydration.
- Cover approval request, response, denial, and resume conversion.
- Cover preliminary output transitioning to final output.

## 2. Give Rev.ai polling a private sealed state parser

### Location

`src/commonMain/kotlin/ai/torad/aisdk/providers/RevaiProvider.kt:413`

### Current shape

One mutable `JsonObject` carries submission, polling, and final result state. Raw status extraction is repeated through the operation. Unknown, missing, or non-string statuses effectively become retryable until timeout.

The polling loop also uses `return@repeat`, which returns from the current lambda iteration rather than from the polling operation. A `transcribed` job can therefore burn remaining attempts before the final state check.

### Recommended shape

```kotlin
private data class RevaiJobSnapshot(
    val id: String?,
    val language: String?,
    val state: RevaiJobState,
)

private sealed interface RevaiJobState {
    data object Transcribed : RevaiJobState
    data object Failed : RevaiJobState
    data class Pending(val raw: String) : RevaiJobState
    data class Unknown(val raw: String?) : RevaiJobState
}
```

Decode the provider response once:

- `Transcribed` exits polling immediately.
- `Failed` throws immediately.
- Only `Pending` delays and retries.
- `Unknown(raw)` fails as malformed provider data rather than masquerading as a timeout.

This type is private, so it has no public ABI cost.

### Required tests

- Submission already returns `transcribed`: no status GET, then transcript GET.
- `in_progress → transcribed`: exact status GET count.
- Failed initial response.
- Failed polled response.
- Missing status.
- Non-primitive status.
- Unknown status.
- Exhausted pending attempts.

## 3. Type xAI video operations and reject unknown routing values

### Locations

- `src/commonMain/kotlin/ai/torad/aisdk/providers/XaiProvider.kt:401`
- `src/commonMain/kotlin/ai/torad/aisdk/providers/XaiMediaModels.kt:170`

### Current shape

The raw mode determines the endpoint:

- `edit-video` selects `/videos/edits`.
- `extend-video` selects `/videos/extensions`.
- Every other value selects `/videos/generations`.

An explicit typo therefore silently changes the operation rather than failing. Edit and extend also discover missing `videoUrl` only later during body construction, and reference generation has separate reference-image requirements.

### Recommended shape

A closed operation model is appropriate because each future operation requires SDK routing, request-schema, and validation logic.

```kotlin
public enum class XaiVideoOperation(public val wireValue: String) {
    Generate("generate-video"),
    Edit("edit-video"),
    Extend("extend-video"),
    Reference("reference-video"),
}
```

If operation-specific required inputs should be owned by the variant, use a sealed consumer-constructed model instead. Parameterized public variants should use internal constructors plus builders/factories and `@Poko`, not public data classes.

Add a distinctly named typed builder method. Retain and deprecate the existing raw `mode(String?)` method as a compatibility bridge. The routing value can remain excluded from the request payload exactly as it is today.

### Required tests

- Endpoint and body for every operation.
- Missing edit URL.
- Missing extend URL.
- Missing reference images.
- Legacy-string parity.
- Unknown legacy mode must not fall through to ordinary generation.

## 4. Parse Replicate video prediction states once

### Location

`src/commonMain/kotlin/ai/torad/aisdk/providers/ReplicateProvider.kt:668`

### Current shape

Replicate video status is parsed once to decide whether polling should continue and again for terminal handling near `ReplicateProvider.kt:684`. A status outside the recognized set can fall through as apparent success and then fail later with a misleading missing-output error.

### Recommended shape

```kotlin
private sealed interface ReplicatePredictionState {
    data object Starting : ReplicatePredictionState
    data object Processing : ReplicatePredictionState
    data object Succeeded : ReplicatePredictionState
    data object Failed : ReplicatePredictionState
    data object Canceled : ReplicatePredictionState
    data class Unknown(val raw: String) : ReplicatePredictionState
}
```

- Poll only `Starting` and `Processing`.
- Read output only for `Succeeded`.
- Preserve current failure and cancellation errors.
- Fail `Unknown(raw)` immediately with `InvalidResponseDataError`.

Do not apply the same strict classifier to Replicate image handling. That path intentionally accepts an output-authoritative `Prefer: wait` response whose status may be absent.

### Required tests

- `processing → succeeded` remains correct.
- Canceled initial and polled responses.
- Failed after processing.
- Unknown status fails immediately without another poll.
- Missing and non-primitive status behavior remains intentional.

## 5. Close Quiver and Luma local routing selectors

These are stronger candidates than ordinary provider-option enums because they alter endpoint selection, request topology, and validation.

### Quiver operation

**Locations:**

- `src/commonMain/kotlin/ai/torad/aisdk/providers/QuiverAIProvider.kt:29`
- `src/commonMain/kotlin/ai/torad/aisdk/providers/QuiverAIProvider.kt:302`
- `src/commonMain/kotlin/ai/torad/aisdk/providers/QuiverAIProvider.kt:438`

The complete domain is `generate` or `vectorize`. Each operation has a different:

- endpoint,
- request body,
- prompt requirement,
- file cardinality,
- and applicable option set.

Use `QuiverAIOperation.Generate` and `QuiverAIOperation.Vectorize` with explicit wire values. Do not include `Custom`, because an unknown operation cannot work without corresponding endpoint and request-schema code.

Tests should cover both endpoint/body paths, generate and vectorize cardinality, operation-specific option handling, legacy raw parity, and unknown legacy rejection.

### Luma reference type

**Locations:**

- `src/commonMain/kotlin/ai/torad/aisdk/providers/LumaProvider.kt:29`
- `src/commonMain/kotlin/ai/torad/aisdk/providers/LumaProvider.kt:234`

The complete locally supported domain is:

- `image`
- `style`
- `character`
- `modify_image`

Each value creates a different request body and different cardinality rules. Use a closed `LumaReferenceType` enum or sealed model. A sealed configuration model becomes more valuable if it also replaces the loosely structured per-image JSON configuration.

The existing behavior has two additional concerns that should be decided during migration:

- an unknown selector is ignored when no files are present because request construction returns before reading it,
- raw option keys can overwrite generated editing fields after the typed branch creates them.

Tests should cover all four body shapes, per-type cardinality, the no-file selector policy, legacy parity, and reserved-key collision behavior.

### Compatibility for both

- Add a non-null typed builder method.
- Retain and deprecate the raw nullable setter.
- Store one canonical raw wire value internally.
- Avoid nullable same-name typed overloads because calls such as `operation(null)` become ambiguous.
- Preserve exact wire strings.

## 6. Correct and then type xAI image output format

### Locations

- `src/commonMain/kotlin/ai/torad/aisdk/providers/XaiProvider.kt:322`
- `src/commonMain/kotlin/ai/torad/aisdk/providers/XaiMediaModels.kt:50`
- `src/commonMain/kotlin/ai/torad/aisdk/providers/XaiMediaModels.kt:67`

Known values are `png`, `jpeg`, and `webp`, but this is a provider-owned vocabulary that may evolve.

More importantly, base64 output is currently always exposed as `image/png`, even when JPEG or WebP was requested. Adding a typed option before fixing output media-type propagation would make a false correctness promise.

### Recommended shape

After fixing media-type propagation, use an open value type with named constants rather than a closed enum:

```kotlin
@JvmInline
public value class XaiImageOutputFormat private constructor(
    public val wireValue: String,
) {
    public companion object {
        public val Png: XaiImageOutputFormat = XaiImageOutputFormat("png")
        public val Jpeg: XaiImageOutputFormat = XaiImageOutputFormat("jpeg")
        public val Webp: XaiImageOutputFormat = XaiImageOutputFormat("webp")

        public fun custom(wireValue: String): XaiImageOutputFormat =
            XaiImageOutputFormat(wireValue.requireNotBlank())
    }
}
```

This provides typo-resistant known values without pretending the provider protocol is permanently closed.

### Required tests

- Exact request value for every known codec.
- Returned base64 media type for PNG, JPEG, and WebP.
- Custom format passthrough.
- Blank custom value rejection.
- Legacy raw bridge parity and precedence.

---

# Good typed-boundary improvements

## 7. Voyage embedding input and output values

### Locations

- `src/commonMain/kotlin/ai/torad/aisdk/providers/VoyageProvider.kt:24`
- `src/commonMain/kotlin/ai/torad/aisdk/providers/VoyageProvider.kt:253`

Known `inputType` values:

- `query`
- `document`

Known `outputDtype` values:

- `float`
- `int8`
- `uint8`
- `binary`
- `ubinary`

These are provider-evolving values. Use value classes with named constants and `custom(...)`, not enums or sealed hierarchies that imply closure. Since these are outbound settings, the escape variant is conceptually `Custom`, not `Unknown`; `Unknown(raw)` is for inbound parsing.

### Correctness prerequisite

The current result path exposes `List<List<Float>>` and unconditionally decodes numeric arrays. Packed binary output lacks an honest result representation that preserves dtype and original dimensions. Typed compressed/binary output must not be advertised as fully supported until that result policy is decided.

Recommended order:

1. Decide the compressed-result representation.
2. Test actual request and response paths.
3. Add typed known values with a custom escape.

### Required tests

- Known and custom values reach actual request JSON.
- Null/absent behavior.
- Legacy raw parity.
- Model capability restrictions.
- Explicit packed-output representation and decoding behavior.

## 8. Centralize Google Interactions status parsing

### Locations

- `src/commonMain/kotlin/ai/torad/aisdk/providers/GoogleInteractionsMapping.kt:641`
- `src/commonMain/kotlin/ai/torad/aisdk/providers/GoogleInteractionsStreamState.kt:179`

The provider status is independently mapped to a `FinishReason` and to streaming terminality. The provider domain is open, so the raw value must remain available, but both paths should consume one private parsed representation.

```kotlin
private sealed interface GoogleInteractionStatus {
    data object Completed : GoogleInteractionStatus
    data object RequiresAction : GoogleInteractionStatus
    data object Failed : GoogleInteractionStatus
    data object Incomplete : GoogleInteractionStatus
    data object Cancelled : GoogleInteractionStatus
    data class Unknown(val raw: String?) : GoogleInteractionStatus
}
```

Centralize `finishReason` and `isTerminal`. Continue preserving `rawFinishReason`; `Unknown` should retain the current forward-compatible behavior.

This is lower priority than Rev.ai because current Google behavior is already safe. Its value is preventing mapping drift between synchronous and streaming paths.

## 9. Add typed APIs for selected finite provider options

These are useful but lower impact because they currently pass through to provider JSON rather than controlling substantial local state machines.

### AssemblyAI summary model and type

**Location:** `src/commonMain/kotlin/ai/torad/aisdk/providers/AssemblyAIProvider.kt:129`

Known summary models:

- `informative`
- `conversational`

Known summary types:

- `bullets`
- `bullets_verbose`
- `gist`
- `headline`
- `paragraph`

Use explicit-wire enums and additive typed builder APIs. Validate coherence with summarization being enabled. Preserve deprecated raw setters.

### Cohere thinking configuration

**Location:** `src/commonMain/kotlin/ai/torad/aisdk/providers/CohereProvider.kt:138`

The better API is not simply an enum for `type`. Model the relationship between enabled thinking and its token budget through one constrained operation such as:

```kotlin
thinkingEnabled(budgetTokens: Int)
```

This prevents incoherent type/budget combinations while preserving the existing primitive wire representation.

### Cohere embedding input type

**Location:** `src/commonMain/kotlin/ai/torad/aisdk/providers/CohereProvider.kt:179`

This is provider-evolving. Offer named known constants plus `Custom(nonBlank)` and preserve the raw bridge.

### Open Responses allowed-tools mode

**Location:** `src/commonMain/kotlin/ai/torad/aisdk/providers/OpenResponsesProvider.kt:406`

The locally supported values are `auto` and `required`. Use an explicit-wire type such as `OpenResponsesAllowedToolsMode`, with an additive typed builder method and raw compatibility bridge.

### Fireworks thinking configuration

**Location:** `src/commonMain/kotlin/ai/torad/aisdk/providers/FireworksFacade.kt:30`

Like Cohere, prefer one constrained `thinkingEnabled(budgetTokens)` operation over separately mutable type and budget fields.

### Rev.ai diarization type

**Location:** `src/commonMain/kotlin/ai/torad/aisdk/providers/RevaiProvider.kt:34`

This is outbound provider policy rather than display text, but the vendor owns and may extend the domain. Offer documented known values plus `Custom(nonBlank)`, not a closed enum.

### Black Forest Labs output format

**Location:** `src/commonMain/kotlin/ai/torad/aisdk/providers/BlackForestLabsProvider.kt:68`

This is provider-evolving. If promoted, use constants plus `Custom`, preserving the primitive string wire value.

---

# Deepgram provider-local algebraic output configuration

## Locations

- `src/commonMain/kotlin/ai/torad/aisdk/providers/DeepgramSpeechModel.kt:81`
- `src/commonMain/kotlin/ai/torad/aisdk/providers/DeepgramSpeechModel.kt:140`
- `src/commonMain/kotlin/ai/torad/aisdk/providers/DeepgramSpeechModel.kt:200`

The generic `SpeechGenerationParams.responseFormat: String?` should remain open because speech providers support incompatible vocabularies. Deepgram, however, interprets its output as a structured combination of:

- encoding,
- container,
- sample rate,
- bitrate,
- aliases,
- and compound strings such as `<encoding>_<sampleRate>`.

Supported families include:

- MP3,
- AAC,
- FLAC,
- Opus/Ogg,
- WAV with linear PCM, mu-law, or A-law,
- and raw PCM.

The current parser repairs or drops incompatible options through warnings and query mutation. Separate encoding/container enums would not solve the actual invariant because the legality lives in their cross-product.

## Recommended shape

Add a provider-local algebraic output model whose variants own only valid tuples:

```kotlin
public sealed interface DeepgramSpeechOutput {
    public data object Mp3 : DeepgramSpeechOutput
    public data object Aac : DeepgramSpeechOutput
    public data object Flac : DeepgramSpeechOutput
    public data object Opus : DeepgramSpeechOutput

    @Poko
    public class Wav internal constructor(
        public val encoding: WavEncoding,
        public val sampleRate: Int,
    ) : DeepgramSpeechOutput

    @Poko
    public class RawPcm internal constructor(
        public val sampleRate: Int,
    ) : DeepgramSpeechOutput
}
```

Because parameterized variants are consumer-constructed public configuration, their constructors should be internal and fronted by builders or factories. Do not use public data classes.

The typed API should be additive and canonicalize into current wire parameters. The generic response-format string remains the cross-provider compatibility path.

## Required tests

- Exact query parameters for every variant.
- Correct media type for every variant.
- Invalid rate/bitrate combinations cannot be constructed through the typed API.
- Legacy compound-string behavior remains covered.
- Precedence between generic `responseFormat` and Deepgram-specific typed output is explicit.
- Provider-specific raw options do not create an incoherent second source of truth.

---

# Repair or remove before typing

Two public fields should not receive new enums or sealed types because they are currently ineffective.

## Groq transcription response format

**Location:** `src/commonMain/kotlin/ai/torad/aisdk/providers/GroqFacade.kt:198`

The generic transcription transport always sends `response_format=json` and does not consume this option. Existing tests establish builder equality/serialization, not wire behavior.

Recommended disposition:

- deprecate/remove the ineffective option, or
- implement real typed format-to-wire and decoder dispatch first.

Do not expose text, SRT, or VTT formats until the result decoder can represent them. If a typed API is eventually added, retain the old raw symbol as a compatibility bridge and test exact multipart wire fields.

## LMNT format

**Location:** `src/commonMain/kotlin/ai/torad/aisdk/providers/LMNTProvider.kt:31`

The option is never read. Actual output format comes from the generic speech generation parameters.

Recommended disposition:

- deprecate/remove it, or
- define and implement precedence relative to the shared `responseFormat` before adding a type.

Typing a dead setting would add API surface without adding behavior.

---

# Provider polling: where not to add a type

A private state model is justified when it consolidates repeated parsing, prevents duplicated transition logic, or fixes misleading fallthrough. It is not justified merely to move literals out of a one-site `when`.

## Black Forest Labs

`src/commonMain/kotlin/ai/torad/aisdk/providers/BlackForestLabsProvider.kt:583`

Known terminal failures were expanded in prior fixes, demonstrating that the provider vocabulary evolves. The current one-site dispatch is simple, and unknown values appear intentionally retryable.

Priority should be transition tests, especially for:

- `Error`,
- `Request Moderated`,
- `Task not found`,
- an unknown token continuing to timeout,
- and missing both `status` and `state`.

A local type is optional and must preserve `Unknown(raw)` as retryable if that remains policy.

## Fireworks async image workflow

`src/commonMain/kotlin/ai/torad/aisdk/providers/FireworksFacade.kt:374`

This is one raw extraction and one dispatch. Prefer a direct boundary fix over a new hierarchy:

- known pending states retry,
- `Ready` succeeds,
- `Error` and `Failed` fail,
- missing/non-string/unknown values should have an explicit policy instead of silently timing out.

## Already strict enough

The following are adequately represented by one provider-boundary `when`; add transition tests rather than new types unless future logic spreads across more sites:

- Alibaba: `src/commonMain/kotlin/ai/torad/aisdk/providers/AlibabaProvider.kt:550`
- AssemblyAI: `src/commonMain/kotlin/ai/torad/aisdk/providers/AssemblyAIProvider.kt:649`
- Gladia: `src/commonMain/kotlin/ai/torad/aisdk/providers/GladiaProvider.kt:612`
- KlingAI: `src/commonMain/kotlin/ai/torad/aisdk/providers/KlingAIProvider.kt:405`
- Luma polling: `src/commonMain/kotlin/ai/torad/aisdk/providers/LumaProvider.kt:354`
- ByteDance: `src/commonMain/kotlin/ai/torad/aisdk/providers/ByteDanceProvider.kt:440`

Do not introduce a generic shared `PollState` or generic poller. Provider field names, vocabularies, success payloads, errors, security rules, backoff, abort handling, and unknown-value policies differ materially.

---

# Value-class assessment

## Existing correct pattern

`ProviderId` and `ModelId` in `src/commonMain/kotlin/ai/torad/aisdk/ModelRef.kt:7` are appropriate value classes because the SDK owns meaningful registry syntax and validation:

- provider IDs must be nonblank and cannot contain the provider/model separator,
- model IDs must be nonblank,
- parsing and registry operations benefit from preventing provider/model interchange.

`ModelRef` itself remains a public data class. Adding fields would change its constructor, `copy`, and component surface and should not be done casually.

## Do not broadly retrofit existing identifiers

Potential nominal wrappers include:

- `ToolCallId`
- `ApprovalId`
- response IDs
- source IDs
- provider-issued interaction IDs
- provider tool names

These wrappers could prevent accidental interchange, but replacing current public `String` properties would change JVM descriptors and KLIB signatures across a very broad surface. Many of these IDs are also opaque external values with no SDK-owned validation beyond nonblankness.

Recommended policy:

- Use dedicated value classes for future SDK-owned public APIs.
- Consider `ToolCallId`/`ApprovalId` only as one deliberate pre-beta whole-surface migration, not piecemeal.
- Do not add wrappers that force repeated unwrap/rewrap at every provider boundary without eliminating a concrete error class.
- Do not use `typealias`; it preserves ABI but adds no type safety.

---

# Explicit keep-as-String decisions

## Protocol and transport boundaries

### Gateway and UI codec discriminators

The `type: String` fields in protocol codecs are correct wire-boundary representations. They are immediately mapped into typed `StreamEvent` and `ContentPart` values. Unknown content has explicit `Raw` or null/extension behavior.

Do not introduce another sealed layer merely to represent the raw discriminator before the existing typed layer.

### Anthropic, Open Responses, Hugging Face, Cohere, and Google event types

These are provider-owned and evolving wire vocabularies. Current code deliberately:

- preserves unknown values,
- ignores unknown deltas safely,
- emits `StreamEvent.Raw`,
- retains raw provider JSON,
- or supports patterned extensions such as arbitrary `*_call` and `*_result` values.

Closing these domains would break forward compatibility.

### MCP and JSON-RPC values

MCP method names, protocol versions, command/action strings, roles, tool names, prompt names, resource names, cursors, URIs, URI templates, and server metadata belong to extensible protocol namespaces.

The local client recognizing a subset of methods does not make the public protocol namespace closed. Keep the raw wire fields. Existing internal lifecycle state is already typed where the SDK owns the state machine.

### Raw finish reason

`rawFinishReason` should remain a string. It intentionally preserves provider evidence that is collapsed by `FinishReason` into broader SDK categories such as `Other`.

## Human-readable reasons and diagnostics

Keep these as strings:

- `Agent.outputUnavailableReason`
- approval denial reasons
- tool execution error messages
- provider error explanations
- warning messages
- titles and filenames
- telemetry display text

If machine branching becomes necessary, add a separate typed classification field while retaining the raw explanation. Do not convert prose into an enum.

`CallWarning.type` also remains open/mixed; no closed ownership contract was proven.

## Identifiers and names

Keep raw external values as strings unless undertaking a deliberate whole-surface value-class migration:

- tool names,
- tool-call IDs,
- approval IDs,
- source IDs,
- response IDs,
- provider-issued IDs,
- signatures,
- model identifiers at provider boundaries.

## MIME/media types

MIME types belong to an open IANA/vendor domain. Do not enumerate them. A public closed type would reject valid extension media types and break current construction sites.

## UI custom data type

`UIMessagePart.Data.type` belongs to the caller-owned `data-*` extension namespace. It should remain an open string.

## WireDecoder labels

`WireDecoder.provider`, `operation`, and `path` are diagnostic context, not state. Typing them would cause broad churn without preventing invalid runtime behavior.

## Provider-specific values to keep open

### Generic speech response format

`src/commonMain/kotlin/ai/torad/aisdk/SpeechModels.kt:33`

Keep `String?`. Providers accept incompatible and sometimes compound output-format vocabularies. Provider-local typed adapters may supplement it, but a common enum would erase valid provider values.

### KlingAI mode

`src/commonMain/kotlin/ai/torad/aisdk/providers/KlingAIProvider.kt:127`

Valid values depend on the endpoint and model. A global enum would freeze vendor evolution while still allowing invalid model/mode combinations.

### Replicate image output format

`src/commonMain/kotlin/ai/torad/aisdk/providers/ReplicateProvider.kt:43`

This belongs to arbitrary model-specific prediction schemas and is externally open.

### Deepgram raw encoding/container bridges

Even if a provider-local algebraic output type is added, retain the raw compatibility path. Deepgram’s vendor vocabulary may grow, and existing compound formats must continue to work.

### ElevenLabs transcription file format

This describes caller-supplied file representation rather than an SDK-owned output vocabulary. Keep it open.

### Cerebras error type

This is returned diagnostic data, not a request selector. Keep the raw provider string.

---

# Existing typed models to retain

These are examples of the correct type boundary and should not be replaced with generic abstractions.

## `StreamEvent`

The library-owned event taxonomy is already sealed and serializable. Raw provider extensions live in `StreamEvent.Raw`, preserving forward compatibility.

## `ContentPart`

The SDK message-content taxonomy is already sealed. Raw provider content has an explicit escape hatch.

## `ToolResultOutput`

Successful text/JSON, error text/JSON, denied execution, and content outputs are already distinct typed variants with stable wire tags.

## `ToolLoopAgentAction` and `ToolLoopAgentState.Phase`

Library-owned actions and high-level engine phases are already sealed. User prompt and denial-reason strings correctly remain prose.

## `CompletionPhase` and `StructuredObjectPhase`

Streaming result state is already expressed through closed variants.

## `GenerationInput`

Prompt, message history, and history-plus-prompt are already separated into valid variants.

## `FileData`

Base64, bytes, and URL-backed representations are already distinct variants. The open MIME value correctly remains a string within each representation.

## `GoogleInteractionsStreamState.OpenBlockState`

The provider stream’s library-owned local lifecycle is already sealed, including an `Unknown` state for unrecognized provider material.

## `McpConnectionLifecycle.State`

Idle, active, and permanently closed lifecycle states are already modeled with a private sealed type and atomic transitions.

---

# Public API and ABI migration rules

Any implementation campaign based on this audit must obey the following rules.

1. **Do not replace a public `String` property or builder parameter in place unless an explicit pre-release breaking migration is approved.** A value class, enum, or sealed type changes JVM getter/constructor descriptors and KLIB signatures.
2. **Preserve construct-type design.** Existing settings should remain `@Poko` classes with internal constructors and builder/DSL factories.
3. **Do not introduce public data variants for growable models.** Use `@Poko` public variants or `data object`s. Internal variants may be data classes.
4. **Store one canonical representation.** A typed builder method should canonicalize into the existing raw wire value, not create independently mutable raw and typed fields.
5. **Use explicit wire mappings.** Never rely on enum entry names as protocol values.
6. **Use the right escape concept.** Outbound provider values use `Custom(nonBlankValue)`; inbound wire parsing uses `Unknown(raw)`.
7. **Preserve primitive wire strings.** Do not accidentally serialize a sealed object discriminator where the provider expects a scalar string.
8. **Avoid nullable same-name overloads.** Adding `operation(SomeType?)` beside `operation(String?)` makes `operation(null)` ambiguous. Prefer a non-null typed overload or a distinct canonical name.
9. **Add request-body and round-trip tests.** Builder equality tests alone do not prove the provider receives the intended field.
10. **Public API changes require repository contract updates.** Regenerate ABI dumps and update `CHANGELOG.md` and `INTERFACE_CONTRACT.md`.
11. **Adding a public sealed variant is a source-compatibility event.** Downstream exhaustive `when` expressions may break even when binary signatures remain loadable.

---

# Suggested campaign order

## Phase 1: Internal correctness without ABI growth

1. Add private `RevaiJobState` and snapshot parsing.
2. Add private `ReplicatePredictionState` for video polling.
3. Centralize private Google Interaction status parsing.
4. Add targeted provider transition tests.
5. Resolve explicit unknown/malformed status policies for Fireworks and similar one-site pollers.

## Phase 2: Repair ineffective and incorrect options

1. Fix xAI output media-type propagation.
2. Decide and implement Voyage compressed embedding result representation.
3. Deprecate or implement Groq transcription `responseFormat`.
4. Deprecate or implement LMNT `format` and its precedence.

## Phase 3: Closed routing selectors

1. Add typed Quiver operation.
2. Add typed Luma reference type.
3. Add typed xAI video operation.
4. Preserve raw compatibility bridges and exact wire values.

## Phase 4: Provider-local typed configuration

1. Add Deepgram algebraic output configuration.
2. Add Voyage typed known values plus custom escape.
3. Add Cohere typed input and constrained thinking configuration.
4. Add AssemblyAI summary enums.
5. Add smaller provider-specific typed adapters where request tests justify them.

## Phase 5: UI lifecycle redesign

Undertake the `ToolInvocationState` migration as a dedicated API campaign because it crosses:

- public UI message construction,
- serialization and persistence,
- stream-to-UI conversion,
- typed tool renderers,
- dynamic tools,
- approval flows,
- ABI dumps,
- and exhaustive consumer branching.

If beta compatibility is already binding, stage it through an additive typed view and validated factories rather than replacing all flat fields in one release.

---

# Verification standard

A migration should not be considered complete merely because the new type compiles. Each unit must prove:

- exact request wire values,
- correct response decoding,
- preservation of unknown provider values where required,
- absence of invalid representable states on typed paths,
- compatibility behavior of deprecated raw bridges,
- ABI dump changes are intentional,
- `CHANGELOG.md` and `INTERFACE_CONTRACT.md` are updated for public changes,
- and the repository CI gate passes without bypasses.

The intended outcome is not maximal type count. It is a smaller set of types placed at the boundaries where they eliminate real invalid states while leaving open protocols genuinely open.
