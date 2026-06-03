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
  OpenAI-compatible transport for `responses(modelId)`, while the standalone
  Open Responses package surface is listed separately below.
- Open Responses package surface: `createOpenResponses`,
  `OpenResponsesOptions`, request conversion, provider options,
  generate/stream response mapping, reasoning, tool calls, usage, finish
  reasons, response metadata, and model-visible tool-result content are
  present with fake HTTP/SSE tests. The upstream `VERSION` export is exposed
  as `OPEN_RESPONSES_VERSION` while packages are folded into the root module.
- MCP package surface: JSON-RPC message contracts, transport callback
  interface, `createMCPClient`, initialize handshake, capability-gated tools,
  resources, prompts, elicitation, dynamic tool conversion, OAuth public
  types, `auth`, `UnauthorizedError`, and the stdio transport API shape are
  present with fake-transport tests; concrete HTTP/SSE/stdio platform
  transports are tracked in the package parity ledger.
- Valibot package surface: `valibotSchema` is represented as a Kotlin-native
  `Schema` adapter with JSON Schema preservation and validator callback tests.
- Devtools package surface: `devToolsMiddleware` is represented as a
  Kotlin-native recorder-backed middleware with generate/stream aggregation,
  raw chunk capture, production guard, and wrapped-provider metadata
  preservation tests.
- LlamaIndex package surface: `toUIMessageStream` is represented as a
  Kotlin `Flow<LlamaIndexEngineResponse>` to `Flow<UIMessage>` adapter with
  leading-whitespace trimming, streaming/done text states, and stream
  callback lifecycle tests.
- Text-stream, UI-message-stream, and chat transport primitives.
- Telemetry helpers and host-injected telemetry integration registry.
- Provider-utils-style helpers: `generateId`, `createIdGenerator`, `jsonSchema`, `asSchema`, `zodSchema`, `valibotSchema`, and `dynamicTool`.
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

- `./gradlew allTests`: 490 test executions, 0 failures, 0 errors, 0 skips.
- `./gradlew publishToMavenLocal`: published JVM, Android, iOS x64, iOS arm64, and iOS simulator arm64 artifacts locally.

On Linux, iOS unit-test binaries compile but iOS simulator execution is skipped by Gradle. Publication verification still compiles the iOS artifacts.

## Platform Boundaries

No runtime package behavioral gaps are tracked in this audit. The remaining boundaries are host/platform bindings where a Kotlin Multiplatform library must hand control to the app, server, or operating system:

- MCP stdio process spawning is implemented on JVM and Android. iOS exposes
  the same API boundary and uses HTTP, SSE, or custom `MCPTransport`
  implementations because iOS app sandboxes cannot launch arbitrary child
  processes.
- Devtools DB persistence and viewer/server UI are tooling bindings. The
  common module exposes recorder-backed middleware and run/step/result
  structures; apps choose storage and viewer hosting.
- Web framework response adapters are host bindings. The core exposes stream
  response value objects and writer interfaces.
- Alternate gateway HTTP client implementations; the core ships Ktor and
  keeps `GatewayTransport` so OkHttp, CIO, iOS, and server adapters can
  plug in.
- External OpenTelemetry bridge packages; the core exposes telemetry
  settings, spans, tracer abstraction, and integration hooks.
- Provider-executed tools are surfaced as hosted tool descriptors. Execution
  happens inside the provider unless the host supplies a local executor.

## Optional Hardening Additions

- Add binary API validation once the public API is stabilized for a first non-snapshot release.
- Add Dokka-generated API docs once the publication artifact shape is final.
- Add Maven Central publishing after GitHub Packages publication is proven in CI.
