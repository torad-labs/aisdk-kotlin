# Contributing

## Development

Use JDK 21 and the checked-in Gradle wrapper:

```sh
./gradlew jvmTest
```

For changes that affect Android publication, also run:

```sh
./gradlew assembleRelease
```

For publication metadata checks:

```sh
./gradlew publishToMavenLocal
```

## Code Standards

- Keep public API changes intentional and documented in `INTERFACE_CONTRACT.md`.
- Prefer common Kotlin code. Add platform-specific source sets only when a target cannot share behavior.
- Keep provider integrations out of the core library; add provider modules/packages separately.
- Add tests for behavior changes, regression fixes, and public API additions.
- Do not commit generated build outputs, local credentials, model files, or IDE state.

## Commit Style

Use concise conventional commit prefixes where they help reviewers:

- `feat:`
- `fix:`
- `docs:`
- `test:`
- `build:`
- `chore:`
