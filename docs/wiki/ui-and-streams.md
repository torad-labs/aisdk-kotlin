# UI And Streams

The UI layer is framework-neutral. Instead of React, Vue, Svelte, or RSC
runtime bindings, AI SDK Kotlin exposes message and stream contracts that can
be rendered by Compose, SwiftUI, terminal UIs, servers, or tests.

## Concepts

Use this order when building a chat UI:

1. Run an agent or model and get `StreamEvent` values.
2. Convert events into `UIMessage` snapshots.
3. Render `UIMessagePart` variants.
4. Persist validated messages.
5. Convert persisted UI messages back to `ModelMessage` when resuming.

## Stream Events

`Flow<StreamEvent>` is the low-level stream. It carries text, reasoning,
sources, files, tool input, tool calls, tool results, approval requests,
errors, metadata, step boundaries, and finish events.

For stream adapter, cancellation, server response, and error-handling examples,
see [Streaming](streaming.md).

Use it when the host needs full control:

```kotlin
agent.stream(prompt = prompt, options = context).collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> appendText(event.text)
        is StreamEvent.ToolCall -> showTool(event.toolName)
        is StreamEvent.ToolResult -> updateTool(event.toolName)
        is StreamEvent.ToolApprovalRequest -> askForApproval(event)
        is StreamEvent.Finish -> finish(event.finishReason)
        else -> Unit
    }
}
```

## UI Messages

`UIMessage` is the high-level render contract. It groups stream events into
message snapshots and typed parts.

```kotlin
val messages = StreamToUiMessages(
    events = agent.stream(prompt = prompt, options = context),
    assistantMessageId = "assistant-$turnId",
)
```

Renderers switch on `UIMessagePart`:

```kotlin
fun render(part: UIMessagePart) {
    when (part) {
        is UIMessagePart.Text -> renderText(part.text, part.state)
        is UIMessagePart.Reasoning -> renderReasoning(part.text, part.state)
        is UIMessagePart.ToolUI -> renderTool(part.toolName, part.state)
        is UIMessagePart.DynamicToolUI -> renderDynamicTool(part.toolName)
        is UIMessagePart.SourceUrl -> renderSource(part.url, part.title)
        is UIMessagePart.SourceDocument -> renderDocument(part.title)
        is UIMessagePart.File -> renderFile(part.mediaType, part.filename)
        is UIMessagePart.Data -> renderData(part.type, part.data)
        is UIMessagePart.StepStart -> renderStep(part.stepNumber)
        is UIMessagePart.Error -> renderError(part.message)
    }
}
```

Text and reasoning parts carry `Streaming` or `Done` state. Tool parts carry
tool-call lifecycle state and can mark preliminary output from `StreamingTool`.

## Typed Tool UI

For small apps, switch on `toolName`. For larger apps, register handlers.

```kotlin
val handlers = buildToolPartHandlerRegistry<RenderNode>(
    fallback = { part -> UnknownToolNode(part.toolName) },
) {
    register(searchDocsTool) { invocation ->
        SearchResultsNode(invocation.output.orEmpty(), invocation.state)
    }
}
```

You can also decode directly at render time:

```kotlin
val result: SearchResult? = part.outputAs<SearchResult>()
```

## Chat

`Chat` stores in-memory UI messages and delegates sending to a `ChatTransport`.

```kotlin
val chat = Chat(
    id = "support",
    transport = DirectChatTransport { request ->
        StreamToUiMessages(
            events = agent.stream(
                messages = ModelMessageConversion.convertToModelMessages(request.messages),
                options = currentContext,
            ),
            assistantMessageId = UiMessageStreams.getResponseUiMessageId(request.messages),
        )
    },
)
```

`TextStreamChatTransport` adapts plain text streams into assistant messages.
`DefaultChatTransport` delegates to another transport and keeps the same API
shape as upstream transport-based chat.

For text stream versus UI message stream protocols, custom UI streams,
message ids, metadata, and host writers, see [UI Stream Protocols](ui-stream-protocols.md).

## ChatSession

Use `ChatSession` when a UI wants a `StateFlow<ChatState>`.

```kotlin
val session = ChatSession(
    id = "support",
    transport = transport,
)

session.sendMessage(
    UIMessage(
        id = "user-1",
        role = UIMessageRole.User,
        parts = listOf(UIMessagePart.Text("Hello")),
    ),
).collect()

session.state.collect { state ->
    renderMessages(state.messages)
    renderLoading(state.isStreaming)
}
```

`ChatSession` supports setting messages, clearing errors, tool outputs,
approval responses, regeneration, stop, and resume.

## AgentSession

Use `AgentSession` when you want agent state rather than UI-message state.

```kotlin
val session = agent.session(viewModelScope)

session.submitStreaming(prompt = "Find the docs.", options = context)

session.state.collect { state ->
    renderText(state.text)
    renderApprovals(state.pendingApprovals)
}
```

`AgentSession` is useful for ViewModels, repositories, and services that want
messages, text, output, status, approvals, result, and errors in one place.

## Stream Responses

Servers can return value objects and adapt them to their framework:

```kotlin
val result = TextGenerator(model).streamResult(GenerationInput.Prompt(prompt))
val response = result.toTextStreamResponse()
```

For UI-message streams:

```kotlin
val response = result.toUiMessageStreamResponse(
    assistantMessageId = "assistant-1",
)
```

Use `UiMessageStreams.pipeTextStreamToResponse` and
`UiMessageStreams.pipeUiMessageStreamToResponse` with a host-provided
`ServerResponseWriter`.

## Persistence

Validate before storing or replaying UI messages:

```kotlin
when (val checked = UiMessageStreams.safeValidateUIMessages(messages)) {
    is SafeValidateUIMessagesResult.Success -> save(checked.messages)
    is SafeValidateUIMessagesResult.Failure -> report(checked.error)
}
```

Convert UI messages back to model messages when resuming:

```kotlin
val modelMessages = ModelMessageConversion.convertToModelMessages(
    messages = savedMessages,
    ignoreIncompleteToolCalls = false,
)
```

Files and sources are preserved when they have model-side representations.
Incomplete tool calls throw by default because they usually indicate corrupted
history.

## Completion And Object Helpers

The framework facades include Kotlin equivalents of completion and object UI
helpers:

- `Completion`, `UseCompletionOptions`, `CompletionTransport`
- `StructuredObject`, `StructuredObjectOptions`, `StructuredObjectTransport`

These are transport-driven state holders. They are useful when a host wants
the shape of upstream UI hooks without depending on a web framework.

For examples, see [Completion And Object UI](completion-and-object-ui.md).

## Framework Facades

The `react`, `vue`, `svelte`, `angular`, and `rsc` packages expose aliases and
adapters for upstream package parity. They do not ship actual framework
components. Compose, SwiftUI, and server renderers belong in host apps.

For the exact facade surfaces and when to use them, see [Framework Facades](framework-facades.md).

## Related

- [Streaming](streaming.md)
- [UI Stream Protocols](ui-stream-protocols.md)
- [Chatbots](chatbots.md)
- [Completion And Object UI](completion-and-object-ui.md)
- [Framework Facades](framework-facades.md)
- [Prompts And Messages](prompts-and-messages.md)
- [Tools](tools.md)
- [Agents](agents.md)
