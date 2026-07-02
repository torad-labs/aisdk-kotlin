# Completion And Object UI

Completion and object helpers are transport-driven state holders for focused
UI flows. Use chat for conversations; use completion for a single text result;
use object UI for partial structured JSON.

## Completion

```kotlin
val completion = Completion(
    UseCompletionOptions(block = {
        id("title")
        transport(
            object : CompletionTransport {
                override fun complete(request: CompletionRequest): Flow<String> =
                    TextGenerator(model)
                        .stream(GenerationInput.Prompt(request.prompt))
                        .filterIsInstance<StreamEvent.TextDelta>()
                        .map { it.text }
            },
        )
        onFinish { prompt, text ->
            audit.completed(prompt, text.length)
        }
    }),
)

val text = completion.complete("Write a title for this article.")
```

`Completion` tracks:

- `completion`
- `input`
- `loading`
- `error`

Use `stop()` to abort an active completion.

## Completion State

`Completion` is the public Kotlin state holder. Compose, SwiftUI, server, and
terminal hosts collect or read its `state`, `completion`, `loading`, and
`error` properties directly instead of going through a framework hook package.

## Structured Object UI

```kotlin
val schema = Schemas.jsonSchema(
    schema = buildJsonObject { put("type", JsonPrimitive("object")) },
    validate = { it.jsonObject },
)

val structured = StructuredObject(
    StructuredObjectOptions<JsonObject, String>(block = {
        api("/api/object")
        schema(schema)
        transport(
            object : StructuredObjectTransport<String> {
                override fun submit(request: StructuredObjectRequest<String>): Flow<String> =
                    flowOf("""{"summary":"${request.input}""", ""","done":true}""")
            },
        )
        onFinish { finish ->
            audit.objectFinished(finish.error == null)
        }
    }),
)

structured.submit("Extract the action items.")
```

`StructuredObject` parses partial JSON as chunks arrive, validates it against
the schema, and updates `value`, `rawValue`, `error`, and `loading`.

## Object State

`StructuredObject` is likewise a Kotlin state holder. Call `submit(...)`, read
`value`, `rawValue`, `error`, and `loading`, or collect `state` from the host UI.

Use object helpers when the UI should render partial structured state as it
arrives. Use [Structured Output](structured-output.md) for ordinary server-side
typed generation.

## Tips

- Use completion for one text field, not chat.
- Use object UI for partial structured UI state.
- Use `TextGenerator.generate(input, output = ...)` when partial object updates
  are not needed.
- Keep transports thin and test them with fake streams.

## Related

- [UI And Streams](ui-and-streams.md)
- [Framework Facades](framework-facades.md)
- [Structured Output](structured-output.md)
- [Streaming](streaming.md)
