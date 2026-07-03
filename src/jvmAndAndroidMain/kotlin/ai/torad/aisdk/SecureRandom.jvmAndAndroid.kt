package ai.torad.aisdk

import kotlin.random.Random
import kotlin.random.asKotlinRandom

// Shared by the JVM and Android targets via the jvmAndAndroidMain intermediate source set:
// java.security.SecureRandom is the CSPRNG on both.
internal actual fun SecureRandom(): Random = java.security.SecureRandom().asKotlinRandom()
