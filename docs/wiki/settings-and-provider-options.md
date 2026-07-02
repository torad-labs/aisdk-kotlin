# Settings And Provider Options

Settings control model sampling, limits, cancellation, and provider-specific
features. High-level text generation takes a `CallConfig`; agents and step
hooks use `AgentSettings` and `StepSettings` for dynamic overrides.

## Call Settings

```kotlin
val config = CallConfig {
    temperature(0.2f)
    topP(0.9f)
    maxOutputTokens(700)
    stopSequences(listOf("</answer>"))
    presencePenalty(0.1f)
    frequencyPenalty(0.1f)
}

val result = TextGenerator(model, config)
    .generate(
        GenerationInput.Messages(
            GenerationInput.NonEmptyMessages.of(
                SystemMessage("Answer as a careful SDK maintainer."),
                UserMessage("Explain provider options."),
            ),
        ),
    )
    .first()
```

Use `CallConfig` when a call has several optional knobs. It keeps call sites
readable and makes defaults easy to merge.

## Provider Options

Provider options are grouped by provider key:

```kotlin
@Serializable
data class OpenAiCallOptions(val reasoningEffort: String)

val openAiOptions = OpenAiCallOptions(reasoningEffort = "medium")
val options = ProviderOptions.ofPairs(
    "openai" to buildJsonObject {
        put("reasoningEffort", JsonPrimitive(openAiOptions.reasoningEffort))
    },
    "anthropic" to buildJsonObject {
        put("thinking", JsonPrimitive("enabled"))
    },
)

val result = TextGenerator(
    model,
    CallConfig {
        providerOptions(options)
    },
)
    .generate(GenerationInput.Prompt("Draft a migration plan."))
    .first()
```

For ad hoc options, `ProviderOptions.ofPairs` is usually enough:

```kotlin
val options = ProviderOptions.ofPairs(
    "anthropic" to buildJsonObject {
        put("thinking", JsonPrimitive("enabled"))
    },
)
```

Keep provider options typed at app boundaries when the shape is stable. Use
`JsonObjectBuilder` only for one-off provider switches.

## Defaults With Middleware

Use middleware when many calls need the same defaults:

```kotlin
val modelWithDefaults = WrapLanguageModel(
    model = rawModel,
    middlewares = listOf(
        DefaultSettingsMiddleware(
            temperature = 0.2f,
            maxOutputTokens = 1_000,
            providerOptions = ProviderOptions.ofPairs(
                "openai" to buildJsonObject {
                    put("reasoningEffort", JsonPrimitive("medium"))
                },
            ),
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
    AgentSettings<AppContext> {
        instructions(instructions + "\nWorkspace: ${options?.workspaceId}")
        providerOptions(
            ProviderOptions.ofPairs(
                "openai" to buildJsonObject {
                    put("reasoningEffort", JsonPrimitive("high"))
                },
            ),
        )
    }
}
```

Use `prepareStep` when each model step may need a different model, tool set, or
context:

```kotlin
prepareStep = {
    StepSettings<AppContext> {
        model(if (stepNumber == 1) fastModel else strongModel)
        activeTools(if (stepNumber == 1) listOf("classify") else null)
    }
}
```

## Cancellation

```kotlin
val controller = AbortController()

val result = TextGenerator(
    model,
    CallConfig {
        abortSignal(controller.signal)
    },
)
    .generate(GenerationInput.Prompt("Summarize the report."))
    .first()
```

For coroutine-owned work, derive signals from jobs with `AbortSignalFromJob`
or `AbortSignals`.

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
