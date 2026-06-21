package ai.torad.aisdk

import kotlin.random.Random
import kotlin.random.asKotlinRandom

internal actual fun SecureRandom(): Random = java.security.SecureRandom().asKotlinRandom()
