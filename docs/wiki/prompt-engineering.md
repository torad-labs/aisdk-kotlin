# Prompt Engineering

Prompt engineering in this SDK is mostly about separating concerns:
instructions go in `system`, user work goes in `prompt` or `messages`, tool
contracts live in tools, and output shape lives in `Output`.

## Start With A Stable System Prompt

```kotlin
val result = TextGenerator(model)
    .generate(
        GenerationInput.Messages(
            GenerationInput.NonEmptyMessages.of(
                SystemMessage(
                    """
                    You are a support engineer for AI SDK Kotlin.
                    Answer with concrete Kotlin examples.
                    If a capability belongs to the host app, say where the boundary is.
                    """.trimIndent(),
                ),
                UserMessage("How should I persist a chat?"),
            ),
        ),
    )
    .first()
```

Use `SystemMessage` for durable behavior, safety constraints, and formatting rules.
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

val result = TextGenerator(model)
    .generate(
        GenerationInput.Messages(
            GenerationInput.NonEmptyMessages.of(
                SystemMessage(supportSystem),
                UserMessage(prompt),
            ),
        ),
    )
    .first()
```

This makes later migration into `messages`, tools, or `Output` straightforward.

## Use Messages For Conversations

```kotlin
val result = TextGenerator(model)
    .generate(
        GenerationInput.Messages(
            GenerationInput.NonEmptyMessages.of(
                SystemMessage("Answer with exact API names."),
                UserMessage("What does streamResult give me?"),
                AssistantMessage("It gives text and UI stream adapters plus metadata."),
                UserMessage("Show the UI stream path."),
            ),
        ),
    )
    .first()
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
    inputExamples = listOf("""{"query":"streamResult UI messages","limit":5}"""),
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

val diagnosis = TextGenerator(model)
    .generate(
        GenerationInput.Messages(
            GenerationInput.NonEmptyMessages.of(
                SystemMessage("Diagnose SDK integration problems."),
                UserMessage("The agent called a tool, then stopped before final text."),
            ),
        ),
        OutputObj(serializer<Diagnosis>()),
    )
    .first()
    .output
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

- `SystemMessage` contains durable behavior.
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
