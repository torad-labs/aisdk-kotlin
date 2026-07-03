# Chatbots

Chatbots combine streaming, UI messages, persistence, tool rendering, and
resume logic. AI SDK Kotlin keeps the UI layer framework-neutral so the same
contracts can back Compose, SwiftUI, terminal apps, web servers, and tests.

## Minimal Direct Chat

```kotlin
val transport = DirectChatTransport { request ->
    StreamToUiMessages(
        events = agent.stream(
            messages = ModelMessageConversion.convertToModelMessages(request.messages),
            options = currentContext,
        ),
        assistantMessageId = UiMessageStreams.getResponseUiMessageId(request.messages),
    )
}

val chat = Chat(
    id = "support",
    transport = transport,
)
```

`DirectChatTransport` is the simplest bridge from UI messages to an agent or
model call.

## StateFlow Chat Session

```kotlin
val session = ChatSession(
    id = "support",
    transport = transport,
)

scope.launch {
    session.state.collect { state ->
        renderMessages(state.messages)
        renderStatus(state.status)
        renderError(state.error)
    }
}

session.sendMessage(
    UIMessage(
        id = "user-${clock.now()}",
        role = UIMessageRole.User,
        parts = listOf(UIMessagePart.Text("How do I use tools?")),
    ),
).collect()
```

Use `ChatSession` in ViewModels and UI controllers. It owns message state,
status, errors, regeneration, stop, and resume.

## Persist Messages

Validate messages before storage:

```kotlin
val checked = UiMessageStreams.safeValidateUIMessages(session.state.value.messages)

if (checked is SafeValidateUIMessagesResult.Success) {
    chatStore.save(chatId = session.id, messages = checked.messages)
}
```

Restore by passing stored messages back into the session:

```kotlin
session.setMessages(chatStore.load(chatId))
```

## Tool UI

Render tool parts separately from text:

```kotlin
fun renderPart(part: UIMessagePart) {
    when (part) {
        is UIMessagePart.Text -> renderText(part.text)
        is UIMessagePart.ToolUI -> renderToolCard(
            name = part.toolName,
            state = part.state,
            input = part.input,
            output = part.output,
        )
        is UIMessagePart.DynamicToolUI -> renderDynamicTool(part)
        is UIMessagePart.Error -> renderError(part.message)
        else -> renderSupplemental(part)
    }
}
```

For larger apps, register typed renderers:

```kotlin
val handlers = ToolPartHandlerRegistry<Node>(
    fallback = { part -> UnknownToolNode(part.toolName) },
) {
    register(searchDocs) { invocation ->
        SearchNode(invocation.output.orEmpty(), invocation.state)
    }
}
```

## Approval In Chat

When a tool asks for approval, add the user's decision through the chat or
agent session:

```kotlin
session.addToolApprovalResponse(
    toolCallId = pending.toolCallId,
    approvalId = pending.approvalId,
    approved = userApproved,
    reason = "Decision made in the approval dialog.",
)
```

Approval is stored in messages so a refresh or process restart can still
resume the interaction.

## Resume Streams

Use resume when the transport can reconnect to an active or recoverable
stream:

```kotlin
session.resumeStream().collect { message ->
    renderMessage(message)
}
```

For app-owned agents, prefer durable message persistence plus a new generation
call unless the host has a real stream-resume backend.

## Plain Text Transport

Use `TextStreamChatTransport` when a backend only emits text:

```kotlin
val transport = TextStreamChatTransport(handler = { request ->
    TextGenerator(model)
        .stream(
            GenerationInput.Messages(
                GenerationInput.NonEmptyMessages.from(
                    ModelMessageConversion.convertToModelMessages(request.messages),
                ),
            ),
        )
        .filterIsInstance<StreamEvent.TextDelta>()
        .map { it.text }
})
```

This is useful for simple demos and CLI-like surfaces. Use UI message streams
when you need tools, files, sources, and reasoning.

## Tips

- Persist validated UI messages after each completed turn.
- Render every `UIMessagePart` variant with a fallback.
- Keep approval UI tied to message ids and tool-call ids.
- Use `AgentSession` when you care more about agent state than chat message
  rendering.

## Related

- [UI And Streams](ui-and-streams.md)
- [UI Stream Protocols](ui-stream-protocols.md)
- [Completion And Object UI](completion-and-object-ui.md)
- [Streaming](streaming.md)
- [Advanced Streaming](advanced-streaming.md)
- [Framework Host Integration](framework-facades.md)
- [Agents](agents.md)
- [Tools](tools.md)
