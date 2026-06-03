# Core

Core is the provider-neutral layer for calling models. It contains text
generation, streaming, structured output, tool contracts, model-family
interfaces, middleware, telemetry, and the public error taxonomy.

## Text Generation

Use `generateText` for a single provider call without an agent loop:

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
        userMessage("What changed in the last release?"),
    ),
)
```

## Streaming

`streamText` returns a cold `Flow<StreamEvent>`. The upstream call starts when
the flow is collected.

```kotlin
streamText(model = model, prompt = "Write a haiku.").collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.text)
        is StreamEvent.Finish -> println(event.finishReason)
        else -> Unit
    }
}
```

If the host needs both text-only and full event streams, use
`streamTextResult`.

## Structured Output

Structured output is exposed through `Output` and Kotlin serializers:

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

Supported output helpers include object, array, choice, and raw JSON modes.
Deprecated v6-compatible `generateObject` and `streamObject` shims route
through the same structured-output path.

## Tools

Tools are typed with serializers and can be composed into agents or executed
through provider tool-call surfaces:

```kotlin
@Serializable
data class SearchInput(val query: String)

@Serializable
data class SearchResult(val title: String, val url: String)

val searchDocs = tool<SearchInput, List<SearchResult>, AppContext>(
    name = "searchDocs",
    description = "Search product documentation.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
) { input ->
    docs.search(input.query)
}
```

Use `dynamicTool` only when the schema or result type cannot be fixed at
compile time.

## Model Families

Core includes provider-neutral interfaces and helper functions for:

- Language models: `generateText`, `streamText`.
- Embeddings: `embed`, `embedMany`.
- Reranking: `rerank`.
- Images: `generateImage`.
- Speech: `generateSpeech`.
- Transcription: `transcribe`.
- Video: `generateVideo`.

Each model family returns provider metadata, warnings, request/response
metadata, and usage when the provider supplies it.

## Middleware

Use middleware to normalize provider behavior without branching inside agent
or app code:

```kotlin
val normalized = wrapLanguageModel(
    model = rawModel,
    middlewares = listOf(
        loggingMiddleware(logger),
        defaultSettingsMiddleware(temperature = 0.2f),
    ),
)
```

Middleware can wrap generate and stream paths. Provider quirks, tracing,
logging, synthetic streaming, reasoning extraction, and JSON extraction belong
here.

## Telemetry And DevTools

The core telemetry API is host-injected and KMP-safe. It records spans,
attributes, usage, and model metadata without depending on a concrete OpenTelemetry
runtime. DevTools middleware records local runs and stream chunks for
inspection; persistent storage and viewer UI are tooling responsibilities.

## Errors

Public errors mirror the v6 taxonomy where it applies in Kotlin:

- Provider/model lookup failures.
- No-output failures for text, object, image, speech, transcription, video,
  embedding, and reranking calls.
- Tool input/output, approval, execution, and repair failures.
- Gateway and provider-specific HTTP or protocol failures.

Catch the narrowest project error you can, then inspect request/response
metadata when the provider exposes it.
