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
- **Per the user's instruction (re-confirmed at steps 4 and 5), ABI regen is held until the P3
  FINAL gate** — not regenerated per step. The cumulative public delta vs the last regen (`80ac799`)
  is exactly ONE item, `GatewayTransport$Companion` (from p3-3); steps 4-5 add no public surface
  (all re-homed members are internal/private). Known, traced, surfaced — not silently accepted.
  Resolution at P3 end: a single `updateKotlinAbi`.
- The real ABI task is `checkLegacyAbi` (alias `checkKotlinAbi`); there is no `apiCheck` task.
  ABI checks are NOT run per step (the deferral is explicit), to keep the per-step gate to
  compileKotlinJvm + jvmTest + objects-gone + no-camelcase.

## Remaining steps

4. GoogleWire, GoogleVertexWire, GoogleHttp, AnthropicAwsWire, CohereWire, MistralWire, AlibabaWire
5. BedrockMapping, BedrockHttp, FalWire, BflWire, ByteDanceWire, LumaWire, KlingAIWire, HuggingFaceWire
6. audio + remaining facade wires
