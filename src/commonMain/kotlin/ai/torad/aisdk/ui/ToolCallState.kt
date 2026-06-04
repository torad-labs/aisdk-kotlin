package ai.torad.aisdk.ui

import kotlinx.serialization.Serializable

/**
 * Tool call lifecycle in the UI. v6 names — `input-streaming` /
 * `input-available` / `output-available`, NOT v5's `partial-call` /
 * `call` / `result`.
 *
 * State machine:
 *   ```
 *   ┌────────────────────┐
 *   │ InputStreaming     │  model is still emitting tool input JSON tokens
 *   └────────┬───────────┘
 *            │
 *            ▼
 *   ┌────────────────────┐
 *   │ InputAvailable     │  full input is parsed, tool is executing
 *   └────────┬───────────┘
 *            │
 *            ▼
 *   ┌────────────────────┐
 *   │ OutputAvailable    │  tool returned, output is set
 *   └────────────────────┘
 *
 *   (any state can transition to)  Error
 *   (between states, optional)     ApprovalRequired
 *   ```
 */
@Serializable
public enum class ToolCallState {
    InputStreaming,
    InputAvailable,
    ApprovalRequested,
    ApprovalResponded,
    OutputAvailable,
    OutputError,
    OutputDenied,
}
