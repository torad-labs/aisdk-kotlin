# UI Stream Protocols

UI streams are the bridge between model events and renderable chat state. AI
SDK Kotlin exposes both text streams and rich UI message streams.

## Text Streams

Use text streams for simple output:

```kotlin
val result = TextGenerator(model).streamResult(GenerationInput.Prompt(prompt))
val response = result.toTextStreamResponse()
```

The default text response uses `text/plain; charset=utf-8`. It contains text
deltas only, so it cannot represent tools, sources, files, reasoning, or custom
data parts.

## UI Message Streams

Use UI message streams for chat:

```kotlin
val events = agent.stream(
    messages = messages,
    options = context,
)

val response = CreateUiMessageStreamResponse(
    stream = StreamToUiMessages(
        events = events,
        assistantMessageId = "assistant-${turn.id}",
    ),
)
```

The default UI stream response uses `text/event-stream; charset=utf-8`. Host
frameworks adapt `UIMessageStreamResponse` or pipe it through a
`ServerResponseWriter`.

## Pipe To A Host Writer

```kotlin
val result = TextGenerator(model).streamResult(GenerationInput.Prompt(prompt))

UiMessageStreams.pipeUiMessageStreamToResponse(
    stream = result.toUiMessageStream("assistant-1"),
    response = writer,
)
```

Implement `ServerResponseWriter` in Ktor, Spring, http4k, desktop IPC, or test
helpers.

## Create Custom UI Streams

```kotlin
val stream = CreateUiMessageStream {
    write(
        UIMessage(
            id = "status-1",
            role = UIMessageRole.Assistant,
            parts = listOf(
                UIMessagePart.Data(
                    type = "status",
                    data = JsonPrimitive("starting"),
                    transient = true,
                ),
            ),
        ),
    )

    merge(agentMessages)
}
```

Use `Data` parts for UI-only state such as progress, counters, or diagnostics.
Set `transient = true` when the part should render but not be treated as
durable chat content.

## Read UI Message Streams

Use `ReadUiMessageStream` when a host wants to consume message snapshots
outside a chat helper:

```kotlin
val stream = TextGenerator(model).streamResult(
    GenerationInput.Prompt("Write a short story."),
).toUiMessageStream("assistant-1")

ReadUiMessageStream(stream).collect { message ->
    terminal.render(message)
}
```

This is useful for terminal UIs, tests, background processors, and RSC-shaped
helpers.

## Message Ids

Use stable ids for persisted messages:

```kotlin
val assistantId = UiMessageStreams.getResponseUiMessageId(
    messages = priorMessages,
    createId = { idGenerator.generate() },
)
```

Regeneration and stream resume are easier when the assistant message id is
stable for the turn.

## Metadata

Attach metadata to the message when the renderer or host needs extra context:

```kotlin
val message = UIMessage(
    id = "assistant-1",
    role = UIMessageRole.Assistant,
    parts = listOf(UIMessagePart.Text("Done")),
    metadata = mapOf("traceId" to JsonPrimitive(traceId)),
)

val trace = message.metadataAs<String>("traceId")
```

Use metadata for render hints, trace ids, timestamps, or feature flags. Keep
model-visible content in `parts`.

## Wire Chunk Names

`toUiMessageStream` style adapters map low-level events into named chunks. The
Kotlin helper uses these names when encoding stream events:

| Chunk | Meaning |
|---|---|
| `start` | Stream started. |
| `start-step` | A multi-step model/tool loop entered a new step. |
| `text-start`, `text-delta`, `text-end` | Text part lifecycle. |
| `reasoning-start`, `reasoning-delta`, `reasoning-end` | Reasoning part lifecycle. |
| `source-url`, `source-document` | Source citations. |
| `file` | File output. |
| `tool-input-start`, `tool-input-delta`, `tool-input-available` | Tool input lifecycle. |
| `tool-approval-request` | Tool execution needs user or host approval. |
| `tool-output-available`, `tool-output-error`, `tool-output-denied` | Tool output lifecycle. |
| `finish-step` | One loop step finished. |
| `finish` | The full stream finished. |
| `error` | The stream failed. |
| `abort` | The stream was cancelled. |

Renderers should ignore unknown chunk names and keep the last valid message
state.

## Finish Handling

```kotlin
UiMessageStreams.handleUiMessageStreamFinish(messages) { finalMessages ->
    save(finalMessages)
}
```

Persist only after validation when the host stores chat state.

## Tips

- Use text streams for plain text only.
- Use UI message streams when tools, files, sources, reasoning, or custom UI
  parts matter.
- Render unknown `UIMessagePart` variants with a fallback.
- Convert to `ModelMessage` only at model-call boundaries.

## Related

- [UI And Streams](ui-and-streams.md)
- [Chatbots](chatbots.md)
- [Completion And Object UI](completion-and-object-ui.md)
- [Streaming](streaming.md)
- [Advanced Streaming](advanced-streaming.md)
- [Error Handling](error-handling.md)
