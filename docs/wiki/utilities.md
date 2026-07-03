# Utilities

Utility helpers cover stream replay, stream smoothing, ids, pruning, cosine
similarity, abort signals, and small data helpers.

## Generate Ids

```kotlin
val messageId = IdGenerator.generate(prefix = "msg")

val idGenerator = IdGenerator {
    prefix("tool")
    size(12)
}

val toolCallId = idGenerator.generate()
```

Use stable ids for persisted UI messages, tool calls, and stream resume.

## Smooth Streams

```kotlin
val smooth = SmoothStream(
    upstream = TextGenerator(model).stream(GenerationInput.Prompt(prompt)),
    delayMs = 15,
    chunkBy = ChunkBy.Word,
)

smooth.collect { event -> render(event) }
```

Use `SmoothStream` when a provider emits text too quickly or in awkward token
chunks for a chat UI. Non-text events pass through without delay.

## Replay Recorded Streams

```kotlin
val replay = SimulateReadableStream(
    chunks = cachedEvents,
    delayMillis = 5,
)

replay.collect { event -> render(event) }
```

Use this in tests and stream-caching middleware.

## Prune Messages

```kotlin
val pruned = MessagePruning.pruneMessages(
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
val score = EmbeddingMath.cosineSimilarity(queryEmbedding, documentEmbedding)
```

Use this for simple in-memory ranking. For large datasets, use a vector index
owned by the host app.

## Abort Signals

```kotlin
val signal = CombineAbortSignals(
    userRequestSignal,
    with(AbortSignals) { backgroundJob.asAbortSignal() },
)
```

Use merged signals when cancellation can come from more than one owner.

## Data URLs

```kotlin
val parsed = DataUrl.parse("data:image/png;base64,$encoded")
```

Use file and media helpers when passing generated media between model families.

## Related

- [Streaming](streaming.md)
- [Advanced Streaming](advanced-streaming.md)
- [UI Stream Protocols](ui-stream-protocols.md)
- [DevTools](devtools.md)
- [Testing And Release](testing-and-release.md)
