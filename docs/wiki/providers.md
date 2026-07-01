# Providers And Models

Providers adapt concrete model services to the common model interfaces. The
current artifact folds provider facades into the root module while preserving
package boundaries for a future split.

## Choose A Provider Path

| Path | Best for | Entry point |
|---|---|---|
| Gateway | Routing across providers by model id. | `Gateway(...)` / `gateway` |
| OpenAI-compatible | OpenAI-shaped HTTP APIs and local model servers. | `OpenAICompatible(...)` |
| LiteRT-LM | On-device Android/JVM LiteRT-LM engines. | `LiteRTLanguageModel(...)` |
| Dedicated facade | Provider-specific options, metadata, auth, tools, or media APIs. | `Anthropic(...)`, `GoogleGenerativeAI(...)`, etc. |
| Custom provider | Internal services, fakes, unsupported providers. | `Provider(...)` |

## Gateway

Gateway exposes language, embedding, image, video, and reranking models plus
metadata, credits, spend reports, generation info, hosted tools, and Gateway
errors.

```kotlin
val gatewayProvider = Gateway(
    GatewayProviderSettings {
        apiKey(gatewayApiKey)
        transport(KtorGatewayTransport(client))
    },
)

val model = gatewayProvider.languageModel("anthropic/claude-sonnet-4.5")
```

Common Kotlin cannot read process environment variables directly. Pass secrets
through settings, host config, or the `environment` map.

## OpenAI-Compatible

Use `OpenAICompatible` when the service follows OpenAI-compatible chat,
completion, embedding, image, speech, or transcription routes.

```kotlin
val provider = OpenAICompatible(
    client = client,
    settings = OpenAICompatibleProviderSettings {
        name("local")
        baseUrl("http://localhost:11434/v1")
        apiKey(localApiKey)
        includeUsage(true)
    },
)

val model = provider.chatModel("llama3.2")
```

The settings include auth headers, query parameters, structured output support,
supported URL patterns, usage conversion, and request/response transforms for
providers that are almost OpenAI-compatible but need small corrections.

## LiteRT-LM

Use `LiteRTLanguageModel` for on-device LiteRT-LM engines. The SDK owns the
`LanguageModel` contract; the host app supplies a `LiteRTConversationFactory`
that maps the prepared request to Google LiteRT-LM's `Engine.createConversation`
and `Conversation.sendMessage` / `sendMessageAsync` APIs.

The prepared `LiteRTConversationRequest` mirrors LiteRT-LM's
`ConversationConfig`: `systemInstruction`, `initialMessages`, `tools`,
`samplerConfig`, `channels`, and `extraContext`. The adapter always sets
`automaticToolCalling = false`. LiteRT may return tool calls, but the SDK agent
executes tools, records tool results, handles approvals, and calls the model
again. This prevents mobile apps from accidentally bypassing the agent loop by
piping prompts or tool execution directly through the local model runtime.

Reasoning channels are preserved. By default `thinking` and `reasoning`
channels become `ContentPart.Reasoning` / `StreamEvent.ReasoningDelta`, while
normal LiteRT text content becomes text output. For Gemma-style prompt
templates, pass template context through provider options:

```kotlin
val params = LanguageModelCallParams {
    messages(listOf(UserMessage("Plan the next step.")))
    providerOptions(
        ProviderOptions.ofPairs(
            "litert" to buildJsonObject {
                put("enableThinking", JsonPrimitive(true))
                put("extraContext", buildJsonObject {
                    put("screen", JsonPrimitive("home"))
                })
            },
        ),
    )
}
```

## Dedicated Facades

Dedicated facades are available for providers whose behavior needs custom
mapping. Current public factories include:

- `OpenAI`, `OpenResponses`, `AzureOpenAI`
- `Anthropic`, `AnthropicAws`
- `AmazonBedrock`, `BedrockAnthropic`, `BedrockMantle`
- `GoogleGenerativeAI`, `GoogleVertex`, `GoogleVertexAnthropic`,
  `GoogleVertexMaas`, `GoogleVertexXai`
- `Xai`, `Mistral`, `Cohere`, `HuggingFace`
- `DeepSeek`, `Groq`, `Perplexity`, `TogetherAI`
- `Fal`, `Luma`, `Replicate`, `BlackForestLabs`,
  `KlingAI`, `ByteDance`, `Prodia`, `QuiverAI`
- `Deepgram`, `ElevenLabs`, `Gladia`, `Hume`,
  `LMNT`, `AssemblyAI`, `Revai`
- `Voyage`, `Alibaba`, `Baseten`, `Cerebras`,
  `DeepInfra`, `Fireworks`, `MoonshotAI`, `Vercel`

Some top-level singleton facades intentionally throw until configured with the
required client/settings. Prefer explicit PascalCase factories in apps.

The generated [parity ledgers](../parity/README.md) list provider-package
coverage and export mapping.

## Provider Registry

Use `ProviderRegistry` when application code resolves models from string
ids.

```kotlin
val registry = ProviderRegistry(
    providers = mapOf(
        "gateway" to gatewayProvider,
        "openai" to openaiProvider,
    ),
    defaultProviderId = "gateway",
    languageModelMiddleware = listOf(LoggingMiddleware(logger)),
)

val model = registry.languageModel("gateway:anthropic/claude-sonnet-4.5")
```

If only one provider is registered, a provider prefix is optional. With more
than one provider, include the prefix or set `defaultProviderId`.

## Custom Providers

Use `Provider` for internal services, local models, fakes, and tests.

```kotlin
val provider = Provider(
    providerId = "test",
    languageModels = mapOf("small" to MockLanguageModelTextOnly("ok")),
)

val model = provider.languageModel("small")
```

A custom provider can include language, embedding, image, speech,
transcription, reranking, and video models. It can also delegate missing model
ids to a fallback provider.

For usage examples across model families, see [Model Families](model-families.md).

## Model Interfaces

Providers implement only the model families they support:

- `LanguageModel`
- `EmbeddingModel`
- `ImageModel`
- `SpeechModel`
- `TranscriptionModel`
- `RerankingModel`
- `VideoModel`

For language models, `generate`, `stream`, and `streamResult` are low-level
execution methods. Provider implementations and tests opt in explicitly;
application code should pass `LanguageModel` values into agents or high-level
generation helpers.
If a provider exposes a public concrete `LanguageModel` implementation, annotate
its execution overrides with `@LowLevelLanguageModelApi` so direct calls on that
concrete type require the same explicit opt-in.

Each result should preserve provider metadata, warnings, request metadata,
response metadata, usage, and raw finish reasons where available.

## Host Responsibilities

The SDK accepts settings and transports. Hosts own:

- Secret storage.
- HTTP client selection.
- Proxy, TLS, and retry policy.
- Persistent DevTools storage.
- Process-backed MCP stdio on supported platforms.
- UI rendering and local persistence.

Use fake transports, deterministic mock models, and Ktor `MockEngine` in tests.

## Related

- [Getting Started](getting-started.md)
- [Settings And Provider Options](settings-and-provider-options.md)
- [Provider Management](provider-management.md)
- [Model Families](model-families.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
- [Model Context Protocol](mcp.md)
- [Testing And Release](testing-and-release.md)
