# Lifecycle And Events

Lifecycle hooks observe an agent run. Stream events carry incremental model,
tool, and UI state. Telemetry integrations record the same kind of lifecycle at
an observability boundary.

The top-level `generateText` and `streamText` functions do not take lifecycle
callback options in this port. Use `ToolLoopAgent` hooks when you need
request-scoped callbacks, middleware when you need model-call wrapping, and
telemetry integrations when you need app-wide observation.

## Agent Hooks

Use constructor hooks for app-wide observation:

```kotlin
val agent = ToolLoopAgent<AppContext, String>(
    model = model,
    instructions = "Use tools when they help.",
    tools = toolSetOf(searchDocs),
    stopWhen = stepCountIs(8),
    onStepFinish = {
        audit.step(stepNumber, step.finishReason, step.usage.totalTokens)
    },
    onFinish = {
        audit.finish(totalSteps, usage.totalTokens, pendingApprovals.size)
    },
)
```

Use per-call hooks for a single request:

```kotlin
val result = agent.generate(
    prompt = prompt,
    options = context,
    hooks = AgentCallHooks(
        onStart = { event -> trace.start(event.options) },
        onChunk = { event -> trace.chunk(event.stepNumber, event.event) },
        onAbort = { event -> trace.aborted(event.steps.size) },
    ),
)
```

Constructor hooks and per-call hooks both run when both are supplied.

## Hook Events

| Hook | Use it for |
|---|---|
| `onStart` | Log request context and prior message count. |
| `onStepStart` | Inspect the next step's messages and prepared request. |
| `onStepFinish` | Record step output, usage, warnings, model id, and metadata. |
| `onFinish` | Persist final messages, usage, pending approvals, and final context. |
| `onError` | Observe hook, tool, prepare, and model failures. |
| `onChunk` | Mirror streaming events into diagnostics or progress traces. |
| `onAbort` | Persist partial work after cancellation. |
| `experimental_onToolCallStart` | Record parsed tool input before execution. |
| `experimental_onToolCallFinish` | Record tool output or tool failure. |

Hooks are observation points. Use `prepareCall` or `prepareStep` when behavior
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

Use stream events when the host needs incremental behavior. Use hooks when the
host wants lifecycle observation without taking over rendering.

## Tool Input Hooks

Tool input can stream before it is available as typed data:

```kotlin
val lookup = tool<LookupInput, LookupResult, AppContext>(
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

Telemetry integrations receive lifecycle callbacks such as start, step start,
tool-call start/finish, step finish, and finish:

```kotlin
val integration = object : TelemetryIntegration {
    override suspend fun record(span: TelemetrySpan, block: suspend () -> Unit) {
        tracer.span(span.name, span.attributes) { block() }
    }

    override suspend fun onStepFinish(event: TelemetryEvent) {
        metrics.increment("ai.step.finish")
    }
}
```

Use telemetry for durable observability. Use hooks for local orchestration
observation and tests.

## UI Finish Events

For UI streams, finish handling belongs at the message stream boundary:

```kotlin
handleUiMessageStreamFinish(messages) { finalMessages ->
    save(finalMessages)
}
```

Validate final messages before persistence when they come from a client or
external transport.

## Tips

- Hook failures are observed and logged; tool and prepare failures are real
  generation failures.
- Persist `onFinish.messages` when approval may resume later.
- Use `onAbort` for cleanup and partial persistence.
- Do not mutate global prompt or tool state from hooks.

## Related

- [Agents](agents.md)
- [Streaming](streaming.md)
- [Advanced Streaming](advanced-streaming.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
- [Error Handling](error-handling.md)
