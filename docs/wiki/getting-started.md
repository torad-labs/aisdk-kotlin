# Getting Started

This page gets a Kotlin Multiplatform app from dependency setup to a first
agent call. Use shared Kotlin for agent logic, then bind platform models,
transports, and UI from Android, iOS, JVM, or tests.

## Install

Until a non-snapshot release is published, use the local Maven publication:

```sh
./gradlew publishToMavenLocal
```

Then add the dependency:

```kotlin
dependencies {
    implementation("ai.torad:aisdk-kotlin:0.1.0-SNAPSHOT")
}
```

For app development against a checkout, prefer a composite build:

```kotlin
// settings.gradle.kts
includeBuild("../aisdk-kotlin")
```

## Targets

- `commonMain`: shared agent, tool, model, provider, streaming, and UI-message
  contracts.
- `jvmMain`: backend, desktop, and JVM tooling.
- `androidMain`: Android publication.
- `iosX64`, `iosArm64`, `iosSimulatorArm64`: iOS framework publication.

## Pick A Provider

There are three normal provider paths:

| Path | Use when | Entry point |
|---|---|---|
| Gateway | You want one provider facade that can route by model id. | `createGateway()` / `gateway` |
| OpenAI-compatible | You use OpenAI, local OpenAI-compatible servers, or provider APIs with the OpenAI shape. | `createOpenAICompatible()` |
| Dedicated facade | You need provider-specific request mapping, auth, metadata, or media model support. | `createAnthropic()`, `createGoogleGenerativeAI()`, `createXai()`, etc. |

For tests and examples, use mock models:

```kotlin
import ai.torad.aisdk.providers.MockLanguageModel

val model = MockLanguageModel.textOnly("Hello from AI SDK Kotlin.")
```

## First Agent

```kotlin
import ai.torad.aisdk.ToolLoopAgent
import ai.torad.aisdk.stepCountIs
import ai.torad.aisdk.tool
import ai.torad.aisdk.toolSetOf
import ai.torad.aisdk.providers.MockLanguageModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
data class EmptyInput(val unused: String = "")

val helloTool = tool<EmptyInput, String, Unit>(
    name = "hello",
    description = "Return a greeting.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
) {
    "Hello from a tool."
}

val agent = ToolLoopAgent<Unit, String>(
    model = MockLanguageModel.textOnly("Done."),
    instructions = "Use tools when they help.",
    tools = toolSetOf(helloTool),
    stopWhen = stepCountIs(4),
)
```

## Generate

```kotlin
val result = agent.generate(
    prompt = "Say hello.",
    options = Unit,
)

println(result.text)
```

## Stream

```kotlin
agent.stream(prompt = "Say hello.", options = Unit).collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> append(event.text)
        is StreamEvent.ToolCall -> showPendingTool(event.toolName)
        is StreamEvent.ToolResult -> showToolResult(event.toolName, event.outputJson)
        is StreamEvent.Finish -> markFinished()
        else -> Unit
    }
}
```

## Next Steps

- Read [Core](core.md) for one-shot generation, streaming, structured output,
  embeddings, and media models.
- Read [Agents](agents.md) before building multi-step tool loops.
- Read [Providers and Models](providers.md) before wiring real credentials.
- Read [UI and Streams](ui-and-streams.md) before rendering chat or tool UI.
