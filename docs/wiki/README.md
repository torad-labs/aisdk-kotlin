# AI SDK Kotlin Wiki

This wiki is the working documentation for AI SDK Kotlin. It follows the
Vercel AI SDK v6 documentation shape while explaining the Kotlin Multiplatform
API that exists in this repository.

## Start Here

- [Foundations](foundations.md) - the mental model, Kotlin differences, and
  recommended reading paths.
- [Getting Started](getting-started.md) - install, choose a provider, generate
  text, stream, and test a first call.
- [Providers And Models](providers.md) - Gateway, OpenAI-compatible providers,
  dedicated provider facades, custom providers, and provider registries.
- [Provider Management](provider-management.md) - Gateway metadata, provider
  routing, provider registries, middleware, and custom providers.
- [Cookbook](cookbook.md) - short examples for common tasks.

## AI SDK Core

- [Core](core.md) - provider-neutral generation, settings, middleware,
  telemetry, errors, and model families.
- [Prompts And Messages](prompts-and-messages.md) - `prompt`, `system`,
  `messages`, content parts, tool results, approval responses, UI conversion,
  and provider options.
- [Prompt Engineering](prompt-engineering.md) - system prompts, task prompts,
  retrieval context, tool descriptions, and structured-output prompting.
- [Settings And Provider Options](settings-and-provider-options.md) -
  sampling, limits, cancellation, provider-specific options, defaults, and
  dynamic agent settings.
- [Structured Output](structured-output.md) - `Output.obj`, arrays, choices,
  JSON trees, tool use with structured output, and deprecated object helper
  migration.
- [Tools](tools.md) - typed tools, streaming tools, approval, `toModelOutput`,
  dynamic tools, provider-executed tools, and input hooks.
- [Streaming](streaming.md) - `Flow<StreamEvent>`, `streamTextResult`,
  adapters, server responses, cancellation, tool streams, and error handling.
- [Advanced Streaming](advanced-streaming.md) - stopping, resume tradeoffs,
  backpressure, fanout, replay, caching, and cleanup.
- [Model Families](model-families.md) - embeddings, reranking, image, speech,
  transcription, video, and generated files.
- [Middleware And Telemetry](middleware-and-telemetry.md) - language model
  middleware, default settings, JSON extraction, simulated streaming,
  telemetry, and DevTools.
- [DevTools](devtools.md) - recorder-backed local inspection for generate and
  stream calls.
- [Utilities](utilities.md) - ids, stream smoothing, simulated streams,
  message pruning, embeddings math, abort signals, and data URLs.
- [Error Handling](error-handling.md) - typed SDK errors, retry policy,
  streaming failures, tool errors, and UI validation.
- [Lifecycle And Events](lifecycle-and-events.md) - agent hooks, stream
  events, tool input hooks, telemetry events, and UI finish handling.

## Agents

- [Agents](agents.md) - `Agent`, `ToolLoopAgent`, loop control, call/step
  preparation, approval, lifecycle hooks, sessions, subagents, and memory.
- [Application Patterns](application-patterns.md) - RAG, routing, approval
  workflows, message compaction, multi-agent work, and production boundaries.
- [Workflow Patterns](workflow-patterns.md) - sequential calls, routing,
  parallel work, evaluator-optimizer loops, agents as orchestrators, and manual
  loops.
- [Memory](memory.md) - conversation memory, summaries, retrieval memory,
  memory tools, and context injection.
- [Coding Agents](coding-agents.md) - guidance for code-editing agents built
  on this SDK.

## AI SDK UI

- [UI And Streams](ui-and-streams.md) - framework-neutral UI messages,
  stream conversion, typed tool UI, chat state, persistence, and framework
  facades.
- [UI Stream Protocols](ui-stream-protocols.md) - text streams, UI message
  streams, host writers, custom UI streams, ids, metadata, and finish handling.
- [Chatbots](chatbots.md) - direct chat transports, `ChatSession`, message
  persistence, tool rendering, approval, resume, and text-only transports.
- [Completion And Object UI](completion-and-object-ui.md) - completion state,
  completion facades, partial structured object state, and object facades.
- [Framework Facades](framework-facades.md) - React, Vue, Svelte, Angular, and
  RSC-shaped parity surfaces.

## Integrations

- [Model Context Protocol](mcp.md) - MCP transports, tools, resources,
  prompts, elicitation, and cleanup.
- [Testing And Release](testing-and-release.md) - deterministic tests,
  generated parity ledgers, validation commands, and publication workflow.
- [Troubleshooting](troubleshooting.md) - common failures and where to inspect
  first.

## Suggested Paths

For a first server integration:

1. [Foundations](foundations.md)
2. [Getting Started](getting-started.md)
3. [Settings And Provider Options](settings-and-provider-options.md)
4. [Providers And Models](providers.md)
5. [Provider Management](provider-management.md)
6. [Core](core.md)
7. [Streaming](streaming.md)
8. [Advanced Streaming](advanced-streaming.md)

For a tool-using agent:

1. [Tools](tools.md)
2. [Agents](agents.md)
3. [Lifecycle And Events](lifecycle-and-events.md)
4. [Prompt Engineering](prompt-engineering.md)
5. [Structured Output](structured-output.md)
6. [Workflow Patterns](workflow-patterns.md)
7. [Memory](memory.md)
8. [Application Patterns](application-patterns.md)

For a chat UI:

1. [UI And Streams](ui-and-streams.md)
2. [UI Stream Protocols](ui-stream-protocols.md)
3. [Chatbots](chatbots.md)
4. [Completion And Object UI](completion-and-object-ui.md)
5. [Framework Facades](framework-facades.md)
6. [Prompts And Messages](prompts-and-messages.md)
7. [Tools](tools.md)

For production hardening:

1. [Middleware And Telemetry](middleware-and-telemetry.md)
2. [DevTools](devtools.md)
3. [Utilities](utilities.md)
4. [Error Handling](error-handling.md)
5. [Testing And Release](testing-and-release.md)
6. [Troubleshooting](troubleshooting.md)

## Reference Material

- [llms.txt](../../llms.txt) - agent-oriented project context.
- [Interface Contract](../../INTERFACE_CONTRACT.md) - public API compatibility
  and semantic promises.
- [AI SDK Port Map](../AISDK_PORT.md) - Kotlin mapping for Vercel AI SDK v6
  concepts.
- [Architecture Decisions](../AISDK_PORT_DECISIONS.md) - durable architecture
  records.
- [Parity Ledgers](../parity/) - generated TypeScript-to-Kotlin package and
  export coverage.
- [Kotlin SDK Engineering Standard](../KOTLIN_SDK_BEST_PRACTICES.md) -
  engineering rules for this port.

## Upstream Reference

- Vercel AI SDK docs: <https://ai-sdk.dev/docs>
- Upstream `llms.txt`: <https://ai-sdk.dev/llms.txt>
- AI SDK providers: <https://ai-sdk.dev/providers>
