# Getting Started

This page gets you from a checkout to a first model call and a first agent.
Use shared Kotlin for prompts, tools, agents, and UI-message contracts, then
bind platform HTTP clients, credentials, storage, and UI from the host app.

## Install

Until a stable release is published, publish the artifact locally:

```sh
./gradlew publishToMavenLocal
```

Then add the dependency:

```kotlin
dependencies {
    implementation("ai.torad:aisdk-kotlin:0.1.0-SNAPSHOT")
}
```

For application development against a checkout, use a composite build:

```kotlin
// settings.gradle.kts
includeBuild("../aisdk-kotlin")
```

## Choose A Model Path

There are three normal ways to get a model:

| Path | Use when |
|---|---|
| Gateway | You want one provider that routes by model id. |
| OpenAI-compatible | Your service speaks OpenAI-compatible HTTP. |
| Dedicated facade | You need provider-specific options, metadata, media APIs, or auth. |

For tests and examples, use mock models:

```kotlin
import ai.torad.aisdk.providers.MockLanguageModel

val model = MockLanguageModel.textOnly("Hello from AI SDK Kotlin.")
```

## Generate Text

Use `generateText` for a single model call.

```kotlin
val result = generateText(
    model = model,
    system = "Answer as a concise Kotlin engineer.",
    prompt = "What does this SDK provide?",
)

println(result.text)
```

For Kotlin call sites, the builder form keeps settings grouped:

```kotlin
val result = generateText(model) {
    system("Answer with short bullets.")
    prompt("How do I stream output?")
    settings {
        temperature = 0.2f
        maxOutputTokens = 400
    }
}
```

## Stream Text

`streamText` returns a cold `Flow<StreamEvent>`. The model call starts when
the flow is collected.

```kotlin
streamText(model = model, prompt = "Write a short intro.").collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.text)
        is StreamEvent.Finish -> println(event.finishReason)
        else -> Unit
    }
}
```

Use `streamTextResult` when you want helpers for text-only streams or
UI-message streams:

```kotlin
val result = streamTextResult(model = model, prompt = "Stream this.")
result.textStream.collect { delta -> print(delta) }
```

## Define A Tool

Tools use Kotlin serializers for input and output types.

```kotlin
@Serializable
data class WeatherInput(val city: String)

@Serializable
data class WeatherOutput(val summary: String)

val weatherTool = tool<WeatherInput, WeatherOutput, Unit>(
    name = "weather",
    description = "Get the weather for a city.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
) { input ->
    WeatherOutput(summary = "Mild in ${input.city}.")
}
```

The Kotlin-first tool-set builder can infer serializers:

```kotlin
val tools = toolSet<Unit> {
    tool<WeatherInput, WeatherOutput>(
        name = "weather",
        description = "Get the weather for a city.",
    ) { input ->
        WeatherOutput(summary = "Mild in ${input.city}.")
    }
}
```

## Build An Agent

Use `ToolLoopAgent` when the model should decide when to call tools.

```kotlin
val agent = ToolLoopAgent<Unit, String>(
    model = model,
    instructions = "Use tools when they help. Be brief.",
    tools = toolSetOf(weatherTool),
    stopWhen = stepCountIs(6),
)

val answer = agent.generate(prompt = "What is the weather in Paris?")
println(answer.text)
```

Agents can also stream:

```kotlin
agent.stream(prompt = "Check Paris weather.").collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.text)
        is StreamEvent.ToolCall -> println("Calling ${event.toolName}")
        is StreamEvent.ToolResult -> println("Finished ${event.toolName}")
        else -> Unit
    }
}
```

## Next Steps

- Read [Foundations](foundations.md) for the mental model and reading paths.
- Read [Core](core.md) for text, settings, results, and errors.
- Read [Prompts And Messages](prompts-and-messages.md) before persisting
  conversation state.
- Read [Prompt Engineering](prompt-engineering.md) before designing prompt
  templates.
- Read [Settings And Provider Options](settings-and-provider-options.md) before
  tuning calls or provider-specific features.
- Read [Structured Output](structured-output.md) before asking for typed data.
- Read [Tools](tools.md) and [Agents](agents.md) before building multi-step
  tool loops.
- Read [Workflow Patterns](workflow-patterns.md) when the control flow should
  stay deterministic.
- Read [Memory](memory.md) before storing or recalling conversation state.
- Read [Providers And Models](providers.md) before wiring credentials.
- Read [Provider Management](provider-management.md) before adding routing,
  Gateway metadata calls, provider registries, or custom providers.
- Read [Streaming](streaming.md), [Advanced Streaming](advanced-streaming.md),
  [UI And Streams](ui-and-streams.md), [UI Stream Protocols](ui-stream-protocols.md),
  [Chatbots](chatbots.md), and [Completion And Object UI](completion-and-object-ui.md)
  before rendering chat or focused UI helpers.
- Read [Lifecycle And Events](lifecycle-and-events.md) before adding hooks or
  telemetry.
- Read [DevTools](devtools.md) and [Utilities](utilities.md) before adding
  diagnostics, deterministic ids, stream smoothing, or message pruning.
- Read [Error Handling](error-handling.md) before production rollout.
