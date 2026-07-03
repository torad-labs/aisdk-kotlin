package ai.torad.aisdk

import kotlin.random.Random

/**
 * A cryptographically secure [Random], backed by the platform CSPRNG
 * (`java.security.SecureRandom` on JVM/Android, `/dev/urandom` on native).
 *
 * Use this — never [Random.Default] — for security-sensitive values such as the
 * OAuth `state` (CSRF) and the PKCE `code_verifier`, which RFC 7636 §4.1 requires
 * to be a high-entropy cryptographic random string. [Random.Default] resolves to a
 * non-cryptographic PRNG (e.g. `java.util.Random` on JVM) and is predictable.
 */
internal expect fun SecureRandom(): Random
