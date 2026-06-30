# Beta Backlog — pre-release findings & acceptance criteria

Tracked work items from the pre-beta review sweep. Each was traced in source
(not pattern-matched) unless marked SUSPECTED. Acceptance criteria are written
test-first: a reproducing test (red) precedes the fix (green), and existing
tests must stay green.

- **Source:** pre-beta review on branch `refactor/ts-residue-cleanup`.
- **Reference for parity:** `.reference/vercel-ai-sdk-ai-6.0.208`.
- **Status legend:** `OPEN` · `IN PROGRESS` · `DONE` · `WONTFIX`.
- **Beta gate:** `Blocker` (fix before beta) · `Recommended` (strongly advised) · `Optional` (post-beta OK).

## Branch prerequisites (block committing the WIP — not from the audit)

- **PRE-1 [BLOCKING] — `LanguageModelResultStreamEvents.kt:32` fails the tool-occurrence-identity gate.** `private val emittedToolCallIds = mutableSetOf<String>()` keys dedup by raw provider `toolCallId`; the pre-commit hook runs `ci-gate.sh` tree-wide, so this untracked WIP file blocks **every** commit (this is why the 16 untracked / 163 modified files are stuck). Fix: convert to the repo's occurrence-identity pattern, preserving the content-vs-toolCalls dedup semantics. **Status: DONE** — now uses `ToolCallOccurrenceKey(full ToolCall, ordinal)`; gate exit 0, compile + path tests green (verified).

## Summary

| ID | Title | Severity | Beta gate | Confidence | Status |
|----|-------|----------|-----------|------------|--------|
| BL-001 | Stop/abort hangs agent when tool calls exceed parallel cap | High | Recommended | CONFIRMED | DONE |
| BL-002 | `generateText`/`streamText`/`generateObject` have no retry | High | Recommended | CONFIRMED | OPEN |
| BL-003 | Concurrent `StreamTextResult` collectors are coupled | Med-High | Recommended | CONFIRMED | OPEN |
| BL-004 | `StreamTextResult` re-drives provider after non-terminal failure | Medium | Recommended | CONFIRMED | OPEN |
| BL-005 | RetryPolicy per-attempt timeout aborts whole loop | Medium | Recommended | CONFIRMED | OPEN |
| BL-006 | Default retry predicate won't retry raw network errors | Medium | Recommended | CONFIRMED | OPEN |
| BL-007 | `SmoothStream` can emit buffered text after terminal event | Medium | Optional | CONFIRMED | OPEN |
| BL-008 | `EventStreamParser` errors on `data: [DONE]` with trailing whitespace | Low-Med | Optional | CONFIRMED | DONE |
| BL-009 | `StreamObjectResult` drops later-block text when earlier block unclosed | Low | Optional | CONFIRMED | OPEN |
| BL-010 | `StreamObjectResult` overwrites instead of merges `ResponseMetadata` | Low | Optional | CONFIRMED | OPEN |
| BL-011 | `StreamTextResult` retains full event history for single consumer | Low | Optional | CONFIRMED | OPEN |
| BL-012 | `ConvertToModelMessages` hardcodes `"approval"` tool-name | Low | Optional | CONFIRMED | OPEN |
| BL-013 | Parity ledger overclaims 3 unimplemented adapters | Medium | Recommended | CONFIRMED | OPEN |
| BL-014 | No per-call `timeout` in `CallSettings` | Low-Med | Optional | CONFIRMED | OPEN |
| BL-015 | `simulateReadableStream` test helper missing | Low | Optional | CONFIRMED | OPEN |
| BL-016 | Native provider structured-output (`json_schema`) not used — document | Medium | Recommended | CONFIRMED | OPEN |
| BL-017 | `headers` reachable only via low-level call params, not `CallSettings` builder | Low | Optional | CONFIRMED | OPEN |
| BL-018 | No reactive UI binding (Compose `useChat`-style) | — | Post-beta | CONFIRMED | OPEN |
| BL-019 | MCP inbound SSE reconnect storm (no backoff/cap, reconnects on clean EOF) | High | Recommended | CONFIRMED | OPEN |
| BL-020 | MCP OAuth refresh failure hard-fails instead of re-authorizing | Med-High | Recommended | CONFIRMED | OPEN |
| BL-021 | MCP SSE reconnect loses messages (`last-event-id` not implemented) | Medium | Optional | CONFIRMED | OPEN |
| BL-022 | MCP inbound GET SSE never re-auths on 401 | Medium | Optional | CONFIRMED | OPEN |
| BL-023 | MCP OAuth refresh race under concurrent 401s (no single-flight) | Medium | Recommended | SUSPECTED | OPEN |
| BL-024 | MCP rejects spec-valid `"result": null`, hanging that request | Low-Med | Optional | CONFIRMED | OPEN |
| BL-025 | MCP no default per-request timeout; abort not honored mid-await | Low-Med | Recommended | CONFIRMED | OPEN |
| BL-026 | MCP stdio close SIGTERMs then immediately SIGKILLs | Low | Optional | CONFIRMED | OPEN |
| BL-027 | MCP stdio unbounded line buffering (huge-line OOM) | Low | Optional | CONFIRMED | OPEN |
| BL-028 | MCP unsynchronized `sessionId`/`protocolVersion`/`endpoint` | Low | Optional | SUSPECTED | OPEN |

| BL-029 | Google Interactions streaming parses wrong event shape — all text dropped | Critical | Recommended | CONFIRMED | OPEN |
| BL-030 | Google nullable/union schemas emit invalid `{"type":"null"}` → 400 | High | Recommended | CONFIRMED | DONE |
| BL-031 | Anthropic `max_tokens` not clamped to model ceiling → 400 | High | Recommended | CONFIRMED | DONE |
| BL-032 | Anthropic sends sampling params to models that reject them (incl. `claude-opus-4-8`) | High | Recommended | CONFIRMED | DONE |
| BL-033 | Google drops URL-based images/files (empty `inlineData`) | High | Recommended | CONFIRMED | DONE |
| BL-034 | OpenResponses drops URL-based images/files (empty `data:`) | High | Recommended | CONFIRMED | DONE |
| BL-035 | OpenAI-compatible always sends tool `strict:true` → 400 on real OpenAI/Azure | High | Recommended | CONFIRMED | DONE |
| BL-036 | xAI streaming never requests usage → zero token accounting | High | Recommended | CONFIRMED | DONE |
| BL-037 | Cohere `stream()` is a fake stream (blocking call, no SSE) | High | Recommended | CONFIRMED | OPEN |
| BL-038 | Mistral `model_length` finish reason mismapped to `Other` | Med-High | Recommended | CONFIRMED | DONE |
| BL-039 | Anthropic provider-executed tools keyed by name, args dropped → 400 | Medium | Optional | CONFIRMED | OPEN |
| BL-040 | OpenResponses built-in tool outputs silently dropped | Medium | Optional | CONFIRMED | OPEN |
| BL-041 | Anthropic `cache_control` dropped on non-text blocks (no doc caching) | Medium | Recommended | CONFIRMED | OPEN |
| BL-042 | OpenAI-compatible mid-stream in-band error throws instead of emitting Error+Finish | Medium | Recommended | CONFIRMED | OPEN |
| BL-043 | Anthropic in-band `overloaded_error` surfaced as terminal (not retryable 529) | Medium | Recommended | CONFIRMED | OPEN |
| BL-044 | Provider parity — 12 additional confirmed mapping/usage/metadata gaps | Mixed | Mixed | CONFIRMED | OPEN |

