# Agents

Agents own multi-step interaction. Application code should depend on
`Agent<TContext, TOutput>` and construct `ToolLoopAgent` only at the boundary
where the concrete orchestration strategy is chosen.

## Agent Contract

`Agent` supports:

- `generate(...)` for one complete interaction.
- `stream(...)` for a `Flow<StreamEvent>`.
- `tools` for introspection and UI rendering.

The host owns persistence, user identity, credentials, network clients, and UI.
The agent owns prompt assembly, model calls, tool execution, stop conditions,
and lifecycle callbacks.

## ToolLoopAgent

`ToolLoopAgent` is the canonical loop implementation. It continues until:

- The model finishes with a non-tool-call finish reason.
- A tool call cannot be executed.
- A tool call needs approval.
- A stop condition returns true.

Always set a domain-specific stop condition even though the default
`stepCountIs(20)` is present:

```kotlin
val agent = ToolLoopAgent<AppContext, String>(
    model = model,
    instructions = "Answer using the project tools.",
    tools = toolSetOf(searchDocs, createTicket),
    stopWhen = anyOf(
        stepCountIs(8),
        hasToolCall("finalizeAnswer"),
    ),
)
```

## Context And Call Options

Use `TContext` for application context that tools need:

```kotlin
@Serializable
data class AppContext(
    val userId: String,
    val workspaceId: String,
)
```

Pass it at call time:

```kotlin
agent.generate(
    prompt = "Find docs for stream resume.",
    options = AppContext(userId = "u_123", workspaceId = "w_456"),
)
```

If you expose agent calls over an API boundary, provide `callOptionsSchema` so
incoming options can be validated.

## prepareCall And prepareStep

Use `prepareCall` for per-request setup such as RAG, entitlements, or
workspace-specific defaults.

Use `prepareStep` for per-step routing such as active tool gating, model
selection, or changing provider options after a classification step.

Keep call sites boring: `prompt`, `options`, and `abortSignal` should be the
normal inputs. Do not move model-selection branching into UI or handler code.

## Lifecycle Hooks

Lifecycle hooks let hosts observe the loop without owning the loop:

- `onStart`
- `onStepStart`
- `onStepFinish`
- `onFinish`
- `onError`
- `onChunk`
- experimental tool-call start/finish hooks

Use hooks for logging, metrics, tracing, audit events, and step-level debug
output.

## Tool Approval

Tools that touch external state should define `needsApproval`.

```kotlin
val sendMessage = tool<SendInput, SendResult, AppContext>(
    name = "sendMessage",
    description = "Send a message to a user.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
    needsApproval = { input, context ->
        input.body.length > 100 || context?.workspaceId == null
    },
) { input ->
    messaging.send(input.recipientId, input.body)
    SendResult(sent = true)
}
```

When approval is required, the loop returns. Persist or render
`pendingApprovals`, then resume with `toolApprovalResponseMessage`.

## Subagents

Use subagents only when a task has a real internal loop, policy, or tool set of
its own. A subagent is normally exposed as a tool that calls another
`Agent`.

Forward `abortSignal` and context into subagents so cancellation and
authorization stay hierarchical.

## Memory Boundary

The core agent does not own durable memory. Store messages, summaries,
retrieval state, and user profile data in the host app. Feed selected memory
back through `messages`, `instructions`, `prepareCall`, or retrieval tools.
