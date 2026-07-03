package ai.torad.aisdk.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/**
 * Whole-codebase architecture invariants (Konsist) — the declaration-level tenets a
 * single-file lint (ast-grep / detekt) structurally cannot see, because they quantify over
 * the entire declaration graph. Mirrors of the ast-grep PreToolUse rules, enforced for every
 * developer in `./gradlew check`. See docs/enforcement-layers.md.
 *
 * Scope note: `scopeFromProduction()` does not recognise KMP's `commonTest`/`jvmTest` layout
 * as test source (it expects the JVM `src/test/` convention), so these tenets — which apply to
 * the library's production surface, not test helpers — filter test source sets out by path
 * (`Test/` matches commonTest/jvmTest/…), matching the commonMain scope the ast-grep ci-gate enforces.
 */
class KonsistArchitectureTest {

    @Test
    fun `data classes named Event belong to a sealed event hierarchy`() {
        // Mirror of no-flat-lifecycle-event: a `data class …Event` must be a subtype (an
        // AgentEvent), never a free-floating flat struct delivered through a callback bag.
        Konsist.scopeFromProject()
            .classes()
            .filter { !it.path.contains("Test/") && it.hasDataModifier && it.name.endsWith("Event") }
            .assertTrue { it.parents().isNotEmpty() }
    }

    @Test
    fun `no sealed interfaces in production except serializable wire types and private`() {
        // Mirror of no-sealed-interface with the approved carve-outs (2026-06-22): a sealed
        // hierarchy's root is a sealed CLASS, EXCEPT @Serializable polymorphic wire types (the
        // idiomatic kotlinx pattern) and private internal ones (e.g. the MCP State machine).
        // Test helpers are excluded — this is a production-surface tenet. This whole-codebase
        // check is what caught ToolChoice, which the ast-grep rule's pattern gap was missing.
        Konsist.scopeFromProject()
            .interfaces()
            .filter { !it.path.contains("Test/") }
            .filter { !it.hasAnnotationWithName("Serializable") && !it.hasPrivateModifier }
            .assertFalse { it.hasSealedModifier }
    }

    @Test
    fun `providers do not depend on ui or framework layer`() {
        commonMainFiles()
            .filter { it.path.contains("/providers/") }
            .assertFalse { it.dependsOnPackage("ai.torad.aisdk.ui") }
    }

    @Test
    fun `core agent protocol and ui layers do not import provider implementations`() {
        commonMainFiles()
            .filter { !it.path.contains("/providers/") }
            .assertFalse { it.dependsOnPackage("ai.torad.aisdk.providers") }
    }

    @Test
    fun `protocol codecs stay below agent runtime and ui layers`() {
        val forbiddenAgentRuntimeTerms = listOf(
            "AgentEvent",
            "AgentSession",
            "ToolLoopAgent",
            "ToolLoopAgentEngine",
        )

        commonMainFiles()
            .filter { it.path.contains("/protocol/") }
            .assertFalse { file ->
                file.dependsOnPackage("ai.torad.aisdk.providers") ||
                    file.dependsOnPackage("ai.torad.aisdk.ui") ||
                    file.dependsOnPackage("ai.torad.aisdk.middleware") ||
                    forbiddenAgentRuntimeTerms.any { term -> file.text.contains(term) }
            }
    }

    private fun commonMainFiles(): List<KoFileDeclaration> = Konsist.scopeFromProject()
        .files
        .filter { it.path.contains("/src/commonMain/") }

    private fun KoFileDeclaration.dependsOnPackage(packageName: String): Boolean {
        val packagePrefix = "$packageName."
        return imports.any { import ->
            import.name == packageName || import.name.startsWith(packagePrefix)
        }
    }
}
