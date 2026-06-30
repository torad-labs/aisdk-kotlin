# Testing And Release

This is a published Kotlin Multiplatform library. Release quality comes from
API stability, KMP compilation, deterministic tests, parity ledgers, coverage,
publication metadata, and signing.

## Local Commands

Run JVM tests during normal development:

```sh
./gradlew jvmTest
```

Run the full gate before broad changes:

```sh
./gradlew check
```

Verify local publication:

```sh
./gradlew publishToMavenLocal
```

Before release, run both:

```sh
./gradlew check publishToMavenLocal
```

## Parity Checks

Parity is generated from the pinned Vercel AI SDK v6 reference.

```sh
node tools/check-ai-sdk-reference.mjs
node tools/generate-parity-ledger.mjs --check
```

If the pinned upstream reference changes, regenerate `docs/parity`. Each new
or changed row should be closed by implementation, a KMP mapping decision, or
an explicit non-runtime explanation.

## Test Strategy

- Unit-test pure helpers in `commonTest`.
- Use mock models for generation, streams, tools, media, embeddings,
  reranking, and transcription.
- Use fake HTTP transports or Ktor `MockEngine` for provider facades.
- Test stream event ordering and terminal events.
- Test error surfaces and metadata propagation.
- Test cancellation, approval resume, and superseded submissions.
- Avoid live provider calls in CI.

## Deterministic Model Tests

Use scripted mock models when a test needs a model turn without a provider
call:

```kotlin
val model = mockLanguageModelToolThenText(
    toolName = "searchDocs",
    toolInput = mockToolInput("query" to "stream adapters"),
    finalText = "Use streamTextResult for replayable adapters.",
)

class SearchAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
    ToolLoopAgent<AppContext, String>(
        model = model,
        instructions = "Search before answering.",
        tools = tools,
    )

val agent = SearchAgent(model, ToolSet(searchDocs))

val result = agent.generate(
    prompt = "How do I render stream output?",
    options = AppContext(workspaceId = "docs"),
)

assertEquals("Use streamTextResult for replayable adapters.", result.text)
```

Use `MockEmbeddingModel`, `MockImageModel`, `MockSpeechModel`,
`MockTranscriptionModel`, `MockVideoModel`, and `MockRerankingModel` for
non-text model families.

## What `check` Covers

The Gradle `check` gate includes:

- Kotlin compile checks for configured targets.
- JVM, Android host, and Linux native tests where available.
- Detekt.
- ABI validation.
- Coverage verification.
- Generated/reference freshness checks wired into the build.

iOS simulator execution requires the Apple toolchain. Linux can compile iOS
artifacts for publication verification, but it cannot run simulator tests.

## Coordinates

```text
ai.torad:torad-aisdk:<version>
```

The version comes from `VERSION_NAME` in `gradle.properties`.

## Maven Central And GitHub Packages

Tagged releases run `.github/workflows/release.yml`, which publishes to Maven
Central through the Central Portal. GitHub Packages is a best-effort mirror:

```text
https://maven.pkg.github.com/torad-labs/aisdk-kotlin
```

Required repository secrets:

- `SIGNING_KEY`: in-memory PGP private key.
- `SIGNING_PASSWORD`: PGP key password.
- `SONATYPE_USERNAME`: Central Portal token username.
- `SONATYPE_PASSWORD`: Central Portal token password.

GitHub provides `GITHUB_TOKEN` for the package mirror.

## Release Checklist

1. Update `VERSION_NAME` in `gradle.properties`.
2. Update `CHANGELOG.md`.
3. Run parity checks.
4. Run `./gradlew check publishToMavenLocal`.
5. Run `tools/beta-readiness-check --strict-readme`.
6. Confirm generated artifacts and POM metadata.
7. Commit to `main` and tag with `v<version>`.
8. Push the tag and verify the release workflow.

## Related

- [Cookbook](cookbook.md)
- [Troubleshooting](troubleshooting.md)
- [Error Handling](error-handling.md)
- [Lifecycle And Events](lifecycle-and-events.md)
- [Middleware And Telemetry](middleware-and-telemetry.md)
- [DevTools](devtools.md)
- [Utilities](utilities.md)
- [Model Families](model-families.md)
