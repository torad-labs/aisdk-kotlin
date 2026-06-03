# Review Defect Fix Plan

Status: implemented and verified. `./gradlew check` passed after the fix set.

This plan covers the eleven reviewer findings from the control-flow,
streaming, provider, middleware, and UI-message review. The goal is not only
to patch local lines, but to lock each cascade down with regression tests that
fail before the fix and stay green as the port evolves.

## Strategy

Use TDD for each defect cluster:

1. Add focused failing tests that reproduce the externally visible break.
2. Patch the smallest shared runtime seam that fixes the behavior.
3. Add hardening cases for neighboring finish reasons, event ordering, and
   provider data shapes.
4. Run targeted tests after each cluster and the full verification gate before
   merging.

The implementation order should fix high-risk duplicated provider calls first,
then stream terminal semantics, then data-shape preservation. Several defects
share the same cascades: duplicate billing, false-success finishes, lost
metadata, and UI state corruption.

## Cluster 1: Agent Loop Terminal Semantics

Findings:

- P1: non-`Stop` terminal finishes loop again.
- P1: provider stream errors continue into successful `StepFinish` / `Finish`.
- P2: rich usage is flattened while accumulating steps.

Cascade:

- Providers can bill multiple times after `Length`, `ContentFilter`, `Error`,
  or `Other` because the default step stop condition allows another model
  call.
- Stream consumers can observe `Error` followed by success, so host UIs and
  engine state can mark a failed run complete.
- `GenerateResult.usage` and final `Finish.usage` lose cache-read,
  cache-write, reasoning-token, and raw usage data across multi-step runs.

Tests to add first:

- `ToolLoopAgentTerminalSemanticsTest`:
  - model emits `FinishReason.Length` with no tool calls; `generate` returns
    after one model call, one step, and finish reason `Length`.
  - same assertion for `ContentFilter`, `Error`, and `Other`.
  - stream path emits no extra `StepStart` after a non-tool terminal finish.
- `ToolLoopAgentStreamErrorTest`:
  - model stream emits `TextDelta`, then `StreamEvent.Error`; collected stream
    contains the error and no later `StepFinish` or `Finish`.
  - `generate` on the same stream throws or returns a terminal failure through
    the existing error contract, but must not produce success metadata.
- `ToolLoopAgentUsageAggregationTest`:
  - two-step tool loop with rich `Usage` on each step preserves totals,
    `inputTokens.noCache`, `inputTokens.cacheRead`,
    `inputTokens.cacheWrite`, `outputTokens.text`,
    `outputTokens.reasoning`, and non-null `raw` selection.
  - `GenerateResult.usage` equals the final stream `Finish.usage`.

Acceptance criteria:

- The agent stops after any terminal non-tool finish reason:
  `Stop`, `Length`, `ContentFilter`, `Error`, or `Other`.
- `ToolCalls` remains non-terminal only when tool calls need execution or
  approval.
- `ToolApprovalRequested` remains terminal and resumable.
- A model `StreamEvent.Error` aborts the current stream/loop without a later
  successful terminal event.
- Rich usage aggregation uses a single helper shared by stream and generate
  capture, not ad hoc flat `Usage(promptTokens, completionTokens)` rebuilding.

## Cluster 2: Cold Stream Metadata Re-collection

Finding:

- P1: `StreamTextResult.textStream`, `warnings`, and `response` each collect
  the same cold `fullStream`, causing duplicate provider requests.

Cascade:

- Reading metadata after text streaming can re-run the provider call, duplicate
  billing, repeat tool-visible side effects, and return metadata from a second
  request instead of the one whose text was rendered.

Tests to add first:

- `StreamTextResultSingleCollectionTest`:
  - fake model increments `streamCallCount` each time `stream` is collected.
  - collect `textStream`, then collect `warnings`, then collect `response`;
    assert `streamCallCount == 1`.
  - assert warning and response metadata come from the same event sequence as
    the text.
  - collect `toUiMessageStream(...)` and metadata in the same scenario; assert
    no second provider collection.