| BL-045 | UI-message-stream encoder emits provider-union shapes → breaks `@ai-sdk/react` | High | Recommended | CONFIRMED | OPEN |
| BL-046 | UI-stream path lacks SSE framing, `[DONE]`, and required headers | High | Recommended | CONFIRMED | OPEN |
| BL-047 | No `data-*` UI chunk encoder (decode-only; can't emit custom data parts) | Medium | Optional | CONFIRMED | OPEN |
| BL-048 | Gateway content-part decode drops unknown types (vs Raw on stream path) | Low | Optional | CONFIRMED | OPEN |

| BL-049 | Media binary downloads + Google poll GET bypass timeout & body cap (hang+OOM) | Med-High | Recommended | CONFIRMED | OPEN |
| BL-050 | UI: late/duplicate `ToolInputDelta` reverts a finished tool card, wiping output | Medium | Recommended | CONFIRMED | OPEN |
| BL-051 | Media poll loops: abort not honored in-flight; KlingAI unfloored timeout; Google spin | Medium | Optional | CONFIRMED | OPEN |
| BL-052 | UI residual: same-name tool placeholder mis-drop; Text/Reasoning id-collision stuck | Low | Optional | CONFIRMED | OPEN |
| BL-053 | Media transcription base64 ~2× peak memory (structural) | Low | Optional | CONFIRMED | OPEN |

| BL-054 | ~~SigV4 path needs double-encode~~ → FALSE POSITIVE (boto3: single `%3A` is correct); locked w/ test | ~~Critical~~ | Recommended | REFUTED | DONE (locked) |
| BL-055 | ~~Bedrock ARN should be raw~~ → FALSE POSITIVE (boto3 encodes ARN wholesale); locked w/ test | ~~High~~ | Recommended | REFUTED | DONE (locked) |
| BL-056 | Bedrock eventstream frames not CRC-validated | Low | Optional | CONFIRMED | OPEN |
| BL-057 | Wiki/README/llms.txt code samples don't compile against the real API | High | Recommended | CONFIRMED | OPEN |
| BL-058 | Public API evolvability: data-class traps, no top-level verbs, Java-uncallable fns | High | Recommended | CONFIRMED | OPEN |

All audit areas COMPLETE. Integrations → BL-013..018. MCP → BL-019..028. Core
providers → BL-029..044. Gateway/UI codecs → BL-045..048. UI/media → BL-049..053.
SigV4/Bedrock → BL-054..056. Docs → BL-057. API evolvability → BL-058. Integrations → BL-013..018. MCP → BL-019..028. Core providers →
BL-029..044. Gateway/UI codecs → BL-045..048. UI/media → BL-049..053. SigV4/Bedrock → BL-054..056.

---

## BL-001 — Stop/abort hangs the agent when a step's tool calls exceed the parallel cap

- **Severity:** High · **Beta gate:** Recommended (borderline Blocker — it hangs the user's stop path) · **Confidence:** CONFIRMED
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/ToolLoopParallelExecution.kt:81-148`; supporting: `ToolExecutionPolicy.kt:31,35`, `ToolLoopAgent.kt:1510-1514`, `AbortSignal.kt:124`.
- **Problem:** When `#toolCalls > workerCount` (`workerCount = min(#toolCalls, maxParallelToolCalls)`), the excess calls sit queued in the `work` channel. On abort, each abort-aware tool throws `AbortError` (which IS a `CancellationException`); each worker sends one `Aborted` marker then rethrows and dies. A `CancellationException` from a `launch` child does not cancel siblings or the parent `coroutineScope` (the intended DECISION-3 decoupling), so no worker survives to drain the queued items. The collector loops `while (completedChildren < toolCalls.size) { signals.receive() }` and suspends forever.
- **Failure scenario:** Host sets `maxParallelToolCalls = 2` (common for rate-limited tools); a model step emits 3 abort-aware tool calls; user presses stop while they run. 2 workers send `Aborted`, the 3rd call is still queued, `completedChildren` stalls at 2 < 3 → the agent's stream Flow hangs. Also reproducible at the default cap with ≥9 tool calls.
- **Why current tests miss it:** the abort regression test (`ParallelToolExecutionTest.kt:127`) uses a single tool (`workerCount == size`, no queued work); the many-tools test (`:322`) has no abort. The exact `>workerCount + abort` case is uncovered.
- **Fix direction:** Don't gate the collector on a per-call count. Launch the worker pool and a closer that `signals.close()`s once the pool is drained; have the collector drain until the channel closes. Queued-but-unstarted items then cannot strand it. Surface one terminal `StreamEvent.Abort` when any completion was `Aborted` or the pool ended early.
- **Acceptance criteria:**
  - [ ] New test: agent with `maxParallelToolCalls = 2`, a step emitting 3 abort-aware tools that call `abortSignal.throwIfAborted()`; abort fires mid-step; `withTimeout(10s) { agent.stream(...).toList() }` completes (does not hang) and contains exactly one `StreamEvent.Abort`.
  - [ ] New test: same at default cap with 9 abort-aware tool calls — completes, one `Abort`.
  - [ ] The collector no longer references `toolCalls.size` as its loop bound; completion is driven by worker-pool/channel-close.
  - [ ] All existing `ParallelToolExecutionTest` cases still pass (single-tool abort, ordered results, bounded-start, per-step cap, per-tool timeout).
  - [ ] No regression: a normal (non-abort) step with `#toolCalls > maxParallelToolCalls` still applies all results in call order.

## BL-002 — `generateText` / `streamText` / `generateObject` have no built-in retry

- **Severity:** High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `RetryPolicy` used only in `Embedding.kt:93,133` and `Rerank.kt:67`; absent from `LanguageModel.kt`, `TextGenerator.kt`, `Streaming.kt`, and all chat/stream providers. `KotlinApi.kt:14-25` `CallSettings` has no `maxRetries`.
- **Problem:** The flagship text/object generation paths perform zero retries. The TS SDK applies `maxRetries: 2` to every core call (`generate-text.ts:229` via `prepareRetries`). A transient 429/503 fails the call immediately. There's also an API asymmetry: `embed(maxRetries = 2)` retries, but `generateText` can't be told to.
- **Failure scenario:** `generateText` against any rate-limited provider returns a hard `APICallError(429)` on the first transient throttle instead of backing off and succeeding.
- **Fix direction:** Add `maxRetries: Int = 2` (and optionally a `RetryPolicy`) to `CallSettings` or the model-call entry points; wrap the non-streaming generate and the stream **open** in `RetryPolicy.execute`. Streaming must retry only the connection open, never after the first byte has been emitted.
- **Acceptance criteria:**
  - [ ] `CallSettings` (or the generate/stream entry points) exposes `maxRetries` (default 2) and accepts a custom `RetryPolicy`.
  - [ ] Test: mock model that throws a retryable `APICallError(429)` twice then succeeds → `generateText` returns the success and never surfaces the 429 (with default `maxRetries`).
  - [ ] Test: `maxRetries = 0` disables retry — the bare error surfaces.
  - [ ] Test: `streamText` retries the open (pre-first-event failure) but a mid-stream error (after ≥1 emitted event) is NOT retried.
  - [ ] Test: a non-retryable error (e.g. 400) is not retried.
  - [ ] `generateObject`/`streamObject` inherit the same behavior.
  - [ ] Docs/`INTERFACE_CONTRACT.md` updated to state the retry default.

## BL-003 — Concurrent `StreamTextResult` collectors are coupled

- **Severity:** Med-High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/Generate.kt:58-90` (esp. 69-87).
- **Problem:** Memoized replay shares one `CompletableDeferred<List<StreamEvent>>`. On a non-terminal primary failure the primary calls `mine.completeExceptionally(t)` (line 76); any concurrent collector parked on `existing.await()` (line 86) re-throws that same exception — including a `CancellationException` from the primary's scope. An independent collector is silently cancelled/failed by an unrelated collector's lifecycle.
- **Failure scenario:** `launch { result.fullStream.collect { render(it) } }` while another coroutine awaits `result.response.first()`. The primary's coroutine is cancelled (user navigates away); the secondary, whose Job was never cancelled, dies with the primary's `CancellationException`.
- **Fix direction:** Decouple replayers from the producer — drive the upstream once in a result-owned scope (e.g. a `SharedFlow`/`async` owned by `StreamTextResult`, not by whichever collector won the CAS), so collector lifecycles are independent.
- **Acceptance criteria:**
  - [ ] Test: two concurrent collectors of one `StreamTextResult`; cancel the first mid-stream → the second still completes with the full event sequence (or its own independent error), never the first's `CancellationException`.
  - [ ] Test: a real (non-cancellation) mid-stream error in the primary does not propagate to a passive replayer that arrived after the terminal event was buffered.
  - [ ] Existing concurrent-collector test(s) still pass.

## BL-004 — `StreamTextResult` re-drives the provider after a non-terminal failure

- **Severity:** Medium · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/Generate.kt:75` (reset to null) → `58-83` (re-collect). Contract stated at `Generate.kt:43-44` and `StreamObjectResult.kt:17-19`.
- **Problem:** On a non-terminal failure/cancel the primary does `primaryResult.compareAndSet(mine, null)`, so a later collection re-wins the CAS and re-collects the cold `upstream`. For a real provider that is a **second HTTP request / second paid, possibly divergent generation**, contradicting the documented "collected at most once."
- **Failure scenario:** Collect `partialObjectStream`, cancel midway (UI dismissed), then call `objectValue()` → `finish()` collects the stream again → `KtorGatewayTransport.streamText` issues a fresh request; the model is invoked twice.
- **Fix direction:** Same root as BL-003. If re-drive on error is intended, document and gate it explicitly; otherwise memoize the failed terminal state so later collectors replay it instead of re-hitting the provider.
- **Acceptance criteria:**
  - [ ] Test: `StreamTextResult`/`StreamObjectResult` backed by a provider that counts invocations; collect once and cancel midway, then collect/`objectValue()` again → provider invoked exactly once.
  - [ ] Intended semantics documented on the class (replay vs re-drive); default = provider hit at most once, matching the existing doc comment.

## BL-005 — RetryPolicy per-attempt timeout aborts the whole loop instead of retrying

- **Severity:** Medium · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/RetryPolicy.kt:83-84` vs `:92-99`.
- **Problem:** `withTimeout(perAttemptTimeoutMs)` throws `TimeoutCancellationException` (a `CancellationException`), caught at line 83 (`catch (ce: CancellationException) { throw ce }`) before `classifyFailure` — so a per-attempt timeout terminates the entire retry rather than retrying the next attempt, defeating the per-attempt knob.
- **Fix direction:** Catch `TimeoutCancellationException` inside `executeAttempt`'s own scope and convert it to a retryable failure fed to `classifyFailure`, kept distinct from externally-initiated cancellation (which must still propagate immediately).
- **Acceptance criteria:**
  - [ ] Test: `RetryPolicy(perAttemptTimeoutMs = X, maxRetries >= 1)` where attempt 1 exceeds X and attempt 2 is fast → returns the attempt-2 success.
  - [ ] Test: external cancellation of the surrounding scope still aborts the retry loop immediately (not retried, not swallowed).
  - [ ] Per-attempt timeout appears in the `RetryError` attempt history when retries are ultimately exhausted.

## BL-006 — Default retry predicate won't retry raw network errors

- **Severity:** Medium · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `RetryPolicy.kt:240-242` (`isDefaultRetryable` matches only `APICallError`/`GatewayError`); `HttpTransport.kt:238,309` wraps only non-2xx responses as `APICallError`.
- **Problem:** A connection reset / DNS failure / Ktor `HttpRequestTimeoutException` propagates as a raw exception (not `APICallError`), so the default predicate returns false and it is never retried. TS retries connection errors. Compounds with BL-002 (even embed/rerank, which do retry, won't retry a network blip).
- **Fix direction:** Extend the default predicate to treat known transient network/IO/timeout exception types as retryable, or wrap them in a retryable `APICallError` at the transport boundary.
- **Acceptance criteria:**
  - [ ] Test: a block throwing a simulated transient IO/connection-reset/timeout exception is retried up to `maxRetries`.
  - [ ] Test: non-retryable client errors (4xx other than 408/409/429) are still NOT retried.
  - [ ] `CancellationException` is still never retried.

## BL-007 — `SmoothStream` can emit buffered text after the terminal event

- **Severity:** Medium · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/SmoothStream.kt:135-156` (the `else -> emit(event)` arm) and the post-loop drain `:168-169`.
- **Problem:** v6 `smooth-stream.ts` flushes the buffer before passing through every non-smoothable chunk. The Kotlin port flushes only on `TextEnd`/`ReasoningEnd` and at completion, so any other non-text event passes through while a partial chunk is still buffered, and that text is emitted later — out of order or after the terminal event.
- **Failure scenario:** Feed `TextDelta("t","done")` (no trailing whitespace → stays buffered) then `Finish` with no intervening `TextEnd`: Kotlin emits `Finish` first, then the buffered `done` text. Masked on the OpenAI-compatible path (it sends `TextEnd` before terminal events); exposed for interleaving providers and custom streams fed to this public helper.
- **Fix direction:** In the `else` arm, flush all open text/reasoning buffers before `emit(event)`, mirroring v6's `flushBuffer`-before-passthrough.
- **Acceptance criteria:**
  - [ ] Test: `TextDelta("t","done")` then `Finish` (no `TextEnd`) → the `done` `TextDelta` is emitted before `Finish`.
  - [ ] Test: a non-text event (e.g. `SourcePart`) interrupting an open text buffer flushes the buffered text before the non-text event.
  - [ ] Existing SmoothStream tests still pass.

## BL-008 — `EventStreamParser` errors on `data: [DONE]` with trailing whitespace

- **Severity:** Low-Med · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/EventStreamParser.kt:41,51,87`.
- **Problem:** The data value is left-trimmed (`trimStart()`) but not right-trimmed, so `data: [DONE] \n` yields `"[DONE] "`, which is `isNotBlank()` and `!= "[DONE]"`, gets parsed as JSON → `SerializationException` → `ParseResult.Failure` → surfaced as a `StreamEvent.Error` at the very end of an otherwise-complete stream.
- **Failure scenario:** A proxy/load-balancer appends a trailing space to the sentinel line.
- **Fix direction:** Compare against a trimmed value (`data.trim() != "[DONE]"`) in both the `Flow<String>` and `String` overloads.
- **Acceptance criteria:**
  - [ ] Test: `data: [DONE] \n\n` (trailing space) terminates cleanly with no `StreamEvent.Error`.
  - [ ] Test: `data:\t[DONE]\t` likewise.
  - [ ] Both parser overloads covered.

## BL-009 — `StreamObjectResult` drops later-block text when an earlier block is never closed

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/StreamObjectResult.kt:81-95` (`flushStableSuffixes` bails at the first unclosed block; no final drain).
- **Problem:** The flush loop returns as soon as it reaches an unclosed block, and there is no unconditional drain after `stream.collect` completes, so text in any block behind a never-closed earlier block is never emitted.
- **Failure scenario:** Provider opens `t1`, opens `t2`, streams `t2` deltas, never sends `TextEnd` for `t1` → `t2`'s text is dropped at stream end.
- **Fix direction:** After collection, run a final unconditional drain over `blockOrder` emitting each block's remaining `buffer.substring(flushedLength)`.
- **Acceptance criteria:**
  - [ ] Test: overlapping blocks where an earlier block never closes → later block's text is fully emitted by stream end.
  - [ ] Single-open-block behavior unchanged.

## BL-010 — `StreamObjectResult` overwrites instead of merges `ResponseMetadata`

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/StreamObjectResult.kt:186-192` vs `Generate.kt:125-131` (which merges).
- **Problem:** `finish()` replaces `response` on each `ResponseMetadata` event; a stream carrying more than one metadata event loses earlier headers/fields, whereas `StreamTextResult` merges them.
- **Fix direction:** Accumulate via `response = response.merge(event.toLanguageModelResponseMetadata())` as `commit` does.
- **Acceptance criteria:**
  - [ ] Test: a stream with two `ResponseMetadata` events (headers in one, id/model in another) → `StreamObjectFinish.response` carries both.
  - [ ] Consistent with `StreamTextResult.commit` behavior.

## BL-011 — `StreamTextResult` retains full event history even for a single consumer

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED (design tradeoff)
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/Generate.kt:62-81` (`buffer += event` unconditionally).
- **Problem:** The primary collector buffers every `StreamEvent` for replay even when no second collector ever materializes. Long generations (tens of thousands of deltas) hold their full event log until completion — peak-memory pressure on constrained Android/Native targets. Not a leak (freed on completion).
- **Fix direction:** Retain the buffer only once a second collector actually arrives (lazy capture), or document as an intentional tradeoff.
- **Acceptance criteria:**
  - [ ] Decision recorded: lazy-capture vs documented tradeoff.
  - [ ] If lazy-capture implemented: test that the single-consumer path does not retain the full event list after completion.

## BL-012 — `ConvertToModelMessages` hardcodes the `"approval"` tool-name

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/ui/ConvertToModelMessages.kt:115-118`.
- **Problem:** `approvalResponseMessage` identifies approval responses by the literal `toolName == "approval"` on a single-part user message. A user tool genuinely named `approval` would be misinterpreted as an approval response on history replay.
- **Fix direction:** Identify approval responses by a structural marker (dedicated UI part type / explicit flag), not the tool name string.
- **Acceptance criteria:**
  - [ ] Test: a real tool named `approval` with a normal `OutputAvailable` result round-trips through `convertToModelMessages` as a `ToolCall` + `ToolResult`, not as a `ToolApprovalResponse`.
  - [ ] Existing approval-flow round-trip tests still pass.

---

## Integrations gap (vs `ai@6.0.208`) — analysis complete

**Headline (reassuring):** integration coverage is near-complete. All 40+ model
providers are ported; every hosted/built-in provider tool is present (incl. the
newest Anthropic `computer_20251124`/`webSearch`/`advisor_20260301` and OpenAI
`applyPatch`/`localShell`/`toolSearch`/MCP tools); MCP, Gateway, `customProvider`,
`createProviderRegistry`, all 6 middlewares, provider-executed tools, message
metadata/data parts, and resumable streams (`ui/Chat.kt:233-237`) are all
implemented — not stubbed. The gaps below are narrow.

## BL-013 — Parity ledger overclaims 3 adapters that are not implemented

- **Severity:** Medium · **Beta gate:** Recommended (credibility) · **Confidence:** CONFIRMED
- **Location:** `docs/parity/ai-sdk-langchain.md`, `docs/parity/ai-sdk-llamaindex.md`, `docs/parity/ai-sdk-valibot.md`.
- **Problem:** The parity ledger marks these "ported" with named symbols (`toBaseMessages`, `convertModelMessages`, `LangSmithDeploymentTransport`, `LlamaIndexEngineResponse`, `valibotSchema`), but none exist in any source set or in `api/*.api`/`.klib.api`, and no test asserts them. A *parity* ledger that overclaims is a credibility risk at beta.
- **Fix direction:** Either implement Kotlin-native equivalents (on JVM a LangChain4j bridge is the only sensible one; JS-LangChain/valibot bridging is N/A for Kotlin) or downgrade these rows to `not-applicable`/`not-ported`. Also tighten `docs/parity/ai-sdk-test-server.md` (the test server lives in `commonTest`, not shipped runtime).
- **Acceptance criteria:**
  - [ ] Every "ported" claim in `docs/parity/` maps to a real symbol in `api/torad-aisdk.klib.api` or `api/jvm/torad-aisdk.api`, OR the row is re-labeled `not-applicable`/`not-ported` with a one-line reason.
  - [ ] A check (extend `tools/check-provider-*` or a new `tools/check-parity-claims.mjs`) fails CI if a ledger row claims a symbol absent from the API dumps.
  - [ ] `langchain`, `llamaindex`, `valibot`, and `test-server` rows reconciled.

## BL-014 — No per-call `timeout` in `CallSettings`

- **Severity:** Low-Med · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `CallConfig.kt` / `KotlinApi.kt` `CallSettingsBuilder` (only `abortSignal`); TS `packages/ai/src/prompt/call-settings.ts:141` (`timeout`/`TimeoutConfiguration`).
- **Problem:** No per-request `timeout` option; callers must construct their own `AbortSignal` + timer. (Note: non-streaming requests already have a default 120s ceiling in `HttpTransport.kt:36`, so this is convenience/parity, not an unbounded-hang bug.)
- **Fix direction:** Add `timeout: Duration?` to `CallSettings`, wiring it to an abort signal / `withTimeout` on the call.
- **Acceptance criteria:**
  - [ ] `CallSettings`/builder accepts `timeout`.
  - [ ] Test: a call exceeding `timeout` fails with a timeout error and cancels the in-flight request.
  - [ ] Streaming semantics documented (does `timeout` bound the open, the whole stream, or idle gap?).

## BL-015 — `simulateReadableStream` test helper missing

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** TS `packages/ai/src/util/simulate-readable-stream.ts`. (`simulateStreamingMiddleware` IS ported; the standalone helper is not.)
- **Problem:** No public helper to turn fixed chunks into a delayed stream for consumer tests.
- **Acceptance criteria:**
  - [ ] Public `SimulateReadableStream(chunks, delayMillis)` (or equivalent) returning a cold `Flow`.
  - [ ] Test verifying chunk order and inter-chunk delay.

## BL-016 — Native provider structured output (`response_format: json_schema`) not used — document the limitation

- **Severity:** Medium · **Beta gate:** Recommended (set user expectations) · **Confidence:** CONFIRMED
- **Location:** `StructuredObjectApi.kt`; `docs/provider-capability-matrix.md` marks structured output `partial` for all language providers.
- **Problem:** Object generation is achieved via the SDK strategy (tool-call / JSON-instruction), not each provider's native constrained-decoding `json_schema` mode. This is the most systematic capability difference vs TS and affects output reliability (native constrained decoding is stricter). It is a reasonable v1 design, but should be explicitly documented so users don't assume native schema enforcement.
- **Fix direction:** Document clearly in `docs/wiki/structured-output.md` and the capability matrix; native `json_schema` for OpenAI/Google can follow as a separate item.
- **Acceptance criteria:**
  - [ ] `docs/wiki/structured-output.md` states which strategy is used per provider and that native `json_schema` constrained decoding is not used.
  - [ ] Capability matrix `partial` cells link to that explanation.
  - [ ] Native `json_schema` support for OpenAI and Google tracked as its own backlog item with provider/test acceptance criteria.

## BL-017 — `headers` reachable only via low-level call params, not the `CallSettings` builder

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `LanguageModel.kt:112` (`LanguageModelCallParams.headers`) vs `KotlinApi.kt` `CallSettingsBuilder` (no `headers`).
- **Problem:** Per-call custom headers exist but require dropping to `LanguageModelCallParams`; the ergonomic high-level builder omits them (TS exposes `headers` as a first-class call setting).
- **Acceptance criteria:**
  - [ ] `CallSettings`/builder exposes `headers: Map<String,String>`, forwarded to the call params.
  - [ ] Test: builder-set headers reach the outgoing request.

## BL-018 — No reactive UI binding (Compose `useChat`-style) — post-beta

- **Severity:** — · **Beta gate:** Post-beta · **Confidence:** CONFIRMED
- **Location:** framework-neutral `ui/Chat.kt`, `CompletionApi.kt`, `StructuredObjectApi.kt` substitute for TS `@ai-sdk/react` hooks.
- **Problem:** No Compose Multiplatform state-holder equivalent of `useChat`/`useCompletion`/`useObject`. Intentionally omitted for v1; the framework-neutral core is shipped. This is the one integration a Kotlin app developer will most want next.
- **Acceptance criteria (post-beta):**
  - [ ] Decision recorded on a separate `:aisdk-compose` artifact vs in-core bindings.
  - [ ] If built: a `rememberChat()`-style API exposing messages/status/input as Compose state, with stop/regenerate.

---

## MCP subsystem — analysis complete

The concurrency core is verified solid (see "Verified solid" below). The real
pre-beta risks are two confirmed divergences from `@ai-sdk/mcp@6.0.208`
(BL-019, BL-020); the rest are smaller robustness/parity gaps. BL-019 was
independently re-verified in source.

## BL-019 — MCP inbound SSE reconnect storm: no backoff, no retry cap, reconnects on clean EOF

- **Severity:** High · **Beta gate:** Recommended · **Confidence:** CONFIRMED (re-verified)
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/MCP.kt:1211` (`shouldReconnect = true` after any 200) and `:1228-1240` (`finally` → `ensureInboundSse()` on every parse-loop end, including clean EOF).
- **Problem:** The standalone GET SSE reader reconnects immediately on a clean EOF as well as on error, with no delay and no attempt cap. The TS reference reconnects only on error, with exponential backoff (`1000ms × 1.5^n`, cap 30s) and `maxRetries: 2`, resetting on success (`mcp-http-transport.ts:330-347,405-445`).
- **Failure scenario:** A server that opens the optional GET SSE channel and idle-closes it (proxy/LB, or no notifications to push) yields a clean EOF → client immediately re-GETs → server closes → repeat forever: an unbounded GET flood that never backs off or stops.
- **Fix direction:** Don't reconnect on clean EOF; gate reconnect behind an attempt counter with exponential backoff + max-retries cap, mirroring `reconnectionOptions`.
- **Acceptance criteria:**
  - [ ] Test: a transport whose GET SSE returns 200 then clean-EOFs does NOT immediately reconnect (clean EOF → stop, matching reference).
  - [ ] Test: on an inbound SSE *error*, reconnect is attempted at most `maxRetries` times with increasing backoff, then gives up.
  - [ ] Backoff/cap/maxRetries are configurable (parity with `reconnectionOptions`) and reset on a successful reconnect.

## BL-020 — MCP OAuth refresh failure hard-fails the request instead of re-authorizing

- **Severity:** Med-High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `src/commonMain/kotlin/ai/torad/aisdk/MCP.kt:844-860` (refresh branch has no try/catch; `refreshAuthorization` throws on any non-2xx).
- **Problem:** When a 401 triggers `auth(reauthorize=true)` and the refresh token is expired/revoked, the refresh throws `invalid_grant` straight out of `auth()` → the caller's `tools/call`; the session is permanently broken and the documented re-auth redirect never fires. The reference wraps refresh in try/catch and falls through to `startAuthorization` → `REDIRECT` (`oauth.ts:1264-1296`).
- **Fix direction:** Wrap the refresh attempt; on failure fall through to the fresh-authorization/redirect block instead of rethrowing.
- **Acceptance criteria:**
  - [ ] Test: a 401 with a refresh that returns 400 `invalid_grant` results in a re-authorization redirect signal, not a thrown error.
  - [ ] Test: a successful refresh still resumes the request transparently.

## BL-021 — MCP SSE reconnect loses messages (`last-event-id` resumption not implemented)

- **Severity:** Medium · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `MCP.kt:1191-1199` (GET sends no `last-event-id`); frame `id:` parsed at `:1495` but never stored/used.
- **Problem:** Each reconnect re-opens from scratch; server messages emitted around the gap can't be resumed. Reference tracks `lastInboundEventId` and sends `last-event-id` on reconnect. Compounds BL-019.
- **Acceptance criteria:**
  - [ ] Frame `id` is persisted as messages arrive.
  - [ ] Reconnect sends `last-event-id` with the most recent id.
  - [ ] Test: after a simulated drop, the reconnect request carries the expected `last-event-id`.

## BL-022 — MCP inbound GET SSE never re-auths on 401 (notification channel dies silently)

- **Severity:** Medium · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `MCP.kt:1201-1210` (handles only 405 / generic non-2xx; a 401 dies with no re-auth, no reconnect).
- **Problem:** On mid-session token expiry, outbound POSTs recover (`:1096`) but the server→client inbound channel returns 401 once and is gone. Reference retries after `auth()` on 401 (`openInboundSse(triedAuth)`). Impact limited today because inbound notifications are currently dropped (`onMessage` notification → Unit, `:550`), but that will bite once notifications are handled.
- **Acceptance criteria:**
  - [ ] Inbound GET path retries once after `auth()` on a 401.
  - [ ] Test: inbound 401 → re-auth attempted → channel re-established.

## BL-023 — MCP OAuth refresh race under concurrent 401s (no single-flight)

- **Severity:** Medium · **Beta gate:** Recommended · **Confidence:** SUSPECTED (shared with reference; real race)
- **Location:** `MCP.kt:1096-1108` (HTTP), `:1370-1381`, `:1400-1411` (SSE). `auth(reauthorize=true)` has no single-flight guard.
- **Problem:** N concurrent 401s each run a full refresh and each `saveTokens(...)`. With refresh-token rotation, one wins and the rest fail `invalid_grant`; last-write-wins `saveTokens` can persist a stale token and wedge subsequent auth.
- **Fix direction:** Dedupe concurrent auth with a per-client `Mutex`/single-flight `Deferred`.
- **Acceptance criteria:**
  - [ ] Concurrent 401s trigger exactly one refresh; others await its result.
  - [ ] Test: K parallel requests hitting 401 perform a single token refresh and all proceed on the new token.

## BL-024 — MCP rejects a spec-valid `"result": null` response, hanging that request

- **Severity:** Low-Med · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `MCP.kt:131-138` (`explicitNulls=false` decodes JSON `null` → Kotlin null → discriminator's `result != null` branch skipped → `else -> fail("invalid JSON-RPC envelope")`).
- **Problem:** `{"jsonrpc":"2.0","id":7,"result":null}` (valid JSON-RPC success) fails to decode → routed to `onError` (non-fatal) → pending handler for id 7 never completes → that request hangs until timeout/cancel.
- **Fix direction:** Disambiguate response vs request/notification by absence of `method` (with `id` present), not by `result != null`.
- **Acceptance criteria:**
  - [ ] Test: a `{"id":N,"result":null}` response completes the pending request for id N (with a null result), no error.
  - [ ] Request/notification decoding unchanged.

## BL-025 — MCP has no default per-request timeout; abort not honored mid-await

- **Severity:** Low-Med · **Beta gate:** Recommended · **Confidence:** CONFIRMED (parity, but latent hang)
- **Location:** `MCP.kt:476-485`, `:377`; abort checkpoints `:494,508,510` don't run during `deferred.await()`.
- **Problem:** `tools/call`/`listTools`/`readResource` get no timeout (only `init` passes 30s). If the server ACKs the POST but never sends the JSON-RPC response, the call hangs indefinitely, and `AbortController.abort()` does not interrupt the in-flight `await()` (only checked when a response arrives). The reference shares this, but it's a real UX hang for a "stop" button.
- **Fix direction:** Apply a default request timeout, and `signal.register{}` to complete the deferred exceptionally on abort.
- **Acceptance criteria:**
  - [ ] Test: aborting an in-flight `tools/call` (server never responds) completes/cancels the call promptly rather than hanging.
  - [ ] A configurable default request timeout exists for non-init requests.

## BL-026 — MCP stdio close SIGTERMs then immediately SIGKILLs (no graceful window)

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `src/jvmAndAndroidMain/kotlin/ai/torad/aisdk/MCPStdioProcess.jvmAndAndroid.kt:73-74` (`destroy()` then `if (isAlive) destroyForcibly()` checked nanoseconds later → SIGKILL essentially always fires).
- **Problem:** A server that flushes state / removes a socket on SIGTERM is force-killed before it can. Reference sends SIGTERM only.
- **Acceptance criteria:**
  - [ ] `destroy()` → short grace wait (`waitFor(timeout)`) → `destroyForcibly()` only if still alive.
  - [ ] Test/spec for the grace window.

## BL-027 — MCP stdio unbounded line buffering (huge-line OOM)

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `MCPStdioProcess.jvmAndAndroid.kt:45` (`readLine()` uncapped; HTTP/SSE paths are capped but stdio isn't).
- **Problem:** A buggy/hostile child emitting a multi-GB line with no newline grows the heap unbounded → OOM.
- **Acceptance criteria:**
  - [ ] Accumulated line length capped; exceeding it raises a typed error instead of OOM.
  - [ ] Test with an over-limit line asserts the typed error.

## BL-028 — MCP unsynchronized `sessionId`/`protocolVersion`/`endpoint` across coroutines

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** SUSPECTED (memory-model)
- **Location:** `MCP.kt:1028`, `:1247-1248`, `:1277` (written by inbound reader `:1200` and POST path `:1095`, read in `mcpCommonHeaders`; no `@Volatile`/atomic unlike the lifecycle state).
- **Problem:** On Kotlin/Native (and JVM without volatile) a concurrent request may read a stale/absent `mcp-session-id` and omit the header → server 400/404. Self-healing in practice but a real data race.
- **Acceptance criteria:**
  - [ ] `sessionId`/`protocolVersion`/`endpoint` made `AtomicReference`-backed or guarded by the existing mutex.

---

## Core LLM providers — analysis complete

Streaming tool-call **assembly** is sound across providers (OpenAI-compatible
per-index, Anthropic per-block `input_json_delta`, OpenResponses per-`item_id` —
all verified). The findings are **request-mapping divergences from TS that cause
provider 400s on common inputs**, plus response-parse gaps — none caught by tests
because there are no golden request fixtures for these cases. BL-029 and BL-035
were independently re-verified in source. Common shared fix: a `mediaUrlOrDataUri`
helper (BL-033/BL-034) and golden request-body fixtures per provider.

## BL-029 — Google Interactions live streaming parses a non-existent event shape (all text dropped)

- **Severity:** Critical (scoped to the Interactions streaming path, not mainstream Gemini `generateContent`) · **Beta gate:** Recommended · **Confidence:** CONFIRMED (re-verified)
- **Location:** `providers/GoogleInteractionsModel.kt:197,201` (reads `event["step"]` and `event["type"]=="interaction.complete"`). Real protocol: `event_type` = `step.start`/`step.delta`/`step.stop`/`interaction.completed` (`.reference/.../google/src/interactions/google-interactions-api.ts:315,433,442,483`).
- **Problem:** The non-background `stream()` feeds raw SSE to `accept()`, which references none of the real `event_type` strings (and the completion check is even mis-spelled `interaction.complete` vs `interaction.completed`). A real `{event_type:"step.delta", delta:{type:"text",...}}` has no top-level `step` → text dropped; completion never matches → `Finish(Other, Usage())`. Background/synthesize path (reads `steps[]`) works, which is why CI passed.
- **Fix direction:** Rewrite `accept()` to dispatch on `event_type` with per-index open blocks (port `build-google-interactions-stream-transform.ts`).
- **Acceptance criteria:**
  - [ ] Test fixture of real `event_type` SSE chunks → streamed text matches and `Finish` carries usage/finishReason.
  - [ ] Test: `interaction.completed` triggers terminal finish.
  - [ ] Background and live paths share the step-decoding logic.

## BL-030 — Google nullable/union schemas emit invalid `{"type":"null"}` / `type:[...]`

- **Severity:** High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `providers/GoogleLanguageModel.kt:436-440,447-450`; `ToolJsonSchema.kt` `nullableSchema`. TS collapses `anyOf:[X,null]` → `{...X, nullable:true}` (`convert-json-schema-to-openapi-schema.ts:102-135`).
- **Problem:** Any nullable/optional tool parameter (`val unit: String?`) or optional structured-output field emits `{anyOf:[{type:"string"},{type:"null"}]}`; Kotlin forwards the null branch → Google `400 INVALID_ARGUMENT` (Type enum has no `null`). Multi-type emits an illegal `type:[...]` array. Hits virtually every optional field on the mainstream `generateContent` path.
- **Fix direction:** In `googleSchema`, drop `type:"null"` `anyOf`/`oneOf` members, set `nullable:true`, flatten the remaining branch; convert multi-type to `anyOf`.
- **Acceptance criteria:**
  - [ ] Test: a tool with a nullable param produces `{type:"string", nullable:true}` (no `type:"null"`).
  - [ ] Test: a multi-type schema produces `anyOf`, never `type:[...]`.
  - [ ] Golden Google request-body test covering optional fields.

## BL-031 — Anthropic `max_tokens` not clamped to model ceiling (thinking-budget overflow → 400)

- **Severity:** High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `providers/AnthropicProvider.kt:604,615` (`maxTokens = (maxOutputTokens ?: modelMax) + budget`, no clamp). TS clamps to `maxOutputTokensForModel` (`anthropic-messages-language-model.ts:610-622`).
- **Problem:** `claude-sonnet-4-5`, thinking on, `budgetTokens=10000`, `maxOutputTokens` unset → base = 64000, sends `max_tokens=74000` → Anthropic rejects `74000 > 64000`. Breaks the common "enable thinking, don't set maxOutputTokens" path on every known model.
- **Fix direction:** After computing `maxTokens`, clamp to the model ceiling when known (warn only when `maxOutputTokens != null`).
- **Acceptance criteria:**
  - [ ] Test: thinking on + unset maxOutputTokens → `max_tokens` ≤ model ceiling.
  - [ ] Warning emitted only when an explicit `maxOutputTokens` is clamped.

## BL-032 — Anthropic sends `temperature`/`topP`/`topK` to models that reject sampling params

- **Severity:** High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `providers/AnthropicProvider.kt:121-130,621-644` (no `rejectsSamplingParameters`). TS strips them for `claude-opus-4-8`, `claude-opus-4-7`, `claude-fable-5` (`anthropic-messages-language-model.ts:281-306,2528-2538`).
- **Problem:** A non-thinking call to `claude-opus-4-8` (this project's own model), `-4-7`, or `claude-fable-5` with `temperature=0.5` → Anthropic 400. Masked only when thinking is on (which independently strips them).
- **Fix direction:** Add a `rejectsSamplingParameters` capability for those models; null out temperature/topP/topK with warnings.
- **Acceptance criteria:**
  - [ ] Test: a sampling-param call to each listed model omits temperature/topP/topK and warns.
  - [ ] Other models still forward sampling params.

## BL-033 — Google drops URL-based images/files (empty `inlineData`)

- **Severity:** High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `providers/GoogleLanguageModel.kt:196-207` and `GoogleInteractionsModel.kt:549-564` (always `inlineData.data=part.base64`, ignores `part.url`). TS → `fileData:{fileUri}` (`convert-to-google-generative-ai-messages.ts:281-298`).
- **Problem:** The model advertises `supportedUrls` (Files-API/YouTube/`gs://`), so URL media is passed through unresolved; Kotlin emits empty `inlineData:{mimeType,data:""}` — a YouTube URL is silently dropped, model never sees the attachment.
- **Fix direction:** When `part.url != null`, emit `fileData{mimeType,fileUri}`.
- **Acceptance criteria:**
  - [ ] Test: a URL image/file produces `fileData.fileUri`, not empty `inlineData`.
  - [ ] Base64 media still emits `inlineData`.

## BL-034 — OpenResponses drops URL-based images/files (empty `data:` URL)

- **Severity:** High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `providers/OpenResponsesProvider.kt:1116-1146` (never reads `part.url`; always `data:${mediaType};base64,${base64}`). TS → `image_url:url` / `file_url:url`. The chat provider already fixed this (`OpenAICompatibleHttp.kt:423 openAiImageUrl = url ?: data:…`); Responses + Google were not.
- **Problem:** URL images reach the provider un-downloaded; Kotlin emits `"image_url":"data:image/png;base64,"` (empty) → vision-by-URL broken; non-image URL files have no `file_url` path.
- **Fix direction:** Emit `part.url ?: data-uri` for images and `file_url` for URL files (shared `mediaUrlOrDataUri` helper with BL-033).
- **Acceptance criteria:**
  - [ ] Test: URL image → `image_url:url`; URL file → `file_url:url`; base64 → data-uri.

## BL-035 — OpenAI-compatible always sends tool `strict:true` (→ 400 on real OpenAI/Azure)

- **Severity:** High · **Beta gate:** Recommended · **Confidence:** CONFIRMED (re-verified)
- **Location:** `providers/OpenAICompatibleHttp.kt:434` (`put("strict", tool.strict)` unconditional); `Tool.kt:26,35` (`strict` defaults `true`). TS omits unless set (`openai-compatible-prepare-tools.ts:65`).
- **Problem:** Default tools are sent `strict:true`. Against strict-honoring backends — including real OpenAI on the `openai.chat(...)` path (OpenAIProvider delegates here) and Azure — any tool with an optional/defaulted field (not in `required`) → 400. Pure compat servers that ignore `strict` are unaffected (hence High not Critical).
- **Fix direction:** Make `ToolSchema.strict` `Boolean?` (default null); emit `strict` only when non-null.
- **Acceptance criteria:**
  - [ ] `Tool`/`ToolSchema.strict` default is null/unset.
  - [ ] Test: a default tool omits `strict` in the request body; an explicit `strict=true` includes it.
  - [ ] Regression: structured-output `response_format` strict (separate code path) unchanged.

## BL-036 — xAI streaming never requests usage (zero token accounting)

- **Severity:** High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `providers/XaiProvider.kt:240-250` (`includeUsage=false`; core gates `stream_options.include_usage` on it, `OpenAICompatibleModels.kt:153`). TS always sets `include_usage:true`.
- **Problem:** xAI only emits usage in the final chunk when `include_usage` is requested; without it every xAI stream's `Finish` carries `Usage()` (zeros) → wrong cost/token accounting.
- **Fix direction:** Set `includeUsage = true` in `xaiCompatibleSettings()`.
- **Acceptance criteria:**
  - [ ] Test: an xAI stream request body contains `stream_options.include_usage=true`.
  - [ ] Test: final `Finish` carries non-zero usage from the fixture.

## BL-037 — Cohere `stream()` is a fake stream (blocking call, no SSE)

- **Severity:** High · **Beta gate:** Recommended (fix or document) · **Confidence:** CONFIRMED
- **Location:** `providers/CohereProvider.kt:146-174` (`stream` calls `generate` then re-emits synthetic start/delta/end; `stream:true` never sent). TS has a real SSE `doStream`.
- **Problem:** `streamText(cohere(...))` yields no incremental tokens — the whole response arrives at once after a blocking call; no real TTFT; tool-input "deltas" are a single chunk. Final aggregated content is correct (hence High not Critical).
- **Fix direction:** Implement a real Cohere SSE `doStream`, or document as an explicit beta limitation.
- **Acceptance criteria:**
  - [ ] Either: a real SSE stream test (multiple incremental `TextDelta`s from a fixture), OR a documented limitation in the capability matrix + provider docs.

## BL-038 — Mistral `model_length` finish reason mismapped to `Other`

- **Severity:** Med-High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `ModelMessage.kt:509-514` (`fromOpenAI` has no `model_length` case → `Other`); Mistral delegates here. TS maps `model_length` → `length`.
- **Problem:** A Mistral length-truncated completion reports `Other`; agent loops / `stopWhen` / length-based control + telemetry misclassify the stop. Generate and stream.
- **Fix direction:** Dedicated Mistral finish-reason mapper handling `model_length` → `Length`.
- **Acceptance criteria:**
  - [ ] Test: `finish_reason:"model_length"` → `FinishReason.Length` for Mistral (generate + stream).

## BL-039 — Anthropic provider-executed tools keyed by name (not version id), args dropped

- **Severity:** Medium · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `providers/AnthropicProvider.kt:481,524-557` (switches on `tool.name`; emits bare `{type,name}`). TS switches on version-specific `tool.id` and emits per-tool args.
- **Problem:** `computer_20241022`/`text_editor_20250429`/`web_search` collapse to the newest hardcoded type + wrong/missing beta; built-in tools sent argless (`computer` needs `display_width_px/height`, `web_search` needs `max_uses`/`allowed_domains`) → Anthropic 400 / misconfigured.
- **Acceptance criteria:**
  - [ ] Test: each versioned built-in tool maps to its correct wire `type` + beta and forwards its args.

## BL-040 — OpenResponses built-in tool outputs silently dropped

- **Severity:** Medium · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** request sends provider tools (`OpenResponsesProvider.kt:724,734-757,875-879`) but response `when` (`:287-343`) + stream dispatch only handle `reasoning`/`message`/`function_call`.
- **Problem:** Configuring `web_search` sends it, but the returned `web_search_call`/`file_search_call`/`image_generation_call`/`computer_call`/`mcp_call` items produce no tool-call/result content. Looks wired, yields nothing.
- **Fix direction:** Parse the built-in output item types, or drop the request-side mapping so the feature isn't half-present.
- **Acceptance criteria:**
  - [ ] Test: a `web_search_call` output item surfaces as a tool-call/result, OR the request-side provider-tool mapping is removed and documented.

## BL-041 — Anthropic `cache_control` dropped on non-text blocks (prompt caching of documents impossible)

- **Severity:** Medium · **Beta gate:** Recommended (cost feature) · **Confidence:** CONFIRMED
- **Location:** `providers/AnthropicProvider.kt:822-825,883-901,791-798,484-490` (cache_control only on text; reads only `cacheControl`, not `cache_control` alias). TS threads it on image/document/tool_result/assistant/tool-def.
- **Problem:** A cache breakpoint on a large PDF/image, tool_result, assistant turn, or tool definition is silently ignored — caching a large document (a prime cost-saver) is impossible.
- **Acceptance criteria:**
  - [ ] Test: cache_control on a document/image/tool_result/tool-def appears in the request body.
  - [ ] Both `cacheControl` and `cache_control` key spellings accepted.

## BL-042 — OpenAI-compatible mid-stream in-band error throws instead of emitting Error+Finish

- **Severity:** Medium · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `providers/OpenAICompatibleModels.kt:182-190` (`throw toApiCallError` inside `.map`, before `state.accept`), making the graceful branch at `OpenAICompatibleStreaming.kt:35-44` dead code. TS emits `finishReason=error` + an `error` chunk and completes.
- **Problem:** An `{"error":{…}}` chunk after partial content throws out of the Flow rather than emitting terminal `StreamEvent.Error` + `Finish(Error)`; partial content and the finish event are lost.
- **Acceptance criteria:**
  - [ ] Test: a mid-stream `{"error":...}` chunk yields a terminal `StreamEvent.Error` and a `Finish(Error)`, not a thrown exception; partial content preserved.

## BL-043 — Anthropic in-band `overloaded_error` surfaced as terminal (not retryable 529)

- **Severity:** Medium · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `providers/AnthropicProvider.kt:190-205,1144` (leading `error` event → `StreamEvent.Error`, non-retryable). TS peeks the first chunk and throws `APICallError(statusCode=529, isRetryable=true)`.
- **Problem:** Anthropic returns 200 then `{"type":"error","error":{"type":"overloaded_error"}}`; Kotlin surfaces it with no retry (compounds BL-002 — no retry on the text path anyway).
- **Acceptance criteria:**
  - [ ] Test: a leading `overloaded_error` event becomes a retryable `APICallError(529)` before the stream is consumed.

## BL-044 — Provider parity: 12 additional confirmed mapping/usage/metadata gaps

- **Severity:** Mixed (Medium→Low) · **Beta gate:** Mixed · **Confidence:** CONFIRMED
- **Acceptance criteria:** each sub-item gets a golden request/response fixture test proving parity with the cited TS behavior; triage each as Recommended/Optional during grooming.
  - [ ] **Anthropic** streaming `message_delta` usage merge ignores the `iterations` array → streamed totals under-count under compaction (`ModelMessage.kt:400-419` vs non-stream `:368-380`).
  - [ ] **Anthropic** streaming `redacted_thinking` drops `block["data"]` (`AnthropicProvider.kt:1043`) → redacted thinking can't be replayed.
  - [ ] **Anthropic** caller-supplied `anthropic-beta` header overwritten by the computed set instead of unioned (`AnthropicProvider.kt:59-61`).
  - [ ] **Anthropic** `speed:"fast"`/`taskBudget` sent without required betas; `structured-outputs-2025-11-13` added whenever `effort`/`taskBudget` set (`AnthropicProvider.kt:649-650,671,703`).
  - [ ] **Google** `providerOptions.google.structuredOutputs=false` ignored; `responseSchema` always sent (`GoogleLanguageModel.kt:125-128`).
  - [ ] **Google** streamed `Finish` drops `providerMetadata.google` (grounding/safety/promptFeedback); sources not de-duped across chunks (`GoogleLanguageModel.kt:629-653`).
  - [ ] **Google** built-in tool args dropped (`fileSearch:{}` no store names; wrong `retrieval.vertexRagStore` key) (`GoogleLanguageModel.kt:295-306`).
  - [ ] **xAI** usage math diverges (reasoning tokens should always be additive to output total; cache-exceeds-prompt unhandled) (`ModelMessage.kt:310-318`).
  - [ ] **Mistral** `frequency_penalty`/`presence_penalty` forwarded (unsupported) with no warning (`MistralProvider.kt:56-73`).
  - [ ] **OpenAI-compatible** `completion_tokens_details.{accepted,rejected}_prediction_tokens` never surfaced; non-stream reads a non-existent `obj["providerMetadata"]` key (`OpenAICompatibleHttp.kt:250`).
  - [ ] **OpenResponses** `output_item.done` prefers `item.arguments` over accumulated delta args; empty-but-present `arguments` discards deltas (`OpenResponsesProvider.kt:491`).
  - [ ] **Cohere** assistant `tool_plan` surfaced as an extra `Reasoning` part; TS never emits it (`CohereProvider.kt:476-478`).

---

## Gateway / UI-stream wire codecs — analysis complete

The **gateway** direction (Kotlin client → Vercel gateway, LanguageModelV3 union)
is wire-correct and the tool-output collision fix is well-engineered (verified
solid below). The bug is concentrated in the **UI-message-stream encoder**
(`Ui*ChunkCodec` via `ToUIMessageStream`), which emits provider-union shapes onto
the `UIMessageChunk` (`@ai-sdk/react`) wire. BL-045 was independently re-verified.

**Scoping decision needed:** Kotlin-encode → Kotlin-decode round-trips fine
(`MessageStreamReader` decodes the same shapes; tests lock them in). The break is
specifically Kotlin → JS `@ai-sdk/react`. If serving a React/`useChat` frontend from
a Kotlin backend is a beta promise (it is the purpose of this protocol), BL-045/046
are effectively **Critical**; if UI streaming is scoped Kotlin-only for beta, they
drop to "post-beta interop." Decide and document.

## BL-045 — UI-message-stream encoder emits provider-union shapes (breaks `@ai-sdk/react` strict schema)

- **Severity:** High (Critical if React interop is a beta promise) · **Beta gate:** Recommended · **Confidence:** CONFIRMED (re-verified)
- **Root cause:** `@ai-sdk/react` validates each chunk against `uiMessageChunkSchema` — a union of `z.strictObject` — and `throw`s on any mismatch (`default-chat-transport.ts:22-31`), tearing down the stream. The Kotlin UI encoder conflates the provider stream union (LanguageModelV3) with the UI union (`UIMessageChunk`).
- **Acceptance criteria:** each sub-item maps `StreamEvent` → the correct `UIMessageChunk` shape, and `ToUIMessageStreamTest` is rewritten to assert the TS shapes (it currently green-lights the wrong ones). A round-trip test against fixtures captured from `@ai-sdk/react`'s schema (or a port of `uiMessageChunkSchema`) gates it.
  - [ ] **tool-call/result** (`UiToolMessageChunkCodec.kt:46-70`): emit `tool-input-available` `{toolCallId,toolName,input,providerExecuted?,dynamic?}` and `tool-output-available` `{toolCallId,output,preliminary?,...}`; drop `tool-call`/`tool-result`/`isError`. (`ui-message-chunks.ts:62,91`)
  - [ ] **tool-input-delta** (`:14-17`): field `inputTextDelta`, not `delta`. (`ui-message-chunks.ts:59`)
  - [ ] **tool-approval-request** (`:53-61`): only `{approvalId,toolCallId,signature?}`; drop `toolName`/`input`. (`ui-message-chunks.ts:85`)
  - [ ] **tool-output-denied** (`:22-25`): only `{toolCallId}`; drop `approvalId`. (`ui-message-chunks.ts:110`)
  - [ ] **error chunk** (`UiTerminalMessageChunkCodec.kt:18`): `{type:"error",errorText}`, not `error`. (`ui-message-chunks.ts:43`)
  - [ ] **file chunk** (`UiMediaMessageChunkCodec.kt:53-60`): `{type:"file",mediaType,url=dataUrl(),providerMetadata?}`; drop `id`/`data`/`filename`. (`ui-message-chunks.ts:145`)
  - [ ] **start-step/finish-step** (`UiTerminalMessageChunkCodec.kt:11-14`, `UiMessageChunkCodec.kt:19-21`): bare `{type}`; drop `stepNumber`/`finishReason`. (`ui-message-chunks.ts:161,164`)
  - [ ] **tool chunk metadata**: thread `providerExecuted`/`dynamic`/`title`/`toolMetadata`; add a `tool-input-error` path for pre-execution failures. (`ui-message-chunks.ts:73`)

## BL-046 — UI-stream path lacks SSE framing, `[DONE]`, and required headers

- **Severity:** High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `ToUIMessageStream.kt:19` yields a bare `Flow<JsonObject>` (no SSE framing, no trailing `data: [DONE]\n\n`); `Streams.kt:132-133` sets only `Content-Type`. The framing helper `pipeUiMessageStreamToResponse` (`Streams.kt:146-159`) operates on `UIMessage` snapshots, not chunks — not wired to the chunk producer.
- **Problem:** Missing `x-vercel-ai-ui-message-stream: v1` (some clients/proxies gate on it), `cache-control:no-cache`, `connection:keep-alive`, `x-accel-buffering:no`, and `[DONE]` → hung/buffered streams behind nginx and rejected handshakes. TS: `ui-message-stream-headers.ts` + `json-to-sse-transform-stream.ts:13`.
- **Acceptance criteria:**
  - [ ] A single `ToUIMessageStream → SSE` server helper writes `data: <json>\n\n` per chunk + trailing `data: [DONE]\n\n`.
  - [ ] All five UI-stream headers set.
  - [ ] Test asserts framing + headers.

## BL-047 — No `data-*` UI chunk encoder (decode-only)

- **Severity:** Medium · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** no `Ui*ChunkCodec` branch emits `{type:"data-…"}` and `StreamEvent` has no data-part variant; yet `MessageStreamReader.kt:483-501` decodes `data-*` and `UIMessagePart.Data` exists. TS: `ui-message-chunks.ts:150-159`.
- **Problem:** A Kotlin server can't emit custom/transient `data-*` parts to a JS client; the feature is one-directional.
- **Acceptance criteria:**
  - [ ] A `data-*` emitter (likely a `StreamEvent.Data` variant) produces `{type:"data-$name",id?,data,transient?}`.
  - [ ] Encode→decode round-trip test.

## BL-048 — Gateway content-part decode drops unknown types

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `GatewayContentDecoder.kt:24` (`else -> null`) + `KtorGatewayTransport.kt:516` (`mapNotNull` silently drops). The gateway *stream* decode is forward-compatible (`GatewayStreamCodec.kt:18` → `Raw`), but content-part decode isn't.
- **Problem:** A newer gateway adding a content-part type is silently dropped (data loss) instead of preserved as Raw.
- **Acceptance criteria:**
  - [ ] Unknown content `type` falls back to a Raw/unknown part (parity with the stream path), or at minimum logs.

---

## UI assembly & media polling — analysis complete

`Chat`/`ChatSession` concurrency and `ToolCallState` matching are verified solid
(see below). Findings are media-download/poll robustness + two stream-reader edge cases.

## BL-049 — Media binary downloads + Google poll GET bypass the request timeout and body cap (hang + OOM)

- **Severity:** Med-High · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `FalProvider.kt:118-122` (mirrored `LumaProvider.kt:261`, `BlackForestLabsProvider.kt:297`, `ReplicateProvider.kt:291`) use raw `client.request{} + bodyAsBytes()`; `GoogleMediaModels.kt:271-283` uses `client.request + bodyAsText()` uncapped. All bypass `HttpTransport.requestJson`'s `withRealTimeout(120s)` + 50 MB chunked cap (`HttpTransport.kt:27,149,176`).
- **Problem:** A large/slow media download (video = tens of MB) buffers the entire body into one ByteArray with no size limit and no per-request timeout → unbounded memory + indefinite hang on a stalled byte stream. The SDK's own body-cap defense explicitly doesn't cover this path.
- **Fix direction:** Add a `bodyAsBytesCapped` (chunked, like `bodyAsTextCapped`) wrapped in `withRealTimeout`; route all binary downloads + the Google poll GET through it.
- **Acceptance criteria:**
  - [ ] Binary download path enforces a max-bytes cap (typed error past limit) and a per-request timeout.
  - [ ] Google video poll GET routed through the capped+timed reader.
  - [ ] Test: an over-cap body raises a typed error; a stalled response times out.

## BL-050 — UI: a late/duplicate `ToolInputDelta` reverts a finished tool card and wipes its output

- **Severity:** Medium · **Beta gate:** Recommended · **Confidence:** CONFIRMED
- **Location:** `MessageStreamReader.kt:313-326` (`ToolInputDelta` → `upsertTool(InputStreaming)` via `lastToolIndex`, no terminal-state guard; contrast `ToolApprovalRequest` at `:379-381` which does guard).
- **Problem:** A stray `ToolInputDelta` whose id matches a tool already at `OutputAvailable`/`OutputError` overwrites that card back to `InputStreaming`, discarding `output`/`error` — the user sees a completed result collapse to "streaming input…" and the output is lost from the assembled message.
- **Fix direction:** In the `ToolInputDelta` branch, only target a non-terminal occurrence (exclude `OutputAvailable`/`OutputError`/`OutputDenied`), or ignore deltas whose last occurrence is terminal.
- **Acceptance criteria:**
  - [ ] Test: a `ToolInputDelta` arriving after a tool reaches a terminal state does not mutate that card; its output is preserved.

## BL-051 — Media poll-loop robustness (abort latency, unfloored timeout, spin, late error detection)

- **Severity:** Medium · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Acceptance criteria:** each sub-item threads `abortSignal`/floors inputs/detects terminal error; a test per provider where applicable.
  - [ ] **Abort not honored in-flight (systemic):** `controller.abort()` (without coroutine cancel) is only observed at the next loop-top `throwIfAborted()`; several GET helpers don't accept a signal (`FalProvider.kt:75`, `RevaiProvider.kt:192`, `AssemblyAIProvider.kt:201`, `KlingAIProvider.kt:336`, `LumaProvider.kt:218`). Register the abort against a child `Job` to cancel the in-flight request. (Coroutine cancellation already works.)
  - [ ] **Fireworks** image poll takes no `AbortSignal` and hammers at fixed 500ms × 240 (`FireworksFacade.kt:201-231`) — thread the signal + add backoff.
  - [ ] **KlingAI** `while(true)` with only a wall-clock bound and an unfloored `pollTimeoutMs` (`KlingAIProvider.kt:148-174`) — a `pollTimeoutMs<=0` polls forever; add `maxPollAttempts` + `coerceAtLeast(1)`.
  - [ ] **Google** video poll uses `return@repeat` (continue) instead of `break` on `done` → busy-spins remaining iterations (`GoogleMediaModels.kt:203-205`); and a terminal `error` without `done:true` isn't detected mid-loop (`:204,219`). Use a labeled `break`; check `error` inside the loop.

## BL-052 — UI residual edge cases (narrow)

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Acceptance criteria:**
  - [ ] **Same-name concurrent tool placeholder mis-drop:** when streaming partial id ≠ final `toolCallId` and a `ToolCall` arrives with no preceding `ToolInputEnd`, the fallback removes an arbitrary same-name placeholder (`MessageStreamReader.kt:341-355`). Disambiguate by buffered-input prefix, or require `ToolInputEnd` before `ToolCall`. (Doesn't affect OpenAI-family where ids match.)
  - [ ] **Text/Reasoning id collision:** reusing a block id across kinds clobbers `partIndexById`, leaving the original Text part stuck in `Streaming` forever (`MessageStreamReader.kt:138-145,181-188`). Track text/reasoning indices separately.
  - [ ] **Errored/denied tool vanishes on replay (by-design):** `OutputError`/`OutputDenied` emit no ToolCall/ToolResult; a message containing only such a part is dropped (`ConvertToModelMessages.kt:302,89`). Decide whether to re-emit ToolCall+error ToolResult so the model sees the failed attempt.

## BL-053 — Media transcription input is whole-file-in-memory at ~2×N (structural)

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `AssemblyAIProvider.kt:124`, `GladiaProvider.kt:201`, `RevaiProvider.kt:178`, `FalProvider.kt:409` — audio arrives as `audio.base64: String` (whole file in RAM) then `Base64Codec.decode` produces a second full ByteArray.
- **Problem:** Large audio/video → ~2× peak memory; no streaming-upload path. Matches the upstream Vercel input contract — lowest priority.
- **Acceptance criteria:**
  - [ ] Documented as a known limitation, or a streaming `Source` input type added (post-beta).

---

## AWS SigV4 / Bedrock — analysis complete

The HMAC signing chain, crypto primitives, `SecureRandom` (CSPRNG), secret handling,
amzDate, eventstream framing offsets, and region derivation are verified solid (the
signer passes AWS's official `AKIDEXAMPLE` test vector). One Critical path-encoding
bug; BL-054 was independently re-verified in source.

## BL-054 — ~~SigV4 canonical path single-encoded~~ → FALSE POSITIVE (verified against boto3); lock with a regression test

- **VERDICT: REFUTED.** The audit hypothesis (Bedrock requires double-encoding `%253A`) is WRONG. boto3 (the AWS reference SDK, botocore 1.43) signs the colon Bedrock path with **single-encoded `%3A`** on both the wire and the canonical request — byte-identical to the SDK's current output. Applying the proposed double-encode "fix" would sign `%253A` while sending `%3A` → signature mismatch → 403 on every Bedrock call. The agent's `%253A` "golden" was its own assumption, never checked against boto3.
- **Action (do this instead):** add a regression test that LOCKS the verified-correct single-encoding so nobody "fixes" it later. boto3 golden (controlled request: POST `/model/anthropic.claude-3-5-sonnet-20240620-v1:0/converse`, body `{"messages":[]}`, content-type application/json, key `AKIDEXAMPLE`/`wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY`, date `20150830T123600Z`, us-east-1/bedrock): canonical path `/model/anthropic.claude-3-5-sonnet-20240620-v1%3A0/converse`, **signature `d01a8d899a4fa4002b4b754611d4da1c824394200b23acaaa4609304bef93847`**, SignedHeaders `content-type;host;x-amz-date`.
- **Acceptance criteria:**
  - [ ] Test: SDK signs the controlled colon Bedrock request → Authorization signature == the boto3 golden above; canonical path contains `%3A` and NOT `%253A`. (Extends `AwsSigV4Test`, which today asserts only host/date/token for the Bedrock URL — the gap the audit correctly identified.)
  - [ ] Do NOT change `uriEncodePreservingEscapes` / `bedrockEncodeModelId` encoding.

<details><summary>superseded original hypothesis (kept for the record)</summary>

- ~~Severity: Critical · Confidence: CONFIRMED (re-verified; agent ran differential signature tests)~~
- **Location:** `AwsSigV4.kt:103-104,119-141` (`canonicalAwsPath`/`uriEncodePreservingEscapes`). The `%XX` passthrough branch (`:126-130`) preserves an existing `%3A` instead of re-encoding `%`→`%25`.
- **Problem:** `bedrockEncodeModelId` puts `%3A` on the wire for a model-id colon; the canonical path then keeps `%3A`, but AWS double-encodes (non-S3 rule) to `%253A`, so signatures diverge → 403 "signature ... does not match." Hits `anthropic.claude-3-5-sonnet-20240620-v1:0`, `amazon.nova-lite-v1:0`, `meta.llama3-70b-instruct-v1:0` — almost every Bedrock model. The agent reproduced AWS's expected canonical and got divergent hashes (SDK `16b2a3f7…` vs AWS `0b507367…`).
- **Why tests miss it:** `AwsSigV4Test:36-52` uses a colon model id but only asserts the credential-scope substring, never the full signature.
- **Fix direction:** Double-encode the path for the canonical request only (wire URL stays single-encoded): re-encode `%`→`%25` in the canonical-path pass (drop the `%XX` passthrough when building the canonical path), or run the encoder twice. **Confirm the exact byte against boto3 / a real Bedrock call.**
- **Acceptance criteria:**
  - [ ] Full-signature assertion test for a colon model id, matching a boto3/aws4fetch golden.
  - [ ] Canonical path for `…-v1:0` contains `%253A`.
  - [ ] Standard `AKIDEXAMPLE` vector still passes (no regression to non-Bedrock signing).

</details>

## BL-055 — ~~Bedrock ARN model ids percent-encoded wholesale~~ → FALSE POSITIVE (verified against boto3); lock with a regression test

- **VERDICT: REFUTED.** The audit hypothesis (ARNs must be passed RAW, not percent-encoded) is WRONG. boto3's bedrock-runtime client wholesale percent-encodes an ARN model id on the wire: `arn:aws:bedrock:us-east-1:123456789012:application-inference-profile/abc123` → `/model/arn%3Aaws%3Abedrock%3Aus-east-1%3A123456789012%3Aapplication-inference-profile%2Fabc123/converse` (`:`→`%3A`, `/`→`%2F`). The SDK's `bedrockEncodeModelId` (AmazonBedrockProvider.kt:64) percent-encodes every non-`[A-Za-z0-9-_.~]` char → **byte-identical** to boto3. The proposed "use ARN raw" fix would diverge from boto3 → 403.
- **Action:** lock the verified-correct encoding with a unit test on `bedrockEncodeModelId`.
- **Acceptance criteria:**
  - [ ] Test: `bedrockEncodeModelId("arn:aws:bedrock:us-east-1:123456789012:application-inference-profile/abc123")` == `arn%3Aaws%3Abedrock%3Aus-east-1%3A123456789012%3Aapplication-inference-profile%2Fabc123`.
  - [ ] Test: `bedrockEncodeModelId("anthropic.claude-3-5-sonnet-20240620-v1:0")` == `anthropic.claude-3-5-sonnet-20240620-v1%3A0`.
  - [ ] Do NOT add an `arn:` carve-out.

## BL-056 — Bedrock eventstream frames not CRC-validated

- **Severity:** Low · **Beta gate:** Optional · **Confidence:** CONFIRMED
- **Location:** `BedrockEventStream.kt:23-33,78-101` (prelude-CRC and message-CRC offsets correct but skipped, not checked).
- **Problem:** A bit-flipped frame is silently misparsed as content instead of rejected. Correctness-under-corruption only; not security.
- **Acceptance criteria:**
  - [ ] Both CRC32s validated; mismatch throws `InvalidResponseDataError`.

---

## Public API evolvability & docs accuracy — analysis complete

The headline is the **API-freeze decision** (BL-058 A1/A2/A3 are expensive-after-publish)
and a **systemic docs drift** (BL-057): the wiki/README/llms.txt were written to an
intended v6-style top-level/`create*` API the Kotlin port implemented as PascalCase
factories + object-qualified members + `Flow`-returning agents, with no wiki
compile-guard. A2/B1, A3, A1, B8 were independently re-verified in source.

## BL-057 — Wiki/README/llms.txt code samples don't compile against the real API

- **Severity:** High · **Beta gate:** Recommended (first users copy-paste these) · **Confidence:** CONFIRMED (re-verified B11/B12/B16/A2-B1/B8)
- **Root cause:** docs target a v6-style API (top-level `generateText`, `create*` factories, `.text` on a direct result) that doesn't exist; the port uses PascalCase factories, object-qualified members, and `Flow`-returning agents. Only `ReadmeQuickStartTest` compiles one snippet; all 33 wiki pages + llms.txt + INTERFACE_CONTRACT have no compile guard (B10), so drift went unchecked.
- **Fix direction:** mechanical doc rewrite + add `*DocSnippetTest` compile guards for the first-hit pages. Tie B1-B3 to the BL-058/A2 decision (if top-level verb wrappers are added, ~80% of broken snippets resolve automatically).
- **Acceptance criteria:** each break fixed AND a compile-guard test added so it can't regress.
  - [ ] **B1** top-level `generateText`/`streamText` (getting-started:52,64,80; core:15,27,45,78,132; structured-output; foundations; README:68,78; INTERFACE_CONTRACT:204) → don't exist; rewrite to `TextGenerator(model).generate(GenerationInput.Prompt(...))` or add the wrappers (BL-058/A2).
  - [ ] **B2** `create*` factories (~45 refs across providers.md, README:83, llms.txt:93) → PascalCase `Gateway()`/`Anthropic()`/`OpenAICompatible()`; plus renames `createAzure`→`AzureOpenAI`, `createVertex`→`GoogleVertex`, `createVertexAnthropic`→`GoogleVertexAnthropic`, `createVertexMaas`→`GoogleVertexMaas`.
  - [ ] **B3** `generateObject`/`streamObject`/`streamTextResult` (README:80; core:97; structured-output:92,109,114) → don't exist; use `TextGenerator.streamResult(...)`/`StreamObjectResult(...)`.
  - [ ] **B4** `agent.generate(...).text` (getting-started:143; tools:39; agents:183,193,256) → `generate(...)` returns `Flow<GenerateResult>`; use `.first().text`.
  - [ ] **B5** `createMcpTransport`/`createMCPClient` (mcp:10,19) → `CreateMcpTransport`/`CreateMCPClient` (latter `suspend`).
  - [ ] **B6** `callSettings{}`/`wrapLanguageModel`/`*Middleware` lowercase (core:123,190-208; INTERFACE_CONTRACT:133; llms.txt:89) → PascalCase.
  - [ ] **B7** INTERFACE_CONTRACT:13-14,54-58,175 wrong `Agent.generate` sig + phantom `outputObj`/`customProvider`/`createIdGenerator`/`generateId` → real `Output.obj`, `Provider`, `IdGenerator`.
  - [ ] **B8** llms.txt:8-9 `ai.torad:aisdk-kotlin` / `0.1.0-SNAPSHOT` → `ai.torad:torad-aisdk` / `0.3.0-beta01` (won't resolve as written).
  - [ ] **B9/B14** `imageGenerationFile`/`generatedFile` (core:172; model-families:71,74,133) → `ImageGenerationFile(data)`/`GeneratedFile(data)`.
  - [ ] **B11** media verbs object-qualified (model-families:10-114) → `Embedding.embed`/`ImageGeneration.generateImage`/`Reranking.rerank`/`Transcription.transcribe`/`SpeechGeneration.generateSpeech`/`VideoGeneration.generateVideo`.
  - [ ] **B12** `customProvider(...)` (providers:15,143) → `Provider(providerId=…, languageModels=…)`.
  - [ ] **B13** `buildProviderOptions{}` (agents:138) → `ProviderOptions.ofPairs(...)` (type is `ProviderOptions`, not `Map`).
  - [ ] **B15** `extractJsonMiddleware`/`loggingMiddleware`/`mockLanguageModelTextOnly`/`toolApprovalResponseMessage` → PascalCase.
  - [ ] **B16** `MCPTransportKind` has only `{Http,Sse}` (MCP.kt:890); mcp.md:25,124 stdio prose → point to `Experimental_StdioMCPTransport`.
  - [ ] **B17** `providerExecuted=true` as a `Tool(...)` arg (agents:78) → `ToolSchemaOptions(providerExecuted=true)` / `ProviderExecutedTool(...)`.
  - [ ] **B10/B18** add `*DocSnippetTest` compile guards (getting-started/core/tools/structured-output/providers/mcp/agents) and show required imports for companion/extension calls.

## BL-058 — Public API evolvability (expensive to change after the ABI freezes)

- **Severity:** High · **Beta gate:** Recommended (decide before publishing) · **Confidence:** CONFIRMED (A1/A2/A3 re-verified)
- **Why now:** once `0.3.0-beta01` publishes, these are binary-compatibility-breaking to change. This is the one section that is cheap now and expensive later.
- **Acceptance criteria (decisions + mechanical changes):**
  - [ ] **A1 [HIGH] Data-class trap:** ~358 public `data class`es (442 decls incl. internal). `copy()`/`componentN()`/`equals` make adding a field a binary break. Demote consumer-**read** types (`*Result`/`*Response`/`*Metadata`/`Usage`/`StepResult`) to regular `class` so fields append additively; keep `data` only where `copy`/destructuring is contractual, and reserve a trailing extensibility bag there. Priority offenders: `GenerateTextResult` (18 fields), `GenerateObjectResult`, `Usage`, `StepResult`, `CallSettings`, `LanguageModelCallParams`, `EmbedResult`/`EmbedManyResult`, `RerankResult`, every `*ProviderSettings`/`*ModelOptions`.
  - [ ] **A2 [HIGH] No top-level `generateText`/`streamText`/`generateObject`/`streamObject`** (only `Gateway` members + `TextGenerator(model).generate(...)`). Decide before freeze: add the v6-style top-level wrappers (recommended — also fixes most of BL-057) OR drop them from the contract + docs.
  - [ ] **A3 [HIGH] Java/Android consumers:** 0 `@JvmOverloads` vs 555 `$default` bridges; ~145-184 value-class-mangled public fns (`chat-tDyRgq0`, `getModelId-o4H4ZZ8`) are uncallable from Java; `Tool()` has 14 params/11 defaulted. Add `@JvmOverloads` to headline factories + `ModelId.of(String)`/`@JvmName` for value-class accessors, OR explicitly declare Java unsupported for beta (Android Kotlin is fine; Java interop is the gap).
  - [ ] **A4 [MED] Visibility leaks:** `EventStreamParser`, `Base64Codec`, `TypedJsonOps`, `DataUrl`, `DirectCompletionTransport`, `DirectStructuredObjectTransport`, `HttpMCPTransport`, `SseMCPTransport` are `public` with no `@InternalAiSdkApi` (the marker exists and is used elsewhere) → frozen into the ABI. Annotate `@InternalAiSdkApi` or make `internal`.
  - [ ] (A5 enum-vs-sealed and A6 experimental-gating verified correct — no action; see Verified solid.)

---

## Verified solid (no action — recorded so we don't re-investigate)

These high-stakes areas were independently traced and are correct:

- **Secret redaction** — `LoggingMiddleware` (`Logging.kt:16-18`) and telemetry (`Telemetry.kt:37-38`, `:207-264`) default to metadata-only; all content gated behind opt-in flags; headers/errors always redacted.
- **`embedMany` ordering** — `BoundedParallel.map` (`BoundedParallel.kt:23-39`) tags indices and re-sorts; no embedding/input mis-association under parallel completion.
- **`FixJson`** (`FixJson.kt`) — correct partial-number/escape handling; `isStrictJsonValue` rejects `NaN`/`Infinity`/`1e999` to match JS `JSON.parse`.
- **Cancellation hygiene** — no swallowed `CancellationException` (all `runCatching` wrap synchronous parsing); no `runBlocking`/`GlobalScope`; `engineScope`/`clientScope` cancelled on `close()`.
- **RetryPolicy core** (`RetryPolicy.kt`) — correct full jitter, Retry-After (case-insensitive + delta-seconds + HTTP-date, capped 60s), off-by-one (`maxRetries=2` → 3 attempts), `CancellationException` rethrow. (Edge cases tracked as BL-005/BL-006.)
- **AbortController** (`AbortSignal.kt:54-121`) — copy-on-write CAS callback list; idempotent abort; straggler-fire on register race; Native-safe.
- **MCP concurrency core** — JSON-RPC id correlation (monotonic `Long` ids under mutex; keyed by `JsonPrimitive.content` so numeric-vs-string echoes resolve; concurrent requests can't swap/drop responses); pending-map cleanup under `NonCancellable` `finally` (no continuation leak on cancel/timeout); `close()` synchronous drain + once-only `onClose` (CAS); `McpConnectionLifecycle` folds state+scope+reader into one `AtomicReference` (clean concurrent start/close/self-close/spawn-rollback); per-message isolation in SSE/stdio readers; PKCE S256 + CSPRNG (`SecureRandom`, not `Random.Default`); no tokens in logs; stderr drain thread prevents pipe-full deadlock.
- **Provider streaming tool-call assembly** — OpenAI-compatible accumulates per-`index` and parses incrementally (interleaved indices + split args reconstruct correctly); Anthropic accumulates `input_json_delta` per block index with no cross-contamination (all delta/block type strings match TS); OpenResponses keys by `item_id`; Anthropic `message_delta` output-token overwrite + input/cache preservation correct. Finish-reason and well-formed-usage mapping match TS for OpenAI-compatible/OpenResponses/Anthropic/Google; OpenAI facade routing and shared HTTP error/`isRetryable`/`Retry-After` mapping match TS.
- **Gateway provider-stream codecs** — `Gateway{Text,Tool,Terminal,Media,Lifecycle}StreamCodec` correctly target the LanguageModelV3 union; tool-output discriminator-collision fix (`Tool.kt:865-906`) only decodes the exact `{type,value}` shape and preserves colliding payloads verbatim; `EventStreamParser` skips `[DONE]` on decode. (The UI-stream *encoder* is the BL-045 exception.)
- **UI Chat/ChatSession concurrency** — single atomic `InternalState` via `applyState`/`updateAndGet` (no torn half-assembled message); op-token guard (`currentOpRef`) so a superseded `sendMessage` can't clobber a newer turn; `stop()` cancels the active Job; cancellation rethrows without entering Error; `regenerate()` de-dups the trailing user turn. `ToolCallState` forward transitions + `firstToolIndex` result-matching correct even with repeated ids; DataPart keyed upsert-vs-append correct. (Covered by `ChatConcurrencyTest`, `ToolOccurrenceContractTest`.)
- **Media poll hardening (the good examples)** — Replicate, ByteDance, Black Forest Labs are the gold standard: bounded attempts + abort checkpoint + comprehensive `failed`/`canceled`/unknown-status detection that throws immediately. Coroutine cancellation unwinds every poll loop. Gladia's same-origin guard on the provider-supplied `result_url` prevents API-key exfiltration.
- **API evolvability — the parts done right** — `FinishReason` carries `Other` + `rawFinishReason: String?` (forward-safe); open sets (`ContentPart`/`StreamEvent`/`ToolResult`) are `sealed` so new subtypes are additive; closed enums won't grow. Experimental gating is correct: `@ExperimentalAiSdkApi`/`@LowLevelLanguageModelApi` applied at 17+5 sites, `Experimental_*` MCP carries both prefix and annotation, no ungated experimental leak. (The data-class/Java/visibility issues are BL-058.)
- **Docs — the parts that are correct** — lowercase action verbs (`embed`/`rerank`/`generateImage`/`pruneMessages`/`cosineSimilarity`) match the real convention; `Tool`/`DynamicTool`/`StreamingTool`/`ToolSet`, stop conditions, `AgentSession`, `Output.obj/array/choice/json`, provider accessors, most MCP members, and all media param names are documented correctly; `ToolLoopAgent` is abstract and subclassed correctly everywhere. (The broken snippets are BL-057.)
- **AWS SigV4 signing core** — passes the official AWS `AKIDEXAMPLE` test vector (kDate→kRegion→kService→kSigning, credential scope, string-to-sign, sorted/trimmed/lowercased headers, signed-headers, Authorization format all byte-correct); query canonicalization spec-correct; session token handled as signed `x-amz-security-token`; Bedrock signs the real body hash. Crypto primitives pass RFC 4231; `SecureRandom` is a true CSPRNG (JVM `java.security.SecureRandom`, native `/dev/urandom` read-to-completion); AWS keys/token/Authorization never logged or in errors; eventstream framing offsets + partial-chunk reassembly + endianness correct; region derivation per-endpoint. (The path double-encoding is the BL-054 exception.)

---

## Pending investigation (don't lose track)

Areas still under audit or not yet deep-audited. Convert findings into BL-NNN items as they land.

- [x] **Integrations gap vs `ai@6.0.208`** (user's top concern): COMPLETE → BL-013..BL-018. Coverage near-complete; narrow gaps only.
- [x] **MCP subsystem**: COMPLETE → BL-019..BL-028. Concurrency core verified solid; risks are the SSE reconnect storm + OAuth refresh recovery.
- [x] **Core LLM providers**: COMPLETE → BL-029..BL-044. Streaming assembly solid; request-mapping 400-class bugs are the main risk.
- [x] **AWS SigV4 / Bedrock signing**: COMPLETE → BL-054..056. Signing core solid; the path double-encoding is a Critical Bedrock blocker.
- [x] **Gateway / UI-stream wire codecs**: COMPLETE → BL-045..048. Gateway direction correct; UI encoder breaks `@ai-sdk/react`.
- [x] **UI message assembly**: COMPLETE → BL-050, BL-052. Chat/ChatSession concurrency solid; two stream-reader edge cases.
- [x] **Media/audio providers**: COMPLETE → BL-049, BL-051, BL-053. Replicate/ByteDance/BFL gold-standard; download timeout/cap + poll robustness gaps.
- [x] **Public API evolvability**: COMPLETE → BL-058. Data-class freeze trap + no top-level verbs + Java-uncallable fns.
- [x] **Docs/wiki accuracy**: COMPLETE → BL-057. Systemic drift; no wiki compile-guard.

**All audit areas complete.** 58 items tracked. The two cheap-now/expensive-later
decisions (BL-058 A1 data-class demotion, A2 top-level verbs) should be settled
before publishing `0.3.0-beta01`.
