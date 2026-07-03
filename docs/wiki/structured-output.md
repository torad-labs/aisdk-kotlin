# Structured Output

Use structured output when the model must return typed data instead of prose.
AI SDK Kotlin puts final typed text generation on `TextGenerator.generate`
through an `Output` value, and typed partial streaming on
`StructuredObjectGenerator`.

## Provider Strategy And Native Json Schema

Structured object generation in AI SDK Kotlin is SDK-managed. The high-level
object paths build tool-call or JSON-oriented instructions, then validate,
repair, and decode the returned JSON with the SDK `Output`/`Schema` surfaces.
They do not currently enable provider-native constrained decoding modes such as
OpenAI `response_format: { "type": "json_schema" }` or Google native response
schema enforcement for object generation.

In the provider capability matrix, `partial` for structured output means the SDK
can request, validate, decode, and stream typed output for that provider, not
that the provider is enforcing the JSON schema token-by-token. This can affect
reliability: native constrained decoding can reject invalid tokens earlier than
post-generation validation and repair.

Native `json_schema` support for OpenAI/OpenAI-compatible and Google providers
is tracked as a separate future backlog item. Acceptance for that item should
include provider golden tests proving:

- OpenAI/OpenAI-compatible requests send native `response_format` JSON schema.
- Google requests send the native response schema shape.
- Invalid output or provider schema errors are surfaced without being silently
  repaired into a successful result.

## Object Output

```kotlin
@Serializable
data class Recipe(
    val name: String,
    val ingredients: List<String>,
    val steps: List<String>,
)

val result = TextGenerator(model)
    .generate(
        GenerationInput.Prompt("Generate a simple soup recipe."),
        Output.obj(serializer<Recipe>(), name = "Recipe"),
    )
    .first()

val recipe: Recipe = result.output
```

## Array Output

```kotlin
@Serializable
data class Finding(val title: String, val severity: String)

val findings = TextGenerator(model)
    .generate(
        GenerationInput.Prompt("List likely issues in this bug report."),
        Output.array(serializer<Finding>(), name = "FindingList"),
    )
    .first()
    .output
```

`Output.array(...)` accepts either a JSON array or an object with an
`elements` array when decoding.

## Choice Output

```kotlin
val priority = TextGenerator(model)
    .generate(
        GenerationInput.Prompt("Classify this ticket: production checkout is down."),
        Output.choice("low", "medium", "high", name = "Priority"),
    )
    .first()
    .output
```

Use choices for routing, labels, and simple classifiers.

## JSON Tree Output

```kotlin
val json = TextGenerator(model)
    .generate(
        GenerationInput.Prompt("Return a small JSON object with the issue summary."),
        Output.json(name = "IssueSummary"),
    )
    .first()
    .output
```

Use `Output.json` at system boundaries where the schema is not known at compile
time. Prefer typed serializers inside application code.

## Structured Output With Tools

```kotlin
@Serializable
data class Answer(val summary: String, val sources: List<String>)

class AnswerAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
    ToolLoopAgent<AppContext, Answer>(
        model = model,
        instructions = "Search docs before answering.",
        tools = tools,
        output = Output.obj(serializer<Answer>()),
        stopWhen = StepCountIs(6),
    )

val agent = AnswerAgent(model, ToolSet(searchDocs))
val result = agent.generate(messages = messages, options = context).first()
```

When tools and structured output are combined, budget for both the tool steps
and the final structured response step.

## Streaming Structured Output

```kotlin
val schema = Schemas.jsonSchema(
    schema = Output.array(serializer<ChecklistItem>()).schema,
    validate = { element ->
        aiSdkOutputJson.decodeFromJsonElement(
            ListSerializer(serializer<ChecklistItem>()),
            element,
        )
    },
)

val phases = StructuredObjectGenerator(model, schema)
    .stream(GenerationInput.Prompt("Create a release checklist."))

phases.collect { phase ->
    if (phase is StructuredObjectPhase.Streaming) {
        phase.partial?.let(::renderDraft)
    }
}
```

For only the final value, call `StructuredObjectGenerator.generate(...)` instead
of collecting `stream(...)`. Streams are cold and collection drives the model
call.

## Compatibility Helpers

New code should use:

- `TextGenerator(model).generate(input, output)` for final typed values.
- `StructuredObjectGenerator(model, schema).stream(input)` when typed partial or
  final stream phases are needed.
- `Output.obj`, `Output.array`, `Output.choice`, `Output.json`
- `OutputObj`, `OutputArray`, `OutputChoice`, `OutputJson`

## Tips

- Name schemas when logs, provider traces, or prompt inspection matter.
- Use typed serializers for app-owned contracts.
- Use `ExtractJsonMiddleware` for models that wrap JSON in markdown fences.
- Keep prose instructions out of the schema. Put behavior in `system` or
  `prompt`, then let `Output` describe the shape.

## Related

- [Core](core.md)
- [Prompt Engineering](prompt-engineering.md)
- [Tools](tools.md)
- [Settings And Provider Options](settings-and-provider-options.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
- [Testing And Release](testing-and-release.md)
