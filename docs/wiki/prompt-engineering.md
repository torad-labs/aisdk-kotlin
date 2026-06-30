# Prompt Engineering

Prompt engineering in this SDK is mostly about separating concerns:
instructions go in `system`, user work goes in `prompt` or `messages`, tool
contracts live in tools, and output shape lives in `Output`.

## Start With A Stable System Prompt

```kotlin
val result = generateText(
    model = model,
    system = """
        You are a support engineer for AI SDK Kotlin.
        Answer with concrete Kotlin examples.
        If a capability belongs to the host app, say where the boundary is.
    """.trimIndent(),
    prompt = "How should I persist a chat?",
)
```

Use `system` for durable behavior, safety constraints, and formatting rules.
Do not put high-trust instructions inside user-editable message history.

## Keep The User Task Small

Bad prompts mix policy, task, schema, and examples into one paragraph. Prefer
small sections:

```kotlin
val prompt = """
    Task:
    Explain why this stream never finishes.

    Context:
    - The caller collects the same cold Flow twice.
    - The UI only renders text deltas.

    Return:
    - One likely cause.
    - One code-level fix.
""".trimIndent()

val result = generateText(model = model, system = supportSystem, prompt = prompt)
```

This makes later migration into `messages`, tools, or `Output` straightforward.

## Use Messages For Conversations

```kotlin
val result = generateText(
    model = model,
    messages = listOf(
        systemMessage("Answer with exact API names."),
        userMessage("What does streamTextResult give me?"),
        assistantMessage("It gives text and UI stream adapters plus metadata."),
        userMessage("Show the UI stream path."),
    ),
)
```

Use messages when prior assistant output, tool results, approval responses, or
attachments matter for the next turn.

## Let Tools Describe Actions

The model chooses tools from names, descriptions, schemas, and examples. Keep
the prompt focused on policy and task; put action details on the tool:

```kotlin
val searchDocs = Tool<SearchInput, List<SearchHit>, AppContext>(
    name = "searchDocs",
    description = "Search AI SDK Kotlin docs when the user asks about API usage.",
    inputExamples = listOf("""{"query":"streamTextResult UI messages","limit":5}"""),
) { input ->
    docs.search(input.query, input.limit)
}
```

If the prompt has to explain when to call a tool, the tool description is too
weak.

## Let Output Describe Shape

```kotlin
@Serializable
data class Diagnosis(val cause: String, val fix: String, val confidence: Double)

val diagnosis = generateText(
    model = model,
    system = "Diagnose SDK integration problems.",
    prompt = "The agent called a tool, then stopped before final text.",
    output = outputObj(serializer<Diagnosis>()),
).output
```

Use prose for behavior and `Output` for validation. Avoid asking for JSON in
plain text when you can use typed output.

## Retrieval Prompts

For RAG, pass retrieved context as a clearly labeled section and make the
model report uncertainty:

```kotlin
val prompt = """
    Question:
    $question

    Retrieved docs:
    ${hits.joinToString("\n\n") { "[${it.id}] ${it.text}" }}

    Instructions:
    Use the retrieved docs first. If they are insufficient, say what is missing.
    Cite doc ids in the answer.
""".trimIndent()
```

For large results, prefer a retrieval tool plus `toModelOutput` over dumping
every hit into the prompt.

## Checklist

- `system` contains durable behavior.
- `prompt` contains the current task.
- `messages` contain conversation state, not hidden globals.
- Tools explain actions and limits.
- `Output` owns typed response shape.
- Provider-specific settings live in [Settings And Provider Options](settings-and-provider-options.md).

## Related

- [Prompts And Messages](prompts-and-messages.md)
- [Structured Output](structured-output.md)
- [Tools](tools.md)
- [Application Patterns](application-patterns.md)
