# AI SDK Port — Architecture Decisions

> Each decision is locked. Future agents working on this port: don't
> re-litigate these without a documented reason.

## Decision 1 — Schema library: kotlinx.serialization

**Choice.** All input/output schemas are `@Serializable` Kotlin types
serialized via `kotlinx.serialization`.

**Rationale.** It's the de facto KMP serialization library, ships with a
JSON Schema-shaped descriptor system, and is the standard serialization
choice for Kotlin Multiplatform libraries. Adding a second schema library
would make consumers carry redundant schema/runtime dependencies.

**Cost.** kotlinx.serialization's JSON Schema export isn't 1:1 with Zod's,
so `jsonSchemaFor(tool)` implements the provider-safe subset directly:
objects, required fields, primitives, enums, arrays, maps, nullable fields,
sealed variants, and explicit dynamic-tool schemas.

## Decision 2 — JSON handling: kotlinx.serialization JsonElement tree

**Choice.** Tool inputs / outputs cross the wire as `JsonElement`. Typed
decoding happens at the dispatch site via the tool's own serializer.

**Rationale.** v6's `JsonValue` shape maps cleanly to Kotlin's
`JsonElement`. Avoids string-based intermediate representation, which
would force re-parsing per access. Plays well with Skiko/Compose UI
serialization paths.

**Trade-off.** `JsonElement` carries no schema validation by itself.
We rely on the tool's serializer at decode time to surface shape
mismatches as exceptions, which the loop converts to `ToolError` events.

## Decision 3 — Cancellation: custom AbortSignal interface wrapping Job

**Choice.** `AbortSignal` is a Kotlin interface with `isAborted` /
`throwIfAborted()` / `register(onAbort)`. `AbortController.signal`
exposes the read-side. Internally backed by a coroutine `Job`.

**Rationale.** Three options weighed:
- Pure `Job` / `CoroutineContext.Job`: most idiomatic Kotlin, but
  doesn't match v6 ergonomics because there is no `register` callback
  equivalent.
- Pass `CoroutineContext` everywhere: heavyweight, and it means every API
  takes a context arg.
- Custom interface: matches v6 exactly, internally wraps Job so Kotlin
  scope cancellation flows through.

The interface is propagable through tool execution context and lets
non-coroutine callers, such as iOS Swift bridging, interop cleanly.

## Decision 4 — Streaming buffering: cold Flow

**Choice.** All streams (`Agent.stream`, `streamText`,
`LanguageModel.stream`, `streamToUiMessages`) return cold `Flow<T>`. Each
collection drives a fresh upstream call.

**Rationale.** Matches v6 semantics: `streamText()` doesn't fire until
its return value is consumed. Avoids accidental double-billing if a caller
subscribes twice. Compose `collectAsState` / `stateIn` already expect cold
flows; integrating with `StateFlow` is a one-liner via `stateIn`.

**Cost.** Replay isn't free. If a chat VM needs to share one stream with
multiple collectors, the host does that explicitly via `shareIn` /
`stateIn` at the consumer boundary. Inside this SDK, every stream is
single-shot.

## Decision 5 — Tool registration: typed builder DSL with internal Map

**Choice.** Public API is `toolSetOf(toolA, toolB, ...)` returning a
`ToolSet<TContext>` that internally holds `Map<String, Tool<*, *, TContext>>`.

**Rationale.** `Map<String, Tool>` directly is too easy to misuse. A typed
builder gives compile-time guarantees the tools share a `TContext` and
lets the SDK derive descriptors lazily.

**Trade-off.** Tool inputs/outputs are erased to `*` projections inside
the set. Type recovery happens at dispatch via each tool's own serializer.
The cost of generic erasure here is acceptable because the serializer is
the only thing that needs the full type at runtime.

## Decision 6 — `prepareStep` runs before every step

**Choice.** Per the v6 contract, `prepareStep` runs before each step in
the loop, including the first one.

**Rationale.** Lets `prepareStep` own all per-step config without callers
needing a separate first-step hook. Makes the loop shape uniform.

**Test.** `PrepareStepTest.prepareStep_runs_before_every_step_and_can_gate_active_tools`
asserts step numbers 1, 2, ... in order.

## Decision 7 — Hook failures don't crash the loop

**Choice.** Failures in `onStart`, `onStepFinish`, `onFinish`, and
telemetry lifecycle integrations are caught and surfaced through the
existing error/lifecycle path where appropriate. The loop continues
normally.

**Rationale.** Hooks are observation points. A broken telemetry exporter
shouldn't stop generation. Failures in `prepareStep`, `prepareCall`, the
model itself, or tool execution are load-bearing and do fail the call.

**Test.** `LifecycleHooksTest.hook_failure_does_not_crash_the_loop`
asserts the loop completes despite a thrown `error("boom")` in `onStart`.

