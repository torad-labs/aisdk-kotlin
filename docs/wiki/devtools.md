# DevTools

DevTools records model calls and streams through middleware. Use it during
development, debugging, and tests. Do not enable it in production.

## Wrap A Model

```kotlin
val recorder = InMemoryDevToolsRecorder()

val inspectedModel = WrapLanguageModel(
    model = rawModel,
    middlewares = listOf(
        DevToolsMiddleware(
            recorder = recorder,
            environment = "development",
        ),
    ),
)

val result = TextGenerator(inspectedModel)
    .generate(GenerationInput.Prompt("Explain tool calling."))
    .first()
```

`DevToolsMiddleware` records a run and one step per generate or stream call.

## Inspect Recorded Data

```kotlin
recorder.runs.forEach { runId ->
    println("run: $runId")
}

recorder.steps.forEach { step ->
    println("${step.stepNumber}: ${step.provider}/${step.modelId}")
}

recorder.results.forEach { (stepId, result) ->
    println("$stepId finished in ${result.durationMs}ms")
}
```

Recorded step data includes provider, model id, input, provider options,
duration, output summary, usage, error message, and stream chunks when present.

## Streaming Diagnostics

```kotlin
val model = WrapLanguageModel(
    model = rawModel,
    middlewares = listOf(DevToolsMiddleware(recorder)),
)

TextGenerator(model)
    .stream(GenerationInput.Prompt("Stream a short answer."))
    .collect { event -> render(event) }

val streamResult = recorder.results.values.last()
println(streamResult.rawChunks.size)
```

Use this to verify event order, usage reporting, and tool-call flow.

## Production Guard

Passing `environment = "production"` throws immediately:

```kotlin
DevToolsMiddleware(environment = "production")
```

This guard prevents accidental production capture. Use telemetry integrations
for production observability.

## Custom Recorder

Implement `DevToolsRecorder` to write to files, a database, or an internal
debug panel:

```kotlin
class FileDevToolsRecorder(
    private val files: DebugFileStore,
) : DevToolsRecorder {
    override suspend fun createRun(runId: String) {
        files.append("runs.log", runId)
    }

    override suspend fun createStep(step: DevToolsStep) {
        files.writeJson("steps/${step.id}.json", step)
    }

    override suspend fun updateStepResult(stepId: String, result: DevToolsStepResult) {
        files.writeJson("steps/$stepId.result.json", result)
    }
}
```

Keep custom recorders out of common library code when they depend on platform
storage.

## Related

- [Middleware And Telemetry](middleware-and-telemetry.md)
- [Lifecycle And Events](lifecycle-and-events.md)
- [Utilities](utilities.md)
- [Error Handling](error-handling.md)
- [Testing And Release](testing-and-release.md)
