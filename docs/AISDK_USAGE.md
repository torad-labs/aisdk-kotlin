# Usage Guide

This package is a KMP library. Put agent and tool definitions in shared code, then bind the same agent from Android, iOS, JVM services, or tests.

## Gradle

From a published artifact:

```kotlin
dependencies {
    implementation("ai.torad:aisdk-kotlin:<version>")
}
```

From a source checkout in a composite build:

```kotlin
// settings.gradle.kts
includeBuild("../aisdk-kotlin")
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.torad:aisdk-kotlin:0.1.0-SNAPSHOT")
}
```

## Define A Tool

Tools should live in dedicated files. Inputs and outputs are normal `@Serializable` Kotlin types.

```kotlin
package example.agent.tools

import ai.torad.aisdk.tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
data class SearchInput(val query: String)

@Serializable
data class SearchResult(val title: String, val url: String, val snippet: String)

fun searchDocsTool(search: SearchService) = tool<SearchInput, List<SearchResult>, AppContext>(
    name = "searchDocs",
    description = "Search the product documentation.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
) { input ->
    search.query(input.query)
}
```

For native Kotlin call sites, the reified tool and tool-set builders remove
the serializer boilerplate and reject duplicate names:

```kotlin
val tools = toolSet<AppContext> {
    tool<SearchInput, List<SearchResult>>(
        name = "searchDocs",
        description = "Search the product documentation.",
    ) { input ->
        search.query(input.query)
    }
}
```

## Compose An Agent

```kotlin
package example.agent

import ai.torad.aisdk.Agent
import ai.torad.aisdk.LanguageModel
import ai.torad.aisdk.ToolLoopAgent
import ai.torad.aisdk.stepCountIs
import ai.torad.aisdk.toolSetOf
import ai.torad.aisdk.wrapLanguageModel
import example.agent.tools.searchDocsTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
data class AppContext(val userId: String, val workspaceId: String)

fun supportAgent(
    model: LanguageModel,
    search: SearchService,
): Agent<AppContext, String> = ToolLoopAgent(
    model = wrapLanguageModel(model, listOf(/* logging, retries, provider quirks */)),
    instructions = "Answer using available tools when current documentation is needed.",
    tools = toolSetOf(searchDocsTool(search)),
    stopWhen = stepCountIs(8),
    callOptionsSchema = serializer<AppContext>(),
)
```

## Generate

```kotlin
val result = agent.generate(
    prompt = "How do I configure streaming?",
    options = AppContext(userId = "u_123", workspaceId = "w_123"),
)

println(result.text)
```

## Kotlin-First Text Calls

For direct model calls, prefer grouped settings and request builders. The
v6-shaped named-argument calls remain available for compatibility.

```kotlin
val result = generateText(model) {
    system("Answer as a concise product engineer.")
    message(userMessage("Use Kotlin examples."))
    prompt("How do I configure streaming?")
    settings {
        temperature = 0.2f
        maxOutputTokens = 600
        stopSequence("</answer>")
        providerOptions {
            provider("openai", OpenAiTuning(reasoningEffort = "high"))
        }
    }
}
```

Provider options and metadata can stay typed at application boundaries:

```kotlin
@Serializable
data class OpenAiTuning(val reasoningEffort: String)

@Serializable
data class OpenAiMetadata(val cacheHit: Boolean)

val options = buildProviderOptions {
    provider("openai", OpenAiTuning(reasoningEffort = "high"))
    provider("anthropic") {
        putJson("cacheControl", CacheControl(type = "ephemeral"))
    }
}

val cacheHit = result.providerMetadataAs<OpenAiMetadata>("openai")?.cacheHit
```

Structured output keeps the same native shape:

```kotlin
@Serializable
data class Recipe(val name: String, val ingredients: List<String>)

val recipe = generateText(
    model = model,
    output = outputObj<Recipe>(serializer(), name = "Recipe"),
) {
    prompt("Generate a recipe for chocolate cake.")
}.output
```

Use typed model references when a provider registry is routing calls:

```kotlin
val registry = createProviderRegistry(
    "openai" to openAiProvider,
    "anthropic" to anthropicProvider,
)

val model = registry.languageModel(modelRef("openai:gpt-5"))
```

For generated or attached files, prefer `FileData` at application
boundaries and convert to provider wire shapes only when calling a model:

```kotlin
val inputImage = imageGenerationFile(
    FileData.Bytes(
        bytes = editedImageBytes,
        mediaType = "image/png",
        filename = "mask.png",
    ),
)
```

## Stream

