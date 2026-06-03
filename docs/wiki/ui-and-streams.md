# UI And Streams

The UI layer is framework-neutral. Instead of React, Vue, Svelte, or RSC
runtime bindings, AI SDK Kotlin exposes message and stream contracts that can
be rendered by Compose, SwiftUI, terminal UIs, web servers, or test hosts.

## Stream Events

`Flow<StreamEvent>` is the low-level runtime stream. It carries text deltas,
reasoning, tool calls, tool results, tool approval requests, errors, source
parts, file parts, step boundaries, and finish metadata.

Use it when the host needs full control over incremental behavior:

```kotlin
agent.stream(prompt = prompt, options = options).collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> appendText(event.text)
        is StreamEvent.ToolCall -> showToolCall(event.toolName, event.inputJson)
        is StreamEvent.ToolResult -> showToolResult(event.toolName, event.outputJson)
        is StreamEvent.ToolApprovalRequest -> askForApproval(event)
        is StreamEvent.Finish -> finish(event.finishReason)
        else -> Unit
    }
}
```

## UI Messages

`UIMessage` is the high-level render contract. It groups stream events into
assistant/user messages and typed message parts.

```kotlin
val messages = streamToUiMessages(
    events = agent.stream(prompt = prompt, options = options),
    assistantMessageId = "assistant-${turnId}",
)
```

Renderers can switch on `UIMessagePart`:

```kotlin
fun render(part: UIMessagePart) {
    when (part) {
        is UIMessagePart.Text -> renderText(part.text)
        is UIMessagePart.ToolUI -> renderTool(part.toolName, part.state)
        is UIMessagePart.Reasoning -> renderReasoning(part.text)
        is UIMessagePart.Error -> renderError(part.message)
        else -> Unit
    }
}
```

## Chat

`Chat` stores an in-memory message list and delegates sending to a
`ChatTransport`.

```kotlin
val chat = Chat(
    id = "support",
    transport = DirectChatTransport { request ->
        streamToUiMessages(
            events = agent.stream(
                messages = convertToModelMessages(request.messages),
                options = currentContext,
            ),
            assistantMessageId = getResponseUiMessageId(request.messages),
        )
    },
)
```

For server or mobile apps, build platform-specific transports around the same
`ChatRequest` and `UIMessage` contracts.

## Stream Responses

The library includes value objects and writer helpers for text streams and UI
message streams. Server frameworks should adapt those to Ktor, Spring, Javalin,
http4k, Swift server runtimes, or Android local services as needed.

## Tool UI

For string-based dispatch, switch on `toolName`.

For typed dispatch, register handlers with `ToolPartHandlerRegistry` and reuse
the same serializers used by the tool definitions.

## Persistence

Persist `UIMessage` lists or lower-level `ModelMessage` lists in the host app.
On reload, validate messages with `validateUiMessages` or
`safeValidateUIMessages`, then pass them back to `Chat` or `Agent`.
