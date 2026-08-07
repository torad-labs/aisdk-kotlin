# Changelog

All notable changes to this project will be documented here.

This project follows Semantic Versioning once the first stable release is cut.

## Unreleased

### Full-SDK review campaign — 53 verified defects fixed

A section-by-section adversarial review of the whole SDK produced 54 findings that survived
independent verification; 53 are fixed here and one was rejected on evidence (below). Every fix
landed with a regression test proven to FAIL before it and pass after. The campaign ledger with
per-item repro, verifier evidence, and rulings is `dev/campaigns/sdk-review.toml`.

**Breaking public API changes**

- **`StreamObjectResult(...)` no longer flattens call settings into 17 positional parameters.**
  The factory is now `StreamObjectResult(model, output, input: GenerationInput, config: CallConfig
  = CallConfig(), repairText)`, matching `TextGenerator` and `StructuredObjectGenerator`. The old
  shape froze a 15-default parameter list plus its synthesized `$default` bridge into the ABI, so
  adding any setting was a `NoSuchMethodError` for already-compiled callers — and it could not
  express `headers`, `timeout`, or `maxRetries` at all, which now travel with everything else.
  Migrate `StreamObjectResult(model, out, prompt = "x")` to
  `StreamObjectResult(model, out, GenerationInput.Prompt("x"))`.
- **`ToolSchema` is constructed through its DSL factory.** The 7-parameter positional constructor
  is now `internal`; use `ToolSchema { name("searchDocs"); description("…") }`. Every `Tool`
  subclass authors a `ToolSchema`, so the frozen parameter list was the same ABI trap.
- **Gateway error types carry `responseHeaders`.** Appended last and defaulted on
  `GatewayError` and its variants, so existing read sites are unaffected. Without it the retry
  layer discarded the server's `Retry-After` on a gateway 429 and re-sent on its own schedule.

**Correctness fixes worth calling out**

- **A stopped stream deadlocked its collectors.** `AbortError` is a `CancellationException`, and
  providers throw it in-band per chunk; the stream-replay layer rethrew it without recording a
  terminal, so every `textStream`/`fullStream` collector — and `warnings`/`response` — waited
  forever on a terminal the dead producer would never publish. The documented stop-button pattern
  hung the UI instead of ending the stream. Teardown cancellation is now distinguished from an
  in-band one and only the former stays a cancellation.
- **Gateway V3 wire decoding was wrong in four places**, verified field-by-field against the
  pinned `.reference` upstream: tool results are carried under `result` on the response side (the
  decoder read the prompt-side `output` and threw, killing the stream); `tool-approval-request`
  carries neither `toolName` nor `input`, which were required; tool-call `input` is a
  stringified-JSON string, not a structured element; and usage arrives as nested
  `inputTokens`/`outputTokens` breakdowns, so every token count decoded as 0.
- **SigV4 signed every Bedrock model id wrong.** The canonical URI was single-encoded where AWS
  requires double-encoding outside S3, so any model id containing a colon failed to authenticate.
- **MCP SSE never streamed.** All three streaming reads used `client.request`, which Ktor 3.5
  fully pre-buffers, so a live SSE session delivered nothing until the response completed.
- **The internal request ceiling escaped as a bare `TimeoutCancellationException`**, which reads
  as cooperative cancellation: it was silently swallowed and never retried. It now surfaces as
  `CallTimeoutError`.
- **Multi-turn tool conversations were broken on Bedrock Mantle** (tool calls and results dropped
  from both the emitted stream and the request mapping) and on Hugging Face (assistant tool calls
  and tool results dropped from history).
- **Chat turn state**: a second assistant reply reused the first turn's message id and overwrote
  it, and `convertToModelMessages` crashed on messages produced by `Chat.addToolOutput`.
- **Tool approvals**: approving with the documented effective `approvalId` threw
  `InvalidApprovalResponse`; resuming one of several pending approvals corrupted the turn and
  stranded the rest; only the last `Tool` message's responses were read.
- **Two provider defaults could never succeed**: Prodia's default async video path parsed a 201
  job response as multipart and never polled, and Hume's built-in fallback voice id was paired
  with a provider value that rejects it.
- **`Output.Arr`'s default schema name was rejected by OpenAI.** It defaulted to `Recipe[]`, and
  `json_schema.name` must match `^[a-zA-Z0-9_-]+$`, so the default array-output path 400'd on
  every call. The default suffix is now `_array`.

**Not changed, deliberately**

- An OpenResponses file id whose characters happen to form valid base64 is still sent inline
  rather than as a file id. The two cases are mechanically indistinguishable from the string
  alone and the current classification is pinned by test; pass `openai.file_id` provider metadata
  when a value must be treated as an id. Documented on `OpenResponsesProviderSettings.fileIdPrefixes`.

- **Java interop: `@JvmSynthetic` removed from 52 public abstract members (ABI change).**
  A codemod had applied it to body-less declarations across 25 files. On an abstract
  member that emits `ACC_ABSTRACT | ACC_SYNTHETIC`, and `javac` skips synthetic members
  when checking that a class implements everything abstract — so a Java class
  implementing `StopCondition`, `ChatTransport`, `Tool`, `LanguageModel`, `Agent`,
  `StreamingTool`, `CompletionTransport` and friends **compiled clean and then threw
  `java.lang.AbstractMethodError`** on the first call. Verified against the built jar
  (`javap -v` showed `flags: (0x1401) ACC_PUBLIC, ACC_ABSTRACT, ACC_SYNTHETIC`) and
  reproduced end-to-end. The JVM ABI dump loses `synthetic` on 51 members; **no
  signature changed and nothing was added or removed**, so this is compatible for
  callers and *restores* the ability to implement these interfaces from Java.
  `no-public-suspend-without-jvmsynthetic` and `no-flow-return-without-wrapper` now
  exempt body-less declarations (keyed on absence of a function body, not the
  `abstract` keyword — most are interface members that never write it), and
  `AbstractSpiJavaInteropTest` is a Java-source wall that fails to compile if the
  annotation is ever reapplied there.
- **Tool results reached Google, Google Interactions and LiteRT wrapped in the SDK's
  internal envelope.** `ContentPart.ToolResult.modelVisible` carries what
  `ToolResultOutput.toJsonElement()` emits; once its `Json` arm began tagging values as
  `{"type":"json","value":…}`, those three providers wrote the wrapper straight to the
  wire, so a tool returning `{"temperature":72}` reached Gemini as
  `{"type":"json","value":{"temperature":72}}`. Five other providers already decoded it.
  Added `ToolResultOutputs.toolResultPayloadJson` (internal) as the shared inverse and
  applied it at the three sites; error/denial variants flatten to their message rather
  than landing in the model's context as a tagged object.