```kotlin
agent.stream(prompt, options = options).collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> appendText(event.text)
        is StreamEvent.ToolCall -> showToolPending(event.toolName, event.inputJson)
        is StreamEvent.ToolResult -> showToolResult(event.toolName, event.outputJson)
        is StreamEvent.Finish -> markComplete()
        else -> Unit
    }
}
```

## Render UI Messages

`streamToUiMessages` converts low-level stream events into growing `UIMessage` snapshots. The core library does not depend on Compose or SwiftUI.

```kotlin
val assistantMessages: Flow<UIMessage> = streamToUiMessages(
    events = agent.stream(prompt, options),
    assistantMessageId = "assistant-${turn.index}",
)
```

For UI state holders, use `ChatSession` to observe a single `StateFlow`:

```kotlin
val session = chatSession(
    id = "support",
    transport = DirectChatTransport { request ->
        streamToUiMessages(
            events = agent.stream(messages = convertToModelMessages(request.messages)),
            assistantMessageId = getResponseUiMessageId(request.messages),
        )
    },
)

session.state.collect { state ->
    renderMessages(state.messages)
    renderLoading(state.isStreaming)
}
```

For agent-level state, use `AgentSession` with a caller-owned scope:

```kotlin
val session = supportAgent.session(viewModelScope)

session.submit(
    prompt = "How do I configure streaming?",
    options = AppContext(userId = "u_123", workspaceId = "w_123"),
)

session.state.collect { state ->
    renderMessages(state.messages)
    renderPendingApprovals(state.pendingApprovals)
}
```

For live text updates, use the streaming session path. `session.cancel()`
cancels the coroutine job and aborts providers/tools that observe the SDK
abort signal:

```kotlin
val job = session.submitStreaming(
    prompt = "How do I configure streaming?",
    options = AppContext(userId = "u_123", workspaceId = "w_123"),
)

stopButton.onClick { session.cancel() }
```

Coroutine callers can bind cancellation directly:

```kotlin
val signal = coroutineScope.asAbortSignal()

generateText(
    model = model,
    prompt = "Summarize the current document.",
    settings = callSettings {
        abortSignal = signal
    },
)
```

Renderer dispatch can stay string-based:

```kotlin
fun render(part: UIMessagePart) {
    when (part) {
        is UIMessagePart.Text -> renderText(part.text)
        is UIMessagePart.ToolUI -> when (part.toolName) {
            "searchDocs" -> renderSearchResult(part.outputAs<List<SearchResult>>())
            else -> renderUnknownTool(part.toolName)
        }
        is UIMessagePart.Reasoning -> renderReasoning(part.text)
        is UIMessagePart.Error -> renderError(part.message)
        else -> Unit
    }
}
```

Or use typed dispatch:

```kotlin
val handlers = buildToolPartHandlerRegistry<RenderNode>(
    fallback = { part -> UnknownToolNode(part.toolName) },
) {
    register(searchDocsTool) { invocation ->
        SearchResultsNode(invocation.output.orEmpty(), invocation.state)
    }
}
```

## Cancellation

```kotlin
val controller = AbortController()

scope.launch {
    agent.stream(prompt, options, abortSignal = controller.signal).collect { event ->
        render(event)
    }
}

controller.abort()
```

The signal flows through `ToolExecutionContext` to tools and subagents, so cancellation is hierarchical.

## Tool Approval

Use `needsApproval` for tools that change external state.

```kotlin
val sendMessageTool = tool<SendInput, SendResult, AppContext>(
    name = "sendMessage",
    description = "Send a message to a user.",
    inputSerializer = serializer(),
    outputSerializer = serializer(),
    needsApproval = { input, _ -> input.body.length > 100 },
) { input ->
    messaging.send(input.recipientId, input.body)
    SendResult(sent = true)
}
```

When approval is needed, the loop returns. Persist or render `pendingApprovals`, then resume with approval response messages:

```kotlin
val first = agent.generate(prompt = prompt, options = options)
val responses = first.pendingApprovals.map { pending ->
    val approved = approvalUi.ask(pending.toolName, pending.input)
    toolApprovalResponseMessage(
        toolCallId = pending.toolCallId,
        approved = approved,
        reason = if (approved) null else "user denied",
        approvalId = pending.approvalId,
    )
}

val resumed = agent.generate(
    messages = first.messages + responses,
    options = options,
)
```

## Structured Output

Structured output goes through `generateText` plus `Output`.

```kotlin
@Serializable
data class Recipe(val name: String, val ingredients: List<String>)

val result = generateText(
    model = model,
    prompt = "Generate a recipe for chocolate cake.",
    output = Output.obj(serializer<Recipe>()),
)

val recipe: Recipe = result.output
```
