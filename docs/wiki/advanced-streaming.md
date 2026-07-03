# Advanced Streaming

Most apps only need [Streaming](streaming.md) and [UI Stream Protocols](ui-stream-protocols.md).
Use this page when you need cancellation, replay, caching, or explicit
backpressure decisions.

## Stop A Stream

Pass an `AbortSignal` through the call:

```kotlin
val controller = AbortController()

val job = scope.launch {
    TextGenerator(
        model,
        CallConfig { abortSignal(controller.signal) },
    ).stream(GenerationInput.Prompt(prompt)).collect(::render)
}

controller.abort()
job.cancel()
```

For agent streams, `events(...)` surfaces abort lifecycle events with the
completed steps:

```kotlin
agent.events(prompt = prompt, options = context).collect { event ->
    if (event is AgentEvent.Aborted) {
        audit.aborted(event.steps.size)
    }
}
```

## Stop Versus Resume

Stopping a stream and resuming a stream are different host contracts. A stopped
stream should be treated as a terminal partial result. A resumable stream needs
durable server-side state and stable message ids.

For most hosts, the simpler recovery path is:

1. Persist validated UI messages after completed turns.
2. On reconnect, load the latest saved messages.
3. Start a new model call if the user asks to continue.

Use `ChatSession.resumeStream()` only when the transport can actually reconnect
to an active stream.

## Backpressure

`Flow` is lazy. Keep it lazy:

```kotlin
val stream = TextGenerator(model).stream(GenerationInput.Prompt(prompt))

stream.collect { event ->
    writer.write(event)
}
```

Avoid launching background collectors that keep reading after the UI or network
writer is gone. Couple collection to the host request, screen, or service
scope, and cancel that scope when the consumer goes away.

## Fanout And Replay

Collecting `TextGenerator.stream(...)` twice starts two provider calls. Use
`TextGenerator.streamResult(...)` when multiple adapters need the same completed
stream:

```kotlin
val result = TextGenerator(model).streamResult(GenerationInput.Prompt(prompt))

val uiMessages = result.toUiMessageStream("assistant-1")
val textOnly = result.textStream
```

Use the low-level stream for one consumer. Use the result object for adapters,
tests, and replay after successful completion.

## Caching

Cache deterministic work first:

- retrieval results,
- embeddings,
- reranked candidates,
- small classifiers,
- provider metadata needed for billing or trace lookup.

When caching model generations, include all behavior-affecting inputs in the
cache key: model id, messages, system prompt, settings, provider options, tool
set, output schema, and retrieved context.

For stream caching, use middleware so both generate and stream calls share the
same policy:

```kotlin
val caching = object : LanguageModelMiddleware {
    override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult {
        val key = cacheKey(context.params)
        cache.getGenerate(key)?.let { return it }
        return context.doGenerate(context.params).also { cache.putGenerate(key, it) }
    }
}
```

Cache replay should emit the same event shape the UI expects.

## Cleanup

Always close or cancel host-owned resources:

- cancel the collection job,
- close MCP clients,
- stop active chat or agent sessions,
- flush telemetry exporters owned by the host,
- persist completed messages before dropping state.

## Related

- [Streaming](streaming.md)
- [UI Stream Protocols](ui-stream-protocols.md)
- [Utilities](utilities.md)
- [Application Patterns](application-patterns.md)
- [Error Handling](error-handling.md)