Acceptance criteria:

- Accessing `textStream`, `warnings`, `response`, `toTextStreamResponse`, or
  `toUiMessageStream` must not trigger more than one upstream provider stream
  collection per `StreamTextResult`.
- Metadata must be captured while the original stream is consumed.
- If callers request metadata before the stream is consumed, the result either
  drives one shared stream collection or waits for the shared collection to
  complete; it must not silently perform an independent cold collection.
- The public API remains source-compatible where possible.

## Cluster 3: Provider-Executed Tools And Approval Denials

Findings:

- P1: provider-executed tools are queued for local execution.
- P2: rejected approvals emit `ToolError` instead of `ToolOutputDenied` and
  drop `approvalId`.

Cascade:

- Hosted provider tools can appear as failed local tools even when the provider
  executed them successfully.
- UIs show user denial as an error state instead of an expected
  `OutputDenied` state.
- Approval correlation breaks when approval id differs from tool-call id.

Tests to add first:

- `ProviderExecutedToolLoopTest`:
  - advertise a `providerExecutedTool`.
  - fake provider emits a visibility `ToolCall` with provider metadata and a
    terminal finish.
  - agent must not call the placeholder executor, emit `ToolError`, or append a
    local tool message.
  - stream still exposes the tool-call visibility event.
- `ToolApprovalDenialTest`:
  - denied approval emits `StreamEvent.ToolOutputDenied` with preserved
    `approvalId`.
  - UI conversion maps denial to `ToolCallState.OutputDenied`, not
    `OutputError`.
  - resumed message log contains an execution-denied tool result that the model
    can consume.

Acceptance criteria:

- `Tool.providerExecuted == true` tools are never locally executed by the agent
  unless the host explicitly supplies a local executor in a separate tool.
- Provider-executed visibility events remain visible to stream consumers and
  message content where appropriate.
- Approval denial is represented as a denial event/state, not as an exception
  or tool error.
- `approvalId` is preserved from request through response, event, UI state,
  and model-visible tool result.

## Cluster 4: Tool Schema Strictness

Finding:

- P2: per-tool `strict = false` is lost when building `LanguageModelTool`
  descriptors and providers hardcode strict tool schemas.

Cascade:

- Providers can reject valid non-strict schemas.
- Tool authors cannot opt out of strict schemas even though the public tool API
  exposes the flag.

Tests to add first:

- `ToolDescriptorStrictnessTest`:
  - `tool(strict = false)` produces a descriptor with `strict == false`.
  - default tool remains `strict == true`.
- Provider serialization tests:
  - OpenAI-compatible function tool JSON writes `"strict": false`.
  - Open Responses function tool JSON writes `"strict": false`.
  - Anthropic tool JSON writes `"strict": false` and only adds structured
    output beta when needed by provider behavior.
  - Gateway transport preserves strict in serialized tool descriptors if the
    gateway protocol supports it; otherwise it documents the boundary and does
    not force strict locally.

Acceptance criteria:

- `LanguageModelTool` carries `strict`.
- `ToolSet.descriptors` maps `Tool.strict` into `LanguageModelTool.strict`.
- Provider serializers use the descriptor strict flag instead of hardcoded
  `true`.
- Existing tests for default strict behavior continue to pass.

## Cluster 5: Streaming Middleware Correctness

Findings:

- P2: split opening reasoning tags leak into visible text.
- P2: `extractJsonMiddleware` generate path updates `text` but leaves stale
  `content`.

Cascade:

- Inline reasoning can be rendered as user-visible assistant text when tags
  arrive across chunk boundaries.
- Structured-output middleware can return clean `text` while
  `GenerateTextResult.content` exposes the original fenced/prose text,
  causing downstream renderers and audit logs to disagree.

Tests to add first:

