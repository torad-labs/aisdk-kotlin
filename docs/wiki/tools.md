# Tools

Tools let the model call code. They are the bridge from language generation to
search, databases, workflows, provider-hosted actions, and UI cards.

## Define A Typed Tool

```kotlin
@Serializable
data class SearchInput(val query: String, val limit: Int = 5)

@Serializable
data class SearchHit(val title: String, val url: String, val snippet: String)

val searchDocs = tool<SearchInput, List<SearchHit>, AppContext>(
    name = "searchDocs",
    description = "Search product documentation by query.",
    inputExamples = listOf("""{"query":"streamTextResult adapters","limit":3}"""),
) { input ->
    docs.search(input.query, limit = input.limit)
}
```

The reified helper uses `kotlinx.serialization` automatically. Use the
serializer-explicit overload when the type is not reified at the call site.

## Use Tools With Agents

```kotlin
val supportAgent = ToolLoopAgent<AppContext, String>(
    model = model,
    instructions = "Use documentation search before answering SDK questions.",
    tools = toolSetOf(searchDocs, createTicket),
    stopWhen = anyOf(stepCountIs(8), repeatedToolCallLoop(3)),
)

val result = supportAgent.generate(
    prompt = "How do I persist chat messages?",
    options = context,
)

println(result.text)
```

Agents own tool execution and loop control. Direct `generateText` and
`streamText` calls are better for one-step work; agents are better when the
model may need to act, observe, and continue. Set `stopWhen` whenever tools are
available. Structured output can add another model step, so leave enough room
for tool use plus final formatting.

## Approval

Use `needsApproval` for tools that write data, spend money, contact users, or
run privileged operations.

```kotlin
val createTicket = tool<CreateTicket, Ticket, AppContext>(
    name = "createTicket",
    description = "Create a customer support ticket.",
    needsApproval = { input, options ->
        input.priority == "urgent" ||
            options.experimental_context?.canCreateTickets != true
    },
) { input ->
    tickets.create(input)
}
```

When approval is required, generation returns with pending approval data. Add
a `toolApprovalResponseMessage(...)` to the message log, then call the agent
again with the updated messages.

## Control What The Model Sees

Use `toModelOutput` when a tool returns rich UI data but the model only needs
a summary:

```kotlin
val searchDocs = tool<SearchInput, List<SearchHit>, AppContext>(
    name = "searchDocs",
    description = "Search product documentation.",
    toModelOutput = { hits, _ ->
        ToolResultOutput.Text(
            hits.joinToString("\n") { "- ${it.title}: ${it.snippet}" },
        )
    },
) { input ->
    docs.search(input.query)
}
```

This keeps the message log useful for UI rendering while reducing model-visible
context.

## Streaming Tools

Use `streamingTool` when a tool can show progress before the final result:

```kotlin
val lookupIssue = streamingTool<IssueInput, IssueSnapshot, AppContext>(
    name = "lookupIssue",
    description = "Fetch issue details and stream progress.",
) { input ->
    flow {
        emit(issues.cachedSnapshot(input.id))
        emit(issues.fullSnapshot(input.id))
    }
}
```

Earlier emissions are preliminary UI updates. The final emission is the tool
result used by the model on the next step.

## Dynamic And Provider-Executed Tools

Use `dynamicTool` when the schema is runtime-defined:

```kotlin
val external = dynamicTool<AppContext>(
    name = "externalAction",
    description = "Call an externally registered action.",
    inputSchemaJson = runtimeSchemaJson,
) { input ->
    actionRegistry.invoke("externalAction", input)
}
```

Use `providerExecuted = true` or provider-defined helpers when the provider
runs the tool on its side. Keep local executors for app-owned side effects.

## Input Lifecycle Hooks

Use input hooks to surface streamed tool-call input:

```kotlin
val draftEmail = tool<DraftInput, Draft, AppContext>(
    name = "draftEmail",
    description = "Draft an email.",
    onInputStart = { id -> ui.markToolInputStarted(id) },
    onInputDelta = { id, delta -> ui.appendToolInput(id, delta) },
    onInputAvailable = { callId, input -> audit.toolInput(callId, input) },
) { input ->
    mailer.draft(input)
}
```

## Tips

- Keep tool names stable and action-oriented.
- Make descriptions describe when to use the tool, not just what it calls.
- Add `inputExamples` for tools with optional fields or ambiguous schemas.
- Use `strict = false` only for providers or schemas that reject strict mode.
- Treat approval as message history, not an in-memory pause.

## Related

- [Agents](agents.md)
- [Prompt Engineering](prompt-engineering.md)
- [Application Patterns](application-patterns.md)
- [Chatbots](chatbots.md)
- [Model Context Protocol](mcp.md)
