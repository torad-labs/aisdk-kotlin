# Functionality Audit

Status as of 2026-06-03: the KMP library is a full-package port target for the stable Vercel AI SDK v6 `ai` package surface, adapted to Kotlin `Flow` and host-injected provider/runtime contracts.

The latest stable Vercel AI SDK v6 reference verified during this pass is `ai@6.0.195` (`vercel/ai` tag `ai@6.0.195`). No stable `ai@6.1.x` release was published at verification time; canary/beta releases had moved toward v7.

## Verified Surface

- Agent interface and `ToolLoopAgent`.
- Single-value tools, streaming tools, dynamic tools, and schema wrappers.
- Tool approval return-then-resume flow.
- Tool-call repair and typed `AgentError` wiring.
- Stop conditions, lifecycle hooks, `prepareCall`, and `prepareStep`.
- Middleware wrapping for generate and stream paths.
- Structured output helpers: `Output.obj`, `Output.array`, `Output.choice`, `Output.json`, `fixJson`, partial JSON parsing, and JSON instruction injection.
- Deprecated v6 object helpers: `generateObject` and `streamObject` delegate to the `generateText` / `streamText` structured-output path.
- Rich top-level result metadata: provider metadata, warnings, request/response metadata, content, reasoning, files, sources, and raw finish reason.
- Stream taxonomy and UI-message aggregation.
- Provider metadata propagation.
- Rich usage accounting.
- Cancellation propagation to subagents.
- Embeddings and embedding middleware.
- Image, speech, transcription, video, and reranking model families.
- Provider registry, `customProvider`, and provider wrapping.
- Text-stream, UI-message-stream, and chat transport primitives.
- Telemetry helpers and host-injected telemetry integration registry.
- Provider-utils-style helpers: `generateId`, `createIdGenerator`, `jsonSchema`, `asSchema`, `zodSchema`, and `dynamicTool`.
- Utility helpers: cosine similarity, data URL parsing, media-type detection, deep JSON equality, retry policy, serial job execution, and abort-signal merging.

## Test Coverage

The extracted test suite is executed on both JVM and Android host targets.

Last local verification:

- `./gradlew allTests`: 370 test executions, 0 failures, 0 errors, 0 skips.
- `./gradlew publishToMavenLocal`: published JVM, Android, iOS x64, iOS arm64, and iOS simulator arm64 artifacts locally.

On Linux, iOS unit-test binaries compile but iOS simulator execution is skipped by Gradle. Publication verification still compiles the iOS artifacts.

## Known Missing Or Deferred Surface

These are intentionally represented as extension/provider packages or platform adapters:

- Real providers: OpenAI, Anthropic, LiteRT, MLX, Gemini, local server adapters.
- React hooks and UI components; Kotlin hosts use `Chat`, `ChatTransport`, `Flow<UIMessage>`, Compose, SwiftUI, or server renderers.
- Concrete HTTP clients/web framework response adapters; the core exposes stream response value objects and writer interfaces.
- Concrete OpenTelemetry bridge; the core exposes telemetry settings, spans, and integration hooks.
- Provider-executed tools such as hosted web search or code interpreter.

## Recommended Next Additions

- Add provider packages as separate modules or repositories, for example `aisdk-provider-openai`, `aisdk-provider-anthropic`, and `aisdk-provider-litert`.
- Add binary API validation once the public API is stabilized for a first non-snapshot release.
- Add Dokka-generated API docs once the publication artifact shape is final.
- Add Maven Central publishing after GitHub Packages publication is proven in CI.
