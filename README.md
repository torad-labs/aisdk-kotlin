# AI SDK Kotlin

Kotlin Multiplatform agent, tool, streaming, and UI-message primitives inspired by Vercel AI SDK v6 patterns.

This is a green-room Kotlin rewrite of the architectural contracts, not a TypeScript source translation and not an official Vercel package.

## Targets

- Common Kotlin API for shared agent code.
- Android library publication.
- iOS static framework publication for `iosX64`, `iosArm64`, and `iosSimulatorArm64`.
- JVM artifact for backend and desktop services.

## Install

Until the first release is published, use a source dependency or `publishToMavenLocal`:

```sh
./gradlew publishToMavenLocal
```

```kotlin
dependencies {
    implementation("ai.torad:aisdk-kotlin:0.1.0-SNAPSHOT")
}
```

## Quick Start

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
) { "Hello from a tool." }

val agent = ToolLoopAgent<Unit, String>(
    model = MockLanguageModel.textOnly("Welcome."),
    instructions = "Be brief.",
    tools = toolSetOf(helloTool),
    stopWhen = stepCountIs(3),
)
```

## What Is Included

- `Agent` and `ToolLoopAgent`.
- Typed `tool()` definitions and `ToolSet`.
- `generateText`, `streamText`, and cold `Flow<StreamEvent>` streaming.
- Structured output through `Output.obj`, `Output.array`, `Output.choice`, and `Output.json`.
- Lifecycle hooks, middleware, stop conditions, call/step preparation, and cancellation.
- UI message aggregation types for Compose, SwiftUI, or server-rendered hosts.
- `MockLanguageModel` for deterministic tests.

Provider packages for OpenAI, Anthropic, LiteRT, MLX, or other runtimes should live in separate `aisdk-provider-*` modules.

## Documentation

- [Interface contract](INTERFACE_CONTRACT.md)
- [Port notes](docs/AISDK_PORT.md)
- [Architecture decisions](docs/AISDK_PORT_DECISIONS.md)
- [Known gaps](docs/AISDK_PORT_GAPS.md)
- [Usage guide](docs/AISDK_USAGE.md)
- [Best practices](docs/AISDK_BEST_PRACTICES.md)
- [Functionality audit](docs/FUNCTIONALITY_AUDIT.md)
- [Publishing](docs/PUBLISHING.md)

## Build

Use JDK 21 to build the project. JVM and Android bytecode target JVM 17.

```sh
./gradlew jvmTest
./gradlew publishToMavenLocal
```

Android publication requires an Android SDK with compile SDK 36 installed.

## License

Apache-2.0. See [LICENSE](LICENSE).
