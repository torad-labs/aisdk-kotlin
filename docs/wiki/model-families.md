# Model Families

Core is not only language generation. AI SDK Kotlin exposes provider-neutral
helpers for embeddings, reranking, images, speech, transcription, and video.

## Embeddings

Use `Embedding.embed` for one value:

```kotlin
val result = Embedding.embed(
    model = provider.embeddingModel("text-embedding-3-small"),
    value = "AI SDK Kotlin streams UI messages.",
)

vectorStore.insert(id = "doc-1", vector = result.embedding)
```

Use `Embedding.embedMany` for batches:

```kotlin
val result = Embedding.embedMany(
    model = embeddingModel,
    values = chunks.map { it.text },
    maxParallelCalls = 4,
)

result.embeddings.zip(chunks).forEach { (embedding, chunk) ->
    vectorStore.insert(chunk.id, embedding)
}
```

`embedMany` respects explicit batch size, then the model's
`maxEmbeddingsPerCall`, then one batch. When a model supports parallel calls,
batch concurrency defaults to 8; pass `maxParallelCalls` to raise or lower it.

## Reranking

```kotlin
val reranked = Reranking.rerank(
    model = provider.rerankingModel("rerank-v3.5"),
    query = "How do I resume streams?",
    documents = candidateDocs.map { it.text },
    topN = 5,
)

val bestDocs = reranked.results.map { scored ->
    candidateDocs[scored.index]
}
```

Use reranking after retrieval and before answer generation.

## Image Generation

```kotlin
val result = ImageGeneration.generateImage(
    model = provider.imageModel("image-model"),
    prompt = "A clean diagram of Kotlin Flow streaming into UI messages.",
    n = 2,
    aspectRatio = "16:9",
    maxParallelCalls = 4,
)

val firstImage = result.image
```

For image edits, pass `files` and an optional `mask`:

```kotlin
val result = ImageGeneration.generateImage(
    model = imageModel,
    prompt = "Replace the background with a simple studio surface.",
    files = listOf(ImageGenerationFile(FileData.Bytes(inputBytes, "image/png"))),
    mask = ImageGenerationFile(FileData.Bytes(maskBytes, "image/png")),
)
```

## Speech

```kotlin
val speech = SpeechGeneration.generateSpeech(
    model = provider.speechModel("tts-model"),
    text = "Your report is ready.",
    voice = "alloy",
    responseFormat = "mp3",
)

val audio: GeneratedFile = speech.audio
```

## Transcription

```kotlin
val transcript = Transcription.transcribe(
    model = provider.transcriptionModel("transcribe-model"),
    audio = AudioSource(
        mediaType = "audio/mpeg",
        base64 = audioBase64,
        filename = "call.mp3",
    ),
    language = "en",
)

println(transcript.text)
```

Transcription can also return segments, detected language, duration, warnings,
response metadata, and provider metadata when the provider supplies them.

## Video

```kotlin
val result = VideoGeneration.generateVideo(
    model = provider.videoModel("video-model"),
    prompt = "A calm product walkthrough animation.",
    durationSeconds = 5f,
    aspectRatio = "16:9",
    maxParallelCalls = 2,
)

val video = result.video
```

Video support depends heavily on the provider. Keep provider-specific knobs in
`providerOptions`.

## Files

Generated media uses `GeneratedFile`. Inputs can be built from `FileData`:
import `ai.torad.aisdk.GeneratedFiles.fileData` when calling `fileData()`.

```kotlin
val generated = GeneratedFile(
    FileData.Url(
        value = "https://example.com/source.png",
        mediaType = "image/png",
        filename = "source.png",
    ),
)

val local = generated.fileData()
```

## Tips

- Keep media generation behind provider capability checks.
- Persist `response`, `warnings`, and `providerMetadata` with generated media.
- Use `maxParallelCalls` to bound embedding, image, and video load in servers.
- Treat reranking as a relevance pass, not as a replacement for retrieval.

## Related

- [Providers And Models](providers.md)
- [Core](core.md)
- [Application Patterns](application-patterns.md)
- [Testing And Release](testing-and-release.md)