- **Anthropic server-tool results carried a malformed `toolName`.**
  `"web_search_tool_result".removeSuffix("_result")` produced `"web_search_tool"`; the
  real tool is `"web_search"`, so one response emitted a `ToolCall` and a `ToolResult`
  with different names for the same `toolCallId`. Both the buffered and streaming paths
  now take the name from the paired originating call. Suffix arithmetic could not be
  repaired in place — `"_tool_result"` yields `"mcp"` for `mcp_tool_result` and
  `"tool_search"` for `tool_search_tool_result`, whose real names are
  `tool_search_tool_regex` / `tool_search_tool_bm25`.
- **Provider wire fixes (Tier 1 — live-API breakages against current docs):**
  - **Anthropic:** server-tool result blocks no longer require `name` (API omits it);
    5-series flagships default `max_tokens` to 128k; `redacted_thinking` is resent
    byte-exact with `data` instead of as plain `thinking`.
  - **Bedrock:** `topK` moved from invalid `inferenceConfig` into
    `additionalModelRequestFields.top_k`; Mantle `responses()` uses the Responses
    contract (`input` / `output`, not Chat Completions) with Responses tool shape +
    `input_tokens`/`output_tokens` usage; redacted reasoning reads wire key
    `redactedContent`; stream handles `serviceUnavailableException`;
    `additionalModelResponseFieldPaths` is `/stop_sequence` (not `/delta/...`);
    `outputConfig.textFormat.structure` wraps `jsonSchema`; legacy `json`
    pseudo-tool dual-path removed.
  - **Google Interactions:** `function_result` is a top-level input step; initial
    text on `step.start` is emitted; retrieval defaults to documented
    `rag_store` types + config passthrough; `budget_exceeded` is terminal.
  - **Mistral:** specific `tool_choice` objects are preserved; `prefix: true` is
    opt-in via `providerOptions.mistral.prefix` and only applied when the
    conversation ends on an assistant message.
  - **xAI:** `stop` forwarded; `parallel_function_calling` maps to
    `parallel_tool_calls`; Responses code tool is `code_interpreter`; image
    options drop undocumented `sync_mode`/`quality`.
  - **Speech/STT:** AssemblyAI `speech_models` (plural array); Deepgram TTS
    forwards `speed`; Gladia `language_config` is `{languages[], code_switching}`
    only and `audio_to_llm_config` uses `prompts` + auto-enables `audio_to_llm`;
    Rev.ai errors parse RFC 7807.
  - **Media:** BFL FLUX.2 uses `disable_pup`, 8-image cap, aspect_ratio gated off
    for FLUX.2 / FLUX.1 [pro], validation `detail[].msg` extracted; Fal speech
    sends `prompt`+`text` and accepts `audio_url`, video sends `start_image_url`,
    queue host honors `baseURL`; Luma refs use `*_ref` keys; Kling Motion Control
    requires `image_url`; Alibaba wan2.7 media types `first_frame` /
    `reference_image` + `parameters.ratio`; ByteDance errors read top-level
    `error_code`/`message`; Together `guidance` → `guidance_scale`; xAI drops
    image `output_format`, maps `1080p`, treats video `expired` as terminal;
    Replicate image output accepts `{url}` objects.
  - **LMNT:** wire key `format`, default model `blizzard`, required
    `lmnt-version` header, documented format allowlist incl. `pcm_f32le`; drops
    unsupported `speed`/`conversational`/`length` body fields.
  - **Security:** Fireworks image download sends no caller-configured credentials
    off-origin. The download URL comes from the provider's `result.sample`, so the
    cross-origin path now forwards only `User-Agent` (an allowlist) rather than
    stripping a fixed `Authorization`/`x-api-key` pair — a denylist could not cover
    a `Cookie` or bespoke auth header set through `settings.headers`. The decision is
    re-made on every physical send, so it survives redirects: a same-origin URL that
    redirects to a third-party CDN is the ordinary shape of a signed download, and
    Ktor's own redirect handling drops only `Authorization`. Same-origin hops keep the
    full provider + per-call header baseline, so gateway/proxy deployments are
    unaffected. Ktor keeps ownership of redirect mechanics; the download additionally
    refuses an HTTPS-to-HTTP hop even if the caller's client allows downgrades.

