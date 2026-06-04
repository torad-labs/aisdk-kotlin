# Troubleshooting

This page lists common failures and the fastest evidence to inspect.

## Gradle Cannot Find The Artifact

Check whether you are using a published version or local Maven:

```sh
./gradlew publishToMavenLocal
```

Then confirm the consuming build uses the same version:

```kotlin
implementation("ai.torad:aisdk-kotlin:0.1.0-SNAPSHOT")
```

For source checkouts, prefer `includeBuild("../aisdk-kotlin")`.

## Android Publication Fails

Confirm the Android SDK is installed and contains the compile SDK configured in
`build.gradle.kts`. The project currently expects compile SDK 36.

## iOS Tests Do Not Execute On Linux

iOS artifacts can compile on Linux through publication tasks, but simulator
test execution requires the Apple toolchain. Use publication verification on
Linux and run iOS simulator tests on macOS when adding platform code.

## Provider Authentication Fails

Authentication belongs to the host app. Verify:

- The provider settings are constructed with the expected key or token.
- Headers are forwarded through the selected transport.
- The provider facade uses the expected base URL.
- Tests use fake credentials and assert request headers with mock transports.

## A Stream Hangs

Inspect:

- Whether the flow is being collected.
- Whether the provider emitted a finish event.
- Whether a tool call is waiting for approval.
- Whether `stopWhen` can be reached.
- Whether `abortSignal` is forwarded to tools and subagents.

## Tool Input Fails To Decode

Check the tool input serializer, generated JSON shape, and any
`experimental_repairToolCall` callback. Tests should include malformed input
and the repaired call shape.

## UI Message Validation Fails

Run `validateUiMessages` or `safeValidateUIMessages` at persistence and API
boundaries. Check that tool-call ids, part states, and approval response parts
match the stream protocol.

## Parity Check Fails

Run:

```sh
node tools/generate-parity-ledger.mjs
git diff -- docs/parity
```

If generated ledgers changed, either implement the new surface, map it to a
KMP equivalent, or document why it is not a runtime feature.

## Upstream Reference Comment Looks Like A Runtime Failure

The pinned upstream reference is TypeScript source. Some comments inside
`.reference/vercel-ai-sdk-ai-6.0.196` include historical JavaScript runtime
messages, including the provider-utils note about calling browser or
Cloudflare base64 globals with the wrong receiver.

That string is documentation in the upstream source, not a Kotlin build or
test failure. Verify by checking the command exit status first. The KMP port
does not call Node, browser, or Cloudflare globals for this path:
`convertBase64ToUint8Array`, `convertUint8ArrayToBase64`,
`convertBase64ToByteArray`, and `convertByteArrayToBase64` use
`kotlin.io.encoding.Base64`.

Regression coverage lives in
`GatewayAndProviderUtilsParityTest.binary url and media provider-utils are
available in common code`, including standard and URL-safe base64 aliases.

## Publication Metadata Fails

Inspect `gradle.properties`, `build.gradle.kts`, and `docs/PUBLISHING.md`.
Common causes are missing SCM metadata, invalid signing configuration, or a
publication task that no longer assembles all target artifacts.
