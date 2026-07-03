# Application Patterns

These patterns keep application code small while letting the SDK own model
calls, tool execution, streaming, approval, and cancellation.

## Put Tools At The Edge

Define tools near the service they wrap, then compose them into agents.

```kotlin
fun searchDocsTool(search: SearchService) =
    Tool<SearchInput, List<SearchResult>, AppContext>(
        name = "searchDocs",
        description = "Search product documentation.",
        inputSerializer = serializer(),
        outputSerializer = serializer(),
    ) { input ->
        search.query(input.query)
    }
```

Agent constructors should read like composition, not service implementation.

```kotlin
fun supportAgent(
    model: LanguageModel,
    search: SearchService,
    tickets: TicketService,
): Agent<AppContext, String> = SupportAgent(
    model,
    ToolSet(searchDocsTool(search), createTicketTool(tickets)),
)

private class SupportAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
    ToolLoopAgent<AppContext, String>(
        model = model,
        instructions = "Answer using current product context.",
        tools = tools,
        stopWhen = StepCountIs(8),
        callOptionsSchema = serializer<AppContext>(),
    )
```

## Keep Call Sites Boring

Normal app code should pass prompt, messages, context, and cancellation. Do
not thread model selection, tool gating, or provider quirks through UI event
handlers.

Use:

- Constructor settings for stable defaults.
- `prepareCall` for request-specific setup.
- `prepareStep` for per-step routing.
- Middleware or provider settings for provider behavior.

## Use Context Deliberately

Put typed app context in `TContext`.

```kotlin
@Serializable
data class AppContext(
    val userId: String,
    val workspaceId: String,
    val plan: String,
)
```

Tools receive it through `ToolExecutionContext.context`. `prepareCall` and
`prepareStep` can inspect it. Do not hide required authorization or tenancy
state in globals.

## Start With One Agent

One `ToolLoopAgent` is enough for most apps. Add subagents when a task needs
its own internal loop, prompt policy, or tool set.

Good subagent cases:

- Research that may call several retrieval tools.
- Code review that should run separate analyzers.
- Planning followed by execution with different tools.

Weak subagent cases:

- A helper that just calls one API.
- A prompt section that could be `prepareStep`.
- A tool split done only to make the code look multi-agent.

## Summarize Large Tool Results

Use `toModelOutput` when the UI needs a rich result but the model only needs a
short summary for the next step.

```kotlin
val searchDocs = Tool<SearchInput, List<SearchResult>, AppContext>(
    name = "searchDocs",
    description = "Search product documentation.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
    toModelOutput = { results, _ ->
        ToolResultOutput.Text(
            results.take(5).joinToString("\n") { "- ${it.title}: ${it.url}" },
        )
    },
) { input ->
    docs.search(input.query)
}
```

The full output still reaches UI and persistence. The shorter model-visible
value is used when the conversation continues.

## Ask For Approval At The Tool

Approval rules belong on the tool, where the typed input and context are
available.

```kotlin
needsApproval = { input, options ->
    input.text.length > 100 || options.experimental_context?.plan != "admin"
}
```

When the agent pauses, render `pendingApprovals` and resume with
`toolApprovalResponseMessage` or `AgentSession.approve` / `deny`.

## Prefer Sessions For Interactive Hosts

Use direct calls for jobs and tests:

```kotlin
val result = agent.generate(prompt = "Summarize this.")
```

Use sessions for UI and long-lived services:

```kotlin
val session = agent.session(viewModelScope)
session.submitStreaming(prompt = "Help me debug this.", options = context)
```

Sessions handle cancellation, superseded submissions, streaming text,
approvals, errors, and message state.

## Keep Provider Logic Out Of Agents

Provider-specific request mapping belongs in:

- dedicated provider facades,
- provider options,
- middleware,
- host transports.

Agents should ask for capabilities, not provider names. This keeps the same
agent usable with Gateway, OpenAI-compatible, dedicated, and mock providers.

## Add Cost And Rate Guards At The Host Boundary

Rate limits belong around calls, not inside prompts.

```kotlin
if (!quota.allow(context.userId, estimatedTokens = 2_000)) {
    throw InvalidArgumentError("quota", "user has exhausted the AI budget")
}

val result = agent.generate(
    prompt = prompt,
    options = context,
    abortSignal = requestSignal,
)

quota.record(context.userId, result.totalUsage.totalTokens)
```

For tool calls that can spend money or write data, combine host quotas with
tool-level `needsApproval`.

## Cache At Deterministic Boundaries

Cache retrieval, embeddings, and deterministic classifications before caching
full generations.

```kotlin
val hits = cache.getOrPut("docs:${query.hashCode()}") {
    docs.search(query)
}
```

Avoid caching full model output unless the prompt, model id, settings, provider
options, tools, and retrieved context are all part of the cache key.

## Respect Backpressure

Streams are `Flow` values. Do not fan out one provider stream to several
collectors unless you intentionally buffer or replay it.

```kotlin
val result = TextGenerator(model).streamResult(GenerationInput.Prompt(prompt))

val uiJob = scope.launch {
    result.fullStream.collect { event -> render(event) }
}
```

Use `TextGenerator.streamResult` when adapters need a replayable stream. Use a
single collection for a plain `TextGenerator.stream` flow.

## Keep Security Outside Model Choice

Authorization, tenancy, policy, and destructive-action checks must run in
normal application code.

```kotlin
val deleteRecord = Tool<DeleteInput, DeleteResult, AppContext>(
    name = "deleteRecord",
    description = "Delete one record after host authorization.",
    needsApproval = { _, _ -> true },
) { input ->
    auth.requirePermission(context.userId, "record.delete")
    records.delete(input.id)
}
```

The prompt can describe policy, but the host enforces policy.

## Persist Messages, Not Internal State

For chat UIs, persist `UIMessage` after validation and convert with
`convertToModelMessages` when resuming. For agent services, persist
`ModelMessage` directly.

Store durable memory outside the SDK:

- user profile,
- retrieval index,
- summaries,
- approvals,
- app-side audit data.

Feed the relevant slice back through messages, instructions, `prepareCall`, or
tools.

## Related

- [Tools](tools.md)
- [Agents](agents.md)
- [Workflow Patterns](workflow-patterns.md)
- [Memory](memory.md)
- [Chatbots](chatbots.md)
- [Settings And Provider Options](settings-and-provider-options.md)
- [Provider Management](provider-management.md)
- [Advanced Streaming](advanced-streaming.md)
- [Lifecycle And Events](lifecycle-and-events.md)
- [Utilities](utilities.md)
- [Error Handling](error-handling.md)
- [Model Families](model-families.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
