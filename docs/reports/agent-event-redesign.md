# AgentEvent redesign — target spec (decision: full sealed + Flow)

The 9 lifecycle events are flat `data class`es delivered via a bag of 9 nullable
callback lambdas (inherited from ai@6 JS). Replace with a sealed hierarchy emitted
as a Flow — sealed single source of truth, exhaustive `when`, Flow-first, no `Any?`.

## Target shape
```kotlin
public sealed class AgentEvent {
    public data class Started(val input: GenerationInput, val request: LanguageModelCallParams) : AgentEvent()
    public data class StepStarted(val stepNumber: Int, val request: LanguageModelCallParams, val priorSteps: List<StepResult>) : AgentEvent()
    public data class Chunk(val event: StreamEvent, val stepNumber: Int) : AgentEvent()
    public data class StepFinished(val stepNumber: Int, val step: StepResult) : AgentEvent()
    public data class ToolCallStarted(...) : AgentEvent()
    public data class ToolCallFinished(..., val outcome: OnToolCallFinishEvent.Outcome) : AgentEvent()
    public data class Errored(val error: Throwable, val stepNumber: Int, val source: ErrorSource) : AgentEvent()
    public data class Aborted(...) : AgentEvent()
    public data class Finished<TOutput>(val output: TOutput, val totalSteps: Int, val usage: Usage,
        val messages: List<ModelMessage>, val pendingApprovals: List<PendingApproval>) : AgentEvent()
}
```

## Rules of the redesign
1. **Kill the `Any?` payloads** — `OnFinishEvent.finalOutput: Any?` → typed `TOutput`
   (the agent is `ToolLoopAgent<TContext, TOutput>`); `experimental_context: Any?` →
   `TContext`. No erasure-friendly `Any?` at the event surface.
2. **Dispatch**: agent exposes `fun events(): Flow<AgentEvent>` (Flow-first). Replace the
   9 nullable `onX` callbacks with the stream; consumers `collect { when (it) … }`
   (exhaustive — no `else`). Provide a thin `collectAgentEvents { … }` convenience if needed.
3. **Telemetry** (`Telemetry.onAgentStart/onStepStart/…`) becomes a single collector of
   `Flow<AgentEvent>` dispatching via `when`, not 10 override points.
4. **`StreamEvent`**: sealed **interface** → sealed **class** (tenet: sealed class not interface).
5. **Enforcement**: activate `no-flat-lifecycle-event` (staged as
   `disabled_no-flat-lifecycle-event.yaml`) in the SAME commit — a top-level
   `data class …Event` must be an `AgentEvent` subtype. Runs in the Claude hook + ci-gate.
6. Breaking public-API change (parity already dropped). Update all hook/telemetry tests
   to collect the event stream + assert exhaustive handling. Regenerate ABI.

## Sequencing
Runs AFTER the FIX-X xray-fix loop (steps 2–3 touch Lifecycle/Streaming/Telemetry —
avoid two writers). The redesign subsumes the x-ray's OnStepStart/StepResult/Any? findings.
