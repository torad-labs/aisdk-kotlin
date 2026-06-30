# Changelog

All notable changes to this project will be documented here.

This project follows Semantic Versioning once the first stable release is cut.

## 0.3.0-beta01

- Beta-readiness hardening: tool execution now uses an explicit bounded `ToolExecutionPolicy` (default `maxParallelToolCalls=8`, `maxToolCallsPerStep=128`) so a model cannot create unbounded child coroutines or in-step tool work. The loop now surfaces typed `AgentError.MaxToolCallsPerStepExceeded` and `AgentError.ToolExecutionTimedOut` failures.
- Retry hardening: `RetryPolicy` now defaults to retrying only typed retryable `APICallError` / `GatewayError`, uses injectable full-jitter backoff, honors `Retry-After` with an injected clock, supports per-attempt and total deadlines, and carries retry decision details through `RetryError.attempts`.
- Privacy hardening: telemetry integrations are metadata-only by default (`recordInputs=false`, `recordOutputs=false`) and receive a redacted event projection. `LoggingMiddleware` now logs tool metadata and byte counts by default; raw/redacted payload logging is explicit via `LoggingOptions` and the shared `Redactor` seam.
- Release gates: coverage thresholds, detekt baseline budget ratchet, dependency verification metadata, provider capability/API review checks, local-staging consumer smoke fixtures, SHA-pinned GitHub Actions, workflow timeouts, and a `tools/beta-readiness-check` gate were added.
- Public API hardening: JVM default-method compatibility is now pinned to `JvmDefaultMode.ENABLE`; experimental MCP/media aliases and functions now require `@ExperimentalAiSdkApi`; mutable byte payloads now defensively copy on input/output (`FileData.Bytes.toByteArray()`, `DefaultGeneratedFile.byteArray`); and `MutableTelemetrySpan` now accepts a read-only `Map` instead of a public `MutableMap`.
- Beta contract correction: the checked ABI now exposes `Tool` as a non-sealed
  `abstract class`, so external modules can subclass it exactly as the beta
  docs and migration notes describe. Open Responses streaming now emits a
  terminal `StreamEvent.Error` for `response.failed` events and prefers final
  `output_item.done` tool-call arguments over an empty pending placeholder.
- Tool strictness is now opt-in (breaking ABI change): `ToolSchema.strict`,
  `ToolSchemaOptions.strict`, `Tool.strict`, and `LanguageModelTool.strict` are
  `Boolean?` values defaulting to `null`. OpenAI-compatible tool requests omit
  `strict` unless callers explicitly set `true` or `false`; structured-output
  `response_format` strict behavior is unchanged.
- Non-streaming text/object generation now retries transient model-call failures
  by default (breaking ABI change): `CallSettings`, `CallConfig`, `ToolLoopAgent`,
  `AgentSettings`, and `StepSettings` expose `maxRetries` (`2` by default, `0`
  disables). Retries wrap each individual `LanguageModel.generate` round-trip,
  so a later model retry in a tool loop does not re-run already-executed tools.
- MCP HTTP inbound SSE reconnects now stop on clean EOF and only retry after
  stream errors with capped exponential backoff. `MCPReconnectionOptions`
  configures `initialReconnectionDelayMillis`, `reconnectionDelayGrowFactor`,
  `maxReconnectionDelayMillis`, and `maxRetries` for `HttpMCPTransport` and
  `MCPTransportConfig`.
- data class -> @Poko migration (pre-beta): result/metadata value types lose
  generated `copy()` / `componentN()` ABI as they are demoted from public
  `data class` to `@Poko class`. This begins with `CallWarning` as the
  standalone `@Serializable` canary and continues with the `UIMessagePart`
  and `StreamEvent` sealed-leaf families as polymorphic serialization
  canaries, plus media-model result/metadata holders, lifecycle
  `AgentEvent` / `StepResult` payloads, embedding/rerank result holders, and
  language-model result/metadata/middleware-context holders, and gateway
  response/spec/metadata holders, provider tool-namespace holders, and MCP
  protocol result/capability holders, plus tool result/output and approval
  holders, structured-object result/phase holders, and UI stream result
  holders, error/parser/devtools/telemetry result holders, OAuth metadata/token
  payloads, provider error payloads, and model message/content/usage wire
  types, plus generate result holders, loop snapshots, and clean state-machine
  phase leaves; field access, equality, hashCode, toString, and JSON
  serialization remain supported where applicable. State containers such as
  `AgentSessionState`, `ToolLoopAgentState`, `ChatState`, and `CompletionState`
  intentionally remain data classes for `StateFlow.update { it.copy(...) }`
  MVI usage.

- **Tools are now class-based and extensible (breaking ABI change).** `Tool` is an `abstract class`
  you can extend for reusable, dependency-injected tools — mirroring how a concrete agent extends
  `ToolLoopAgent`:
  ```kotlin
  class SearchDocsTool(private val repo: DocRepository) :
      Tool<SearchInput, List<SearchResult>, AppContext>() {
      override val schema = ToolSchema("searchDocs", "Search the product documentation")
      override val inputSerializer = serializer<SearchInput>()
      override val outputSerializer = serializer<List<SearchResult>>()
      override fun execute(input: SearchInput, ctx: ToolExecutionContext<AppContext>) = flow {
          emit(ToolResult.Success(repo.search(input.query)))
      }
  }
  // usage: ToolSet(SearchDocsTool(repo))
  ```
  The executor and the optional callbacks (`needsApproval`, `toModelOutput`, `onInputStart`,
  `onInputDelta`, `onInputAvailable`) are now overridable methods instead of constructor lambdas —
  override only what you need. Tools that emit preliminary snapshots extend the new `StreamingTool`
  base and override `executeStream`. The `Tool(...)` / `StreamingTool(...)` / `DynamicTool(...)` /
  `ProviderExecutedTool(...)` factories keep their exact signatures for trivial inline tools; they
  now build an internal `LambdaTool` / `LambdaStreamingTool` subclass.

  Migration: the `Tool(...)` constructor is no longer invoked directly, and the public `Tool.executor`
  / `Tool.needsApproval` / `Tool.toModelOutput` / `Tool.onInput*` *fields* are removed (they became
  methods). Keep using the factories (unchanged), or extend `Tool` / `StreamingTool`. To drive a tool's
  executor directly, prefer `ExecuteTool(tool, input, ctx)` — it handles preliminary/final emissions
  consistently for both plain and streaming tools.

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
