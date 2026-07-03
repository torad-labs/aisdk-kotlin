package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

/**
 * The terminal outcome of executing one tool call inside the loop.
 *
 * For [Success], `outputJson` is the full typed payload that drives the UI's
 * per-tool renderer pipeline. `modelVisible` is what the agent feeds back to
 * the model in its `toolMessage(...)` — by default the same as `outputJson`,
 * but tools can override via `toModelOutput` to send the model a token-cheap
 * summary (e.g. "lineup: 2127 artists across 9 stages") while the UI still
 * gets the full thing. Without this split, rich tools blow the model's
 * context window every call.
 */
internal sealed class ToolExecutionResult {
    data class Success(
        val toolName: String,
        val outputJson: JsonElement,
        val output: ToolResultOutput = ToolResultOutputs.toolResultOutputFromJson(outputJson),
        val modelOutput: ToolResultOutput = output,
        val modelVisible: JsonElement = outputJson,
    ) : ToolExecutionResult() {
        val isError: Boolean = with(ToolResultOutputs) { modelOutput.isToolResultError() }
    }

    data class Failure(
        val toolName: String,
        val error: AgentError,
    ) : ToolExecutionResult()
}

/** Result of draining a tool executor Flow — see `collectFinalToolOutput`. */
internal data class ToolOutputCapture(val hasOutput: Boolean, val value: Any?)
