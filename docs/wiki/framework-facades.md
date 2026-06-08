# Framework Facades

AI SDK Kotlin includes framework-shaped facades for upstream package parity.
They are Kotlin Multiplatform state and stream helpers, not React, Vue, Svelte,
Angular, or RSC component implementations.

## React-Shaped Helpers

```kotlin
val helpers = ai.torad.aisdk.react.useChat(
    ai.torad.aisdk.react.UseChatOptions(
        id = "support",
        transport = transport,
        initialMessages = savedMessages,
    ),
)

helpers.sendMessage(userMessage).collect { message ->
    render(message)
}
```

The React facade exposes `useChat`, `useCompletion`, and experimental object
helpers as Kotlin state wrappers. The host UI framework still owns rendering.

## Completion Helpers

```kotlin
val completion = ai.torad.aisdk.react.useCompletion(
    UseCompletionOptions(transport = completionTransport),
)

val text = completion.complete("Draft a changelog entry.")
```

Use completion helpers for single-text-field flows. Use chat helpers when the
history, tools, or UI message parts matter.

For the root transport/state examples, see [Completion And Object UI](completion-and-object-ui.md).

## Vue, Svelte, And Angular Facades

The Vue, Svelte, and Angular packages expose aliases and thin wrappers over the
same root state objects:

- `Chat`
- `UIMessage`
- `Completion`
- completion options
- structured object options

They exist so ported code can keep familiar package names while host apps
build idiomatic platform UI.

## RSC-Shaped Helpers

```kotlin
val controller = createStreamableValue("starting")

scope.launch {
    controller.update("working")
    controller.done("done")
}

readStreamableValue(controller.value).collect { value ->
    render(value)
}
```

RSC helpers include:

- `createStreamableValue` and `readStreamableValue`
- `createStreamableUI`
- `streamUI`
- `createAI`
- `getAIState`, `getMutableAIState`, `useAIState`, `useUIState`, `useActions`
- `createAgentUIStream`

These are experimental parity surfaces. Use the framework-neutral UI message
and stream APIs for new production integrations unless you are porting code
that expects RSC-shaped names.

## Generative UI Pattern

Use tool UI parts rather than asking the model to render arbitrary UI:

```kotlin
val handlers = buildToolPartHandlerRegistry<Node>(
    fallback = { part -> UnknownToolNode(part.toolName) },
) {
    register(showInvoiceTool) { invocation ->
        InvoiceNode(invocation.output, invocation.state)
    }
}
```

This keeps routing probabilistic at the model layer but deterministic at the
renderer boundary.

## Tips

- Prefer [UI And Streams](ui-and-streams.md) for new apps.
- Use facades when porting upstream-shaped code or preserving package names.
- Keep rendering in the host framework.
- Treat RSC helpers as experimental.

## Related

- [UI And Streams](ui-and-streams.md)
- [Chatbots](chatbots.md)
- [Completion And Object UI](completion-and-object-ui.md)
- [UI Stream Protocols](ui-stream-protocols.md)
- [Tools](tools.md)
