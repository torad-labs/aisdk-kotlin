# Middleware And Telemetry

Middleware changes model behavior without scattering provider checks through
agents or app code. Telemetry records what happened without tying common code
to one observability backend.

## Wrap A Model

```kotlin
val model = WrapLanguageModel(
    model = rawModel,
    middlewares = listOf(
        DefaultSettingsMiddleware(
            temperature = 0.2f,
            maxOutputTokens = 800,
        ),
        ExtractJsonMiddleware(),
    ),
)
```

The first middleware in the list is the outermost wrapper. It sees the call
first and returns last.

## Default Settings

Use default settings for repeated knobs:

```kotlin
val tuned = WrapLanguageModel(
    model = rawModel,
    middlewares = listOf(
        DefaultSettingsMiddleware(
            temperature = 0.1f,
            providerOptions = ProviderOptions.ofPairs(
                "openai" to buildJsonObject {
                    put("reasoningEffort", JsonPrimitive("medium"))
                },
            ),
        ),
    ),
)
```

Explicit call parameters override middleware defaults.

## JSON Extraction

Some models return fenced JSON even when asked for structured output. Use
`ExtractJsonMiddleware` before decoding:

```kotlin
val model = WrapLanguageModel(
    model = localModel,
    middlewares = listOf(ExtractJsonMiddleware()),
)

val recipe = TextGenerator(model)
    .generate(
        GenerationInput.Prompt("Return only a JSON recipe."),
        OutputObj(serializer<Recipe>()),
    )
    .first()
    .output
```

## Simulated Streaming

Use `SimulateStreamingMiddleware` when a model only supports one-shot
generation but the UI expects a stream contract:

```kotlin
val streamingModel = WrapLanguageModel(
    model = batchOnlyModel,
    middlewares = listOf(SimulateStreamingMiddleware()),
)

TextGenerator(streamingModel)
    .stream(GenerationInput.Prompt("Explain MCP."))
    .collect(::render)
```

## Custom Middleware

```kotlin
val auditMiddleware = object : LanguageModelMiddleware {
    override suspend fun transformParams(
        operation: MiddlewareOperation,
        params: LanguageModelCallParams,
        model: LanguageModel,
    ): LanguageModelCallParams {
        audit.log("ai.${operation.name}", model.modelId)
        return params
    }
}
```

Custom middleware can transform params, wrap `generate`, wrap `stream`, and
override visible provider/model metadata.

## Telemetry Integration

```kotlin
val integration = object : Telemetry {
    override val name: String = "app"

    override suspend fun onEvent(call: TelemetryCall, event: AgentEvent) {
        when (event) {
            is AgentEvent.StepFinished -> {
                metrics.increment("ai.step.finish")
                metrics.tokens(event.step.usage.totalTokens)
            }
            is AgentEvent.Errored -> errors.record(event.source.name, event.error)
            else -> Unit
        }
    }
}

Telemetry.registerTelemetry(integration)
```

Global integrations are app-wide. For request-scoped observation, collect
agent events and forward the fields you need to your tracer or metrics layer:

```kotlin
agent.events(prompt = prompt, options = context).collect { event ->
    when (event) {
        is AgentEvent.StepFinished -> {
            metrics.increment("ai.step.finish")
            metrics.tokens(event.step.usage.totalTokens)
        }
        is AgentEvent.Errored -> errors.record(event.source.name, event.error)
        else -> Unit
    }
}
```

`TelemetrySettings` exists as shared telemetry configuration for lower-level
helpers and tests. Do not pass it to `TextGenerator`; use `CallConfig` for
model settings, provider options, cancellation, and structured output.

## DevTools

`DevToolsMiddleware` records step starts, step results, generated text, stream
chunks, and tool data through a `DevToolsRecorder`.

```kotlin
val recorder = InMemoryDevToolsRecorder()

val inspected = WrapLanguageModel(
    model = rawModel,
    middlewares = listOf(DevToolsMiddleware(recorder)),
)
```

The SDK includes in-memory recording. Persistent storage and viewer UI belong
to host tooling.

## Tips

- Put provider normalization in middleware, not in agents.
- Use `agent.events` for request-scoped telemetry.
- Use `InMemoryTelemetryTracer` and `InMemoryDevToolsRecorder` in tests.
- Keep middleware small. One concern per wrapper is easier to test and reorder.

## Related

- [Core](core.md)
- [Settings And Provider Options](settings-and-provider-options.md)
- [Structured Output](structured-output.md)
- [Providers And Models](providers.md)
- [DevTools](devtools.md)
- [Lifecycle And Events](lifecycle-and-events.md)
- [Error Handling](error-handling.md)
- [Testing And Release](testing-and-release.md)
