# AI SDK Port — Best Practices

The 12 practices the v6 community converged on, with Kotlin examples for
this port.

---

## 1. Define tools in dedicated files, compose them into agents

```kotlin
// One tool per file. Agent imports tools and composes.
// agent/tools/SearchDocsTool.kt
fun searchDocsTool(search: SearchService) = tool<SearchInput, List<SearchResult>, AppContext>(
    name = "searchDocs", ...
) { input -> search.query(input.query) }

// agent/SupportAgent.kt
fun supportAgent(...) = ToolLoopAgent(
    tools = toolSetOf(searchDocsTool(...), createTicketTool(...), sendMessageTool(...)),
    ...
)
```

Inline tool definitions inside agent constructors don't scale — the
constructor balloons, swap-out becomes a refactor.

---

## 2. End-to-end type safety from tool definition to UI component

The `outputAs(part, serializer)` extraction at the UI seam keeps types
flowing from `tool { ... }` through the message stream into the renderer
slot. Adding a tool means: define once, extract once at render.

```kotlin
@Serializable data class SearchResult(val title: String, val url: String)

val searchDocsTool = tool<..., List<SearchResult>, AppContext>(...) { ... }

// Renderer side — typed via the same serializer.
val results: List<SearchResult>? = outputAs(part, serializer<List<SearchResult>>())
SearchResultsView(results.orEmpty(), part.state)
```

For zero string repetition use `ToolPartHandlerRegistry` —
register once, dispatch automatically.

---

## 3. Single agent first; subagents only when warranted

If you're writing a system prompt that says "first do X, then do Y, then
check Z" you probably want subagents (tools that wrap other agents).
Don't preemptively split — multi-agent coordination has real cost.

```kotlin
// SUBAGENT-AS-TOOL pattern, when warranted:
val researchSubagentTool = tool<ResearchInput, String, AppContext>(
    name = "deepResearch",
    description = "Spin up a research subagent that summarizes findings",
    inputSerializer = serializer(), outputSerializer = serializer(),
) { input ->
    val sub = ToolLoopAgent<AppContext, String>(
        model = parentModel,
        instructions = "Research ${input.topic} and summarize",
        tools = toolSetOf(searchTool, fetchTool),
        stopWhen = stepCountIs(5),
    )
    // I-10: forward the parent's abortSignal!
    sub.generate(input.topic, options = context, abortSignal = abortSignal).text
}
```

---

## 4. Stop conditions are mandatory

Default `stepCountIs(20)` runs even without explicit `stopWhen` so loops
terminate. For specific completion signals, use `hasToolCall("done")` or
custom `StopCondition`. Combine with `anyOf` / `allOf`.

```kotlin
stopWhen = anyOf(
    stepCountIs(8),                 // hard ceiling
    hasToolCall("postFinalAnswer"), // happy path
)
```

---

## 5. Configuration aggregates on the agent

- Constructor: defaults that won't change per call
- `prepareCall`: per-invocation overrides (RAG injection, user-specific config)
- `prepareStep`: per-step routing (model selection per step, tool gating, dynamic system prompts)

Call sites take only `prompt`, `options`, `abortSignal`. Never pass
config at the call site.

```kotlin
// ❌ Wrong — config at call site
agent.generate(prompt, options, model = bigModel)  // doesn't compile, by design

// ✅ Right — model selection per step
val agent = ToolLoopAgent<...>(
    model = cheapModel,
    prepareStep = {
        StepSettings(
            model = if (stepNumber == 1) cheapModel else expensiveModel,
            activeTools = if (stepNumber == 1) listOf("classify") else listOf("execute"),
        )
    },
    ...
)
```

---

## 6. Provider differences live in middleware, not orchestration

```kotlin
// ❌ Wrong — branching on provider name in agent code
val agent = ToolLoopAgent(
    model = if (model.modelId.startsWith("anthropic/")) /* anthropic stuff */ else ...
)

// ✅ Right — provider quirks normalized via middleware
val normalized = wrapLanguageModel(rawModel, listOf(
    AnthropicQuirksMiddleware(),  // injects anthropic-specific provider options
    LoggingMiddleware(),
    RetryMiddleware(maxAttempts = 3),
))
val agent = ToolLoopAgent(model = normalized, ...)
```

