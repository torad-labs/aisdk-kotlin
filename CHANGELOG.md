# Changelog

All notable changes to this project will be documented here.

This project follows Semantic Versioning once the first stable release is cut.

## 0.2.0-SNAPSHOT

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
