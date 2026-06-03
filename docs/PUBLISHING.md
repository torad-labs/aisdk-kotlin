# Publishing

The project publishes a Kotlin Multiplatform artifact with JVM, Android, iOS x64, iOS arm64, and iOS simulator arm64 publications.

## Coordinates

```text
ai.torad:aisdk-kotlin:<version>
```

The version comes from `VERSION_NAME` in `gradle.properties`.

## Local Verification

```sh
./gradlew allTests
./gradlew publishToMavenLocal
```

`publishToMavenLocal` verifies all configured publications can be assembled and written to the local Maven cache.

## GitHub Packages

Tagged releases run `.github/workflows/release.yml`, which publishes to:

```text
https://maven.pkg.github.com/torad-labs/aisdk-kotlin
```

Required repository secrets:

- `SIGNING_KEY`: required in-memory PGP private key.
- `SIGNING_PASSWORD`: required PGP key password.

GitHub automatically provides `GITHUB_TOKEN` for package publication.

## Release Checklist

1. Update `VERSION_NAME` in `gradle.properties`.
2. Update `CHANGELOG.md`.
3. Run `./gradlew check publishToMavenLocal`.
4. Commit to `main` and tag with `v<version>`. The tag version must exactly match `VERSION_NAME`.
5. Push the tag to trigger package publication.

Maven Central publication is not wired yet. Add it only after package metadata, signing, and release ownership are finalized.
