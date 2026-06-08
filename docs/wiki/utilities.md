# Utilities

Utility helpers cover stream replay, stream smoothing, ids, pruning, cosine
similarity, abort signals, and small data helpers.

## Generate Ids

```kotlin
val messageId = generateId(prefix = "msg")

val idGenerator = createIdGenerator(
    prefix = "tool",
    size = 12,
)

val toolCallId = idGenerator.generate()
```

Use stable ids for persisted UI messages, tool calls, and stream resume.

## Smooth Streams

```kotlin
val smooth = smoothStream(
    upstream = streamText(model = model, prompt = prompt),
    delayMs = 15,
    chunkBy = ChunkBy.Word,
)

smooth.collect { event -> render(event) }
```

Use `smoothStream` when a provider emits text too quickly or in awkward token
chunks for a chat UI. Non-text events pass through without delay.

## Replay Recorded Streams

```kotlin
val replay = simulateReadableStream(
    events = cachedEvents,
    initialDelayMs = 0,
    chunkDelayMs = 5,
)

replay.collect { event -> render(event) }
```

Use this in tests and stream-caching middleware.

## Prune Messages

```kotlin
val pruned = pruneMessages(
    messages = messages,
    reasoning = PruneReasoning.BeforeLastMessage,
    toolCalls = PruneToolCalls.BeforeLastMessages(2),
)
```

Pruning keeps recent context useful while removing old reasoning and tool
details. Keep enough tool history for unresolved approvals and current UI
state.

## Cosine Similarity

```kotlin
val score = cosineSimilarity(queryEmbedding, documentEmbedding)
```

Use this for simple in-memory ranking. For large datasets, use a vector index
owned by the host app.

## Abort Signals

```kotlin
val signal = mergeAbortSignals(
    userRequestSignal,
    backgroundJob.asAbortSignal(),
)
```

Use merged signals when cancellation can come from more than one owner.

## Data URLs

```kotlin
val parsed = splitDataUrl("data:image/png;base64,$encoded")
```

Use file and media helpers when passing generated media between model families.

## Related

- [Streaming](streaming.md)
- [Advanced Streaming](advanced-streaming.md)
- [UI Stream Protocols](ui-stream-protocols.md)
- [DevTools](devtools.md)
- [Testing And Release](testing-and-release.md)
