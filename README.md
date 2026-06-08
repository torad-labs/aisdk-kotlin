# AI SDK Kotlin

Kotlin Multiplatform port of Vercel AI SDK v6 patterns and public feature areas.

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
- Typed `tool()` definitions, `dynamicTool()`, schemas, and `ToolSet`.
- `generateText`, `streamText`, and cold `Flow<StreamEvent>` streaming.
- Structured output through `Output.obj`, `Output.array`, `Output.choice`, and `Output.json`.
- Deprecated v6 compatibility shims: `generateObject` and `streamObject`.
- Embeddings, reranking, image generation, speech generation, transcription, and video generation model contracts.
- Provider registry and `customProvider` routing.
- Gateway facade with `createGateway`, `gateway`, gateway metadata APIs, gateway errors, provider-executed gateway tool descriptors, and a Ktor-backed `KtorGatewayTransport`.
- OpenAI-compatible Ktor provider for chat, completions, embeddings, images, speech, and transcription through `createOpenAICompatible`.
- Provider-utils parity helpers: schemas, IDs, JSON event stream parsing, headers, base64 byte helpers, media and URL validation utilities.
- Text stream, UI message stream, and chat transport primitives for Kotlin hosts.
- Telemetry helpers, global/local telemetry integrations, and a KMP tracer/span abstraction.
- Compatibility helpers such as `DefaultGeneratedFile`, `pruneMessages`, experimental media aliases, and v6 public error types.
- Lifecycle hooks, middleware, stop conditions, call/step preparation, and cancellation.
- UI message aggregation types for Compose, SwiftUI, or server-rendered hosts.
- Mock models for deterministic tests across every model family.

Provider facades for the AI SDK v6 package ecosystem are folded into this root artifact for now. Future `aisdk-provider-*` artifacts can split publication boundaries without changing the common contracts.

## Documentation

- [Docs wiki](docs/wiki/README.md)
- [Local LLM context](llms.txt)
- [Interface contract](INTERFACE_CONTRACT.md)
- [Port notes](docs/AISDK_PORT.md)
- [Architecture decisions](docs/AISDK_PORT_DECISIONS.md)
- [Parity ledgers](docs/parity/README.md)
- [Kotlin SDK engineering standard](docs/KOTLIN_SDK_BEST_PRACTICES.md)

## Build

Use JDK 21 to build the project. JVM and Android bytecode target JVM 17.

```sh
./gradlew jvmTest
./gradlew check publishToMavenLocal
```

Android publication requires an Android SDK with compile SDK 36 installed.

## License

Apache-2.0. See [LICENSE](LICENSE).
