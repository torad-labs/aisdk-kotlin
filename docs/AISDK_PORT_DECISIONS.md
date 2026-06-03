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

## Decision 10 — Generic HTTP providers live here; provider-specific runtimes do not

**Choice.** The module ships provider-neutral contracts, mock models,
Ktor Gateway transport, and a generic OpenAI-compatible Ktor provider.
Provider-specific runtimes such as MLX, LiteRT, Anthropic, Gemini, and
other bespoke APIs live in `aisdk-provider-*` modules.

**Rationale.** Gateway and OpenAI-compatible HTTP are stable protocol
adapters and work well as common KMP code over Ktor. Device runtimes and
bespoke provider APIs need provider-specific runtime validation on actual
devices or provider sandboxes.

Applications use the built-in Ktor adapters where the protocol matches and
wire provider-specific packages or host code for everything else; the SDK
stays platform-agnostic.
