# Provider Management

Provider management is where apps decide how model ids resolve, how Gateway is
configured, and where provider-specific options belong.

## Choose The Smallest Provider Path

| Path | Use when |
|---|---|
| Gateway | You want one routing layer across many providers. |
| Dedicated facade | You need provider-specific auth, options, metadata, or media support. |
| OpenAI-compatible | The service exposes OpenAI-shaped routes. |
| Provider registry | App code resolves string ids at runtime. |
| Custom provider | You own the model service or need deterministic tests. |

## Gateway Provider

```kotlin
val gatewayProvider = Gateway(
    GatewayProviderSettings {
        apiKey(gatewayApiKey)
        transport(KtorGatewayTransport(httpClient))
    },
)

val model = gatewayProvider.languageModel("anthropic/claude-sonnet-4.5")
```

Common code cannot read environment variables. Pass secrets through host
configuration.

## Gateway Metadata

Gateway exposes operational metadata:

```kotlin
val models = gatewayProvider.getAvailableModels()
val credits = gatewayProvider.getCredits()
val spend = gatewayProvider.getSpendReport(
    GatewaySpendReportParams {
        startDate("2026-06-01")
        endDate("2026-06-08")
    },
)
val generation = gatewayProvider.getGenerationInfo(
    GatewayGenerationInfoParams {
        id("gen_123")
    },
)
```

Use these methods in diagnostics, admin tools, and billing dashboards. Avoid
calling them in hot rendering paths unless the result is cached.

## Gateway Routing Options

Gateway routing options are provider options:

```kotlin
val gatewayOptions = ProviderOptions.ofPairs(
    "gateway" to buildJsonObject {
        put("order", JsonArray(listOf(JsonPrimitive("vertex"), JsonPrimitive("anthropic"))))
        put("only", JsonArray(listOf(JsonPrimitive("vertex"), JsonPrimitive("anthropic"))))
    },
    "anthropic" to buildJsonObject {
        put("thinking", JsonPrimitive("enabled"))
    },
)

val result = TextGenerator(
    gatewayProvider.languageModel("anthropic/claude-sonnet-4.5"),
    CallConfig {
        providerOptions(gatewayOptions)
    },
)
    .generate(GenerationInput.Prompt("Explain stream adapters."))
    .first()
```

Use the actual provider key for provider-specific options that Gateway forwards
to the selected provider.

## Provider Registry

Use a registry when app code receives model ids from config or user choice:

```kotlin
val registry = ProviderRegistry.createProviderRegistry(
    "gateway" to gatewayProvider,
    "local" to localProvider,
    defaultProviderId = "gateway",
)

val model = registry.languageModel("gateway:anthropic/claude-sonnet-4.5")
```

With more than one provider, include a prefix or set `defaultProviderId`.

## Middleware In Registries

Apply middleware at the registry boundary when every resolved language model
needs the same behavior:

```kotlin
val registry = ProviderRegistry.createProviderRegistry(
    "gateway" to gatewayProvider,
    languageModelMiddleware = listOf(
        DefaultSettingsMiddleware(maxOutputTokens = 1_000),
        DevToolsMiddleware(recorder),
    ),
)
```

Use this for defaults, diagnostics, JSON extraction, or stream simulation.

## Custom Providers

```kotlin
val provider = CustomProvider {
    providerId("test")
    languageModel("small", MockLanguageModelTextOnly("ok"))
}

val result = TextGenerator(provider.languageModel("small"))
    .generate(GenerationInput.Prompt("Say ok."))
    .first()
```

Custom providers are ideal for tests and internal services. Preserve warnings,
usage, response metadata, and provider metadata so callers get the same result
shape as dedicated providers.

## Related

- [Providers And Models](providers.md)
- [Settings And Provider Options](settings-and-provider-options.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
- [DevTools](devtools.md)
- [Testing And Release](testing-and-release.md)
