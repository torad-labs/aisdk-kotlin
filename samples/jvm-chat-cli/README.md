# JVM Chat CLI Sample

Small JVM command-line sample for the published `ai.torad:torad-aisdk`
artifact. It resolves from `mavenLocal()` first so a checkout can run it after
publishing locally:

```sh
./gradlew publishToMavenLocal
./gradlew -p samples/jvm-chat-cli run --args="Explain the SDK in one sentence."
```

When republishing the same non-SNAPSHOT version to `mavenLocal()`, Gradle may
serve cached bytes from the previous local artifact. Use
`./gradlew --refresh-dependencies -p samples/jvm-chat-cli run ...` after
republishing a fixed version with the same coordinate.

By default the sample uses `MockLanguageModelTextOnly`, so it runs without
network access or credentials even if provider credentials are present in the
shell. To use an OpenAI-compatible endpoint instead:

```sh
AISDK_SAMPLE_PROVIDER=openai \
OPENAI_API_KEY=... \
OPENAI_BASE_URL=https://api.openai.com/v1 \
OPENAI_MODEL=gpt-4o-mini \
./gradlew -p samples/jvm-chat-cli run --args="Write a Kotlin haiku."
```

`OPENAI_BASE_URL` defaults to `https://api.openai.com/v1` and `OPENAI_MODEL`
defaults to `gpt-4o-mini`.
