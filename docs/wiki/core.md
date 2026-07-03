# Core

Core is the provider-neutral layer. It defines prompts, messages, model
interfaces, generation APIs, structured output, middleware, telemetry,
errors, and helpers for non-text model families.

## Text Generation

Use `TextGenerator` for a one-shot model call.
Do not call `LanguageModel.generate` from application prompt paths unless you
are deliberately using the low-level provider API with
`@OptIn(LowLevelLanguageModelApi::class)`.

```kotlin
val result = TextGenerator(model)
    .generate(GenerationInput.Prompt("Summarize Kotlin Multiplatform in one paragraph."))
    .first()

println(result.text)
```

Use `messages` when you already own the conversation state:

```kotlin
val input = GenerationInput.Messages(
    GenerationInput.NonEmptyMessages.of(
        SystemMessage("Answer with short bullets."),
        UserMessage("What changed in the latest release?"),
    ),
)
val result = TextGenerator(model).generate(input).first()
```

The result contains text, content parts, tool calls, finish reason, usage,
warnings, request/response metadata, provider metadata, files, sources, and
reasoning when the provider supplies them.

## Streaming

Use `TextGenerator.stream` for interactive output. It returns a cold `Flow`.

```kotlin
TextGenerator(model).stream(GenerationInput.Prompt("Write a haiku.")).collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.text)
        is StreamEvent.Finish -> println(event.finishReason)
        else -> Unit
    }
}
```

Use `TextGenerator.streamResult` when you want stream adapters:

```kotlin
val result = TextGenerator(model).streamResult(GenerationInput.Prompt("Tell me a story."))

result.textStream.collect { delta -> print(delta) }

val response = result.toUiMessageStreamResponse(
    assistantMessageId = "assistant-1",
)
```

`StreamTextResult.fullStream` collects the upstream once and replays captured
events to later collectors after a successful completion. `TextGenerator.stream`
starts a fresh call for each collection.

## Structured Output

Structured output is expressed with `Output`.

```kotlin
@Serializable
data class Label(val category: String, val confidence: Double)

val result = TextGenerator(model)
    .generate(
        GenerationInput.Prompt("Classify this ticket: payment failed after checkout."),
        Output.obj(serializer<Label>()),
    )
    .first()

val label: Label = result.output
```

Supported variants:

- `Output.obj(serializer<T>())`
- `Output.array(serializer<T>())`
- `Output.choice("low", "medium", "high")`
- `Output.json()`

PascalCase top-level constructors are also available: `OutputObj`,
`OutputArray`, `OutputChoice`, and `OutputJson`.

Use `TextGenerator.generate(input, output)` for a final typed value. Use
`StructuredObjectGenerator(model, schema).stream(input)` when you need typed
partial or final values from a structured stream.

## Prompts And Messages

Model calls use `ModelMessage` and sealed `ContentPart` values.

```kotlin
val messages = GenerationInput.NonEmptyMessages.of(
    SystemMessage("You are concise."),
    UserMessage("Explain streaming."),
)
```

Content parts include text, reasoning, tool calls, tool results, approval
requests, approval responses, sources, files, and images. The sealed shape
keeps rendering and conversion code exhaustive.

## Settings

High-level generators accept Kotlin-first grouped settings through `CallConfig`:

```kotlin
val config = CallConfig {
    temperature(0.2f)
    maxOutputTokens(600)
    stopSequences(listOf("</answer>"))
    providerOptions(
        ProviderOptions.ofPairs(
            "openai" to buildJsonObject {
                put("reasoningEffort", JsonPrimitive("high"))
            },
        ),
    )
}

val result = TextGenerator(model, config)
    .generate(
        GenerationInput.Prompt(
            """
            Answer as a product engineer.
            How do provider options work?
            """.trimIndent(),
        ),
    )
    .first()
```

`CallSettings` is the lower-level settings value used by agents and step
configuration:

```kotlin
val settings = CallSettings {
    temperature(0.2f)
    maxOutputTokens(600)
    providerOptions {
        provider("openai") {
            put("reasoningEffort", JsonPrimitive("high"))
        }
    }
}
```

Provider options and provider metadata can stay typed at application
boundaries:

```kotlin
@Serializable
data class OpenAiMetadata(val cacheHit: Boolean)

val cacheHit = result.providerMetadataAs<OpenAiMetadata>("openai")?.cacheHit
```

For defaults, cancellation, provider-specific options, and dynamic agent
settings, see [Settings And Provider Options](settings-and-provider-options.md).

## Model Families

Core includes provider-neutral model interfaces and helpers for:

- Language models: `TextGenerator`.
- Embeddings: `Embedding.embed`, `Embedding.embedMany`.
- Reranking: `Reranking.rerank`.
- Images: `ImageGeneration.generateImage`.
- Speech: `SpeechGeneration.generateSpeech`.
- Transcription: `Transcription.transcribe`.
- Video: `VideoGeneration.generateVideo`.

Embeddings can batch automatically through `maxEmbeddingsPerCall`. Image
generation can split large `n` requests when a model has a per-call cap.

For generated files, use `GeneratedFile` and `FileData`:

```kotlin
val imageInput = ImageGenerationFile(
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
val wrapped = WrapLanguageModel(
    model = rawModel,
    middlewares = listOf(
        DefaultSettingsMiddleware(temperature = 0.2f),
        LoggingMiddleware(logger),
    ),
)
```

Available middleware includes default settings, logging, reasoning extraction,
JSON extraction, tool-input examples, and simulated streaming.

## Telemetry And DevTools

Telemetry is host-injected and KMP-safe. Use telemetry integrations or a
`TelemetryTracer` to record spans, attributes, usage, metadata, and errors
without tying common code to one observability runtime.

`DevToolsMiddleware` records local runs and stream chunks through a
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
