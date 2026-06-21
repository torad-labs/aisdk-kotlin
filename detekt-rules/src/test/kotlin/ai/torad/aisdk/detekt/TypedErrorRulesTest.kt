package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TypedErrorRulesTest {
    @Test
    fun `flags error() call`() {
        val findings = PreferTypedErrorOverErrorCall(Config.empty).compileAndLint("""fun f(): Int = error("boom")""")
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags checkNotNull() call`() {
        val findings = PreferTypedErrorOverCheckNotNull(Config.empty).compileAndLint("fun f(x: Int?): Int = checkNotNull(x)")
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags throw of a generic JDK exception`() {
        val findings = PreferTypedErrorOverGenericThrow(Config.empty).compileAndLint("fun f(): Nothing = throw RuntimeException()")
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag throw of a domain exception`() {
        val findings = PreferTypedErrorOverGenericThrow(Config.empty).compileAndLint("fun f(): Nothing = throw AiSdkTimeoutException()")
        assertEquals(0, findings.size)
    }
}
