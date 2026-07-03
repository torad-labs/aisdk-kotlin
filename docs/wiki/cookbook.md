# Cookbook

Short examples for common AI SDK Kotlin tasks.

## Generate Text With A Provider

```kotlin
val provider = OpenAICompatible(
    client = httpClient,
    settings = OpenAICompatibleProviderSettings {
        name("local")
        baseURL("http://localhost:11434/v1")
        apiKey(localApiKey)
    },
)

val result = TextGenerator(
    provider.chatModel("llama3.2"),
    CallConfig {
        maxOutputTokens(800)
    },
).generate(
    GenerationInput.Messages(
        GenerationInput.NonEmptyMessages.of(
            SystemMessage("Be concise."),
            UserMessage("Explain Kotlin Flow in two sentences."),
        ),
    ),
).first()
```

Read next: [Providers And Models](providers.md), [Core](core.md).

## Set Defaults And Provider Options

```kotlin
val config = CallConfig {
    temperature(0.2f)
    maxOutputTokens(800)
    providerOptions(
        ProviderOptions.ofPairs(
            "openai" to buildJsonObject {
                put("reasoningEffort", JsonPrimitive("medium"))
            },
        ),
    )
}

val result = TextGenerator(model, config).generate(
    GenerationInput.Messages(
        GenerationInput.NonEmptyMessages.of(
            SystemMessage("Answer as an SDK maintainer."),
            UserMessage("Explain stream adapters."),
        ),
    ),
).first()
```

Read next: [Settings And Provider Options](settings-and-provider-options.md).

## Route Through Gateway

```kotlin
val gateway = Gateway(
    GatewayProviderSettings {
        apiKey(gatewayApiKey)
        transport(KtorGatewayTransport(httpClient))
    },
)

val result = TextGenerator(
    gateway.languageModel("anthropic/claude-sonnet-4.5"),
    CallConfig {
        providerOptions(
            ProviderOptions.ofPairs(
                "gateway" to buildJsonObject {
                    put("only", JsonArray(listOf(JsonPrimitive("anthropic"))))
                },
            ),
        )
    },
).generate(GenerationInput.Prompt("Compare stream adapters.")).first()
```

Read next: [Provider Management](provider-management.md), [Providers And Models](providers.md).

## Build A Prompt With Retrieval Context

```kotlin
val hits = docs.search(question)

val answer = TextGenerator(model).generate(
    GenerationInput.Messages(
        GenerationInput.NonEmptyMessages.of(
            SystemMessage("Answer using retrieved docs. Cite doc ids."),
            UserMessage(
                """
                Question:
                $question

                Retrieved docs:
                ${hits.joinToString("\n\n") { "[${it.id}] ${it.text}" }}
                """.trimIndent(),
            ),
        ),
    ),
).first()
```

Read next: [Prompt Engineering](prompt-engineering.md).

## Stream Into UI Messages

```kotlin
val result = TextGenerator(model).streamResult(
    GenerationInput.Prompt("Walk through a tool-calling loop."),
)

val messages = StreamToUiMessages(
    events = result.fullStream,
    assistantMessageId = "assistant-1",
)
```

Read next: [Streaming](streaming.md), [UI And Streams](ui-and-streams.md).

## Shape A Text Stream For Display

```kotlin
TextGenerator(model)
    .stream(GenerationInput.Prompt(prompt))
    .filterIsInstance<StreamEvent.TextDelta>()
    .map { it.text }
    .collect { chunk ->
        render(chunk)
    }
```

Read next: [Utilities](utilities.md), [Advanced Streaming](advanced-streaming.md).

## Create A Custom UI Message Stream

```kotlin
val stream = CreateUiMessageStream {
    write(
        UIMessage(
            id = "status-1",
            role = UIMessageRole.Assistant,
            parts = listOf(
                UIMessagePart.Data(
                    type = "status",
                    data = JsonPrimitive("running"),
                    transient = true,
                ),
            ),
        ),
    )
    merge(agentMessages)
}
```

Read next: [UI Stream Protocols](ui-stream-protocols.md).

## Create Stable UI Ids

```kotlin
val messageId = IdGenerator.generate(prefix = "msg")

val toolId = IdGenerator {
    prefix("tool")
    size(12)
}.generate()
```

Use stable ids when storing UI messages, tool calls, and stream state.

Read next: [Utilities](utilities.md), [UI Stream Protocols](ui-stream-protocols.md).

## Build A Tool-Using Agent

```kotlin
class DocsAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
    ToolLoopAgent<AppContext, String>(
        model = model,
        instructions = "Search docs before answering SDK questions.",
        tools = tools,
        stopWhen = StepCountIs(6),
    )

val agent = DocsAgent(model, ToolSet(searchDocs))
val result = agent.generate(
    prompt = "How do I validate UI messages?",
    options = AppContext(workspaceId = "docs"),
).first()
```

Read next: [Tools](tools.md), [Agents](agents.md).

## Require Approval For A Write Tool

```kotlin
val archiveProject = Tool<ArchiveInput, ArchiveResult, AppContext>(
    name = "archiveProject",
    description = "Archive a project by id.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
    needsApproval = { _, options ->
        options.experimental_context?.role != "admin"
    },
) { input ->
    projects.archive(input.projectId)
}
```

Read next: [Tools](tools.md), [Chatbots](chatbots.md).

