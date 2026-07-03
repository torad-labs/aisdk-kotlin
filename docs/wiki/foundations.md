# Foundations

AI SDK Kotlin follows the same mental model as Vercel AI SDK v6: choose a
provider, pick a model, send prompts or messages, optionally add tools or
structured output, and decide whether the result should stream into a UI.

This page is the map. Use the linked pages for deeper examples.

## Core Objects

| Concept | Kotlin type | Use it when |
|---|---|---|
| Provider | `Provider` | You need models from Gateway, OpenAI-compatible APIs, dedicated provider facades, or a custom test provider. |
| Model | `LanguageModel`, `EmbeddingModel`, `ImageModel`, etc. | You are selecting a provider-neutral model for agents or high-level generation helpers. |
| Prompt | `prompt`, `system`, `messages` | You are sending model input. |
| Message | `ModelMessage` | You are preserving a conversation, tool result, source, file, or approval decision. |
| Tool | `Tool<TInput, TOutput, TContext>` | The model needs to call app code or a provider-side action. |
| Output | `Output<T>` | The model should return validated structured data. |
| Stream | `Flow<StreamEvent>` | You need incremental text, reasoning, tool state, or UI updates. |
| UI message | `UIMessage` | A host app wants a renderable, persistable chat history. |

## The Smallest Path

```kotlin
val model = provider.languageModel("anthropic/claude-sonnet-4.5")
val result = TextGenerator(model)
    .generate(GenerationInput.Prompt("What is AI SDK Kotlin?"))
    .first()

println(result.text)
```

Add structured output when the model should return typed data:

```kotlin
val result = TextGenerator(model)
    .generate(
        GenerationInput.Messages(GenerationInput.NonEmptyMessages.from(savedMessages)),
        Output.obj(serializer<Answer>()),
    )
    .first()
```

Use an agent when the model should call tools:

```kotlin
class AnswerAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
    ToolLoopAgent<AppContext, Answer>(
        model = model,
        instructions = "Search docs before answering.",
        tools = tools,
        output = Output.obj(serializer<Answer>()),
        stopWhen = StepCountIs(6),
    )

val agent = AnswerAgent(model, ToolSet(searchDocs))
```

## Kotlin Differences From Upstream

- Streams are Kotlin `Flow` values.
- Schemas come from `kotlinx.serialization` serializers.
- `Output.object()` is `Output.obj(...)` because `object` is a Kotlin keyword.
- UI primitives are framework-neutral. Compose, SwiftUI, server responses, and tests can all render the same `UIMessage` values.
- Common code receives settings and transports. Hosts own secrets, HTTP clients, storage, and platform-specific process support.

## Recommended Reading Paths

For a first integration:

1. [Getting Started](getting-started.md)
2. [Settings And Provider Options](settings-and-provider-options.md)
3. [Providers And Models](providers.md)
4. [Core](core.md)
5. [Prompts And Messages](prompts-and-messages.md)

For a tool-using agent:

1. [Tools](tools.md)
2. [Agents](agents.md)
3. [Prompt Engineering](prompt-engineering.md)
4. [Workflow Patterns](workflow-patterns.md)
5. [Memory](memory.md)
6. [Application Patterns](application-patterns.md)
7. [Chatbots](chatbots.md)

For a UI:

1. [Streaming](streaming.md)
2. [Advanced Streaming](advanced-streaming.md)
3. [UI And Streams](ui-and-streams.md)
4. [UI Stream Protocols](ui-stream-protocols.md)
5. [Chatbots](chatbots.md)

## Tips

- Start with `TextGenerator.generate` until the app needs visible progress. Move to `TextGenerator.streamResult` when you need adapters or replayable streams.
- Keep prompts on `Agent.generate` / `Agent.stream` or `TextGenerator`. Direct `LanguageModel.generate`, `LanguageModel.stream`, and `LanguageModel.streamResult` calls are low-level provider APIs and require `@OptIn(LowLevelLanguageModelApi::class)`.
- Persist messages at boundaries. Approval state, tool results, files, and sources all belong in the message log.
- Use `Output` instead of parsing text. `TextGenerator.generate(input, output)` returns a typed final value; `StructuredObjectGenerator` handles typed partial streaming.
- Keep provider differences in provider options or middleware. Agent code should not branch on model ids.

## Related

- [Prompts And Messages](prompts-and-messages.md)
- [Prompt Engineering](prompt-engineering.md)
- [Settings And Provider Options](settings-and-provider-options.md)
- [Tools](tools.md)
- [Workflow Patterns](workflow-patterns.md)
- [Structured Output](structured-output.md)
- [Streaming](streaming.md)
- [Memory](memory.md)
- [Advanced Streaming](advanced-streaming.md)
- [Error Handling](error-handling.md)
- [Lifecycle And Events](lifecycle-and-events.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
