# Workflow Patterns

Use workflows when you need predictable control flow around model calls. Use
agents when the model should decide the next action. The same app can use both.

## Sequential Generation

Run one model call, then feed its result into the next step:

```kotlin
val classification = TextGenerator(fastModel)
    .generate(
        GenerationInput.Prompt("Classify this request: $request"),
        OutputChoice("billing", "technical", "general"),
    )
    .first()
    .output

val answer = TextGenerator(strongModel)
    .generate(
        GenerationInput.Messages(
            GenerationInput.NonEmptyMessages.of(
                SystemMessage("Answer as a $classification support specialist."),
                UserMessage(request),
            ),
        ),
    )
    .first()
```

Use sequential generation when each step has a clear input and output.

## Model Routing

Route to a tool, model, or prompt based on a structured decision:

```kotlin
@Serializable
data class Route(val lane: String, val reason: String)

val route = TextGenerator(fastModel)
    .generate(
        GenerationInput.Prompt("Route this user request: $request"),
        OutputObj(serializer<Route>()),
    )
    .first()
    .output

val model = when (route.lane) {
    "simple" -> fastModel
    "complex" -> strongModel
    else -> defaultModel
}

val result = TextGenerator(model).generate(GenerationInput.Prompt(request)).first()
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

val summary = TextGenerator(model)
    .generate(GenerationInput.Prompt(reviews.joinToString("\n\n") { it.text }))
    .first()
```

Use this for independent document review, rubric scoring, or multi-lens
analysis. Keep each branch bounded by its own prompt and settings.

## Evaluator Optimizer

Generate, evaluate, then retry with feedback:

```kotlin
@Serializable
data class Evaluation(val pass: Boolean, val feedback: String)

var draft = TextGenerator(model).generate(GenerationInput.Prompt(task)).first().text

repeat(3) {
    val evaluation = TextGenerator(evaluatorModel)
        .generate(
            GenerationInput.Prompt("Evaluate this draft:\n$draft"),
            OutputObj(serializer<Evaluation>()),
        )
        .first()
        .output

    if (evaluation.pass) return@repeat

    draft = TextGenerator(model)
        .generate(GenerationInput.Prompt("Revise using this feedback:\n${evaluation.feedback}\n\n$draft"))
        .first()
        .text
}
```

Use explicit retry limits. Persist evaluation feedback when the workflow is
important enough to audit.

## Agent As Orchestrator

Use an agent when the model needs to choose tools or delegate:

```kotlin
class WorkflowAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
    ToolLoopAgent<AppContext, String>(
        model = model,
        instructions = "Choose the smallest safe workflow for the user request.",
        tools = tools,
        stopWhen = StepCountIs(8),
    )

val agent = WorkflowAgent(model, ToolSet(searchDocs, createTicket, runSubagent))
```

For deterministic workflows, keep orchestration in Kotlin. For open-ended tool
selection, use an agent.

## Manual Loop

Build a manual loop when `ToolLoopAgent` is too opinionated:

```kotlin
var messages = listOf(UserMessage(request))

repeat(5) {
    val result = TextGenerator(model)
        .generate(GenerationInput.Messages(GenerationInput.NonEmptyMessages.from(messages)))
        .first()

    messages = messages + AssistantMessage(result.text)

    if (isGoodEnough(result.text)) return@repeat

    messages = messages + UserMessage("Improve the answer using more concrete steps.")
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
