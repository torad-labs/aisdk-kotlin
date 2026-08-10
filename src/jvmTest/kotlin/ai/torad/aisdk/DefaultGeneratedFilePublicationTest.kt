package ai.torad.aisdk

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [DefaultGeneratedFile] memoizes its base64/byte payload on first read of a public getter, so the
 * cache is shared mutable state on a value a consumer may hand between threads.
 *
 * Whether that memoization is CORRECT cannot be observed from a behavioural test. A plain
 * (non-final, non-volatile) field write publishing a freshly decoded `ByteArray` is a data race
 * whose visible symptom — a reader seeing the array reference before its element writes, so
 * `copyOf()` returns zeroed bytes — cannot reproduce on x86's store-ordered memory model, only on
 * the ARM/Android and Kotlin/Native targets this library also ships to. A concurrent stress test
 * here would be green on this machine no matter how the field is written, i.e. no instrument at all.
 *
 * So the instrument is the field shape itself, which is exactly what the JMM predicates safe
 * publication on: every instance field of the type must be final or volatile.
 */
class DefaultGeneratedFilePublicationTest {
    @Test
    fun `cached payload fields are safely published`() {
        val unsafe = DefaultGeneratedFile::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filterNot { Modifier.isFinal(it.modifiers) || Modifier.isVolatile(it.modifiers) }
            .map { it.name }

        assertTrue(unsafe.isEmpty(), "instance fields are neither final nor volatile: $unsafe")
    }
}
