# Core

Core is the provider-neutral layer. It defines prompts, messages, model
interfaces, generation functions, structured output, middleware, telemetry,
errors, and helpers for non-text model families.

## Text Generation

Use `generateText` for a one-shot model call.

```kotlin
val result = generateText(
    model = model,
    system = "Be precise.",
    prompt = "Summarize Kotlin Multiplatform in one paragraph.",
)

println(result.text)
```

Use `messages` when you already own the conversation state:

```kotlin
val result = generateText(
    model = model,
    messages = listOf(
        systemMessage("Answer with short bullets."),
        userMessage("What changed in the latest release?"),
    ),
)
```

The result contains text, content parts, tool calls, finish reason, usage,
warnings, request/response metadata, provider metadata, files, sources, and
reasoning when the provider supplies them.

## Streaming

Use `streamText` for interactive output. It returns a cold `Flow`.

```kotlin
streamText(model = model, prompt = "Write a haiku.").collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.text)
        is StreamEvent.Finish -> println(event.finishReason)
        else -> Unit
    }
}
```

Use `streamTextResult` when you want stream adapters:

```kotlin
val result = streamTextResult(model = model, prompt = "Tell me a story.")

result.textStream.collect { delta -> print(delta) }

val response = result.toUiMessageStreamResponse(
    assistantMessageId = "assistant-1",
)
```

`StreamTextResult.fullStream` collects the upstream once and replays captured
events to later collectors after a successful completion. The top-level
`streamText` function starts a fresh call for each collection.

## Structured Output

Structured output is expressed with `Output`.

```kotlin
@Serializable
data class Label(val category: String, val confidence: Double)

val result = generateText(
    model = model,
    prompt = "Classify this ticket: payment failed after checkout.",
    output = Output.obj(serializer<Label>()),
)

val label: Label = result.output
```

Supported variants:

- `Output.obj(serializer<T>())`
- `Output.array(serializer<T>())`
- `Output.choice("low", "medium", "high")`
- `Output.json()`

Top-level Kotlin helpers are also available: `outputObj`, `outputArray`,
`outputChoice`, and `outputJson`.

`generateObject` and `streamObject` remain as compatibility shims. Prefer
`generateText(output = ...)` and `streamText(output = ...)` for new code.
Use `streamObjectResult(...)` when you need typed partial or final values from
a structured stream.

## Prompts And Messages

Model calls use `ModelMessage` and sealed `ContentPart` values.

```kotlin
val messages = listOf(
    systemMessage("You are concise."),
    userMessage("Explain streaming."),
)
```

Content parts include text, reasoning, tool calls, tool results, approval
requests, approval responses, sources, files, and images. The sealed shape
keeps rendering and conversion code exhaustive.

## Settings

Direct calls accept v6-shaped named arguments and Kotlin-first grouped
settings:

```kotlin
val settings = callSettings {
    temperature = 0.2f
    maxOutputTokens = 600
    stopSequence("</answer>")
    providerOptions {
        provider("openai", OpenAiTuning(reasoningEffort = "high"))
    }
}

val result = generateText(model = model, settings = settings) {
    system("Answer as a product engineer.")
    prompt("How do provider options work?")
}
```

Provider options and provider metadata can stay typed at application
boundaries:

```kotlin
@Serializable
data class OpenAiTuning(val reasoningEffort: String)

@Serializable
data class OpenAiMetadata(val cacheHit: Boolean)

val cacheHit = result.providerMetadataAs<OpenAiMetadata>("openai")?.cacheHit
```

For defaults, cancellation, provider-specific options, and dynamic agent
settings, see [Settings And Provider Options](settings-and-provider-options.md).

## Model Families

Core includes provider-neutral model interfaces and helpers for:

- Language models: `generateText`, `streamText`.
- Embeddings: `embed`, `embedMany`.
- Reranking: `rerank`.
- Images: `generateImage`.
- Speech: `generateSpeech`.
- Transcription: `transcribe`.
- Video: `generateVideo`.

Embeddings can batch automatically through `maxEmbeddingsPerCall`. Image
generation can split large `n` requests when a model has a per-call cap.

For generated files, use `GeneratedFile` and `FileData`:

```kotlin
val imageInput = imageGenerationFile(
    FileData.Bytes(
        bytes = editedImageBytes,
        mediaType = "image/png",
        filename = "mask.png",
    ),
)
```

For complete examples across embeddings, reranking, image, speech,
transcription, and video, see [Model Families](model-families.md).

## Middleware

Use middleware to normalize model behavior without branching in agent or app
code.

```kotlin
val wrapped = wrapLanguageModel(
    model = rawModel,
    middlewares = listOf(
        defaultSettingsMiddleware(temperature = 0.2f),
        loggingMiddleware(logger),
    ),
)
```

Available middleware includes default settings, logging, reasoning extraction,
JSON extraction, tool-input examples, and simulated streaming.

## Telemetry And DevTools

Telemetry is host-injected and KMP-safe. Use telemetry integrations or a
`TelemetryTracer` to record spans, attributes, usage, metadata, and errors
without tying common code to one observability runtime.

`devToolsMiddleware` records local runs and stream chunks through a
`DevToolsRecorder`. The SDK provides an in-memory recorder; persistent storage
and viewer UI belong to tooling or host apps.

## Errors

Public errors are typed. Catch the narrowest SDK error you can, then inspect
request, response, usage, warnings, and provider metadata.

Transport errors expose machine-readable status, headers, retryability, URL,
and raw provider payload where available. Retry helpers only retry retryable
API errors.

For a full typed-error map and retry examples, see [Error Handling](error-handling.md).

## Related

- [Prompts And Messages](prompts-and-messages.md)
- [Prompt Engineering](prompt-engineering.md)
- [Settings And Provider Options](settings-and-provider-options.md)
- [Structured Output](structured-output.md)
- [Tools](tools.md)
- [Streaming](streaming.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
- [DevTools](devtools.md)
- [Utilities](utilities.md)
- [Error Handling](error-handling.md)
- [Model Families](model-families.md)
