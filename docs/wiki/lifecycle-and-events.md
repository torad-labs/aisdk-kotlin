# Lifecycle And Events

Lifecycle events observe an agent run. Stream events carry incremental model,
tool, and UI state. Telemetry integrations record the same kind of lifecycle at
an observability boundary.

The `TextGenerator` helpers do not take lifecycle callback options in this
port. Use `ToolLoopAgent.events(...)` or `collectAgentEvents(...)` when you
need request-scoped observation, middleware when you
need model-call wrapping, and telemetry integrations when you need app-wide
observation.

## Agent Events

Use the lifecycle event stream for full-loop observation:

```kotlin
class AuditAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
    ToolLoopAgent<AppContext, String>(
        model = model,
        instructions = "Use tools when they help.",
        tools = tools,
        stopWhen = StepCountIs(8),
    )

val agent = AuditAgent(model, ToolSet(searchDocs))
agent.events(prompt = prompt, options = context).collect { event ->
    when (event) {
        is AgentEvent.StepFinished -> audit.step(event.stepNumber, event.step.usage.totalTokens)
        is AgentEvent.Finished<*, *> -> audit.finish(event.totalSteps, event.usage.totalTokens)
        else -> Unit
    }
}
```

Use `collectAgentEvents` for a single request:

```kotlin
agent.collectAgentEvents(
    prompt = prompt,
    options = context,
) { event ->
    when (event) {
        is AgentEvent.Started<*> -> trace.start(event.options)
        is AgentEvent.Chunk -> trace.chunk(event.stepNumber, event.event)
        is AgentEvent.Aborted -> trace.aborted(event.steps.size)
        else -> Unit
    }
}
```

Use `collectAgentEvents` when you only need a small subset of lifecycle events.

## Event Types

| Event | Use it for |
|---|---|
| `AgentEvent.Started` | Log request context and prior message count. |
| `AgentEvent.StepStarted` | Inspect the next step's messages and prepared request. |
| `AgentEvent.StepFinished` | Record step output, usage, warnings, model id, and metadata. |
| `AgentEvent.Finished` | Persist final messages, usage, pending approvals, and final context. |
| `AgentEvent.Errored` | Observe tool, prepare, model, and collector failures. |
| `AgentEvent.Chunk` | Mirror streaming events into diagnostics or progress traces. |
| `AgentEvent.Aborted` | Persist partial work after cancellation. |
| `AgentEvent.ToolCallStarted` | Record parsed tool input before execution. |
| `AgentEvent.ToolCallFinished` | Record tool output or tool failure. |

Events are observation points. Use `prepareCall` or `prepareStep` when behavior
needs to change.

## Stream Events

```kotlin
agent.stream(prompt = prompt, options = context).collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> ui.append(event.text)
        is StreamEvent.ToolCall -> audit.toolCall(event.toolName)
        is StreamEvent.ToolResult -> audit.toolResult(event.toolName)
        is StreamEvent.StepFinish -> metrics.tokens(event.usage.totalTokens)
        is StreamEvent.Finish -> metrics.finish(event.finishReason)
        is StreamEvent.Abort -> metrics.abort()
        is StreamEvent.Error -> report(event.error)
        else -> Unit
    }
}
```

Use stream events when the host needs incremental behavior. Use agent events
when the host wants lifecycle observation without taking over rendering.

## Tool Input Hooks

Tool input can stream before it is available as typed data:

```kotlin
val lookup = Tool<LookupInput, LookupResult, AppContext>(
    name = "lookup",
    description = "Look up one record.",
    onInputStart = { id -> ui.startInput(id) },
    onInputDelta = { id, delta -> ui.appendInput(id, delta) },
    onInputAvailable = { callId, input -> audit.input(callId, input) },
) { input ->
    records.lookup(input.id)
}
```

Use these hooks for UI progress and audit trails. Tool authorization should
still happen inside the tool executor or `needsApproval`.

## Telemetry Events

Telemetry integrations receive `AgentEvent` values such as start, step start,
tool-call start/finish, step finish, and finish:

```kotlin
val integration = object : Telemetry {
    override val name: String = "metrics"

    override suspend fun onEvent(call: TelemetryCall, event: AgentEvent) {
        when (event) {
            is AgentEvent.StepFinished -> metrics.increment("ai.step.finish")
            is AgentEvent.ToolCallFinished -> metrics.increment("ai.tool.finish")
            else -> Unit
        }
    }
}

Telemetry.registerTelemetry(integration)
```

Use telemetry for durable observability. Use `events` or `collectAgentEvents`
for local orchestration observation and tests.

## UI Finish Events

For UI streams, finish handling belongs at the message stream boundary:

```kotlin
UiMessageStreams.handleUiMessageStreamFinish(messages) { finalMessages ->
    save(finalMessages)
}
```

Validate final messages before persistence when they come from a client or
external transport.

## Tips

- Collector failures are observed as lifecycle errors; tool and prepare
  failures are real generation failures.
- Persist `AgentEvent.Finished.messages` when approval may resume later.
- Use `AgentEvent.Aborted` for cleanup and partial persistence.
- Do not mutate global prompt or tool state from event collectors.

## Related

- [Agents](agents.md)
- [Streaming](streaming.md)
- [Advanced Streaming](advanced-streaming.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
- [Error Handling](error-handling.md)
