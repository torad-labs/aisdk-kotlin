# Error Handling

The SDK exposes typed errors so callers can distinguish bad input, provider
failures, stream failures, validation failures, missing tools, and retry
exhaustion.

## Catch Narrow Errors

```kotlin
try {
    val result = TextGenerator(model).generate(GenerationInput.Prompt(prompt)).first()
    render(result.text)
} catch (error: APICallError) {
    logger.warn("Provider call failed: ${error.statusCode} ${error.message}")
    if (error.isRetryable) queueRetry()
} catch (error: AiSdkException) {
    logger.warn("SDK error: ${error.message}")
}
```

Catch `APICallError` when HTTP status, headers, retryability, URL, or provider
error text matters. Catch `AiSdkException` for SDK-level fallback handling.

## Common Error Types

| Error | Usually means | First check |
|---|---|---|
| `InvalidArgumentError` | Caller supplied invalid SDK input. | The named argument in the exception. |
| `InvalidPromptError` | Prompt or messages are not valid for the call. | Whether `prompt` and `messages` were mixed incorrectly. |
| `APICallError` | Provider or HTTP transport failed. | `statusCode`, `isRetryable`, `responseHeaders`. |
| `NoSuchProviderError` | Registry cannot resolve a provider prefix. | `ProviderRegistry.createProviderRegistry` setup. |
| `NoSuchModelError` | Provider cannot resolve the requested model id. | Model family and id string. |
| `NoSuchToolError` | The model called a tool that is not active. | Tool names and `activeTools`. |
| `InvalidToolInputError` | Tool input did not decode. | Tool serializer and repair callback. |
| `MissingToolResultsError` | Conversation history has tool calls without results. | Persisted messages and approval flow. |
| `MessageConversionError` | UI messages cannot become model messages. | Incomplete tool calls or unsupported parts. |
| `RetryError` | Retry policy exhausted or hit a non-retryable error after retries. | `reason`, `errors`, `lastError`. |

## Retries

Embedding and reranking helpers retry retryable provider errors by default.
For custom work, use `RetryPolicy`:

```kotlin
val value = RetryPolicy { maxRetries(3) }.execute(
    shouldRetry = { error -> (error as? APICallError)?.isRetryable == true },
) { attempt ->
    remoteIndex.query(query, attempt)
}
```

Cancellation is not retried. Non-retryable errors fail fast on the first
attempt.

## Streaming Errors

Handle both stream-level and tool-level errors:

```kotlin
agent.stream(prompt = prompt, options = context).collect { event ->
    when (event) {
        is StreamEvent.Error -> renderError(event.message)
        is StreamEvent.ToolError -> renderToolError(event.toolName, event.error)
        is StreamEvent.Finish -> finish(event.finishReason)
        else -> renderEvent(event)
    }
}
```

A stream can fail after partial UI has already rendered. Keep the last valid
message state and show the error as a part or status.

## Tool Errors

Tool execution errors are part of the agent loop. Use `toModelOutput` for
expected domain failures and exceptions for unexpected failures:

```kotlin
toModelOutput = { result, _ ->
    if (result.allowed) {
        ToolResultOutput.Text("Approved")
    } else {
        ToolResultOutput.Error("The user is not allowed to perform this action.")
    }
}
```

If the model emits malformed tool input, use `experimental_repairToolCall`
only for predictable repairs. Let invalid input fail when repair would hide a
real contract problem.

## UI Validation

Validate before persistence:

```kotlin
when (val checked = UiMessageStreams.safeValidateUIMessages(messages)) {
    is SafeValidateUIMessagesResult.Success -> save(checked.messages)
    is SafeValidateUIMessagesResult.Failure -> report(checked.error)
}
```

Do not replay incomplete tool calls unless the host intentionally drops them
with `ignoreIncompleteToolCalls = true`.

## Tips

- Log status, retryability, provider id, model id, and request id when present.
- Keep user-facing errors short; keep diagnostic details in logs.
- Retry only idempotent work unless the tool or host has a dedupe key.
- Treat approval denial as normal conversation state, not an exception.

## Related

- [Troubleshooting](troubleshooting.md)
- [Streaming](streaming.md)
- [Tools](tools.md)
- [Testing And Release](testing-and-release.md)