## Return Typed Data

```kotlin
@Serializable
data class RouteDecision(val route: String, val confidence: Double)

val decision = TextGenerator(model).generate(
    GenerationInput.Prompt("Route this request: user needs invoice copy."),
    output = Output.obj(serializer<RouteDecision>(), name = "RouteDecision"),
).first().output
```

Read next: [Structured Output](structured-output.md).

## Embed And Rerank Documents

```kotlin
val embeddings = Embedding.embedMany(
    model = embeddingModel,
    values = docs.map { it.text },
    maxParallelCalls = 4,
)

val candidates = vectorStore.search(queryVector, limit = 20)

val reranked = Reranking.rerank(
    model = rerankingModel,
    query = "How do I resume a chat stream?",
    documents = candidates.map { it.text },
    topN = 5,
)
```

Read next: [Model Families](model-families.md), [Application Patterns](application-patterns.md).

## Generate An Image

```kotlin
val image = ImageGeneration.generateImage(
    model = imageModel,
    prompt = "A clean architecture diagram for an SDK docs page.",
    aspectRatio = "16:9",
).image
```

Read next: [Model Families](model-families.md).

## Connect MCP Tools

```kotlin
val mcp = CreateMCPClient(
    MCPClientConfig {
        transport(
            CreateMcpTransport(
                client = httpClient,
                config = MCPTransportConfig {
                    type(MCPTransportKind.Http)
                    url("https://tools.example.com/mcp")
                },
            ),
        )
    },
)

class McpAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
    ToolLoopAgent<AppContext, String>(
        model = model,
        instructions = "Use MCP tools when they help.",
        tools = tools,
        stopWhen = StepCountIs(8),
    )

val agent = McpAgent(model, mcp.tools<AppContext>())
```

Read next: [Model Context Protocol](mcp.md).

## Test With A Custom Provider

```kotlin
val provider = CustomProvider {
    providerId("test")
    languageModel("small", MockLanguageModelTextOnly("ok"))
}

val result = TextGenerator(provider.languageModel("small"))
    .generate(GenerationInput.Prompt("anything"))
    .first()

assertEquals("ok", result.text)
```

Read next: [Testing And Release](testing-and-release.md).

## Observe One Agent Flow

```kotlin
agent.collectAgentEvents(
    prompt = prompt,
    options = context,
) { event ->
    when (event) {
        is AgentEvent.StepFinished -> metrics.tokens(event.step.usage.totalTokens)
        is AgentEvent.Errored -> errors.record(event.source.name, event.error)
        else -> Unit
    }
}
```

Read next: [Middleware And Telemetry](middleware-and-telemetry.md).

## Catch Provider And SDK Errors

```kotlin
try {
    val result = TextGenerator(model).generate(GenerationInput.Prompt(prompt)).first()
    render(result.text)
} catch (error: APICallError) {
    logger.warn("Provider call failed: ${error.statusCode}")
    if (error.isRetryable) retryQueue.enqueue(prompt)
} catch (error: AiSdkException) {
    logger.warn("SDK error: ${error.message}")
}
```

Read next: [Error Handling](error-handling.md).

## Debug A Stream

```kotlin
TextGenerator(model).stream(GenerationInput.Prompt(prompt)).collect { event ->
    println("${event::class.simpleName}: $event")
}
```

For more structured diagnostics, wrap the model with `DevToolsMiddleware`.

Read next: [Troubleshooting](troubleshooting.md), [DevTools](devtools.md), [Middleware And Telemetry](middleware-and-telemetry.md).

## Record A Local Model Run

```kotlin
val recorder = InMemoryDevToolsRecorder()

val inspected = WrapLanguageModel(
    model = model,
    middlewares = listOf(DevToolsMiddleware(recorder)),
)

TextGenerator(inspected).generate(GenerationInput.Prompt("Explain retries.")).first()

recorder.results.values.forEach { result ->
    println("${result.durationMs}ms")
}
```

Read next: [DevTools](devtools.md), [Testing And Release](testing-and-release.md).

## Observe Agent Lifecycle

```kotlin
agent.events(prompt = prompt, options = context).collect { event ->
    when (event) {
        is AgentEvent.StepFinished -> metrics.tokens(event.step.usage.totalTokens)
        is AgentEvent.Finished -> messageStore.save(event.messages)
        else -> Unit
    }
}
```

Read next: [Lifecycle And Events](lifecycle-and-events.md).

## Use Kotlin Chat State Helpers

```kotlin
val session = ChatSession(
    id = "support",
    initialMessages = savedMessages,
    transport = TextStreamChatTransport(handler = { request ->
        agent.stream(messages = request.messages).filterIsInstance<StreamEvent.TextDelta>().map { it.text }
    }),
)

session.sendMessage(userMessage).collect { message ->
    render(message)
}
```

Read next: [Chatbots](chatbots.md), [UI And Streams](ui-and-streams.md).

## Use Completion For One Text Field

```kotlin
val completion = Completion(
    UseCompletionOptions(block = {
        transport(
            object : CompletionTransport {
                override fun complete(request: CompletionRequest): Flow<String> =
                    flowOf("Draft for: ${request.prompt}")
            },
        )
    }),
)

val text = completion.complete("Write a release title.")
```

Read next: [Completion And Object UI](completion-and-object-ui.md).
