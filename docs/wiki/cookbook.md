# Cookbook

Short examples for common AI SDK Kotlin tasks.

## Generate Text With A Provider

```kotlin
val provider = createOpenAICompatible(
    client = httpClient,
    settings = OpenAICompatibleProviderSettings(
        name = "local",
        baseUrl = "http://localhost:11434/v1",
        apiKey = localApiKey,
    ),
)

val result = generateText(
    model = provider.chatModel("llama3.2"),
    system = "Be concise.",
    prompt = "Explain Kotlin Flow in two sentences.",
)
```

Read next: [Providers And Models](providers.md), [Core](core.md).

## Set Defaults And Provider Options

```kotlin
val settings = callSettings {
    temperature = 0.2f
    maxOutputTokens = 800
    providerOptions {
        provider("openai") {
            put("reasoningEffort", JsonPrimitive("medium"))
        }
    }
}

val result = generateText(model = model, settings = settings) {
    system("Answer as an SDK maintainer.")
    prompt("Explain stream adapters.")
}
```

Read next: [Settings And Provider Options](settings-and-provider-options.md).

## Route Through Gateway

```kotlin
val gateway = createGateway(
    GatewayProviderSettings(
        apiKey = gatewayApiKey,
        transport = KtorGatewayTransport(httpClient),
    ),
)

val result = generateText(
    model = gateway.languageModel("anthropic/claude-sonnet-4.5"),
    prompt = "Compare stream adapters.",
    providerOptions = buildProviderOptions {
        provider("gateway") {
            put("only", JsonArray(listOf(JsonPrimitive("anthropic"))))
        }
    },
)
```

Read next: [Provider Management](provider-management.md), [Providers And Models](providers.md).

## Build A Prompt With Retrieval Context

```kotlin
val hits = docs.search(question)

val answer = generateText(
    model = model,
    system = "Answer using retrieved docs. Cite doc ids.",
    prompt = """
        Question:
        $question

        Retrieved docs:
        ${hits.joinToString("\n\n") { "[${it.id}] ${it.text}" }}
    """.trimIndent(),
)
```

Read next: [Prompt Engineering](prompt-engineering.md).

## Stream Into UI Messages

```kotlin
val result = streamTextResult(
    model = model,
    prompt = "Walk through a tool-calling loop.",
)

val messages = streamToUiMessages(
    events = result.fullStream,
    assistantMessageId = "assistant-1",
)
```

Read next: [Streaming](streaming.md), [UI And Streams](ui-and-streams.md).

## Smooth A Text Stream

```kotlin
val events = smoothStream(
    upstream = streamText(model = model, prompt = prompt),
    chunkBy = ChunkBy.Word,
    delayMs = 15,
)

events.collect { event ->
    render(event)
}
```

Read next: [Utilities](utilities.md), [Advanced Streaming](advanced-streaming.md).

## Create A Custom UI Message Stream

```kotlin
val stream = createUiMessageStream {
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
val messageId = generateId(prefix = "msg")

val toolId = createIdGenerator(prefix = "tool", size = 12)
    .generate()
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
)
```

Read next: [Tools](tools.md), [Agents](agents.md).

## Require Approval For A Write Tool

```kotlin
val archiveProject = Tool<ArchiveInput, ArchiveResult, AppContext>(
    name = "archiveProject",
    description = "Archive a project by id.",
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

val decision = generateText(
    model = model,
    prompt = "Route this request: user needs invoice copy.",
    output = outputObj(serializer<RouteDecision>()),
).output
```

Read next: [Structured Output](structured-output.md).

## Embed And Rerank Documents

```kotlin
val embeddings = embedMany(
    model = embeddingModel,
    values = docs.map { it.text },
    maxParallelCalls = 4,
)

val candidates = vectorStore.search(queryVector, limit = 20)

val reranked = rerank(
    model = rerankingModel,
    query = "How do I resume a chat stream?",
    documents = candidates.map { it.text },
    topN = 5,
)
```