## Decision 8 — Approval is RPC return-then-resume, not in-flight pause

**Choice.** When a tool's `needsApproval` returns true, the loop emits
`StreamEvent.ToolApprovalRequest`, appends a
`ContentPart.ToolApprovalRequest` to the assistant message, and ends the
generation with `GenerateResult.pendingApprovals` populated. The host
inspects, surfaces UI, and resumes by calling `generate()` again with
`messages + toolApprovalResponseMessage(toolCallId, approved)`.

**Rationale.** Matches v6's `tool-approval-request` /
`tool-approval-response` content-part shape exactly. The earlier
"suspend on a Channel" approach couldn't be serialized. Return-then-resume
puts approval state in the message log, which is already serializable,
persistable, and replayable.

**Cost.** Two `generate()` calls instead of one continuous stream. The
host must thread `result.messages` through to the resume call. This shape
composes with durable storage and with the v6 `useChat` message model.

**Test.** `ToolApprovalTest.needsApproval_returns_pending_then_resumes_on_approval_response`
exercises both approve and deny paths.

## Decision 9 — `Output.obj` (not `Output.object`)

**Choice.** Renamed v6's `Output.object()` to `Output.obj()` because
`object` is a Kotlin keyword.

**Rationale.** Backticks (`Output.\`object\`()`) compile but are bad API
ergonomics. `obj` is short, unambiguous, and matches established Kotlin
style for similar conflicts.

## Decision 10 — Provider facades are folded into the root artifact for now

**Choice.** The module ships provider-neutral contracts, mock models,
Ktor Gateway transport, OpenAI-compatible transport, and the Vercel AI SDK
provider-package facades in the root artifact.

**Rationale.** Keeping the facades together avoids premature publication
boundaries while the public contracts are still stabilizing. The parity
ledgers still group features by upstream package so a future split can happen
without changing the shared contracts.

Applications can use the built-in facades where the protocol matches and
inject host/platform transports where an environment-specific binding is
needed. Future `aisdk-provider-*` artifacts may split the publication
layout without changing the common runtime contracts.

## Decision 11 — Kotlin-first surfaces sit beside compatibility surfaces

**Choice.** Public parity APIs stay available, but Kotlin callers get
typed builders and reified helpers for the highest-friction boundaries:
tool registration, provider options, provider metadata, output schemas,
file payloads, UI part decoding, and session/cancellation state.

**Rationale.** A full port cannot leave Kotlin users manually threading
raw JSON maps, schema strings, and controller-style cancellation through
normal application code. The compatibility shape remains useful for
upstream vocabulary and parity ledgers, but application code should be
able to express these same concepts with `@Serializable` data classes,
`Flow`, `StateFlow`, coroutine scopes, and exhaustive sealed hierarchies.

**Guardrail.** New provider features may enter through raw `JsonElement`
escape hatches, but stable surfaces should graduate to typed DSLs or
extension helpers once the shape is understood.

## Decision 12 — Provider schema cleanup is opt-in per provider

**Choice.** The shared schema cleanup helper is used only at provider
boundaries that are verified to reject generated schema metadata. It
always removes `$schema` and `title`, and it removes
`additionalProperties` only when the provider requires that.

**Rationale.** The upstream AI SDK passes tool input schemas through
verbatim for OpenAI-compatible providers. OpenAI strict tool mode requires
`additionalProperties:false`, so blanket removal would break a valid
strict-mode path. Google already has a provider-specific OpenAPI
conversion path. xAI rejects the generated metadata and therefore uses
`stripUnsupportedSchemaKeys(..., dropAdditionalProperties = true)`.

**Current policy.** xAI strips `$schema`, `title`, and
`additionalProperties`; Google converts through `googleSchema`. OpenAI,
OpenAI-compatible facades, OpenResponses, Bedrock, Cohere, and
HuggingFace intentionally keep generated tool schemas unless provider
docs or regression tests prove rejection. If another provider starts
rejecting these keys, wire the shared helper at that provider edge and add
a wire-shape test.

## Decision 13 — Audit fixes may land as consolidated class batches

**Choice.** The class-driven robustness sweep may land a consolidated
audit batch when the verified fixes share one behavioral class and are
validated together. The media, embedding, and rerank audit sweep landed
this way in `f794e23`.

**Rationale.** The original plan preferred paced modality commits, but
Phase 4 findings crossed helper and provider boundaries: embedding caps,
rerank ordering, media batching caps, image edit routing, and
transcription fields. A single reviewed batch made the cross-cutting
contract changes and tests easier to keep coherent.

**Guardrail.** Consolidate only when the batch is still reviewable, has
focused regression tests, and passes `./gradlew check` plus PR checks. Use
smaller provider commits when the changes do not share a helper or
contract.