---

## 7. `Agent` is an interface; `ToolLoopAgent` is one implementation

Application code depends on `Agent<TContext, TOutput>` — never on the
concrete `ToolLoopAgent` class. This lets future implementations
(`DurableAgent` for offline-resilient mode, `RecordingAgent` for tests)
substitute cleanly.

```kotlin
class ChatViewModel(
    val agent: Agent<AppContext, String>  // interface, not ToolLoopAgent
) { ... }
```

---

## 8. Tool approval (`needsApproval`) for consequential actions

Anything that touches external state or other users (sends a message,
saves data, makes a purchase) gets a `needsApproval` predicate.

```kotlin
val sendMeshMessageTool = tool<SendInput, SendResult, AppContext>(
    needsApproval = { input, ctx ->
        // Dynamic: long messages or unfamiliar recipients always require approval.
        input.message.length > 100 || ctx?.knownContacts?.contains(input.recipient) != true
    },
    ...
)
```

The agent emits `StreamEvent.ToolApprovalRequest` and the loop **ends**
(per v6 RPC semantics). `GenerateResult.pendingApprovals` is populated
and `GenerateResult.messages` contains the assistant message with the
`ContentPart.ToolApprovalRequest` parts appended. The host surfaces UI
and resumes by calling `generate` again:

```kotlin
val first = agent.generate(prompt = "send a message to Alex")
for (pending in first.pendingApprovals) {
    val approved = ui.askUser(pending.toolName, pending.input)
    val response = toolApprovalResponseMessage(
        toolCallId = pending.toolCallId,
        approved = approved,
        reason = if (!approved) "user denied" else null,
    )
    val resumed = agent.generate(messages = first.messages + response)
}
```

State lives entirely in the message log, so it serializes, persists
across process restarts, and replays cleanly.

---

## 9. Strict mode is opt-in per tool, not global

Some providers reject schemas with regex/format constraints under strict
mode. Mark such tools `strict = false` so they still work; keep
simple-schema tools at `strict = true` for the validation guarantee.

```kotlin
val complexInputTool = tool<RegexHeavyInput, ..., ...>(
    ...,
    strict = false,  // schema has format/regex provider can't enforce
)
```

---

## 10. Lifecycle hooks for telemetry, NOT for orchestration

`onStart` / `onStepFinish` / `onFinish` / `onError` are observation
points. They cannot modify behavior. If a hook needs to influence the
loop (e.g. inject a system prompt mid-loop), you wanted `prepareStep`.

```kotlin
// ✅ Right — telemetry only
onStepFinish = { telemetry.recordStep(step) }

// ❌ Wrong — trying to redirect via hook
onStepFinish = {
    if (someCondition) {
        // can't change anything here — return value is ignored
    }
}

// ✅ Right — use prepareStep instead
prepareStep = {
    if (someCondition) StepSettings(model = differentModel)
    else StepSettings()
}
```

Hook failures don't crash the loop — they're surfaced via `onError` with
`source = ErrorSource.Hook`.

---

## 11. The agent loop is the SDK's job; don't reimplement it

```kotlin
// ❌ Wrong — manual loop
var step = 0
while (step < 5) {
    val result = generateText(...)
    if (result.toolCalls.isNotEmpty()) { ... }
    step++
}

// ✅ Right — ToolLoopAgent owns the loop
val agent = ToolLoopAgent(stopWhen = stepCountIs(5), tools = toolSetOf(...), ...)
agent.generate(prompt)
```

If you need behavior the loop doesn't support, that's a `prepareStep`
override or a custom `Agent` implementation — never a manual `while`.

---

## 12. Subagent tools propagate `abortSignal`

When a tool's `executor` calls another `ToolLoopAgent.generate(...)` (the
subagent-as-tool pattern), forward the parent's `abortSignal` from the
execution context. Forgetting this means the subagent keeps generating
even after the user hit "stop" on the parent.

```kotlin
val subagentTool = tool<...>(...) { input ->
    // `this` is ToolExecutionContext; `abortSignal` is the parent's.
    subagent.generate(input.prompt, abortSignal = abortSignal).text
}
```

The `SubagentTest.subagent_tool_receives_parent_abortSignal` test
asserts that the same signal object lands inside the executor —
propagation, not copy.
