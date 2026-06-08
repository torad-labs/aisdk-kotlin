# Providers And Models

Providers adapt concrete model services to the common model interfaces. The
current artifact folds provider facades into the root module while preserving
package boundaries for a future split.

## Choose A Provider Path

| Path | Best for | Entry point |
|---|---|---|
| Gateway | Routing across providers by model id. | `createGateway()` / `gateway` |
| OpenAI-compatible | OpenAI-shaped HTTP APIs and local model servers. | `createOpenAICompatible()` |
| Dedicated facade | Provider-specific options, metadata, auth, tools, or media APIs. | `createAnthropic()`, `createGoogleGenerativeAI()`, etc. |
| Custom provider | Internal services, fakes, unsupported providers. | `customProvider(...)` |

## Gateway

Gateway exposes language, embedding, image, video, and reranking models plus
metadata, credits, spend reports, generation info, hosted tools, and Gateway
errors.

```kotlin
val gatewayProvider = createGateway(
    GatewayProviderSettings(
        apiKey = gatewayApiKey,
        transport = KtorGatewayTransport(client),
    ),
)

val model = gatewayProvider.languageModel("anthropic/claude-sonnet-4.5")
```

Common Kotlin cannot read process environment variables directly. Pass secrets
through settings, host config, or the `environment` map.

## OpenAI-Compatible

Use `createOpenAICompatible` when the service follows OpenAI-compatible chat,
completion, embedding, image, speech, or transcription routes.

```kotlin
val provider = createOpenAICompatible(
    client = client,
    settings = OpenAICompatibleProviderSettings(
        name = "local",
        baseUrl = "http://localhost:11434/v1",
        apiKey = localApiKey,
        includeUsage = true,
    ),
)

val model = provider.chatModel("llama3.2")
```

The settings include auth headers, query parameters, structured output support,
supported URL patterns, usage conversion, and request/response transforms for
providers that are almost OpenAI-compatible but need small corrections.

## Dedicated Facades

Dedicated facades are available for providers whose behavior needs custom
mapping. Current public factories include:

- `createOpenAI`, `createOpenResponses`, `createAzure`
- `createAnthropic`, `createAnthropicAws`
- `createAmazonBedrock`, `createBedrockAnthropic`, `createBedrockMantle`
- `createGoogleGenerativeAI`, `createVertex`, `createVertexAnthropic`,
  `createVertexMaas`, `createGoogleVertexXai`
- `createXai`, `createMistral`, `createCohere`, `createHuggingFace`
- `createDeepSeek`, `createGroq`, `createPerplexity`, `createTogetherAI`
- `createFal`, `createLuma`, `createReplicate`, `createBlackForestLabs`,
  `createKlingAI`, `createByteDance`, `createProdia`, `createQuiverAI`
- `createDeepgram`, `createElevenLabs`, `createGladia`, `createHume`,
  `createLMNT`, `createAssemblyAI`, `createRevai`
- `createVoyage`, `createAlibaba`, `createBaseten`, `createCerebras`,
  `createDeepInfra`, `createFireworks`, `createMoonshotAI`, `createVercel`

Some top-level singleton facades intentionally throw until configured with the
required client/settings. Prefer explicit `create...` factories in apps.

The generated [parity ledgers](../parity/README.md) list provider-package
coverage and export mapping.

## Provider Registry

Use `createProviderRegistry` when application code resolves models from string
ids.

```kotlin
val registry = createProviderRegistry(
    "gateway" to gatewayProvider,
    "openai" to openaiProvider,
    defaultProviderId = "gateway",
    languageModelMiddleware = listOf(loggingMiddleware(logger)),
)

val model = registry.languageModel("gateway:anthropic/claude-sonnet-4.5")
```

If only one provider is registered, a provider prefix is optional. With more
than one provider, include the prefix or set `defaultProviderId`.

## Custom Providers

Use `customProvider` for internal services, local models, fakes, and tests.

```kotlin
val provider = customProvider(
    providerId = "test",
    languageModels = mapOf("small" to mockLanguageModelTextOnly("ok")),
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
