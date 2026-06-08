# Settings And Provider Options

Settings control model sampling, limits, cancellation, and provider-specific
features. AI SDK Kotlin supports both v6-shaped named arguments and a
Kotlin-first `CallSettings` DSL.

## Call Settings

```kotlin
val settings = callSettings {
    temperature = 0.2f
    topP = 0.9f
    maxOutputTokens = 700
    stopSequence("</answer>")
    presencePenalty = 0.1f
    frequencyPenalty = 0.1f
}

val result = generateText(model = model, settings = settings) {
    system("Answer as a careful SDK maintainer.")
    prompt("Explain provider options.")
}
```

Use `CallSettings` when a call has several optional knobs. It keeps call sites
readable and makes defaults easy to merge.

## Provider Options

Provider options are grouped by provider key:

```kotlin
@Serializable
data class OpenAiCallOptions(val reasoningEffort: String)

val options = buildProviderOptions {
    provider("openai", OpenAiCallOptions(reasoningEffort = "medium"))
    provider("anthropic") {
        put("thinking", JsonPrimitive("enabled"))
    }
}

val result = generateText(
    model = model,
    prompt = "Draft a migration plan.",
    providerOptions = options,
)
```

Keep provider options typed at app boundaries when the shape is stable. Use
`JsonObjectBuilder` only for one-off provider switches.

## Defaults With Middleware

Use middleware when many calls need the same defaults:

```kotlin
val modelWithDefaults = wrapLanguageModel(
    model = rawModel,
    middlewares = listOf(
        defaultSettingsMiddleware(
            temperature = 0.2f,
            maxOutputTokens = 1_000,
            providerOptions = buildProviderOptions {
                provider("openai", OpenAiCallOptions("medium"))
            },
        ),
    ),
)
```

Explicit call settings override defaults. Middleware is a good place for
provider normalization, logging, JSON extraction, and simulated streaming.

## Dynamic Agent Settings

Use `prepareCall` for request-level decisions:

```kotlin
prepareCall = {
    AgentSettings(
        instructions = instructions + "\nWorkspace: ${options?.workspaceId}",
        providerOptions = buildProviderOptions {
            provider("openai", OpenAiCallOptions("high"))
        },
    )
}
```

Use `prepareStep` when each model step may need a different model, tool set, or
context:

```kotlin
prepareStep = {
    StepSettings(
        model = if (stepNumber == 1) fastModel else strongModel,
        activeTools = if (stepNumber == 1) listOf("classify") else null,
    )
}
```

## Cancellation

```kotlin
val controller = AbortController()

val result = generateText(
    model = model,
    prompt = "Summarize the report.",
    abortSignal = controller.signal,
)
```

For coroutine-owned work, derive signals from jobs with `abortSignalFromJob`
or `abortSignalFromJobs`.

## Result Metadata

Provider metadata can be decoded when you know the provider shape:

```kotlin
@Serializable
data class ProviderTrace(val cacheHit: Boolean)

val trace = result.providerMetadataAs<ProviderTrace>("openai")
```

Use metadata for diagnostics, billing, trace ids, and provider-specific
features. Do not make core application behavior depend on metadata unless the
provider contract is stable.

## Tips

- Prefer low temperature for extraction, routing, and support answers.
- Prefer `Output` over prompt-only formatting constraints.
- Put stable defaults in middleware; put request-specific changes in
  `prepareCall`.
- Keep provider options close to provider setup unless they truly vary by
  request.

## Related

- [Core](core.md)
- [Providers And Models](providers.md)
- [Provider Management](provider-management.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
- [Agents](agents.md)