- **Provider wire fixes (Tier 2 — silent loss / wrong defaults):**
  - **LiteRT:** sampler carries `maxOutputTokens` / `presencePenalty` /
    `frequencyPenalty`; request carries native `responseFormat` (prompt injection
    kept as fallback); `thinkingTokenBudget` maps into extra context; stream
    terminal usage/finish no longer wiped by trailing empty messages.
  - **Voyage:** embeddings batch cap 128 → 1_000 (documented max); forwards
    `encoding_format`; normalizes documented JSON-array and base64 float32/int8/uint8
    storage into the existing `List<List<Float>>` result while keeping binary/ubinary
    bit-packed. `providerMetadata.voyage.embeddingRepresentation` now records dtype,
    packing, logical dimension, and each row's stored-element count without adding
    strict shape validation; custom dtypes remain compatible for numeric arrays and
    fail explicitly for uninterpretable base64 storage.
  - **Groq / Cerebras:** chat max-output key is `max_completion_tokens`; Groq
    browser_search allowlist includes `openai/gpt-oss-safeguard-20b`.
  - **Groq transcription:** legacy `responseFormat` access remains source/binary
    compatible but is warning-deprecated after 0.3.0-beta01 because the transport
    only requests and decodes JSON. The pre-existing raw `response_format`
    exclusion is preserved; multipart passthrough now also excludes serialized
    camel `responseFormat` and omits null-valued typed defaults instead of
    emitting literal `null` form fields.
  - **Perplexity:** `stop` is forwarded (documented on `/v1/sonar`).
  - **ElevenLabs STT:** no longer force `diarize=true`; parse `speaker_id` into
    `TranscriptSegment.speakerId`.
  - **Anthropic:** sampling-rejection set covers full 5-series flagships.
  - **Azure:** deployment-based URLs no longer pair with `api-version=v1`
    (auto-use dated `2024-10-21` classic version).
  - **Open Responses:** `web_search_preview` no longer sends unsupported
    `filters`/`external_web_access`; code-interpreter containers keep
    `memory_limit`/`network_policy`; MCP `tool_choice` includes `server_label`;
    standalone SSE `error` events surface as stream errors.
  - **Bedrock:** Converse `outputConfig.textFormat` for structured JSON (not
    thinking-gated only); image moderation reads the documented `error` string.
  - **Google Interactions:** `generate()` polls non-terminal model runs;
    `requires_action` is terminal for the poll loop; default poll cap 60 min;
    `mcp_server` tools send name/url from args.
  - **Google Gemini:** Gemma system instructions fold into the first user turn
    (with a warning); finish-reason map covers `UNEXPECTED_TOOL_CALL` /
    `TOO_MANY_TOOL_CALLS` / `MISSING_THOUGHT_SIGNATURE`.
  - **Cohere:** `tool-plan-delta` → reasoning; citation events with URLs →
    `SourcePart`.
  - **Hume:** default voice provider `CUSTOM_VOICE` (override via options).
  - **Prodia video:** defaults to `/job/async` for long jobs.
  - **Replicate:** `Prefer: wait=` coerces to integer seconds.
  - **Quiver:** prefers documented `credits` over deprecated `usage`.
  - **Anthropic multi-turn/stream:** provider-executed tool calls/results store
    full wire blocks and echo them on resend (incl. `encrypted_content`); stream
    emits server-tool result blocks (`web_search_tool_result`,
    `bash_code_execution_tool_result`, …); adaptive thinking no longer sends
    `budget_tokens`; MCP connector beta → `mcp-client-2025-11-20` and emits
    required `mcp_toolset` entries in `tools` (no deprecated
    `tool_configuration` on servers).
  - **Google Interactions multi-turn/media:** provider-executed steps echo
    exact wire type on resend (buffered + stream paths store full wire step);
    model_output accepts audio/video/document.
  - **Quiver:** surfaces response/image `attributes` in providerMetadata; request
    supports `attributes` + `viewBox` (typed options + providerOptions).
  - **LMNT:** `pcm_f32le` MIME falls back to octet-stream; default voice is `leah`
    (docs canonical system voice).
  - **Google Interactions:** stop inventing `signature` on client `function_call` /
    `function_result` (thought-signatures live on `thought` only).
  - **Cohere:** documents Specific→REQUIRED+filter approximation via warning.
  - **Vercel:** clarifies v0 vs AI Gateway; adds `VercelAIGateway` +
    `AI_GATEWAY_OPENAI_COMPAT_BASE_URL` (full gateway remains `Gateway`).
  - **AnthropicAws:** SigV4 signing region derived from endpoint host (host/region
    mismatch was a signature rejection).
  - **Azure media:** image/audio paths always use classic deployment URLs (v1
    path is chat/responses only).
  - **Vertex:** comment corrected — v1beta1 is the documented surface (not "v1
    unavailable").

- **CI/CD hardening:**
  - CI cancels superseded runs on the same ref (`concurrency` on `ci.yml`).
  - Release preflight refuses a `VERSION_NAME` already present on Maven
    Central (immutable coordinates) and documents the main→tag checklist.
  - Release publish emits an SPDX SBOM of the staged Maven layout and attests
    it alongside the Central bundle; creates a GitHub Release attaching
    `bundle.zip` + SBOM so consumer-canary attestation can fire.
  - Weekly OpenSSF Scorecard workflow publishes SARIF to code scanning.
  - `workflow-lint` runs checksum-pinned `actionlint` + `zizmor` on workflow
    changes.
  - `dependency-submission` publishes the Gradle graph so dependency-review
    sees shipping deps; Dependabot PR helpers (verification-metadata regen +
    patch/minor auto-merge) live in one Actions workflow
    (`.github/workflows/dependabot.yml`).
  - CI builds `samples/jvm-chat-cli`, runs Windows `jvmTest`, uploads reports
    on failure, generates Dokka on every verify, and opens issues on scheduled
    job failures.
  - Branch protection: stale reviews dismiss, conversation resolution
    required, admins enforced, `dependency-review` required. Repo security:
    secret scanning, push protection, Dependabot security updates enabled.
  - Snapshots workflow documents the post-release `VERSION_NAME` bump before
    re-arming push-to-main.

- **PR review follow-ups (release hardening):**
  - **Security:** `issue-triage.yml` no longer interpolates `workflow_dispatch` input
    into Bash source; the issue number is passed via `env` and validated as decimal.
  - **Wire:** `ToolResultOutput.Json.toJsonElement()` now emits a `type=json` envelope
    (matching the existing decoder), so a success payload that collides with an
    error/denial shape cannot be re-decoded as `Error`.
  - **Provider registry:** `ModelRef` resolution on `ProviderRegistry` dispatches from
    the typed `providerId`/`modelId` components and no longer re-stringifies through
    colon-hardcoded `qualifiedName` (custom separators work on the typed path).
  - **MCP stdio:** `start()` failure after `begin()` (including cancellation during a
    stale-process close) finishes teardown under `NonCancellable`, clears the process
    field, and resets the lifecycle before rethrowing.
  - **IDs:** Google Interactions / Google Language Model / Bedrock Mantle ID-less
    fallbacks use the injected `generateId` callback instead of the global
    `GenerateId()`.
  - **Gateway:** blank `VERCEL_OIDC_TOKEN` is treated as absent (same as blank API key).
  - **Mock:** `ScriptedResponse` is `@Poko` + internal constructor + DSL factory
    (no public `data class` / `copy`); data-class budget ratcheted 40 → 39.
  - **Triage:** `classify.sh` partitions labels into at most one type + two areas.
  - **Rules:** `no-deprecated-without-version` binds only the message argument
    (ReplaceWith strings no longer false-positive); `no-public-without-since` requires
    a real KDoc `/**` opener.
  - **Open Responses:** unknown provider-tool types still passthrough for forward-compat
    but now emit a local `CallWarning("unsupported", …)` so a typo'd `providerToolId`
    does not look like a remote 4xx outage.
  - **KDoc:** `ProviderId`/`ModelId`/`ToolCallId`/`ToolName`/`ApprovalId` companion
    `invoke` factories tagged `@since 0.3.0-beta01` alongside their `of()` siblings.
  - **Bugfix:** `ModelRef(providerId, modelId)` factory no longer recurses into itself.

- **Breaking (source):** every public `companion object` factory moved to a top-level
  declaration, completing the `no-companion-objects` migration. The capabilities are
  unchanged — only the call syntax moves — and the committed ABI dumps now record it.
  39 members across 28 companions, in two shapes:
  - **Invoke-style factories** become a PascalCase function of the same name:
    `DataUrl.parse(s)` → `DataUrl(s)`, `ProviderOptions.ofPairs(...)` →
    `ProviderOptions(...)`, `ProviderMetadata.ofPairs(...)` → `ProviderMetadata(...)`,
    `ProviderRegistry.createProviderRegistry(vararg Pair, ...)` → `ProviderRegistry(...)`,
    `Usage.of(...)` → `Usage(...)`, and each `LiteRTContent.<Kind>.invoke(...)` →
    `LiteRTContent<Kind>(...)`.
    - **One member has no same-signature replacement.** The companion also carried a
      `Map`-taking overload, `createProviderRegistry(Map<String, Provider>, ...)`, present
      in the `v0.2.0` and `v0.3.0-alpha01` ABI dumps. Its capability now lives on
      `ProviderRegistry`'s public primary constructor, so Kotlin callers write
      `ProviderRegistry(map, ...)` — but a JVM caller compiled against the companion
      static gets a `NoSuchMethodError` and must move to the constructor. A top-level
      `Map` factory of the same name is not available as a migration shim: it collides
      with that constructor (`Conflicting overloads`), which is why the constructor is
      the replacement rather than a matching function. So the migration is 39 members
      relocated to a same-signature replacement plus this one relocated to a constructor.
  - **Named factories** become `<Type><Member>`: `Output.obj/array/choice/json` →
    `OutputObj` / `OutputArray` / `OutputChoice` / `OutputJson`;
    `RetryDelayGenerator.Companion.fullJitter` / `.deterministic` →
    `RetryDelayGeneratorFullJitter` / `RetryDelayGeneratorDeterministic`;
    `ModelRef.parse` → `ParseModelRef`; `TypeValidationError.wrap` →
    `WrapTypeValidationError`; `Telemetry.registerTelemetry` / `.clearGlobalTelemetry`
    → `RegisterTelemetry` / `ClearGlobalTelemetry`;
    `GenerationInput.from` → the top-level `GenerationInput(prompt, messages)` factory;
    `TextGenerationRequest.Input.messages` / `.messagesWithPrompt` / `.prompt` →
    `TextGenerationRequestInputMessages` / `TextGenerationRequestInputMessagesWithPrompt`
    / `TextGenerationRequestInputPrompt`; `DefaultGeneratedFile.fromBase64` /
    `.fromBytes` → the overloaded `DefaultGeneratedFile(data, mediaType)` factory
    (`data: String` base64 / `data: ByteArray`);
    `AnthropicMessagesLanguageModel.forwardAnthropicContainerIdFromLastStep` →
    `ForwardAnthropicContainerIdFromLastStep`.
  - `ToolExecutionPolicy`'s `DEFAULT_MAX_PARALLEL_TOOL_CALLS`,
    `DEFAULT_MAX_TOOL_CALLS_PER_STEP` and `DEFAULT_PROGRESS_BUFFER_CAPACITY` are now
    top-level `public const val` in the same package.

- Pre-tag ABI-evolvability hardening (see `docs/reports/pre-beta-abi-audit.md`):
  - `MCPClient`, `AnthropicAwsProvider`, `BlackForestLabsProvider`,
    `ByteDanceProvider`, `OpenAICompatibleProvider`, `OpenResponsesProvider`,
    and `GatewayProvider` are now `sealed class` (not `interface`) — each had
    exactly one in-module implementation and no plausible external
    implementer, so the SDK keeps the freedom to add members later without
    breaking a third party. (`sealed class`, not `sealed interface`, per this
    repo's `no-sealed-interface` tenet.)
  - `ContentPart.metadata` and `StreamEvent.metadata`, the last 2 public
    top-level extension declarations, are now members of the respective
    sealed base class — completes the "no public extensions" migration.
  - `Schema`, `MiddlewareCallContext`, `EmbeddingMiddlewareCallContext`, and
    `ImageMiddlewareCallContext` are no longer `@Poko`: all 4 hold a closure
    or provider-instance field, so value equality on them was meaningless;
    they are now plain regular classes with identity equality.
  - Removed the duplicate top-level `AbortSignalFromJob(job: Job)` — use
    `AbortSignals.from(job)`, which now holds the implementation directly.
  - `GatewayModelType` gained `Speech` and `Transcription` variants, matching
    the SDK's existing `SpeechModel`/`TranscriptionModel` interfaces.
  - `AgentEvent.Finished.output` and the public DevTools surface
    (`DevToolsStep`, `DevToolsStepResult`, `DevToolsRecorder`,
    `InMemoryDevToolsRecorder`, `DevToolsMiddleware`) are now
    `@ExperimentalAiSdkApi`.
  - `PruneReasoning` is now a `sealed class` with `data object` leaves
    (`All`/`BeforeLastMessage`/`None`), matching `PruneToolCalls`'s shape.
  - `MessageRole`, `UIMessageRole`, `ToolCallState`, `ChatStatus`,
    `RetryErrorReason`, and `FinishReason` now document a forward-compat
    contract: consumers must not rely on exhaustive `when` over these enums.
- `AbortSignals` is now plain factory functions instead of member-extensions:
  `AbortSignals.from(job: Job)` / `AbortSignals.from(scope: CoroutineScope)`
  replace `Job.asAbortSignal()` / `CoroutineScope.asAbortSignal()`, so a call
  site no longer needs `with(AbortSignals) { ... }` or a member-extension
  import.
- Seven more public member-extension functions parked inside public `object`s
  moved onto the type they extend, so call sites no longer need a
  member-extension import or `with(Object) { ... }`:
  - `ProviderModels.provider/languageModel/embeddingModel/imageModel/speechModel/transcriptionModel/rerankingModel/videoModel`
    (typed `ProviderId`/`ModelId`/`ModelRef` overloads) are now default methods
    on `Provider` itself, alongside the existing `String`-typed overloads.
  - `GeneratedFiles.fileData/bytes/bytesOrNull` are now members of
    `GeneratedFile`.
  - `AgentSessions.session` is now a default method on `Agent`.
  - `ChatSessionFactory.asSession` is now a member of `Chat`.
  - `ToolResultOutputs.isToolResultError/toJsonElement` are now members of
    `ToolResultOutput`; `ToolResultOutputs` itself is now `internal` (it kept
    only internal wire-codec helpers).
  - `UsageArithmetic.plus` is now the member operator `Usage.plus`, so
    `a + b` works directly without importing or scoping into `UsageArithmetic`.
  - `UIMessageMetadata.metadataAs` (both overloads) are now members of
    `UIMessage`.
  - A new ast-grep rule, `no-public-member-extension-in-object`, blocks the
    pattern from re-entering the public API.
- **Upgrader callout:** `RetryPolicy.maxRetries` defaults to `2`. If you already
  retry transient failures in your own transport/middleware, composing both
  means the same failing call is attempted `(1 + maxRetries) *
  (1 + middlewareRetries)` times. Pass `maxRetries(0)` on the `RetryPolicy`
  builder if your middleware already owns retry behavior.
- Cancellation hardening: broad `catch(Throwable)` paths no longer swallow
  coroutine cancellation in telemetry dispatch, memoized stream replay, retry
  classification, completion fallback, agent submit, smooth-stream flushing,
  structured-object phases, tool-loop model/prepare/tool paths, MCP elicitation,
  tool-call repair fallback, provider stream parsing, or UI stream wrappers.
  `safeValidateUIMessages` now converts only `IllegalArgumentException` into a
  safe validation failure; unexpected non-validation failures propagate. Parallel
  tool execution also distinguishes cooperative user abort from structurally
  cancelled worker coroutines, so worker cancellation no longer completes a step
  as a normal abort turn.
- HD-era hardening summary: measured coverage, gate, and ratchet state now lives
  in `dev/measurements.toml` and is cited by key instead of copied into docs:
  `[meas: coverage_line_percent]`, `[meas: coverage_instruction_percent]`,
  `[meas: coverage_branch_percent]`, `[meas: kover_branch_floor_percent]`,
  `[meas: ast_grep_rule_count]`, `[meas: gate_fixture_harness_checks]`,
  `[meas: public_data_class_floor]`, and `[meas: ci_gate_wall_clock_s]`.

## 0.3.0-beta01

- Beta-readiness hardening: tool execution now uses an explicit bounded `ToolExecutionPolicy` (default `maxParallelToolCalls=8`, `maxToolCallsPerStep=128`) so a model cannot create unbounded child coroutines or in-step tool work. The loop now surfaces typed `AgentError.MaxToolCallsPerStepExceeded` and `AgentError.ToolExecutionTimedOut` failures.
- Retry hardening: `RetryPolicy` now defaults to retrying only typed retryable `APICallError` / `GatewayError`, uses injectable full-jitter backoff, honors `Retry-After` with an injected clock, supports per-attempt and total deadlines, and carries retry decision details through `RetryError.attempts`.
- Streaming lifecycle hardening: `StreamTextResult` / `StreamObjectResult`
  memoize only terminal stream runs. If every collector leaves before terminal
  completion, the upstream producer is cancelled, partial replay state is
  discarded, and a later collector starts a fresh run in the collector's
  coroutine context.
- Privacy hardening: telemetry integrations are metadata-only by default (`recordInputs=false`, `recordOutputs=false`) and receive a redacted event projection. `LoggingMiddleware` now logs tool metadata and byte counts by default; raw/redacted payload logging is explicit via `LoggingOptions` and the shared `Redactor` seam.
- Release gates: coverage thresholds, detekt baseline budget ratchet, dependency verification metadata, provider capability/API review checks, local-staging consumer smoke fixtures, SHA-pinned GitHub Actions, workflow timeouts, and a `tools/beta-readiness-check` gate were added.
- Public API hardening: JVM default-method compatibility is now pinned to `JvmDefaultMode.ENABLE`; experimental MCP/media aliases and functions, agent tool-call repair/approval-secret knobs, step/tool predicate `experimental_context`, and the remaining public experimental-prefixed surfaces now require `@ExperimentalAiSdkApi`; mutable byte payloads now defensively copy on input/output (`FileData.Bytes.toByteArray()`, `DefaultGeneratedFile.byteArray`); and `MutableTelemetrySpan` now accepts a read-only `Map` instead of a public `MutableMap`.
- Public TS-residue aliases `AlibabaUsage`, `AlibabaCacheControl`, and
  `DeepSeekErrorData` were removed before the beta ABI freeze; use `Usage`,
  `JsonObject`, and `JsonElement` directly.
- Visibility hardening: implementation utilities (`EventStreamParser`,
  `Base64Codec`, `TypedJsonOps`, `DirectCompletionTransport`, and
  `DirectStructuredObjectTransport`) are no longer public ABI. Advanced concrete
  MCP transports (`HttpMCPTransport`, `SseMCPTransport`) remain public but are
  gated with `@InternalAiSdkApi`. `DataUrl` remains public as a documented
  consumer-facing data URL value.
- Java interop hardening: JVM and Android bytecode now enable Kotlin's
  additive boxed value-class exposure (`-Xjvm-expose-boxed`). Java consumers can
  construct and call SDK ID value classes (`ModelId`, `ProviderId`,
  `ToolCallId`, `ToolName`, and `ApprovalId`) through boxed constructors,
  accessors, and `of(String)` factories, while the existing Kotlin/JVM mangled
  bridge signatures remain for binary compatibility. Headline factory APIs now
  expose Java-callable telescoping overloads via `@JvmOverloads` for tools,
  providers, generated files, text generation, and middleware wiring. SDK DSL
  builders are Java-constructable with public constructors, fluent setters, and
  public `build()` methods.
- Beta contract correction: the checked ABI now exposes `Tool` as a non-sealed
  `abstract class`, so external modules can subclass it exactly as the beta
  docs and migration notes describe. Open Responses streaming now emits a
  terminal `StreamEvent.Error` for `response.failed` events and prefers final
  `output_item.done` tool-call arguments over an empty pending placeholder.
- Gateway content-part decoding is forward-compatible on non-stream responses:
  unknown gateway content part types now surface as `ContentPart.Raw` instead
  of being silently dropped, matching the stream path's `StreamEvent.Raw`
  fallback.
- LiteRT host bridges can now set `LiteRTMessage.finishReason` and
  `LiteRTMessage.usage`; the adapter propagates them instead of fabricating
  `Stop` / zero usage, while preserving the historical defaults when omitted.
- Tool strictness is now opt-in (breaking ABI change): `ToolSchema.strict`,
  `ToolSchemaOptions.strict`, `Tool.strict`, and `LanguageModelTool.strict` are
  `Boolean?` values defaulting to `null`. OpenAI-compatible tool requests omit
  `strict` unless callers explicitly set `true` or `false`; structured-output
  `response_format` strict behavior is unchanged.
- Non-streaming text/object generation now retries transient model-call failures
  by default (breaking ABI change): `CallSettings`, `CallConfig`,
  `AgentSettings`, and `StepSettings` expose `maxRetries` (`2` by default, `0`
  disables), and `ToolLoopAgent` resolves its retry default from
  `AgentSettings`. Retries wrap each individual `LanguageModel.generate`
  round-trip, so a later model retry in a tool loop does not re-run
  already-executed tools.
- High-level call configuration now exposes per-call HTTP headers through
  `CallSettings { headers(...) }` and `CallConfig { headers(...) }`, forwarding
  them to `LanguageModelCallParams.headers`. `CallSettings { timeout(...) }` and
  `CallConfig { timeout(...) }` add a total high-level call timeout; non-streaming
  calls and full streaming collection are cancelled with `CallTimeoutError` when
  the deadline is exceeded. `SimulateReadableStream(...)` was added as a cold
  `Flow` helper for deterministic stream replay in tests.
  UI-to-model history conversion now identifies tool approval responses by the
  approval marker instead of the user-controlled tool name, so a real tool named
  `approval` replays as a normal tool call/result.
- UI stream encoding now supports custom `data-*` chunks through
  `StreamEvent.Data(name, data, id, transient)`, matching the existing
  `UIMessagePart.Data` decoder path for Kotlin-server-to-JS-client data parts.
  Transcription docs now call out that audio input is currently base64-backed
  in memory, with streaming upload input tracked as future work.
- MCP HTTP inbound SSE reconnects now stop on clean EOF and only retry after
  stream errors with capped exponential backoff. `MCPReconnectionOptions`
  configures `initialReconnectionDelayMillis`, `reconnectionDelayGrowFactor`,
  `maxReconnectionDelayMillis`, and `maxRetries` for `HttpMCPTransport` and
  `MCPTransportConfig`.
- data class -> @Poko migration (pre-beta): result/metadata value types lose
  generated `copy()` / `componentN()` ABI as they are demoted from public
  `data class` to `@Poko class`. This begins with `CallWarning` as the
  standalone `@Serializable` canary and continues with the `UIMessagePart`
  and `StreamEvent` sealed-leaf families as polymorphic serialization
  canaries, plus media-model result/metadata holders, lifecycle
  `AgentEvent` / `StepResult` payloads, embedding/rerank result holders, and
  language-model result/metadata/middleware-context holders, and gateway
  response/spec/metadata holders, provider tool-namespace holders, and MCP
  protocol result/capability holders, plus tool result/output and approval
  holders, structured-object result/phase holders, and UI stream result
  holders, error/parser/devtools/telemetry result holders, OAuth metadata/token
  payloads, provider error payloads, model message/content/usage wire types,
  and LiteRT wire types (`LiteRTChannel`, the six `LiteRTContent` leaves,
  `LiteRTToolCall`, and `LiteRTMessage`), plus generate result holders, loop
  snapshots, and clean state-machine phase leaves; field access, equality,
  hashCode, toString, and JSON
  serialization remain supported where applicable. State containers such as
  `AgentSessionState`, `ToolLoopAgentState`, `ChatState`, and `CompletionState`
  intentionally remain data classes for `StateFlow.update { it.copy(...) }`
  MVI usage.
- Construct-type builder migration (pre-beta): `CohereProviderSettings` and
  simple audio/media provider settings (`DeepgramProviderSettings`,
  `AssemblyAIProviderSettings`, `GladiaProviderSettings`,
  `RevaiProviderSettings`, `ElevenLabsProviderSettings`,
  `HumeProviderSettings`, `LMNTProviderSettings`, `LumaProviderSettings`,
  `FalProviderSettings`, `ReplicateProviderSettings`,
  `KlingAIProviderSettings`, `BlackForestLabsProviderSettings`,
  `ProdiaProviderSettings`, `ByteDanceProviderSettings`,
  `MistralProviderSettings`, `AlibabaProviderSettings`,
  `GroqProviderSettings`, `CerebrasProviderSettings`,
  `DeepInfraProviderSettings`, `DeepSeekProviderSettings`,
  `FireworksProviderSettings`, `TogetherAIProviderSettings`,
  `PerplexityProviderSettings`, `MoonshotAIProviderSettings`,
  `XaiProviderSettings`, `VoyageProviderSettings`,
  `QuiverAIProviderSettings`, `BasetenProviderSettings`,
  `VercelProviderSettings`, `OpenAIProviderSettings`,
  `AzureOpenAIProviderSettings`, `GoogleGenerativeAIProviderSettings`,
  `OpenAICompatibleProviderSettings`, `OpenResponsesProviderSettings`,
  `GatewayProviderSettings`, `AmazonBedrockProviderSettings`,
  `AnthropicAwsProviderSettings`, `GoogleVertexProviderSettings`,
  `HuggingFaceProviderSettings`, and `AnthropicProviderSettings`) now have
  internal positional constructors and public DSL factories such as
  `CohereProviderSettings { apiKey("..."); baseURL("...") }`. Pure data-only
  settings are `@Poko class` values with generated equality/hashCode/toString;
  settings that hold functions or transport objects are regular classes with
  identity equality. Field access and JSON serialization remain where
  applicable; public positional construction, `copy()`, and `componentN()` are
  intentionally absent so settings can grow without ABI breaks.
  Small provider model option construct types (`CohereLanguageModelOptions`,
  `CohereThinkingOptions`, `CohereEmbeddingModelOptions`,
  `CohereRerankingModelOptions`, `VoyageEmbeddingModelOptions`,
  `VoyageRerankingModelOptions`, `BasetenEmbeddingModelOptions`, and
  `TogetherAIRerankingModelOptions`) and media/transcription option types
  (`AlibabaEmbeddingModelOptions`, `AlibabaLanguageModelOptions`,
  `AlibabaVideoModelOptions`, `AssemblyAITranscriptionModelOptions`,
  `BlackForestLabsImageModelOptions`, `ByteDanceVideoProviderOptions`,
  `DeepgramSpeechModelOptions`, `DeepgramTranscriptionModelOptions`,
  `DeepSeekLanguageModelOptions`, `ElevenLabsSpeechModelOptions`,
  `ElevenLabsTranscriptionModelOptions`, `FalImageModelOptions`,
  `FalSpeechModelOptions`, `FalTranscriptionModelOptions`,
  `FalVideoModelOptions`, `FireworksEmbeddingModelOptions`,
  `FireworksThinkingOptions`, `FireworksLanguageModelOptions`,
  `GladiaTranscriptionModelOptions`, `GroqLanguageModelOptions`,
  `GroqTranscriptionModelOptions`, `HumeSpeechModelOptions`,
  `KlingAIVideoModelOptions`, `LumaImageModelOptions`,
  `LMNTSpeechModelOptions`, `RevaiTranscriptionModelOptions`,
  `ReplicateImageModelOptions`, `ReplicateVideoModelOptions`,
  `ProdiaImageModelOptions`, `ProdiaVideoModelOptions`,
  `QuiverAIImageModelOptions`, `TogetherAIImageModelOptions`,
  `MistralLanguageModelOptions`, `MoonshotAILanguageModelOptions`,
  `ProdiaLanguageModelOptions`, `XaiImageModelOptions`, and
  `XaiVideoModelOptions`) now follow the same DSL builder pattern with
  `@Serializable @Poko class` value semantics. Simple non-provider construct
  types (`GatewaySpendReportParams`, `GatewayGenerationInfoParams`, and
  `AuthOptions`) plus media/rerank/completion request config types
  (`ImageGenerationParams`, `SpeechGenerationParams`, `TranscriptionParams`,
  `VideoGenerationParams`, `RerankingParams`, `CompletionRequestOptions`,
  `CallCompletionApiOptions`, and `HuggingFaceResponsesSettings`) also move to
  builder factories. Additional request/config construct types
  (`TextGenerationRequest`, `CompletionRequest`, `StructuredObjectRequest`,
  `ChatRequest`, `TelemetrySettings`, `MCPClientConfig`, `MCPTransportConfig`,
  and `MCPRequestOptions`) now follow the same pattern. `TextGenerationRequest`,
  `ChatRequest`, `CompletionRequestOptions`, and `HuggingFaceResponsesSettings`
  are value-semantics `@Poko` classes; `AuthOptions`, the media/rerank params,
  `CompletionRequest`, `StructuredObjectRequest`, `TelemetrySettings`,
  `MCPClientConfig`, `MCPTransportConfig`, `MCPRequestOptions`, and
  `CallCompletionApiOptions` are regular classes with identity equality because
  they may hold clients, abort signals, transports, callbacks, coroutine
  contexts, telemetry integrations, or model input objects. The remaining
  non-flagship construct types (`UseCompletionOptions`,
  `StructuredObjectOptions`, `LoggingOptions`, `RedactionOptions`,
  `MCPReconnectionOptions`, `StdioConfig`, `ToolSchemaOptions`,
  `ProviderToolFactoryOptions`, `ToolPredicateOptions`, `BedrockCredentials`,
  `AssemblyAICustomSpelling`, `OpenResponsesOptions`,
  `OpenResponsesAllowedTools`, `XaiLanguageModelChatOptions`,
  `XaiLanguageModelResponsesOptions`, `LiteRTSamplerConfig`,
  `LiteRTConversationRequest`, `LiteRTLanguageModelSettings`,
  `OAuthClientInformation`, `OAuthClientMetadata`, `Configuration`,
  `ElicitationCapability`, `ProviderMiddleware`, `RetryPolicy`, and
  `ToolExecutionPolicy`) now follow the same builder pattern. Pure data-only
  options/configs/credentials/policies are `@Poko` value-semantics classes;
  callback-, transport-, serializer-, middleware-, retry-generator-, or
  arbitrary-context-bearing options are regular classes with identity equality.
  Flagship settings types (`CallSettings`, `CallConfig`, `AgentSettings`, and
  `StepSettings`) now follow the same internal-constructor builder pattern.
  `CallSettings` and `CallConfig` are `@Poko` value-semantics classes;
  `AgentSettings` and `StepSettings` are regular classes with identity equality
  because they can hold model and tool objects. `ToolLoopAgent` now uses
  `AgentSettings<TContext>` as its public settings constructor surface and keeps
  only common subclassing named arguments (`model`, `instructions`, `tools`,
  `output`, `stopWhen`) directly on the constructor; advanced knobs such as
  lifecycle hooks, typed call options, sampler defaults, tool execution policy,
  approval signing, telemetry, logging, and engine context move through
  regular `AgentSettingsBuilder` setter methods, removing the old 26-parameter
  constructor from frozen public ABI.
  Growable generated-result holders (`GenerateResult`, `GenerateTextResult`,
  `StepResult`, `StructuredObjectFinish`, and `StructuredObjectPhase.Streaming`
  / `Done`) now keep field access/value semantics but hide their positional
  constructors from public ABI. Consumers observe them from agent/generator
  calls; test fakes should use the shipped `Mock*` models instead of direct
  result instantiation.
  LiteRT wire types now use internal constructors plus public builders/DSL
  factories (`LiteRTChannel { ... }`, `LiteRTToolCall { ... }`,
  `LiteRTMessage { ... }`, and `LiteRTContent.Text { ... }` etc.); their public
  `copy()` / `componentN()` ABI is removed. LiteRT `extraContext` is now
  `Map<String, JsonElement>` instead of `Map<String, Any?>`,
  `LiteRTSamplerConfig {}` builds the default sampler config, and
  `LiteRTConversation.cancel()` / `close()` KDoc now documents that the defaults
  are no-ops that abortable/resource-owning engines must override. LiteRT tool
  responses also document their name-only correlation limit. LiteRT structured
  output now injects the shared JSON instruction/schema into the prompt for
  `ResponseFormat.Json` instead of warning and dropping the request, and
  `StructuredObjectFinish` / `StructuredObjectPhase.Streaming` / `Done` now
  carry model stream warnings.
  Call-parameter envelopes (`LanguageModelCallParams` and
  `EmbeddingModelCallParams`) are now `@Poko` value-semantics classes with
  internal positional constructors, public DSL factories for fresh
  construction, and public seeded `toBuilder()` helpers for middleware/provider
  one-field overrides. Their public `copy()` and `componentN()` ABI is removed.
  KEEP-floor stragglers `IdGenerator` and `CustomProvider` now join the
  construct builder track as regular classes with identity equality because
  they hold non-value `Random` / model-object fields. Their public positional
  constructors, `copy()`, and `componentN()` ABI are removed; construct them via
  `IdGenerator { ... }` and `CustomProvider { providerId(...); ... }`.

- **Tools are now class-based and extensible (breaking ABI change).** `Tool` is an `abstract class`
  you can extend for reusable, dependency-injected tools — mirroring how a concrete agent extends
  `ToolLoopAgent`:
  ```kotlin
  class SearchDocsTool(private val repo: DocRepository) :
      Tool<SearchInput, List<SearchResult>, AppContext>() {
      override val schema = ToolSchema("searchDocs", "Search the product documentation")
      override val inputSerializer = serializer<SearchInput>()
      override val outputSerializer = serializer<List<SearchResult>>()
      override fun execute(input: SearchInput, ctx: ToolExecutionContext<AppContext>) = flow {
          emit(ToolResult.Success(repo.search(input.query)))
      }
  }
  // usage: ToolSet(SearchDocsTool(repo))
  ```
  The executor and the optional callbacks (`needsApproval`, `toModelOutput`, `onInputStart`,
  `onInputDelta`, `onInputAvailable`) are now overridable methods instead of constructor lambdas —
  override only what you need. Tools that emit preliminary snapshots extend the new `StreamingTool`
  base and override `executeStream`. The `Tool(...)` / `StreamingTool(...)` / `DynamicTool(...)` /
  `ProviderExecutedTool(...)` factories keep their exact signatures for trivial inline tools; they
  now build an internal `LambdaTool` / `LambdaStreamingTool` subclass.

  Migration: the `Tool(...)` constructor is no longer invoked directly, and the public `Tool.executor`
  / `Tool.needsApproval` / `Tool.toModelOutput` / `Tool.onInput*` *fields* are removed (they became
  methods). Keep using the factories (unchanged), or extend `Tool` / `StreamingTool`. To drive a tool's
  executor directly, prefer `ExecuteTool(tool, input, ctx)` — it handles preliminary/final emissions
  consistently for both plain and streaming tools.

  Tool-call repair + approval: the loop now resolves a call's input (decode + a single
  `experimental_repairToolCall` attempt) ONCE, before the approval gate, so repair reaches every tool —
  factory- or subclass-built — and the prior double-decode is gone. An approval-gated tool is still
  gated over its original, cleanly-decoded input: if a gated tool's input only decodes after repair,
  the call is rejected rather than approved over a rewritten input.
- Telemetry revamp (upstream v7 parity): the previously unwired `TelemetryIntegration` surface
  is replaced by a typed `Telemetry` interface that the agent loop now FEEDS AUTOMATICALLY —
  agent start/finish, step start/finish, model-call start/finish, tool-call start/finish
  (including approval-resumed executions), errors (model/prepare/tool/hook sources), and aborts.
  Every event carries a per-invocation `TelemetryCall` correlation envelope (callId, agentId,
  agentVersion, modelId, functionId).
- `registerTelemetry(...)` / `clearGlobalTelemetry()` / `globalTelemetry` replace
  `registerTelemetryIntegration(...)` / `clearGlobalTelemetryIntegrations()` /
  `globalTelemetryIntegrations`. Once an integration is registered globally, ALL agent calls
  emit events (v7 opt-out stance); per-call `TelemetrySettings.integrations` REPLACE the global
  set for that call. Integration failures are swallowed (telemetry observes, never alters the loop).
- `ToolLoopAgent` gains a `telemetry: TelemetrySettings?` constructor parameter.
- AI SDK reference refreshed 6.0.197 → 6.0.202; parity ledgers regenerated. The delta is
  one feature: HMAC-signed tool approvals. `ToolLoopAgent` gains
  `experimental_toolApprovalSecret: ByteArray?` — when set, every issued approval request is
  signed over `(approvalId, toolCallId, toolName, canonicalJson(input))` (the signature rides
  `ContentPart.ToolApprovalRequest`, `StreamEvent.ToolApprovalRequest`, `PendingApproval`, and
  the UI round-trip via `UIMessagePart.ToolUI.approvalId/signature`), and a replayed approval
  is re-validated FAIL-CLOSED before execution: missing/invalid signature throws the new
  `AgentError.InvalidToolApprovalSignature`, the input is re-decoded against the tool's
  schema, and a tool that vanished or no longer requires approval is denied rather than run.
  Upstream's `createIdMap` prototype-pollution hardening is not applicable to Kotlin maps;
  the stream-text empty-stream output classifier maps to the loop's existing finish-reason
  defaults; the array output strategy already decoded fresh elements (no in-place cast).
- Telemetry observability: the loop `Logger.warn`s when an integration throw is swallowed
  (named integration, throwable attached) — a broken integration is discoverable, never
  perfectly silent. `ToolLoopAgent` gains `logger: Logger = NoopLogger`.
- Abort callback observability: `AbortController` now accepts an optional `Logger` and warns
  when a registered abort callback throws, while still delivering abort to remaining callbacks.
  `ToolLoopAgent` wires its logger into its internal abort controllers, and DevTools run/step
  counters are synchronized for concurrent middleware calls.
- The legacy tracer/span machinery moved to `TelemetryTracing.kt` (same package — no ABI
  change); the dead `getTracer` helper was removed.
- Removed the dead JsonElement-bag types `TelemetrySpan`/`TelemetryEvent` and the unwired
  `recordSpan(integration, ...)`; the tracer/span machinery (`TelemetryTracer`,
  `selectTelemetryAttributes`, ...) is unchanged.

## 0.3.0-alpha01

- Made `Tool` an extensible abstract class and added `StreamingTool` for tools that can emit
  preliminary snapshots while executing.
- Changed tool approval flow so tool input is decoded and repaired before the approval gate; an
  approved call executes against the same decoded input that was reviewed.
- Bumped the release line to the `0.3.0-alpha01` alpha checkpoint.

## 0.2.0

- Published `ai.torad:torad-aisdk:0.2.0` to Maven Central and fixed release workflow issues around
  configuration cache, signing task ordering, artifact naming, and the Central target.
- Completed the broad KMP parity sweep across core generation, streaming, tools, providers,
  Gateway, Open Responses, MCP transports, DevTools, UI stream helpers, and provider facades.
- Added the Kotlin-first high-level API layer, Ktor-backed provider transports, TestServer support,
  linuxX64, parity ledgers, and the refreshed AI SDK reference through `ai@6.0.204`.
- Made `ToolLoopAgent` abstract for subclass-based agents, added automatic telemetry delivery
  through the typed `Telemetry` interface, and added HMAC-signed tool approvals.

## 0.1.0-SNAPSHOT

- Extracted the KMP AI SDK module into a standalone library.
- Added Android, iOS, and JVM targets.
- Added publishing metadata, CI, license, contribution, and security docs.
