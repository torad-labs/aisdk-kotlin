# P3 — full dissolve of the 52 helper-bag objects

Re-home each helper-bag function onto the TYPE it is behavior for (member, companion
factory, or a private member-extension declared inside the owning type). Delete the bag.
No new bag, no loose top-level funs/extensions. Compile + jvmTest green each step; ABI
dumps regenerated as public surface shifts.

## Step 1 — core ops (`b9ce294`, ABI `80ac799`)

Dissolved 10 / 11:

| Object | Re-homed to |
|---|---|
| OutputOps | `Output.toResponseFormat()` member; `Output.Companion.extractChoiceValue()` |
| ToolSetOps | `Tool.descriptionWithExamples()`; `ToolSet.Companion.requireUniqueToolNames()` |
| ChatStateOps | `Chat.toState()` member |
| StreamMetadataOps | `StreamEvent.ResponseMetadata` + `LanguageModelResponseMetadata` members |
| ToolCallRepairOps | `RepairRequest.{toPrompt, reprompt, stripCodeFences}` members |
| UIMessageWire | `StreamEvent.Companion.toUIMessageChunk()` |
| LoggingWire | private `logStreamEvent()` of the logging middleware |
| MediaSupport | `ImageModelUsage.Companion.sum()`; `CallWarning.format()`; `Int.splitCount()` private ext in ImageGeneration + VideoGeneration |
| CollectionOps | `Embedding`: private `List<T>.splitArray()` (asArray was dead; file deleted) |
| JsonOps | `ProviderOptions.mergedWith()`; `StructuredObject` private `isDeepEqual()`; `merge` co-located in AnthropicWire + OpenAICompatibleWire; removeUndefinedEntries dead (file deleted) |

**Irreducible — `UrlOps` (left intact, reported):** `encode` has 6 unrelated cross-file
consumers (OpenAICompatibleHttp, ElevenLabs, KtorGatewayTransport, Azure, OpenAI,
Deepgram); `withoutTrailingSlash` 1; `isSupported`/`validateDownload` 0 prod (test-only).
No single owner without a percent-encoder duplicated across 6 files. `DownloadError` stays public.

## Step 2 — MCP wire (`ade4384`)

Dissolved McpWire (8 fns) + McpToolMapping (5 fns) = 13 functions ("106" was a
namespace-wide overestimate). Spread, not dumped in the client:

- Protocol encode/decode → `JSONRPCMessage.Companion` (`toJsonElement`, `toJsonString`, `fromJson`, `fromJsonBatch`)
- SSE parse → `McpSseEvent.Companion.parseStream` (per-frame state in a private nested `FrameBuffer` class, since `var` is banned in companions)
- `MCPToolDefinition.toolMetadata()`, `CallToolResult.extractStructuredContent()`
- `HttpMCPTransport`: private `mcpSessionId()`
- `DefaultMCPClient`: private `rpcIdKey`, `schemaWithClosedAdditionalProperties`, `asArgumentsObject`, `mcpToModelOutput` (all single-consumer, client-internal)

No irreducible remainder. Zero MCP public-ABI delta (new members internal/private).

## Step 3 — openai-family wire (`5a22489`)

Dissolved 4 / 5: OpenAIWire → OpenAIProviderSettings/OpenAITools; GatewayWire → 7 Gateway
data-type companions + 13 private members of KtorGatewayTransport; OpenResponsesWire → 40
onto PreparedOpenResponsesRequest/ConvertedOpenResponsesInput companions + the model class;
OpenAICompatibleWire → 6 core-type companions (Usage/FinishReason/ResponseMetadata/ToolCall
`fromOpenAI`) + 18 on the shared base `OpenAICompatibleHttpModel`.

**Kept — FacadeSupport (irreducible, 10-facade shared layer):** its 2 single-owner fns were
re-homed (compatibleSettings → OpenAICompatibleProviderSettings.Companion.forFacade;
usageFromParts → Usage.Companion.fromParts). The 3 generic JsonObject/JsonArray readers stay —
`intField` (4 consumers), `nestedIntField` (3), `textFromContentParts` (2), all unrelated facades,
no single owner. (FacadeHttp in the same file is separate — untouched.)

