# Changelog

All notable changes to this project will be documented here.

This project follows Semantic Versioning once the first stable release is cut.

## 0.3.0-alpha01

- **Tools are now class-based and extensible (breaking ABI change).** `Tool` is an `abstract class`
  you can extend for reusable, dependency-injected tools — mirroring how a concrete agent extends
  `ToolLoopAgent`:
  ```kotlin
  class SearchDocsTool(private val repo: DocRepository) :
      Tool<SearchInput, List<SearchResult>, AppContext>(
          name = "searchDocs",
          description = "Search the product documentation",
          inputSerializer = serializer(),
          outputSerializer = serializer(),
      ) {
      override suspend fun ToolExecutionContext<AppContext>.execute(input: SearchInput) =
          repo.search(input.query)
  }
  // usage: toolSetOf(SearchDocsTool(repo))
  ```
  The executor and the optional callbacks (`needsApproval`, `toModelOutput`, `onInputStart`,
  `onInputDelta`, `onInputAvailable`) are now overridable methods instead of constructor lambdas —
  override only what you need. Tools that emit preliminary snapshots extend the new `StreamingTool`
  base and override `executeStream`. The `tool { }` / `streamingTool { }` / `dynamicTool(...)` /
  `providerExecutedTool(...)` factories keep their exact signatures for trivial inline tools; they
  now build an internal `LambdaTool` / `LambdaStreamingTool` subclass.

  Migration: the `Tool(...)` constructor is no longer invoked directly, and the public `Tool.executor`
  / `Tool.needsApproval` / `Tool.toModelOutput` / `Tool.onInput*` *fields* are removed (they became
  methods). Keep using the factories (unchanged), or extend `Tool` / `StreamingTool`. To drive a tool's
  executor directly, prefer `executeTool(tool, input, ctx)` — it collects the Flow and works for any
  tool; `with(tool) { ctx.execute(input) }` is only valid for a known plain `Tool` (it throws on a
  `StreamingTool`, which produces values via `executeStream`).

  Tool-call repair + approval: the loop now resolves a call's input (decode + a single
  `experimental_repairToolCall` attempt) ONCE, before the approval gate, so repair reaches every tool —
  factory- or subclass-built — and the prior double-decode is gone. An approval-gated tool is still
  gated over its original, cleanly-decoded input: if a gated tool's input only decodes after repair,
  the call is rejected rather than approved over a rewritten input.
- Telemetry revamp (upstream v7 parity): the previously unwired `TelemetryIntegration` surface
  is replaced by a typed `Telemetry` interface that the agent loop now FEEDS AUTOMATICALLY —
  agent start/finish, step start/finish, model-call start/finish, tool-call start/finish
  (including approval-resumed executions), errors (model/prepare/tool/hook sources), and aborts.
  Every event carries a per-invocation `TelemetryCall` correlation envelope (callId, agentId,
  agentVersion, modelId, functionId).
- `registerTelemetry(...)` / `clearGlobalTelemetry()` / `globalTelemetry` replace
  `registerTelemetryIntegration(...)` / `clearGlobalTelemetryIntegrations()` /
  `globalTelemetryIntegrations`. Once an integration is registered globally, ALL agent calls
  emit events (v7 opt-out stance); per-call `TelemetrySettings.integrations` REPLACE the global
  set for that call. Integration failures are swallowed (telemetry observes, never alters the loop).
- `ToolLoopAgent` gains a `telemetry: TelemetrySettings?` constructor parameter.
- AI SDK reference refreshed 6.0.197 → 6.0.202; parity ledgers regenerated. The delta is
  one feature: HMAC-signed tool approvals. `ToolLoopAgent` gains
  `experimental_toolApprovalSecret: ByteArray?` — when set, every issued approval request is
  signed over `(approvalId, toolCallId, toolName, canonicalJson(input))` (the signature rides
  `ContentPart.ToolApprovalRequest`, `StreamEvent.ToolApprovalRequest`, `PendingApproval`, and
  the UI round-trip via `UIMessagePart.ToolUI.approvalId/signature`), and a replayed approval
  is re-validated FAIL-CLOSED before execution: missing/invalid signature throws the new
  `AgentError.InvalidToolApprovalSignature`, the input is re-decoded against the tool's
  schema, and a tool that vanished or no longer requires approval is denied rather than run.
  Upstream's `createIdMap` prototype-pollution hardening is not applicable to Kotlin maps;
  the stream-text empty-stream output classifier maps to the loop's existing finish-reason
  defaults; the array output strategy already decoded fresh elements (no in-place cast).
- Telemetry observability: the loop `Logger.warn`s when an integration throw is swallowed
  (named integration, throwable attached) — a broken integration is discoverable, never
  perfectly silent. `ToolLoopAgent` gains `logger: Logger = NoopLogger`.
- The legacy tracer/span machinery moved to `TelemetryTracing.kt` (same package — no ABI
  change); the dead `getTracer` helper was removed.
- Removed the dead JsonElement-bag types `TelemetrySpan`/`TelemetryEvent` and the unwired
  `recordSpan(integration, ...)`; the tracer/span machinery (`TelemetryTracer`,
  `selectTelemetryAttributes`, ...) is unchanged.

## 0.1.0-SNAPSHOT

- Extracted the KMP AI SDK module into a standalone library.
- Added Android, iOS, and JVM targets.
- Added publishing metadata, CI, license, contribution, and security docs.
