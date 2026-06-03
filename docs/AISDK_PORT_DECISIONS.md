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

**Cost.** kotlinx.serialization's JSON Schema export isn't 1:1 with
Zod's. We ship a stub `jsonSchemaFor(tool)` in `Tool.kt` that returns a
permissive schema until a descriptor walker lands. Provider-side schema
validation will be best-effort until then.

**Deferred work.** Implement descriptor → JSON Schema walker that handles
nullable, sealed, lists, maps, enums. Likely 100–200 lines + test
coverage.

## Decision 2 — JSON handling: kotlinx.serialization JsonElement tree

**Choice.** Tool inputs / outputs cross the wire as `JsonElement`. Typed
decoding happens at the dispatch site via the tool's own serializer.

**Rationale.** v6's `JsonValue` shape maps cleanly to Kotlin's
`JsonElement`. Avoids string-based intermediate representation (which
would force re-parsing per access). Plays well with Skiko/Compose UI
serialization paths.

**Trade-off.** `JsonElement` carries no schema validation by itself.
We rely on the tool's serializer at decode time to surface shape
mismatches as exceptions, which the loop converts to `ToolError` events.

## Decision 3 — Cancellation: custom AbortSignal interface wrapping Job

**Choice.** `AbortSignal` is a Kotlin interface with `isAborted` /
`throwIfAborted()` / `register(onAbort)`. `AbortController.signal`
exposes the read-side. Internally backed by a coroutine `Job`.

**Rationale.** Three options weighed:
- Pure `Job` / `CoroutineContext.Job` — most idiomatic Kotlin, but
  doesn't match v6 ergonomics (no `register` callback equivalent).
- Pass `CoroutineContext` everywhere — heavyweight; means every API
  takes a context arg.
- **Custom interface (chosen)** — matches v6 exactly, internally wraps
  Job so Kotlin scope cancellation flows through.

The interface is propagable through tool execution context (invariant
I-10) and lets non-coroutine callers (e.g. iOS Swift bridging) interop
cleanly.

## Decision 4 — Streaming buffering: cold Flow

**Choice.** All streams (`Agent.stream`, `streamText`, `LanguageModel.stream`,
`streamToUiMessages`) return cold `Flow<T>`. Each collection drives a
fresh upstream call.

**Rationale.** Matches v6 semantics — `streamText()` doesn't fire until
its return value is consumed. Avoids accidental double-billing if a
caller subscribes twice. Compose `collectAsState` / `stateIn` already
expect cold flows; integrating with `StateFlow` (for example, in a view model)
is a one-liner via `stateIn`.

**Cost.** Replay isn't free — if the chat VM needs to share one stream
with multiple collectors, the host does that explicitly via
`shareIn` / `stateIn` at the consumer boundary. Inside this SDK, every
stream is single-shot.

## Decision 5 — Tool registration: typed builder DSL with internal Map

**Choice.** Public API is `toolSetOf(toolA, toolB, ...)` returning a
`ToolSet<TContext>` that internally holds `Map<String, Tool<*, *, TContext>>`.

**Rationale.** `Map<String, Tool>` directly is too easy to misuse
(typo'd tool names won't fail at construction time). A typed builder
gives compile-time guarantees the tools share a `TContext` and lets the
SDK derive descriptors lazily.

**Trade-off.** Tool inputs/outputs are erased to `*` projections inside
the set — type recovery happens at dispatch via each tool's own
serializer. The cost of generic erasure here is acceptable because the
serializer is the only thing that needs the full type at runtime.

## Decision 6 — `prepareStep` runs before EVERY step (including step 1)

**Choice.** Per the v6 contract, `prepareStep` runs before each step in
the loop, including the first one.

**Rationale.** Lets `prepareStep` own all per-step config without callers
needing a separate "first step" hook. Makes the loop shape uniform.

**Test.** `PrepareStepTest.prepareStep_runs_before_every_step_and_can_gate_active_tools`
asserts step numbers 1, 2, ... in order.

## Decision 7 — Hook failures don't crash the loop

**Choice.** Failures in `onStart`, `onStepFinish`, `onFinish` are caught
and surfaced via `onError` with `source = ErrorSource.Hook`. The loop
continues normally.

**Rationale.** Best practice #10 — hooks are observation points. A
broken telemetry exporter shouldn't stop generation. Failures in
`prepareStep`, `prepareCall`, the model itself, or tool execution DO
crash the loop because they're load-bearing.

**Test.** `LifecycleHooksTest.hook_failure_does_not_crash_the_loop`
asserts the loop completes despite a thrown `error("boom")` in `onStart`.

## Decision 8 — Approval is RPC return-then-resume, not in-flight pause

**Choice.** When a tool's `needsApproval` returns true, the loop emits
`StreamEvent.ToolApprovalRequest`, appends a
`ContentPart.ToolApprovalRequest` to the assistant message, and **ends
the generation** with `GenerateResult.pendingApprovals` populated. The
host inspects, surfaces UI, and resumes by calling `generate()` again
with `messages + toolApprovalResponseMessage(toolCallId, approved)`.
Generation is NOT kept "in flight" while the user decides.

**Rationale.** Matches v6's `tool-approval-request` /
`tool-approval-response` content-part shape exactly. The earlier
"suspend on a Channel" approach (v0.1) couldn't be serialized — if the
process died while the user was deciding, the in-flight call was lost.
Return-then-resume puts approval state in the message log, which is
already serializable, persistable, and replayable.

**Cost.** Two `generate()` calls instead of one continuous stream. The
host must thread `result.messages` through to the resume call. We
considered both surfaces and chose this one because it composes with
durable storage (write `messages` to disk, read back across process
restarts) and with the v6 `useChat` shape that all major v6 hosts
already speak.

**Test.** `ToolApprovalTest.needsApproval_returns_pending_then_resumes_on_approval_response`
exercises both approve and deny paths.

## Decision 9 — `Output.obj` (not `Output.object`)

**Choice.** Renamed v6's `Output.object()` to `Output.obj()` because
`object` is a Kotlin keyword.

**Rationale.** Backticks (`Output.\`object\`()`) compile but are awful
to read at every call site. `obj` is short, unambiguous, and matches
established Kotlin style for similar conflicts (e.g. JsonObject's
`buildJsonObject`).

## Decision 10 — Real provider implementations are NOT in this module

**Choice.** Only `MockLanguageModel` ships. MLX (iOS), LiteRT (Android),
Anthropic, OpenAI providers live in their own modules — `aisdk-provider-*`
— and are out of scope for v0.1.

**Rationale.** Real providers are novel-mechanism work that needs
runtime verification on actual devices. Bundling them here would block
this module's release on tooling work that's better done separately.

Applications wire real providers in provider-specific packages or host
code; the core SDK stays platform-agnostic.
