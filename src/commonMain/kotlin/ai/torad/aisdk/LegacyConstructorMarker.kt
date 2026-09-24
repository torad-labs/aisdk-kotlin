package ai.torad.aisdk

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Trailing parameter of the hidden constructor bridges that keep the JVM constructor
 * descriptors of 0.3.0-beta01 linkable. On the JVM it is `kotlin.jvm.internal.DefaultConstructorMarker`,
 * so a bridge declared as `(…, marker: LegacyConstructorMarker?)` compiles to exactly the synthetic
 * descriptor a Kotlin call site links against. Native has no such descriptor: there it is an
 * uninstantiable placeholder and the bridges are dead weight.
 *
 * Why the bridges exist. Under `-Xjvm-expose-boxed`, a class constructor that takes a value class
 * compiles to a Java-facing boxed constructor plus a synthetic Kotlin-facing one. Kotlin 2.4.20
 * appends a `BoxingConstructorMarker` to the Kotlin-facing one (JetBrains/kotlin 4a7a6f8, KT-85955),
 * but its call sites in any OTHER module still link to the old descriptor, because
 * `shouldBeExposedByAnnotationOrFlag` skips declarations from other modules (JvmIrUtils.kt, v2.4.20).
 * Without the bridges every separately compiled Kotlin caller of these constructors throws
 * `NoSuchMethodError`: binaries built against 0.3.0-beta01, and fresh builds with 2.4.10 or 2.4.20.
 *
 * Kotlin 2.5.0 reverts the scheme (8d6f85e, KT-87664: it "breaks binary compatibility") and emits
 * the old descriptor itself again. On that upgrade every bridge fails to compile with "platform
 * declaration clash"; delete them and this file then.
 */
internal expect class LegacyConstructorMarker

/**
 * Rebuilds the [Duration] whose raw value a pre-2.4.20 call site passed where the constructor
 * takes a [Duration]. The stdlib stores a Duration as one `Long`: the magnitude shifted left by one
 * bit, with the low bit selecting nanoseconds (0) or milliseconds (1). That layout is binary ABI
 * of every compiled caller, so it cannot change; decoding it through the public factories yields
 * the identical value (LegacyConstructorBridgeTest round-trips it against the stdlib's own boxing).
 */
internal fun DurationFromRawValue(rawValue: Long): Duration {
    val magnitude = rawValue shr 1
    return if ((rawValue and 1L) == 0L) magnitude.nanoseconds else magnitude.milliseconds
}
