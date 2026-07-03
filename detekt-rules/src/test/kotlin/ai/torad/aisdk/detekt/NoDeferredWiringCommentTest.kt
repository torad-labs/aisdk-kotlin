package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NoDeferredWiringCommentTest {
    private val rule = NoDeferredWiringComment(Config.empty)

    @Test
    fun `flags a comment admitting deferred wiring`() {
        // The trigger phrase lives inside the linted snippet (a string here), not this file's comments.
        val findings = rule.compileAndLint("val signature: String? = null // populated later by the streaming loop")
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag an ordinary comment`() {
        val findings = rule.compileAndLint("val n = 1 // the request count for this step")
        assertEquals(0, findings.size)
    }
}
