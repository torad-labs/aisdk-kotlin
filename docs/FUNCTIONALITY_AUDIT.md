# Functionality Audit

Status as of extraction: the core KMP library is usable for agent loops, typed tools, streaming events, UI-message aggregation, middleware, structured output, approval flows, and mock-provider testing.

## Verified Surface

- Agent interface and `ToolLoopAgent`.
- Single-value and streaming tools.
- Tool approval return-then-resume flow.
- Tool-call repair and typed `AgentError` wiring.
- Stop conditions, lifecycle hooks, `prepareCall`, and `prepareStep`.
- Middleware wrapping for generate and stream paths.
- Structured output helpers: `Output`, `fixJson`, partial JSON parsing, and JSON instruction injection.
- Stream taxonomy and UI-message aggregation.
- Provider metadata propagation.
- Rich usage accounting.
- Cancellation propagation to subagents.

## Test Coverage

The extracted test suite currently contains 37 test classes executed on both JVM and Android host targets.

Last local verification:

- `./gradlew allTests`: 306 test executions, 0 failures, 0 errors, 0 skips.
- `./gradlew publishToMavenLocal`: published JVM, Android, iOS x64, iOS arm64, and iOS simulator arm64 artifacts locally.

On Linux, iOS unit-test binaries compile but iOS simulator execution is skipped by Gradle. Publication verification still compiles the iOS artifacts.

## Known Missing Or Deferred Surface

These are intentionally not in the core package:

- Real providers: OpenAI, Anthropic, LiteRT, MLX, Gemini, local server adapters.
- Provider registries and multi-provider routing.
- HTTP transport helpers and web framework response adapters.
- React hooks and UI components.
- `generateObject` / `streamObject` convenience entry points.
- Embeddings, reranking, speech, image, video, transcription, and MCP tools.
- OpenTelemetry primitives.
- Provider-executed tools such as hosted web search or code interpreter.

## Recommended Next Additions

- Add provider packages as separate modules or repositories, for example `aisdk-provider-openai`, `aisdk-provider-anthropic`, and `aisdk-provider-litert`.
- Add binary API validation once the public API is stabilized for a first non-snapshot release.
- Add Dokka-generated API docs once the publication artifact shape is final.
- Add Maven Central publishing after GitHub Packages publication is proven in CI.
