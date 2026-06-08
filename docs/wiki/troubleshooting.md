# Troubleshooting

Start with the exact command output, then inspect the narrowest file or test
that owns the failing behavior.

## Gradle Cannot Find The Artifact

If consuming from local Maven, publish first:

```sh
./gradlew publishToMavenLocal
```

Then confirm the consumer uses the same version:

```kotlin
implementation("ai.torad:aisdk-kotlin:0.1.0-SNAPSHOT")
```

For source checkouts, prefer a composite build with `includeBuild`.

## Android Publication Fails

Confirm the Android SDK is installed and contains the compile SDK configured
in `build.gradle.kts`. This project currently expects compile SDK 36.

## iOS Tests Do Not Run On Linux

Linux can compile iOS artifacts for publication verification, but simulator
test execution requires the Apple toolchain. Run iOS simulator tests on macOS
when changing platform-specific code.

## Provider Authentication Fails

Authentication belongs to the host app. Check:

- Provider settings include the expected key or token.
- Dynamic auth headers are returned when expected.
- The selected HTTP client or transport forwards headers.
- The facade uses the expected base URL.
- Tests use fake credentials and assert request headers with mock transports.

Common Kotlin code cannot read platform environment variables directly. Pass
environment maps, settings, or host-injected providers.

## Gateway Transport Is Not Configured

`createGateway()` needs a `GatewayTransport` for real HTTP calls.

```kotlin
val gatewayProvider = createGateway(
    GatewayProviderSettings(
        apiKey = key,
        transport = KtorGatewayTransport(client),
    ),
)
```

Without a transport, Gateway models throw `GatewayTransportNotConfiguredError`.

## A Stream Hangs

Inspect:

- Whether the `Flow` is being collected.
- Whether the provider emitted a finish event.
- Whether a tool is waiting for approval.
- Whether `stopWhen` can be reached.
- Whether `abortSignal` is forwarded to tools and subagents.
- Whether a host adapter forgot to close or flush its stream.

Remember that `streamText` and agent streams are cold.

## Tool Input Fails To Decode

Check:

- The input serializer.
- The generated JSON shape.
- The tool name in the model call.
- `experimental_repairToolCall`.
- Tests for malformed input and repaired calls.

The agent emits tool errors when repair cannot produce valid typed input.

## Approval Resume Does Nothing

Confirm that resume uses the returned message list plus approval responses:

```kotlin
agent.generate(
    messages = result.messages + toolApprovalResponseMessage(
        toolCallId = pending.toolCallId,
        approved = true,
        approvalId = pending.approvalId,
    ),
)
```

For `AgentSession`, use `approve` or `deny` with the pending approval object.

## UI Message Validation Fails

Run validation at persistence and API boundaries:

```kotlin
safeValidateUIMessages(messages)
```

Check:

- message ids are non-blank and unique,
- each message has at least one part,
- tool-call ids match between input, output, and approvals,
- incomplete tool calls are not replayed unless intentionally ignored.

## Conversion To Model Messages Fails

`convertToModelMessages` throws on incomplete tool calls by default. That is
usually correct for persisted history.

If you intentionally want to drop incomplete tool calls:

```kotlin
convertToModelMessages(
    messages = messages,
    ignoreIncompleteToolCalls = true,
)
```

## Parity Check Fails

Regenerate and inspect the diff:

```sh
node tools/generate-parity-ledger.mjs
git diff -- docs/parity
```

Then implement the new surface, map it to a KMP equivalent, or document why it
is not a runtime feature.

## Upstream Reference Looks Like A Runtime Failure

The pinned upstream reference is TypeScript source. Some comments in
`.reference/vercel-ai-sdk-ai-6.0.197` include historical JavaScript runtime
messages. Treat those as upstream documentation unless a local command exits
non-zero.

For base64 and binary helpers, the KMP port uses `kotlin.io.encoding.Base64`.
Regression coverage lives in `GatewayAndProviderUtilsParityTest`.

## Publication Metadata Fails

Inspect:

- `gradle.properties`
- `build.gradle.kts`
- `docs/wiki/testing-and-release.md`

Common causes are missing SCM metadata, invalid signing configuration, or a
publication task that no longer assembles all target artifacts.

## Related

- [Getting Started](getting-started.md)
- [Streaming](streaming.md)
- [Chatbots](chatbots.md)
- [Error Handling](error-handling.md)
- [DevTools](devtools.md)
- [Utilities](utilities.md)
- [Testing And Release](testing-and-release.md)
