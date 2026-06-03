# Testing And Release

The project is a library, so release quality is determined by API stability,
KMP compilation, deterministic behavior tests, parity ledgers, and publication
metadata.

## Local Test Commands

Run JVM tests during normal development:

```sh
./gradlew jvmTest
```

Run all configured host tests before broad changes:

```sh
./gradlew allTests
```

Verify publications can be assembled:

```sh
./gradlew publishToMavenLocal
```

## Parity Checks

Parity is generated from the pinned Vercel AI SDK v6 reference:

```sh
node tools/check-ai-sdk-reference.mjs
node tools/generate-parity-ledger.mjs --check
```

If a new upstream stable v6 package release appears, refresh `.reference`,
regenerate ledgers, and close new rows with implementation or an explicit
KMP mapping decision.

## Test Strategy

- Unit-test pure helpers in `commonTest`.
- Use mock models for generation, stream, tool, media, embedding, reranking,
  and transcription contracts.
- Use fake HTTP transports or Ktor `MockEngine` for provider facades.
- Test stream event ordering and terminal events.
- Test error surfaces and metadata propagation, not only happy paths.
- Test cancellation and approval resume paths.
- Avoid live provider calls in CI.

## CI Gate

The main CI workflow verifies:

- Checkout and toolchain setup.
- Vercel AI SDK v6 reference fetch.
- Reference version check.
- Parity ledger freshness.
- Test execution.
- Publication metadata.

Treat a red CI run as a project stability issue. Read the actual failure log,
trace the cause, fix it, and rerun the relevant checks.

## Release Checklist

1. Update version and publication metadata.
2. Update `CHANGELOG.md`.
3. Run parity checks.
4. Run `./gradlew allTests publishToMavenLocal`.
5. Confirm generated artifacts and POM metadata.
6. Push and verify GitHub Actions.
7. Publish using the configured release workflow.
