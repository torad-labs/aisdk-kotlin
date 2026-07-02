# AI SDK Kotlin

Kotlin Multiplatform port of Vercel AI SDK v6 patterns and public feature areas.

This is a green-room Kotlin rewrite of the architectural contracts, not a TypeScript source translation and not an official Vercel package.

## Targets

- Common Kotlin API for shared agent code.
- Android library publication.
- iOS Kotlin Multiplatform klibs for `iosX64`, `iosArm64`, and `iosSimulatorArm64` published through Maven; the XCFramework is CI-built but not distributed.
- JVM artifact for backend and desktop services.
- Linux/Native `linuxX64` artifact for server-side Kotlin/Native and CLI consumers.

## Install

Use the published beta from Maven Central:

```kotlin
dependencies {
    implementation("ai.torad:torad-aisdk:0.3.0-beta01")
}
```

For local development against a checkout, use a source dependency or `publishToMavenLocal`:

```sh
./gradlew publishToMavenLocal
```

## Quick Start

<!-- beta-readiness:readme-sample:start -->
```kotlin
import ai.torad.aisdk.LanguageModel
import ai.torad.aisdk.StepCountIs
import ai.torad.aisdk.Tool
import ai.torad.aisdk.ToolLoopAgent
import ai.torad.aisdk.ToolSet
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
data class EmptyInput(val unused: String = "")

val helloTool = Tool<EmptyInput, String, Unit>(
    name = "hello",
    description = "Return a greeting.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
) { _ -> "Hello from a tool." }

class HelloAgent(model: LanguageModel, tools: ToolSet<Unit>) :
    ToolLoopAgent<Unit, String>(
        model = model,
        instructions = "Be brief.",
        tools = tools,
        stopWhen = StepCountIs(3),
    )

suspend fun main() {
    val agent = HelloAgent(
        model = MockLanguageModelTextOnly("Welcome."),
        tools = ToolSet(helloTool),
    )

    val result = agent.generate(prompt = "Say hi").first()
    println(result.text)
}
```
<!-- beta-readiness:readme-sample:end -->

Send application prompts through `Agent.generate` / `Agent.stream`, or use
`TextGenerator(model).generate(...)`, `TextGenerator(model).stream(...)`, and
`TextGenerator(model).streamResult(...)` for direct text calls. Direct
`LanguageModel.generate`, `LanguageModel.stream`, and `LanguageModel.streamResult`
calls are still supported for provider authors and low-level integrations, but
they require `@OptIn(LowLevelLanguageModelApi::class)`.

## What Is Included

- `Agent` and `ToolLoopAgent`.
- Typed `Tool()` definitions, `DynamicTool()`, schemas, and `ToolSet`.
- `TextGenerator(model).generate(...)`, `TextGenerator(model).stream(...)`, and cold `Flow<StreamEvent>` streaming.
- Structured output through `Output.obj`, `Output.array`, `Output.choice`, and `Output.json`.
- Structured text helpers through `TextGenerator.streamResult(...)` and `StreamTextResult`.
- Embeddings, reranking, image generation, speech generation, transcription, and video generation model contracts.
- Provider registry and `Provider(...)` routing.
- Gateway facade with `Gateway()`, `gateway`, gateway metadata APIs, gateway errors, provider-executed gateway tool descriptors, and a Ktor-backed `KtorGatewayTransport`.
- OpenAI-compatible Ktor provider for chat, completions, embeddings, images, speech, and transcription through `OpenAICompatible(...)`.
- LiteRT-LM adapter for on-device Android/JVM inference via `LiteRTLanguageModel`, preserving SDK-owned generate, stream, reasoning-channel, and tool-loop routing.
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
