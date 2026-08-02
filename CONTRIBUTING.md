# Contributing

## Development

Use JDK 21 and the checked-in Gradle wrapper:

```sh
./gradlew jvmTest
```

In a fresh clone, activate the committed pre-commit gates before committing:

```sh
tools/bootstrap
```

This sets the local `core.hooksPath=.githooks`; rerunning it is safe.

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

## Tests

New functionality and bug fixes require tests, and a fix needs a test that **fails on
unmodified `main`** — a test that passes before the change proves nothing about it.

- Run the suite with `./gradlew check`; `./gradlew jvmTest` is the fast inner loop.
- Put tests in `commonTest` unless the behavior is genuinely platform-specific.
- Coverage is measured, not estimated: Kover reports feed `dev/measurements.toml` through
  `dev/measurements_ledger.py`. Do not restate coverage numbers in prose — cite the key.
- Gates get the same treatment as code. A new gate needs a red/green fixture under
  `tools/gate-fixtures/`, proven to fail on the violation it exists to catch; an
  unexercised check is indistinguishable from a broken one.

## Commit Style

Use concise conventional commit prefixes where they help reviewers:

- `feat:`
- `fix:`
- `docs:`
- `test:`
- `build:`
- `chore:`