Read next: [Model Families](model-families.md), [Application Patterns](application-patterns.md).

## Generate An Image

```kotlin
val image = generateImage(
    model = imageModel,
    prompt = "A clean architecture diagram for an SDK docs page.",
    aspectRatio = "16:9",
).image
```

Read next: [Model Families](model-families.md).

## Connect MCP Tools

```kotlin
val mcp = createMCPClient(
    MCPClientConfig(
        transport = createMcpTransport(
            client = httpClient,
            config = MCPTransportConfig(
                type = MCPTransportKind.Http,
                url = "https://tools.example.com/mcp",
            ),
        ),
    ),
)

class McpAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
    ToolLoopAgent<AppContext, String>(
        model = model,
        tools = tools,
        stopWhen = StepCountIs(8),
    )

val agent = McpAgent(model, mcp.tools<AppContext>())
```

Read next: [Model Context Protocol](mcp.md).

## Test With A Custom Provider

```kotlin
val provider = customProvider(
    providerId = "test",
    languageModels = mapOf(
        "small" to mockLanguageModelTextOnly("ok"),
    ),
)

val result = generateText(
    model = provider.languageModel("small"),
    prompt = "anything",
)

assertEquals("ok", result.text)
```

Read next: [Testing And Release](testing-and-release.md).

## Observe One Agent Flow

```kotlin
val result = agent.generate(
    prompt = prompt,
    options = context,
    hooks = AgentCallHooks(
        onStepFinish = { event ->
            metrics.tokens(event.step.usage.totalTokens)
        },
        onError = { event ->
            errors.record(event.source.name, event.error)
        },
    ),
)
```

Read next: [Middleware And Telemetry](middleware-and-telemetry.md).

## Catch Provider And SDK Errors

```kotlin
try {
    val result = generateText(model = model, prompt = prompt)
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
streamText(model = model, prompt = prompt).collect { event ->
    println("${event::class.simpleName}: $event")
}
```

For more structured diagnostics, wrap the model with `devToolsMiddleware`.

Read next: [Troubleshooting](troubleshooting.md), [DevTools](devtools.md), [Middleware And Telemetry](middleware-and-telemetry.md).

## Record A Local Model Run

```kotlin
val recorder = InMemoryDevToolsRecorder()

val inspected = wrapLanguageModel(
    model = model,
    middlewares = listOf(devToolsMiddleware(recorder)),
)

generateText(model = inspected, prompt = "Explain retries.")

recorder.results.values.forEach { result ->
    println("${result.stepId}: ${result.durationMs}ms")
}
```

Read next: [DevTools](devtools.md), [Testing And Release](testing-and-release.md).

## Observe Agent Lifecycle

```kotlin
val result = agent.generate(
    prompt = prompt,
    options = context,
    hooks = AgentCallHooks(
        onStepFinish = { event ->
            metrics.tokens(event.step.usage.totalTokens)
        },
        onFinish = { event ->
            messageStore.save(event.messages)
        },
    ),
)
```

Read next: [Lifecycle And Events](lifecycle-and-events.md).

## Use Framework-Shaped Chat Helpers

```kotlin
val helpers = ai.torad.aisdk.react.useChat(
    ai.torad.aisdk.react.UseChatOptions(
        id = "support",
        transport = transport,
        initialMessages = savedMessages,
    ),
)

helpers.sendMessage(userMessage).collect { message ->
    render(message)
}
```

Read next: [Framework Facades](framework-facades.md), [Chatbots](chatbots.md).

## Use Completion For One Text Field

```kotlin
val completion = Completion(
    UseCompletionOptions(
        transport = DirectCompletionTransport { request ->
            flowOf("Draft for: ${request.prompt}")
        },
    ),
)

val text = completion.complete("Write a release title.")
```

Read next: [Completion And Object UI](completion-and-object-ui.md).
