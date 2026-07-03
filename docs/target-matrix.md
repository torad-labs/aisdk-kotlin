# Target Expansion Matrix

This document evaluates possible Kotlin Multiplatform target additions. It is
a decision input only; it does not add targets to the build.

Evidence source: local Gradle module metadata for the dependency versions in
`gradle/libs.versions.toml`: Ktor `3.5.0`, kotlinx.coroutines `1.11.0`, and
kotlinx.serialization `1.11.0`.

## Dependency Support

| Candidate | Ktor `3.5.0` | Coroutines `1.11.0` | Serialization `1.11.0` | Dependency verdict |
| --- | --- | --- | --- | --- |
| `macosArm64` | yes, `macos_arm64` | yes, `macos_arm64` | yes, `macos_arm64` | Supported by dependencies |
| `macosX64` | yes, `macos_x64` | yes, `macos_x64` | yes, `macos_x64` | Supported by dependencies |
| `js` | yes, JS IR | yes, JS IR | yes, JS IR | Supported by dependencies |
| `wasmJs` | yes, `wasm-js` | yes, `wasm-js` | yes, `wasm-js` | Supported by dependencies |
| `mingwX64` | yes, `mingw_x64` | yes, `mingw_x64` | yes, `mingw_x64` | Supported by dependencies |

Dependency support is necessary but not sufficient. Each target must also
compile the SDK's expect/actual seams and run the conformance surface that
protects provider mapping, streaming, tool-loop behavior, cancellation, and
wire-format compatibility.

## Current Portability Surface

Current published targets are JVM, Android, iOS, and `linuxX64`. The source set
layout has only `commonMain`, `jvmAndAndroidMain`, and `nativeMain`.

The internal expect/actual seams that every new target must satisfy are:

- `SecureRandom`: JVM/Android delegates to `java.security.SecureRandom`;
  Native reads `/dev/urandom`.
- `CreateMCPStdioProcess`: JVM/Android uses `ProcessBuilder`; Native is an
  explicit unsupported implementation because Kotlin/Native has no portable
  subprocess API.
- `AiSdkJvmStatic`: JVM/Android typealiases `JvmStatic`; Native is a no-op
  annotation.
- `AbortErrorCancellationBridge`: JVM/Android and Native both convert
  `AbortError` into a coroutine `CancellationException` without changing public
  ABI.

Common tests rely heavily on Ktor `MockEngine`, coroutine timing, serialization,
stream replay, LiteRT adapters, provider request mapping, and MCP transports.
A target should not be considered supported until at least compilation and the
relevant executable test subset are green for that target.

## Candidate Assessments

### `macosArm64` and `macosX64`

Dependencies publish both macOS Native variants. The current `nativeMain`
actuals are likely source-compatible: `/dev/urandom` exists on macOS,
`CreateMCPStdioProcess` already has an explicit unsupported Native behavior,
and the other two actuals are platform-neutral.

Conformance surface:

- Add `macosArm64()` and `macosX64()` targets.
- Compile all main and test klibs.
- Run macOS Native tests on a macOS runner if CI time permits; otherwise start
  with compile-only and mark executable tests as the remaining acceptance gap.
- Confirm the published metadata resolves from a consumer smoke project.

CI cost is low to moderate because the project already has a macOS lane for
Apple verification. The additional Kotlin/Native compilations and tests are not
free, but they do not require a new operating-system family.

Recommendation: adopt first, as a paired target expansion. This is the lowest
risk target group because it reuses the existing Native source set and matches
the infrastructure already needed for iOS.

### `js`

Dependencies publish JS IR variants. The SDK does not currently have a JS source
set, so JS support requires explicit actuals instead of reusing `nativeMain`.

Conformance surface:

- Add `jsMain` actuals for all four expect declarations.
- Implement `SecureRandom` with Web Crypto where available, or make unsupported
  behavior explicit with a typed SDK error if cryptographic randomness cannot be
  guaranteed in the chosen runtime.
- Keep MCP stdio unsupported unless a deliberate Node-specific process adapter
  is added later.
- Add JS compile and test tasks to CI.
- Audit common tests for assumptions about dispatcher behavior, wall-clock
  scheduling, filesystem/process access, and platform exception types.

CI cost is moderate. Linux can compile and run the JS target, but executable
tests add Node/runtime setup and may surface scheduling differences that JVM and
Native tests do not catch.

Recommendation: adopt after macOS, with explicit JS actuals and a focused JS
test pass. Do not add it as metadata-only support.

### `wasmJs`

Dependencies publish `wasm-js` variants. The SDK does not currently have a Wasm
source set, and Wasm has a narrower runtime surface than JS.

Conformance surface:

- Add `wasmJsMain` actuals, potentially sharing no-op or unsupported behavior
  with JS where that is honest.
- Decide whether `SecureRandom` is supported only in browser-like hosts with
  Web Crypto, or explicitly unsupported until the runtime contract is narrower.
- Keep MCP stdio unsupported.
- Start with compile-only CI, then add executable tests once the runtime host
  and test runner expectations are stable.
- Re-check any serialization and streaming tests that assume JS object identity,
  exception shapes, or scheduler behavior.

CI cost is moderate to high because Wasm tests are more constrained than JS
tests and may require target-specific runner tuning.

Recommendation: adopt after JS. Treat it as a compile-first target until the
runtime contract is documented and the executable test set is stable.

### `mingwX64`

Dependencies publish `mingw_x64` variants. The current SDK source is not ready
for Windows Native as-is because `nativeMain` `SecureRandom` assumes
`/dev/urandom`, which is a Unix path. Windows Native needs a separate actual,
for example via the Windows cryptographic API, before the target can be called
supported.

Conformance surface:

- Split Native source sets so Unix Native keeps `/dev/urandom` and Windows
  Native gets a real Windows secure-random actual.
- Keep MCP stdio unsupported unless a Windows process adapter is deliberately
  added.
- Add Windows compile and executable tests, or document compile-only support as
  incomplete.
- Verify consumer metadata resolution from a Windows-capable smoke project.

CI cost is high because reliable compile and test coverage should run on a
Windows runner. Cross-compilation alone would not prove the Windows runtime
surface, especially secure randomness and file/process behavior.

Recommendation: defer until there is a Windows Native crypto actual and a
Windows CI lane. Dependency support alone is not enough to ship this target.

## Proposed Adoption Order

1. `macosArm64` and `macosX64`: lowest source risk; reuse `nativeMain`; add
   compile coverage first and executable tests where macOS CI budget allows.
2. `js`: add explicit JS actuals and a real JS test pass.
3. `wasmJs`: build on the JS work, start compile-first, then graduate to
   executable tests once the runtime contract is documented.
4. `mingwX64`: wait for a Windows secure-random actual and Windows CI coverage.

No target should be added to the published matrix until its compile path,
consumer-resolution path, and target-appropriate conformance tests are green.
