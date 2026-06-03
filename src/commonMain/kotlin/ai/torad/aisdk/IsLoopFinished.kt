package ai.torad.aisdk

/**
 * Consolidated check: would the loop terminate after this state?
 *
 * Mirrors v6's `isLoopFinished` helper — useful when callers want to
 * pre-check whether a `[StopCondition]` would fire without driving the
 * loop manually.
 *
 * Returns true if any of:
 *   - The supplied [stopWhen] reports `shouldStop = true`
 *   - The last step's finish reason is `Stop` and no tools were called
 *   - The last step's finish reason is `ToolApprovalRequested` (loop
 *     pauses for host to resume)
 *   - The last step's finish reason is `Length` / `ContentFilter`
 *     (provider terminated, can't continue)
 */
suspend fun isLoopFinished(
    state: LoopState,
    stopWhen: StopCondition,
): Boolean {
    if (stopWhen.shouldStop(state)) return true
    if (state.toolCallsThisStep.isEmpty() && state.lastFinishReason == FinishReason.Stop) return true
    return when (state.lastFinishReason) {
        FinishReason.ToolApprovalRequested,
        FinishReason.Length,
        FinishReason.ContentFilter,
        FinishReason.Error -> true
        else -> false
    }
}
