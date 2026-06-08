# Workflow Patterns

Use workflows when you need predictable control flow around model calls. Use
agents when the model should decide the next action. The same app can use both.

## Sequential Generation

Run one model call, then feed its result into the next step:

```kotlin
val classification = generateText(
    model = fastModel,
    prompt = "Classify this request: $request",
    output = outputChoice("billing", "technical", "general"),
).output

val answer = generateText(
    model = strongModel,
    system = "Answer as a $classification support specialist.",
    prompt = request,
)
```

Use sequential generation when each step has a clear input and output.

## Model Routing

Route to a tool, model, or prompt based on a structured decision:

```kotlin
@Serializable
data class Route(val lane: String, val reason: String)

val route = generateText(
    model = fastModel,
    prompt = "Route this user request: $request",
    output = outputObj(serializer<Route>()),
).output

val model = when (route.lane) {
    "simple" -> fastModel
    "complex" -> strongModel
    else -> defaultModel
}

val result = generateText(model = model, prompt = request)
```

Use typed output for routing. Do not parse free-form prose when the branch
controls cost, tools, or user experience.

## Parallel Processing

Run independent model calls concurrently:

```kotlin
val reviews = coroutineScope {
    listOf(
        async { reviewSecurity(code) },
        async { reviewPerformance(code) },
        async { reviewMaintainability(code) },
    ).awaitAll()
}

val summary = generateText(
    model = model,
    prompt = reviews.joinToString("\n\n") { it.text },
)
```

Use this for independent document review, rubric scoring, or multi-lens
analysis. Keep each branch bounded by its own prompt and settings.

## Evaluator Optimizer

Generate, evaluate, then retry with feedback:

```kotlin
@Serializable
data class Evaluation(val pass: Boolean, val feedback: String)

var draft = generateText(model = model, prompt = task).text

repeat(3) {
    val evaluation = generateText(
        model = evaluatorModel,
        prompt = "Evaluate this draft:\n$draft",
        output = outputObj(serializer<Evaluation>()),
    ).output

    if (evaluation.pass) return@repeat

    draft = generateText(
        model = model,
        prompt = "Revise using this feedback:\n${evaluation.feedback}\n\n$draft",
    ).text
}
```

Use explicit retry limits. Persist evaluation feedback when the workflow is
important enough to audit.

## Agent As Orchestrator

Use an agent when the model needs to choose tools or delegate:

```kotlin
val agent = ToolLoopAgent<AppContext, String>(
    model = model,
    instructions = "Choose the smallest safe workflow for the user request.",
    tools = toolSetOf(searchDocs, createTicket, runSubagent),
    stopWhen = stepCountIs(8),
)
```

For deterministic workflows, keep orchestration in Kotlin. For open-ended tool
selection, use an agent.

## Manual Loop

Build a manual loop when `ToolLoopAgent` is too opinionated:

```kotlin
var messages = listOf(userMessage(request))

repeat(5) {
    val result = generateText(
        model = model,
        messages = messages,
    )

    messages = messages + assistantMessage(result.text)

    if (isGoodEnough(result.text)) return@repeat

    messages = messages + userMessage("Improve the answer using more concrete steps.")
}
```

Manual loops are harder to get right. Prefer `ToolLoopAgent` unless you need
custom history management, recovery, or workflow state.

## Choosing A Pattern

| Pattern | Use when |
|---|---|
| Sequential | Later steps depend on earlier typed results. |
| Routing | You need a small, explicit branch. |
| Parallel | Work is independent and can be merged. |
| Evaluator optimizer | Quality control matters more than latency. |
| Agent | The model should decide which tools to call. |
| Manual loop | You need full control over loop state. |

## Related

- [Agents](agents.md)
- [Application Patterns](application-patterns.md)
- [Structured Output](structured-output.md)
- [Tools](tools.md)
- [Error Handling](error-handling.md)
