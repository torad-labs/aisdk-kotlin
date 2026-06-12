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
- Removed the dead JsonElement-bag types `TelemetrySpan`/`TelemetryEvent` and the unwired
  `recordSpan(integration, ...)`; the tracer/span machinery (`TelemetryTracer`,
  `selectTelemetryAttributes`, ...) is unchanged.

## 0.1.0-SNAPSHOT

- Extracted the KMP AI SDK module into a standalone library.
- Added Android, iOS, and JVM targets.
- Added publishing metadata, CI, license, contribution, and security docs.
