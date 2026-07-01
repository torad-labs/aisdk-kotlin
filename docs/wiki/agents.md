# Agents

Agents are models plus tools plus a loop. Use them when the model needs to
decide which actions to take, run tools, see results, and continue.

For direct one-step calls, use `TextGenerator`. For multi-step tool work, use
`ToolLoopAgent`.

Application prompts and responses should cross the agent boundary, not the
underlying `LanguageModel` execution methods. Direct model execution remains
available for deliberate low-level integrations, but requires
`@OptIn(LowLevelLanguageModelApi::class)`.

## Agent Contract

Application code should depend on `Agent<TContext, TOutput>`.

```kotlin
public interface Agent<TContext, TOutput> {
    public val id: String
    public val version: String?
    public val tools: ToolSet<TContext>
}
```

The contract supports:

- `generate(...)` for a complete interaction.
- `stream(...)` for `Flow<StreamEvent>`.
- `tools` for inspection and UI dispatch.

`TContext` is typed application context such as user id, workspace id,
entitlements, retrieval state, or request metadata. `TOutput` is usually
`String`, but can be structured when the agent is configured with `Output`.

## ToolLoopAgent

`ToolLoopAgent` owns the loop. It continues until the model finishes, a tool
cannot run, a tool needs approval, or a stop condition is met.

```kotlin
class SupportAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
    ToolLoopAgent<AppContext, String>(
        model = model,
        instructions = "Answer using project tools when needed.",
        tools = tools,
        stopWhen = AnyOf(
            StepCountIs(8),
            HasToolCall("finalizeAnswer"),
        ),
        callOptionsSchema = serializer<AppContext>(),
    )

val agent = SupportAgent(model, ToolSet(searchDocs, createTicket))
```

The default stop condition is `StepCountIs(20)`. Set a domain-specific stop
condition anyway so the loop shape is obvious at the call site.

## Tools

Tools are stateless actions. They have a name, description, input serializer,
output serializer, and executor.

```kotlin
val searchDocs = Tool<SearchInput, List<SearchResult>, AppContext>(
    name = "searchDocs",
    description = "Search product documentation.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
) { input ->
    docs.search(input.query)
}
```

Use `DynamicTool` only when the schema or result type is not known at compile
time. Use `ProviderExecutedTool(...)` for tools run by the provider instead of
the local executor; custom factories can set `ToolSchemaOptions {
providerExecuted(true) }`.

Tools can also carry:

- `needsApproval` for host approval.
- `toModelOutput` for a shorter model-visible summary.
- `metadata` for host-only information.
- `providerOptions` for provider-specific tool config.
- `inputExamples` to improve tool-call quality.
- input lifecycle hooks for streamed tool input.

For complete tool examples, approval patterns, streaming tools, dynamic tools,
and provider-executed tools, see [Tools](tools.md).

## Streaming Tools

Use `StreamingTool` when a tool can produce useful preliminary results before
its final value.

```kotlin
val lookup = StreamingTool<Query, LookupResult, AppContext>(
    name = "lookup",
    description = "Search records and stream progress.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
) { query ->
    flow {
        emit(records.fastSummary(query))
        emit(records.fullResult(query))
    }
}
```

Preliminary emissions appear in the stream/UI with `preliminary = true`. The
last emission is the final tool result that is added to the model message log.

## Loop Control

Stop conditions compose:

```kotlin
stopWhen = AnyOf(
    StepCountIs(8),
    RepeatedToolCallLoop(3),
)
```

Use `activeTools` to restrict the available tool set. Use `maxParallelToolCalls`
to bound concurrent tool execution within one step.

## Call And Step Preparation

Use `prepareCall` once per invocation for request-specific setup.

```kotlin
prepareCall = {
    AgentSettings {
        instructions(instructions + "\nUse workspace ${options?.workspaceId}.")
        providerOptions(
            ProviderOptions.ofPairs(
                "openai" to buildJsonObject {
                    put("reasoningEffort", JsonPrimitive("medium"))
                },
            ),
        )
    }
}
```

Use `prepareStep` before every model step for routing, tool gating, message
compression, or evolving context.

```kotlin
prepareStep = {
    StepSettings {
        model(if (stepNumber == 1) cheapModel else strongModel)
        activeTools(if (stepNumber == 1) listOf("classify") else null)
    }
}
```

If behavior should change, use `prepareCall` or `prepareStep`, not lifecycle
hooks.

## Approval

Use `needsApproval` for tools that affect external state or other users.

```kotlin
val sendMessage = Tool<SendInput, SendResult, AppContext>(
    name = "sendMessage",
    description = "Send a message to a user.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
    needsApproval = { input, options ->
        input.text.length > 100 || options.experimental_context?.workspaceId == null
    },
) { input ->
    messaging.send(input.recipientId, input.text)
    SendResult(sent = true)
}
```

When approval is required, the loop returns with `pendingApprovals`. Resume by
adding approval response messages:

```kotlin
val first = agent.generate(prompt = prompt, options = context).first()

val responses = first.pendingApprovals.map { pending ->
    ToolApprovalResponseMessage(
        toolCallId = pending.toolCallId,
        approved = approvalUi.ask(pending.toolName, pending.input),
        approvalId = pending.approvalId,
    )
}

val resumed = agent.generate(
    messages = first.messages + responses,
    options = context,
).first()
```

Approval state lives in the message log. Persist the messages, not a hidden
agent process.

## Lifecycle Hooks

Constructor hooks and per-call hooks observe the loop:

- `onStart`
- `onStepStart`
- `onStepFinish`
- `onFinish`
- `onError`
- `onChunk`
- `onAbort`
- experimental tool-call start/finish hooks

Hook failures do not crash generation. Tool execution, model calls,
`prepareCall`, and `prepareStep` failures do.

## Sessions

Use `AgentSession` when a UI or service wants state over time.

```kotlin
val session = agent.session(viewModelScope)

session.submitStreaming(
    prompt = "Find docs for streaming.",
    options = context,
)

session.state.collect { state ->
    renderText(state.text)
    renderApprovals(state.pendingApprovals)
}
```

`AgentSession` tracks messages, status, text, output, pending approvals,
last result, and errors. It supports `submit`, `submitStreaming`, `approve`,
`deny`, `cancel`, and `reset`.

`ToolLoopAgent` also exposes an engine-state surface for long-lived hosts that
prefer actions and `StateFlow`: `dispatchEngineAction`, `engineState`, and
`close`.

## Subagents

Use a subagent when a subtask has its own loop, policy, or tool set. Expose it
as a tool and forward context and cancellation.

```kotlin
val researchTool = Tool<ResearchInput, String, AppContext>(
    name = "deepResearch",
    description = "Run a focused research agent.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
) { input ->
    researchAgent.generate(
        prompt = input.prompt,
        options = context,
        abortSignal = abortSignal,
    ).first().text
}
```

## Memory

The SDK does not own durable memory. Store messages, summaries, retrieval
state, user profile data, and approval decisions in the host app. Feed selected
memory back through `messages`, `instructions`, `prepareCall`, or tools.

## Related

- [Tools](tools.md)
- [Workflow Patterns](workflow-patterns.md)
- [Application Patterns](application-patterns.md)
- [Memory](memory.md)
- [Chatbots](chatbots.md)
- [Lifecycle And Events](lifecycle-and-events.md)
- [Structured Output](structured-output.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
