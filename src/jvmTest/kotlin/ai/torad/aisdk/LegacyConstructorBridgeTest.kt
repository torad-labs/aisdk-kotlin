package ai.torad.aisdk

import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Binary compatibility with 0.3.0-beta01 for the constructors Kotlin 2.4.20 re-signed.
 *
 * Callers compiled against 0.3.0-beta01 invoke these exact JVM descriptors, and so does every
 * Kotlin call site compiled in another module today (see [LegacyConstructorMarker]). The ABI dump
 * proves the descriptors exist; this proves each one builds the object its arguments describe,
 * including the default-arguments mask and the raw [Duration] encoding. Expectations are written
 * from the inputs, not from a Kotlin constructor call: from this module such a call links to the
 * very bridge under test.
 */
class LegacyConstructorBridgeTest {
    private val long = Long::class.javaPrimitiveType!!
    private val int = Int::class.javaPrimitiveType!!
    private val string = String::class.java
    private val marker = DefaultConstructorMarker::class.java

    /** Both storage units, both signs, and the boundary where the stdlib switches from ns to ms. */
    private val durations = listOf(
        Duration.ZERO,
        1.nanoseconds,
        (-1).nanoseconds,
        1500.milliseconds,
        30.seconds,
        4_611_686_018_426_999_999.nanoseconds,
        4_611_686_018_427.milliseconds,
        (-200_000).days,
        Duration.INFINITE,
        -Duration.INFINITE,
    )

    /** The raw `Long` a compiled Kotlin caller passes for [duration], read through the stdlib's own boxing. */
    private fun rawValueOf(duration: Duration): Long =
        Duration::class.java.getMethod("unbox-impl").invoke(duration as Any) as Long

    private fun <T> Constructor<T>.assertLegacyShape(): Constructor<T> = apply {
        assertTrue(Modifier.isPublic(modifiers), "$this must stay public for already-compiled callers")
        assertTrue(isSynthetic, "$this must be synthetic so Java cannot call it")
    }

    @Test
    fun `given every raw duration layout when decoded then it is the stdlib duration bit for bit`() {
        for (duration in durations) {
            val raw = rawValueOf(duration)
            val decoded = DurationFromRawValue(raw)
            assertEquals(duration, decoded, "raw=$raw")
            assertEquals(raw, rawValueOf(decoded), "re-encoding $duration")
        }
    }

    @Test
    fun `given the beta01 ToolExecutionTimedOut descriptor when invoked then it builds the same error`() {
        val legacy = AgentError.ToolExecutionTimedOut::class.java
            .getConstructor(string, string, long, marker)
            .assertLegacyShape()
        for (duration in durations) {
            val error = legacy.newInstance("search", "call_1", rawValueOf(duration), null)
            assertEquals("search", error.toolName)
            assertEquals("call_1", error.toolCallId)
            assertEquals(duration, error.timeout)
            assertEquals("Tool 'search' (callId=call_1) timed out after $duration", error.message)
        }
    }

    @Test
    fun `given the beta01 CallTimeoutError descriptors when invoked then they honour the message and its default`() {
        val explicit = CallTimeoutError::class.java
            .getConstructor(long, string, marker)
            .assertLegacyShape()
        val defaults = CallTimeoutError::class.java
            .getConstructor(long, string, int, marker)
            .assertLegacyShape()
        for (duration in durations) {
            val raw = rawValueOf(duration)

            val withMessage = explicit.newInstance(raw, "custom", null)
            assertEquals(duration, withMessage.timeout)
            assertEquals("custom", withMessage.message)

            val messageKept = defaults.newInstance(raw, "custom", 0, null)
            assertEquals("custom", messageKept.message)

            val messageOmitted = defaults.newInstance(raw, null, 1 shl 1, null)
            assertEquals(duration, messageOmitted.timeout)
            assertEquals("Call timed out after $duration.", messageOmitted.message)
        }
    }

    @Test
    fun `given the beta01 ModelRef descriptors when invoked then they honour the provider and its default`() {
        val explicit = ModelRef::class.java
            .getConstructor(string, string, marker)
            .assertLegacyShape()
        val defaults = ModelRef::class.java
            .getConstructor(string, string, int, marker)
            .assertLegacyShape()

        val qualified = explicit.newInstance("gpt-5", "openai", null)
        assertEquals(ModelId("gpt-5"), qualified.modelId)
        assertEquals(ProviderId("openai"), qualified.providerId)
        assertNull(explicit.newInstance("gpt-5", null, null).providerId)

        assertEquals(ProviderId("openai"), defaults.newInstance("gpt-5", "openai", 0, null).providerId)
        val providerOmitted = defaults.newInstance("gpt-5", null, 1 shl 1, null)
        assertEquals(ModelId("gpt-5"), providerOmitted.modelId)
        assertNull(providerOmitted.providerId)
    }
}
