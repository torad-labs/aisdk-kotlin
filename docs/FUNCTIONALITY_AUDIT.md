# Functionality Audit

Status as of 2026-06-03: the KMP library is a full-package port target for the stable Vercel AI SDK v6 `ai` package surface, adapted to Kotlin `Flow` and host-injected provider/runtime contracts.

The latest stable Vercel AI SDK v6 reference verified during this pass is `ai@6.0.195` (`vercel/ai` tag `ai@6.0.195`). No stable `ai@6.1.x` release was published at verification time; canary/beta releases had moved toward v7.

## Verified Surface

- Agent interface and `ToolLoopAgent`.
- Single-value tools, streaming tools, dynamic tools, and schema wrappers.
- Tool approval return-then-resume flow.
- Tool-call repair and typed `AgentError` wiring.
- Stop conditions, lifecycle hooks, `prepareCall`, `prepareStep`,
  constructor/per-call/per-step `activeTools`, and `callOptionsSchema`
  runtime validation.
- Middleware wrapping for generate and stream paths.
- Structured output helpers: `Output.obj`, `Output.array`, `Output.choice`, `Output.json`, `fixJson`, partial JSON parsing, JSON instruction injection, and incremental `extractJsonMiddleware` streaming with suffix-fence stripping.
- Deprecated v6 object helpers: `generateObject` and `streamObject` delegate to the `generateText` / `streamText` structured-output path.
- Rich top-level result metadata: provider metadata, warnings, request/response metadata, content, reasoning, files, sources, and raw finish reason.
- Stream taxonomy, structured tool-result stream output, and UI-message aggregation.
- Provider metadata propagation.
- Rich usage accounting.
- Cancellation propagation to subagents.
- Embeddings and embedding middleware.
- Image, speech, transcription, video, and reranking model families.
- Provider registry, `customProvider`, and provider wrapping.
- Gateway provider facade: `createGateway`, `gateway`, gateway model
  factories, metadata/credits/spend/generation-info calls, gateway tools,
  and gateway errors over an injected KMP transport.
- OpenAI package facade: `createOpenAI`, `createOpenAIProvider`, `openai`,
  OpenAI provider settings, model factory aliases, and hosted OpenAI tool
  descriptors are present; the facade currently reuses the
  OpenAI-compatible transport while Responses-specific transport is tracked
  in the package parity ledger.
- Text-stream, UI-message-stream, and chat transport primitives.
- Telemetry helpers and host-injected telemetry integration registry.
- Provider-utils-style helpers: `generateId`, `createIdGenerator`, `jsonSchema`, `asSchema`, `zodSchema`, and `dynamicTool`.
- Provider-utils and utility helpers: `parseJsonEventStream`, header
  normalization/user-agent helpers, base64 byte helpers, URL support and
  download validation, media-type helpers, cosine similarity, data URL
  parsing, deep JSON equality, retry policy, serial job execution, and
  abort-signal merging.
- Compatibility helpers: `DefaultGeneratedFile`, experimental media
  aliases, `pruneMessages`, uppercase UI validation aliases, and the v6
  public error taxonomy.

## Test Coverage

The extracted test suite is executed on both JVM and Android host targets.

Last local verification:

- `./gradlew allTests`: 450 test executions, 0 failures, 0 errors, 0 skips.
- `./gradlew publishToMavenLocal`: published JVM, Android, iOS x64, iOS arm64, and iOS simulator arm64 artifacts locally.

On Linux, iOS unit-test binaries compile but iOS simulator execution is skipped by Gradle. Publication verification still compiles the iOS artifacts.

## Known Missing Or Deferred Surface

These are intentionally represented as extension/provider packages or platform adapters:

- Provider-specific adapters beyond OpenAI-compatible HTTP: Anthropic, LiteRT, MLX, Gemini, local server adapters.
- React hooks and UI components; Kotlin hosts use `Chat`, `ChatTransport`, `Flow<UIMessage>`, Compose, SwiftUI, or server renderers.
- Web framework response adapters; the core exposes stream response value objects and writer interfaces.
- Alternate gateway HTTP client implementations; the core ships Ktor and
  keeps `GatewayTransport` so OkHttp, CIO, iOS, and server adapters can
  plug in.
- External OpenTelemetry bridge packages; the core exposes telemetry
  settings, spans, tracer abstraction, and integration hooks.
- Provider-executed tools beyond the gateway tool descriptors, such as
  provider-specific code interpreter integrations.

## Recommended Next Additions

- Add provider-specific packages as separate modules or repositories, for example `aisdk-provider-anthropic`, `aisdk-provider-litert`, and `aisdk-provider-mlx`.
- Add binary API validation once the public API is stabilized for a first non-snapshot release.
- Add Dokka-generated API docs once the publication artifact shape is final.
- Add Maven Central publishing after GitHub Packages publication is proven in CI.
