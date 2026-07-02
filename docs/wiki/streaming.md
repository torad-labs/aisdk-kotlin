# Streaming

Streaming is the path for interactive output. It carries text, reasoning,
tool-call state, sources, files, errors, metadata, and finish events through
`Flow<StreamEvent>`.

## Basic Text Stream

```kotlin
TextGenerator(model).stream(
    GenerationInput.Prompt("Write a short migration checklist."),
).collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.text)
        is StreamEvent.Finish -> println("\n${event.finishReason}")
        else -> Unit
    }
}
```

`TextGenerator.stream(...)` is cold. Each collection starts a new provider
call. Use `TextGenerator.streamResult(...)` when you need a result object and
adapters.

## Stream Result Adapters

```kotlin
val result = TextGenerator(model).streamResult(
    GenerationInput.Prompt("Explain UI message streams."),
)

result.textStream.collect { delta ->
    print(delta)
}

val response = result.toUiMessageStreamResponse(
    assistantMessageId = "assistant-42",
)
```

`StreamTextResult.fullStream` collects once and replays captured events to
later collectors after successful completion. This is useful for tests and
framework adapters.

## Stream Events To UI Messages

```kotlin
val uiMessages = StreamToUiMessages(
    events = agent.stream(prompt = prompt, options = context),
    assistantMessageId = "assistant-${turn.id}",
)

uiMessages.collect { message ->
    render(message.parts)
}
```

Use UI messages when the host wants stable render state instead of raw deltas.

## Server Responses

Host frameworks can adapt SDK response values:

```kotlin
val result = TextGenerator(model).streamResult(GenerationInput.Prompt(prompt))

return result.toTextStreamResponse()
```

For rich chat streams:

```kotlin
return result.toUiMessageStreamResponse(
    assistantMessageId = "assistant-${turn.id}",
)
```

Use `UiMessageStreams.pipeTextStreamToResponse` and
`UiMessageStreams.pipeUiMessageStreamToResponse` when the
host framework exposes a writer instead of a returnable response object.

## Streaming Tools

Streaming tools emit preliminary results and then a final result:

```kotlin
agent.stream(prompt = "Research the issue.", options = context)
    .collect { event ->
        when (event) {
            is StreamEvent.ToolResult ->
                renderTool(event.toolName, event.output, event.preliminary)
            is StreamEvent.ToolError ->
                renderToolError(event.toolName, event.error)
            else -> Unit
        }
    }
```

Use preliminary results for progress cards, not for durable model context. The
final tool result is what the loop adds to model messages.

## Cancellation

Pass an `AbortSignal` from your host when users can stop a request:

```kotlin
val controller = AbortController()

val job = launch {
    TextGenerator(
        model,
        CallConfig { abortSignal(controller.signal) },
    ).stream(GenerationInput.Prompt(prompt)).collect(::handleEvent)
}

controller.abort()
job.cancel()
```

Tool executors receive the same cancellation path through their execution
context.

## Error Handling

Non-streaming generation throws errors before returning. Streaming can surface
errors as stream events depending on where the failure occurs. Always handle:

- `StreamEvent.Error`
- `StreamEvent.ToolError`
- collector cancellation
- host writer failures

For typed errors, retries, and UI validation failures, see [Error Handling](error-handling.md).

## Tips

- Collect a stream once unless you intentionally want another provider call.
- Prefer `TextGenerator.streamResult(...)` for framework adapters and tests.
- Store completed UI messages, not raw deltas.
- Render tool and reasoning parts independently from text parts.

## Related

- [Core](core.md)
- [Advanced Streaming](advanced-streaming.md)
- [UI And Streams](ui-and-streams.md)
- [UI Stream Protocols](ui-stream-protocols.md)
- [Chatbots](chatbots.md)
- [Error Handling](error-handling.md)
- [Testing And Release](testing-and-release.md)
