# Completion And Object UI

Completion and object helpers are transport-driven state holders for focused
UI flows. Use chat for conversations; use completion for a single text result;
use object UI for partial structured JSON.

## Completion

```kotlin
val completion = Completion(
    UseCompletionOptions(
        id = "title",
        transport = DirectCompletionTransport { request ->
            streamText(
                model = model,
                prompt = request.prompt,
            ).filterIsInstance<StreamEvent.TextDelta>()
                .map { it.text }
        },
        onFinish = { prompt, text ->
            audit.completed(prompt, text.length)
        },
    ),
)

val text = completion.complete("Write a title for this article.")
```

`Completion` tracks:

- `completion`
- `input`
- `loading`
- `error`

Use `stop()` to abort an active completion.

## Completion Facades

```kotlin
val helper = ai.torad.aisdk.react.useCompletion(
    UseCompletionOptions(
        transport = completionTransport,
    ),
)

val text = helper.complete("Draft a status update.")
```

React, Vue, Svelte, and Angular facades expose framework-shaped names over the
same root completion state.

## Structured Object UI

```kotlin
val schema = jsonSchema<JsonObject>(
    buildJsonObject { put("type", JsonPrimitive("object")) },
    validate = { it.jsonObject },
)

val structured = StructuredObject(
    StructuredObjectOptions<JsonObject, String>(
        api = "/api/object",
        schema = schema,
        transport = DirectStructuredObjectTransport { input ->
            flowOf("""{"summary":"${input.input}""", ""","done":true}""")
        },
        onFinish = { finish ->
            audit.objectFinished(finish.error == null)
        },
    ),
)

structured.submit("Extract the action items.")
```

`StructuredObject` parses partial JSON as chunks arrive, validates it against
the schema, and updates `value`, `rawValue`, `error`, and `loading`.

## Object Facades

```kotlin
val helper = ai.torad.aisdk.react.experimental_useObject(
    StructuredObjectOptions<JsonObject, String>(
        api = "/api/object",
        schema = schema,
        transport = objectTransport,
    ),
)

helper.submit("Extract the fields.")
```

Use object helpers when the UI should render partial structured state as it
arrives. Use [Structured Output](structured-output.md) for ordinary server-side
typed generation.

## Tips

- Use completion for one text field, not chat.
- Use object UI for partial structured UI state.
- Use `generateText(output = ...)` when partial object updates are not needed.
- Keep transports thin and test them with fake streams.

## Related

- [UI And Streams](ui-and-streams.md)
- [Framework Facades](framework-facades.md)
- [Structured Output](structured-output.md)
- [Streaming](streaming.md)