- `ExtractReasoningStreamingBoundaryTest`:
  - chunks `<rea`, `soning>hidden`, `</rea`, `soning>visible` produce
    reasoning delta `hidden`, visible text `visible`, and no leaked tag text.
  - non-tag prefixes still flush promptly without waiting for the whole
    response.
  - incomplete unmatched tag at stream end is emitted as visible text or
    handled by the documented fallback, never silently dropped.
- `ExtractJsonGenerateContentTest`:
  - generate result with `content = listOf(ContentPart.Text("```json..."))`
    returns both `text` and `content.single().text` as the stripped JSON.
  - non-text content parts are preserved.
  - provider metadata, warnings, request, response, finish reason, and usage
    survive the copy.

Acceptance criteria:

- Reasoning extraction buffers possible tag prefixes across chunk boundaries.
- The buffer never grows unbounded; it only retains the longest possible tag
  prefix while outside reasoning and the longest possible close-tag prefix
  inside reasoning.
- Generate-path text transforms rebuild text content parts consistently.
- Middleware copies preserve non-text fields exactly.

## Cluster 6: UI Message Stream Index Integrity

Finding:

- P2: placeholder removal shifts `toolByCallId` indices but not
  `partIndexById` indices.

Cascade:

- Interleaved text/reasoning/tool streams can overwrite the wrong UI part or
  crash after a placeholder tool input is removed before an open text or
  reasoning part.

Tests to add first:

- `MessageStreamReaderIndexIntegrityTest`:
  - open text part, open tool-input placeholder, open reasoning part, then
    final `ToolCall` removes the placeholder; later text/reasoning deltas
    append to the original parts.
  - placeholder before text and placeholder before reasoning both keep indices
    correct after removal.
  - final snapshot has one tool card, one text part, one reasoning part, and no
    duplicate placeholder.

Acceptance criteria:

- Any part removal updates every index map that points into `parts`:
  `toolByCallId`, `partIndexById`, and any future part-index maps.
- Tool placeholder removal is centralized in one helper so future event paths
  cannot forget one map.
- Existing UI stream tests still pass.

## Cluster 7: OpenAI-Compatible Image URL Shape

Finding:

- P2: URL-only image outputs are written into `GeneratedFile.base64` instead
  of `GeneratedFile.url`.

Cascade:

- Downstream callers attempt to base64-decode an HTTP URL.
- Hosts cannot access the actual image URL through the typed `url` field.

Tests to add first:

- `OpenAICompatibleImageUrlTest`:
  - provider response with only `url` maps to
    `GeneratedFile(base64 = "", url = "...")`.
  - provider response with `b64_json` maps to `base64` and leaves `url == null`
    unless URL is also present.
  - response with both fields preserves both.
  - existing warning behavior for unsupported image options remains unchanged.

Acceptance criteria:

- URL-only images are stored in `GeneratedFile.url`.
- Base64-only images are stored in `GeneratedFile.base64`.
- Both-field responses preserve both fields.
- No caller needs to parse URL data out of `base64`.

## Cross-Cutting Hardening

Add or update helper APIs:

- `Usage.plusRich(other: Usage): Usage` or equivalent internal helper for
  total aggregation.
- `LanguageModelResult.withTextContent(text: String)` or middleware-local
  helper for rebuilding text content after transforms.
- `StreamTextResult` shared-state coordinator for single upstream collection.
- `MessageStreamReader.removePartAt(index)` helper that updates all index
  maps.

Hardening acceptance criteria:

- No duplicated provider stream collection in new tests.
- No successful terminal event after `StreamEvent.Error`.
- No rich usage field drops in multi-step agent runs.
- No local execution for provider-executed tools.
- No stale text content after middleware generate transforms.
- No incorrect UI state for denied approvals.
- No image URL stored as base64.

## Verification Gate

Actual verification run:

```sh
./gradlew jvmTest
./gradlew check
```

Final acceptance:

- All new tests fail against the current buggy behavior before fixes are
  applied.
- All new tests pass after the implementation.
- Existing tests and parity ledgers remain green.
- CI passes on `main`.
