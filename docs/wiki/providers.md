# Providers And Models

Providers adapt concrete model services to the common model interfaces. The
current artifact folds provider package surfaces into the root module while
preserving boundaries that can become separate artifacts later.

## Choosing A Provider Path

| Path | Best for | Notes |
|---|---|---|
| Gateway | Multi-provider routing by model id and centralized gateway metadata. | Use `createGateway()` or `gateway`. |
| OpenAI-compatible | OpenAI-compatible HTTP APIs, local model servers, and simple provider facades. | Use `createOpenAICompatible()`. |
| Dedicated provider facade | Provider-specific auth, options, request conversion, hosted tools, media APIs, or metadata. | Use `createAnthropic()`, `createGoogleGenerativeAI()`, `createXai()`, etc. |
| Custom provider | Internal services or unsupported providers. | Implement `Provider` or use `customProvider`. |

## Gateway

Gateway exposes language, embedding, image, video, and reranking model
factories plus metadata, credits, spend, generation-info, hosted tool
descriptors, gateway errors, and Ktor transport support.

Keep credentials and organization policy in the host environment. The SDK
accepts settings and transports; it should not own secret storage.

## OpenAI-Compatible

`createOpenAICompatible` supports common chat, completion, embedding, image,
speech, and transcription routes over an injected HTTP client boundary.

Use this path when provider behavior matches the OpenAI-compatible contract.
Move provider-specific request or usage corrections into a dedicated facade if
the compatibility layer starts accumulating conditional logic.

## Dedicated Facades

The parity ledgers list every folded provider package and export count. Current
facades include Vercel AI Gateway, OpenAI, Open Responses, Anthropic, Amazon
Bedrock, Azure, Google, Google Vertex, xAI, Mistral, Cohere, Deepgram,
ElevenLabs, Fal, Luma, Replicate, Together.ai, Voyage, and more.

Use [Parity Ledgers](../parity/README.md) as the release gate for provider
surface completeness.

## Provider Registry

Use `createProviderRegistry` when application code should resolve providers
from string model identifiers:

```kotlin
val registry = createProviderRegistry(
    providers = mapOf(
        "gateway" to gateway,
        "openai" to openai,
    ),
)

val model = registry.languageModel("gateway:anthropic/claude-sonnet-4.5")
```

Custom separators are supported through the registry settings.

## Custom Providers

For internal or unsupported providers, implement the relevant model interfaces:

- `LanguageModel`
- `EmbeddingModel`
- `ImageModel`
- `SpeechModel`
- `TranscriptionModel`
- `VideoModel`
- `RerankingModel`

Return provider metadata, warnings, request metadata, response metadata, usage,
and raw finish reasons where available. Tests should use fake transports or
Ktor `MockEngine` instead of live network calls.

## Platform Transports

Common code owns contracts. Hosts own concrete transport choices:

- Ktor, OkHttp, CIO, or platform HTTP clients.
- Secure key storage.
- TLS, proxy, and retry policy.
- JVM/Android process-backed MCP stdio.
- iOS boundaries where process spawning is not available.