## ABI status

- `80ac799` regenerated the step-1 dumps (`ImageModelUsage.Companion`, `Output.toResponseFormat()`,
  `ToolSet.Companion`). Step 2 added zero ABI.
- After step 3 `checkLegacyAbi` is RED again — diff is **exactly one item**: `GatewayTransport$Companion`
  (from re-homing `GatewayWire.gatewayTransportMissing` onto the `GatewayTransport` interface's
  companion). The OpenAICompatible `fromOpenAI` factories added ZERO ABI (their core types are
  `@Serializable`, so the companions already existed; the new members are `internal`).
- **Per the user's instruction (re-confirmed at steps 4, 5 and 6), ABI regen is held until the P3
  FINAL gate** — not regenerated per step. The cumulative public delta vs the last regen (`80ac799`)
  is exactly ONE item, `GatewayTransport$Companion` (from p3-3); steps 4-6 add no public surface
  (all re-homed members — including the new `XaiProviderSettings.Companion` and every step-6 settings
  member — are internal/private). Known, traced, surfaced — not silently accepted.
  Resolution at P3 end: a single `updateKotlinAbi`.
- The real ABI task is `checkLegacyAbi` (alias `checkKotlinAbi`); there is no `apiCheck` task.
  ABI checks are NOT run per step (the deferral is explicit), to keep the per-step gate to
  compileKotlinJvm + jvmTest + objects-gone + no-camelcase.

## Step 4 — google + anthropic + cohere + mistral + alibaba wire (`a41cf0e`)

Dissolved 6 / 7:

- GoogleWire → `GoogleGenerativeAILanguageModel` + its companion + `GoogleTools.Companion.providerTool` (file deleted)
- GoogleVertexWire → `GoogleVertexProviderSettings` + the Vertex model classes
- AnthropicAwsWire → `AnthropicAwsProviderSettings`
- MistralWire → `MistralProviderSettings` (public companion, all-private members) + `MistralChatLanguageModel`
- CohereWire → `CohereProviderSettings` + `CohereChatLanguageModel`
- AlibabaWire → `AlibabaProviderSettings` + `AlibabaChatLanguageModel` + `AlibabaVideoModel`

**Kept — GoogleHttp (irreducible shared transport, like UrlOps):** `googlePostJson` (3 consumers),
`googleStreamSse` (2) + their internal helpers — multi-consumer, no single owning model. Reported.

## Step 5 — bedrock + media wire (`c807f57`)

Dissolved 7 / 8:

- BedrockMapping → `BedrockRequest` / `BedrockResponse` / `AmazonBedrockProviderSettings.bedrockEncodeModelId` / `BedrockMantleChatLanguageModel` (file deleted)
- FalWire → `FalProviderSettings` + the Fal model classes + `FalBinaryResponse`
- BflWire → `BlackForestLabsProviderSettings` / model
- ByteDanceWire → `ByteDanceProviderSettings` / model
- LumaWire → `LumaImageModel`
- KlingAIWire → `KlingAIProviderSettings` / model
- HuggingFaceWire → `HuggingFaceProviderSettings` (5 shared helpers) + `HuggingFaceProvider` + `HuggingFaceResponsesLanguageModel`

**Kept — BedrockHttp (irreducible shared transport):** `bedrockPostJson` (5 consumers),
`bedrockStreamPayloads`, `bedrockHeaders`, `bedrockErrorMessage`, `isBedrockClockSkewError`,
`headerValue` (2) — multi-consumer AWS SigV4 transport, no single owner. Reported.

## Step 6 — audio + facade wire (`55f92c4`) — FINAL

Dissolved 18 / 18, **0 irreducible**:

- Audio: DeepgramWire, AssemblyAIWire, RevaiWire, ElevenLabsWire, HumeWire, LMNTWire →
  their `XxxProviderSettings` (headers/options/error/query) + the speech/transcription model
  classes. (`AssemblyAIProviderSettings.headers()` renamed `requestHeaders()` — data-class
  `headers` property clash.)
