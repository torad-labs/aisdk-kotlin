# Framework Host Integration

AI SDK Kotlin does not ship React, Vue, Svelte, Angular, or React Server
Components packages. The runtime is Kotlin Multiplatform and exposes
framework-neutral message, stream, and state primitives that host applications
adapt into Compose, SwiftUI, server responses, or their own presentation layer.

This artifact does not provide JavaScript client packages. Web clients can use
the Vercel AI SDK in the client and talk to a Kotlin backend through an explicit
HTTP/SSE protocol.

## What The Kotlin Runtime Provides

- `UIMessage`, `UIMessagePart`, and validation helpers for renderable,
  persistable chat history.
- `StreamToUiMessages` and `ToUIMessageStream` for converting model/agent
  stream events into message snapshots or wire chunks.
- `CreateTextStreamResponse` and `CreateUiMessageStreamResponse` wrappers for
  hosts that want response-shaped stream metadata.
- `Chat`, `ChatSession`, `ChatTransport`, `DirectChatTransport`, and
  `TextStreamChatTransport` for transport-driven chat state.
- `AgentSession` for UI or service code that wants agent status, text, output,
  pending approvals, and message state over time.
- `Completion` and `StructuredObject` state holders for focused completion and
  partial object UI flows.

These are not components and do not render anything. The host chooses state
collection, lifecycle ownership, threading, persistence, and rendering.

## Compose Hosts

Use `ChatSession` or `AgentSession` from a ViewModel or presenter and collect
their `StateFlow` values into the UI:

```kotlin
val session = ChatSession(
    id = "support",
    transport = DirectChatTransport { request ->
        StreamToUiMessages(
            events = agent.stream(
                messages = ModelMessageConversion.convertToModelMessages(request.messages),
            ),
            assistantMessageId = UiMessageStreams.getResponseUiMessageId(request.messages),
        )
    },
)

session.state.collect { state ->
    renderMessages(state.messages)
}
```

Compose owns recomposition and persistence. The SDK only owns message state and
transport calls.

## SwiftUI Hosts

SwiftUI hosts use the same runtime surfaces from shared Kotlin. Adapt
`StateFlow<ChatState>` or `StateFlow<AgentSessionState<*>>` into the app's
observable model, then render `UIMessagePart` variants in SwiftUI views.

Keep approval buttons tied to the `UIMessagePart.ToolUI` ids and call the
session approval APIs from the host action handlers.

## Server Hosts

Server frameworks can expose text streams, UI message snapshots, or
UI-message-chunk JSON streams:

```kotlin
val result = TextGenerator(model).streamResult(GenerationInput.Prompt("Explain the API."))

val response = result.toUiMessageStreamResponse(
    assistantMessageId = "assistant-1",
)
```

Use `UiMessageStreams.pipeTextStreamToResponse` or
`UiMessageStreams.pipeUiMessageStreamToResponse` when the host framework gives
you a writable response sink.

## Completion And Object UI

Use `Completion` for one text field and `StructuredObject` when the UI should
render partial structured state as it arrives. These are Kotlin state holders
backed by transports, not framework hooks.

For examples, see [Completion And Object UI](completion-and-object-ui.md).

## Migration Guidance

- Put framework code in the host app, not the shared SDK layer.
- Persist validated `UIMessage` values, then convert to model messages when
  resuming with a model or agent.
- Render every `UIMessagePart` variant with a fallback so provider-specific
  parts do not break the UI.
- Keep web clients on their JavaScript SDK and define an explicit wire contract
  when they talk to Kotlin services.
- Prefer [UI And Streams](ui-and-streams.md), [Chatbots](chatbots.md), and
  [UI Stream Protocols](ui-stream-protocols.md) for new integrations.

## Related

- [UI And Streams](ui-and-streams.md)
- [Chatbots](chatbots.md)
- [Completion And Object UI](completion-and-object-ui.md)
- [UI Stream Protocols](ui-stream-protocols.md)
- [Agents](agents.md)
