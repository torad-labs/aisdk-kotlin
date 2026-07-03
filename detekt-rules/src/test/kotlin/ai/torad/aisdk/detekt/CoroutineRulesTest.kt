package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoroutineRulesTest {
    private val rule = NoRunCatchingInSuspendFunction(Config.empty)

    @Test
    fun `flags runCatching inside a suspend function`() {
        val findings = rule.compileAndLint(
            """
            suspend fun fetch(): Result<String> = runCatching { "ok" }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag runCatching in non-suspend code`() {
        val findings = rule.compileAndLint("fun parse(): Result<String> = runCatching { \"ok\" }")

        assertEquals(0, findings.size)
    }

    @Test
    fun `flags runCatching inside launch builder lambda`() {
        val findings = rule.compileAndLint(
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            fun start(scope: CoroutineScope) {
                scope.launch {
                    runCatching { fetch() }
                }
            }

            suspend fun fetch(): String = "ok"
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag ordinary nested non-suspend lambda`() {
        val findings = rule.compileAndLint(
            """
            fun parse(): List<Result<String>> = listOf("ok").map { value ->
                runCatching { value }
            }
            """.trimIndent(),
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not flag pure runCatching inside coroutine builder lambda`() {
        val findings = rule.compileAndLint(
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            fun start(scope: CoroutineScope) {
                scope.launch {
                    runCatching { fetchBlocking() }
                }
            }

            fun fetchBlocking(): String = "ok"
            """.trimIndent(),
        )

        assertEquals(0, findings.size)
    }
}