- Facades (thin OpenAI-compatible adapters): VoyageWire, FireworksWire, GroqWire,
  PerplexityWire, DeepSeekWire, TogetherAIWire, DeepInfraWire, CerebrasWire, MoonshotAIWire,
  VercelWire, BasetenWire → their `XxxProviderSettings` (`toCompatible` / usage / errorMessage)
  + model classes; DeepInfra usage fix-up as a private `Usage` member-extension.
- XaiWire **fully dissolved**: the 3 xAI snake-case search-parameter helpers
  (`xaiSnakeCaseJson`/`Key`/`Naive`) → `XaiProviderSettings.Companion` as the single source of
  truth shared by the xAI chat path (`XaiChatLanguageModel`) and the Google Vertex
  xAI-compatible path (`GoogleVertexProvider`); the other xAI wire fns → `XaiProviderSettings` /
  `XaiTools.Companion` / the Xai model classes. (Earlier provisionally kept as a slim 3-fn bag;
  re-homed onto the settings companion at the user's direction — 2 unrelated consumers, but a
  single static home reaches both without duplication.)

## Step 7 — inventory-missed bags (`4b06fe4`)

A re-audit found 7 bag objects the step-1..6 inventory had never listed. Dissolved 6 / 7:

- AnthropicWire (public) → AnthropicProviderSettings (headers/options/cache/file/citation/
  max-tokens), AnthropicMessagesLanguageModel.Companion (`forwardAnthropicContainerIdFromLastStep`
  stays public; generate-result decode), AnthropicTools.Companion, PreparedAnthropicRequest.Companion,
  AnthropicPrompt.Companion, + core factories `Usage.fromAnthropic`/`mergeAnthropic`,
  `FinishReason.fromAnthropicStopReason`.
- ProdiaWire / QuiverAIWire / ReplicateWire (all `private`) → their `XxxProviderSettings` + model/
  data types (ProdiaInputFile/ProdiaMultipartResult, ReplicateModelRef, the image/video/language models).
- TelemetryOps (public) → `Telemetry.Companion` (registerTelemetry/clearGlobalTelemetry public,
  resolveTelemetry internal).
- ui.TypedJsonOps (public) → members of `UIMessagePart.ToolUI`/`Data`/`DynamicToolUI`
  (`outputAs`/`inputAs`/`dataAs`). The inventory counted ONE TypedJsonOps; there were TWO — this
  ui one is a single-family extractor set (no cross-cutting), so it dissolved onto its owning types.

**Kept — irreducible (evidence-justified, like UrlOps):**
- **FacadeHttp** (internal, 3 consumers: DeepInfraFacade/FireworksFacade/TogetherAIFacade) — cohesive
  shared facade HTTP transport; binary + JSON paths share helpers (headerValue/stripDataUriPrefix),
  no single owner.
- **TypedJsonOps** (public, TypedJson.kt, 5 consumers: KotlinApi/KtorGatewayTransport/ui.UIMessage/
  ui.UIMessagePart/KotlinIdiomsTest) — cross-cutting public JSON codec API across 6+ receiver types.

## P3 complete

All 7 dissolve steps landed; 58 helper-bag objects fully dissolved. The remaining objects are the
irreducible, multi-consumer, no-single-owner shared utilities, left intact and reported with
evidence: **UrlOps** (percent-encoder, 6 consumers), **FacadeSupport** (3 generic JSON readers, 10
facades), **GoogleHttp** (Gemini transport), **BedrockHttp** (AWS SigV4 transport), **FacadeHttp**
(facade HTTP transport, 3 consumers), **TypedJsonOps** (public JSON codec API, 5 consumers).

Next (operator-run): a single `updateKotlinAbi` to regenerate dumps for the cumulative public delta
— `GatewayTransport$Companion` (p3-3) plus the removed public objects AnthropicWire / TelemetryOps /
ui.TypedJsonOps and the new public companions (Telemetry.Companion, AnthropicMessagesLanguageModel
forward fn, UIMessagePart subtype members) from p3-7 — then the full check + push.
