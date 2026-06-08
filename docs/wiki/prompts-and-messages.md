# Prompts And Messages

Prompts are the input you send to a model. AI SDK Kotlin supports the same
three prompt shapes as upstream: plain text, system instructions, and message
history.

## Text And System Prompts

Use a plain prompt for one-shot work:

```kotlin
val result = generateText(
    model = model,
    prompt = "Summarize this release note in three bullets.",
)
```

Use `system` for durable behavior instructions:

```kotlin
val result = generateText(
    model = model,
    system = "You are a support engineer. Be specific and cite uncertainty.",
    prompt = "The customer says uploads fail on mobile.",
)
```

Prefer `system` over user-editable message history for policy, role, and
format instructions.

## Message History

Use `messages` when the app owns conversation state:

```kotlin
val messages = listOf(
    systemMessage("Answer with short, testable steps."),
    userMessage("How do I stream a response?"),
    assistantMessage("Use streamTextResult when you need response adapters."),
    userMessage("Show the server side shape."),
)

val result = generateText(model = model, messages = messages)
```

`ModelMessage` has four roles: `System`, `User`, `Assistant`, and `Tool`.
Tool calls, tool results, files, sources, reasoning, and approval decisions
are content parts, not side channels.

## Multi-Part User Input

Build a `ModelMessage` directly when a turn has more than text:

```kotlin
val message = ModelMessage(
    role = MessageRole.User,
    content = listOf(
        ContentPart.Text("What changed in this screenshot?"),
        ContentPart.File(
            mediaType = "image/png",
            base64 = screenshotBase64,
            filename = "diff.png",
        ),
    ),
)

val result = generateText(
    model = visionModel,
    messages = listOf(message),
)
```

Use content parts for any value you may need to render, validate, persist, or
convert back into model input later.

## Tool Results In Messages

When your host executes a tool outside the agent loop, write the result back as
a tool message:

```kotlin
val toolResult = toolMessage(
    toolCallId = "call-123",
    toolName = "searchDocs",
    output = JsonPrimitive("Found 4 matching pages."),
)

val followUp = generateText(
    model = model,
    messages = previousMessages + toolResult,
)
```

Approval responses use the same principle:

```kotlin
val response = toolApprovalResponseMessage(
    toolCallId = pending.toolCallId,
    approvalId = pending.approvalId,
    approved = true,
    reason = "User confirmed the write.",
)
```

Persist these messages. The next call can resume from history without a hidden
agent process.

## UI Message Conversion

Use `UIMessage` for rendering and persistence in chat surfaces, then convert
back before calling the model:

```kotlin
val checked = safeValidateUIMessages(savedUiMessages)

val modelMessages = when (checked) {
    is SafeValidateUIMessagesResult.Success ->
        convertToModelMessages(checked.messages)
    is SafeValidateUIMessagesResult.Failure ->
        error("Stored chat history is invalid: ${checked.error.message}")
}
```

By default, incomplete tool calls fail conversion. That is usually correct:
an incomplete tool call means the persisted history is not safe to replay.

## Provider Options

Provider options can live on the call:

```kotlin
val result = generateText(
    model = model,
    prompt = "Draft a migration summary.",
    providerOptions = buildProviderOptions {
        provider("openai") {
            put("reasoningEffort", JsonPrimitive("medium"))
        }
    },
)
```

Use provider options sparingly and keep them near the provider boundary. If
every call needs the same option, move it into [Middleware And Telemetry](middleware-and-telemetry.md)
or [Settings And Provider Options](settings-and-provider-options.md).

## Tips

- Use `prompt` for a single task and `messages` for conversations.
- Store `ModelMessage` or validated `UIMessage` values, not rendered text.
- Put high-trust behavior in `system`, not in user-controlled history.
- Keep tool outputs concise for the model; use `toModelOutput` when the UI
  needs richer data than the model does.

## Related

- [Core](core.md)
- [Prompt Engineering](prompt-engineering.md)
- [Settings And Provider Options](settings-and-provider-options.md)
- [Tools](tools.md)
- [Structured Output](structured-output.md)
- [UI And Streams](ui-and-streams.md)
