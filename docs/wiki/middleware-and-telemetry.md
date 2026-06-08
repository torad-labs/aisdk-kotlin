# Middleware And Telemetry

Middleware changes model behavior without scattering provider checks through
agents or app code. Telemetry records what happened without tying common code
to one observability backend.

## Wrap A Model

```kotlin
val model = wrapLanguageModel(
    model = rawModel,
    middlewares = listOf(
        defaultSettingsMiddleware(
            temperature = 0.2f,
            maxOutputTokens = 800,
        ),
        extractJsonMiddleware(),
    ),
)
```

The first middleware in the list is the outermost wrapper. It sees the call
first and returns last.

## Default Settings

Use default settings for repeated knobs:

```kotlin
val tuned = wrapLanguageModel(
    model = rawModel,
    middlewares = listOf(
        defaultSettingsMiddleware(
            temperature = 0.1f,
            providerOptions = buildProviderOptions {
                provider("openai") {
                    put("reasoningEffort", JsonPrimitive("medium"))
                }
            },
        ),
    ),
)
```

Explicit call parameters override middleware defaults.

## JSON Extraction

Some models return fenced JSON even when asked for structured output. Use
`extractJsonMiddleware` before decoding:

```kotlin
val model = wrapLanguageModel(
    model = localModel,
    middlewares = listOf(extractJsonMiddleware()),
)

val recipe = generateText(
    model = model,
    prompt = "Return only a JSON recipe.",
    output = outputObj(serializer<Recipe>()),
).output
```

## Simulated Streaming

Use `simulateStreamingMiddleware` when a model only supports one-shot
generation but the UI expects a stream contract:

```kotlin
val streamingModel = wrapLanguageModel(
    model = batchOnlyModel,
    middlewares = listOf(simulateStreamingMiddleware()),
)

streamText(model = streamingModel, prompt = "Explain MCP.").collect(::render)
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
val integration = object : TelemetryIntegration {
    override val name: String = "app"

    override suspend fun record(span: TelemetrySpan, block: suspend () -> Unit) {
        tracer.span(span.name, span.attributes) {
            block()
        }
    }

    override suspend fun onToolCallFinish(event: TelemetryEvent) {
        metrics.increment("ai.tool.finish")
    }
}

registerTelemetryIntegration(integration)
```

Global integrations are app-wide. For request-scoped observation, use agent
hooks and forward the fields you need to your tracer or metrics layer:

```kotlin
val result = agent.generate(
    prompt = prompt,
    options = context,
    hooks = AgentCallHooks(
        onStepFinish = { event ->
            metrics.increment("ai.step.finish")
            metrics.tokens(event.step.usage.totalTokens)
        },
        onError = { event ->
            errors.record(event.source.name, event.error)
        },
    ),
)
```

`TelemetrySettings` exists as shared telemetry configuration for lower-level
helpers and tests. Do not pass it to `generateText`; that function exposes
model settings, provider options, cancellation, and structured output.

## DevTools

`devToolsMiddleware` records step starts, step results, generated text, stream
chunks, and tool data through a `DevToolsRecorder`.

```kotlin
val recorder = InMemoryDevToolsRecorder()

val inspected = wrapLanguageModel(
    model = rawModel,
    middlewares = listOf(devToolsMiddleware(recorder)),
)
```

The SDK includes in-memory recording. Persistent storage and viewer UI belong
to host tooling.

## Tips

- Put provider normalization in middleware, not in agents.
- Use agent hooks for request-scoped telemetry.
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
