package ai.torad.aisdk.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test
// Konsist's assertTrue operates on a declaration list; this one takes a Boolean. Both are
// named assertTrue, so the scope-coverage test below aliases the kotlin.test one.
import kotlin.test.assertTrue as assertCondition

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
 *
 * Paths are normalized to `/` so Windows (`src\jvmTest\…`) does not leak test helpers into
 * production-surface checks (Konsist returns OS-native separators).
 *
 * Scope is `src/`, NOT `scopeFromProject()`. `scopeFromProject()` walks and PARSES every
 * `.kt` file under the repository root, which includes each campaign's `sandboxes/` — the
 * per-agent git worktrees a campaign leaves behind. Measured on this checkout: 39,229 Kotlin
 * files in the sandboxes against 477 real source files, an 82x amplification that stalled
 * `jvmTest` for over 30 minutes with the worker sitting in `scopeFromProject()` at ~0.2% CPU.
 *
 * The correctness half is worse than the slowness. These tenets select production files with
 * `path.contains("/src/commonMain/")`, and a sandbox path
 * (`dev/campaigns/…/sandboxes/I1/src/commonMain/…`) satisfies that too — so the architecture
 * invariants were being asserted against in-progress campaign copies of the codebase, whose
 * violations are neither this branch's nor necessarily real. Scoping to `src/` is what makes
 * the assertions describe this repository and only this repository.
 */
class KonsistArchitectureTest {

    private companion object {
        /**
         * A deliberately loose floor. commonMain held 212 Kotlin files when this was written;
         * the point is to catch a scope COLLAPSE (0, or a handful), not to track the real count
         * and force an edit on every added file.
         */
        const val MIN_EXPECTED_COMMON_MAIN_FILES = 100
    }

    @Test
    fun `the architecture scope is non-empty and covers commonMain`() {
        // Guards the failure mode every other test in this class shares: all five select a
        // subset and assert a property of it, so an EMPTY scope passes all of them silently.
        // Narrowing the scope from the whole project to `src/` is exactly the kind of change
        // that could have emptied it, and nothing here would have complained. This pins the
        // instrument itself rather than trusting a green run.
        val files = sourceScope().files
        assertCondition(
            files.isNotEmpty(),
            "Konsist scope is empty — the architecture tenets below would pass vacuously",
        )
        val commonMainCount = commonMainFiles().size
        assertCondition(
            commonMainCount >= MIN_EXPECTED_COMMON_MAIN_FILES,
            "commonMain scope collapsed to $commonMainCount files " +
                "(expected at least $MIN_EXPECTED_COMMON_MAIN_FILES) — the tenets no longer cover the library",
        )
        assertCondition(
            files.none { unixPath(it.path).contains("/sandboxes/") },
            "campaign sandbox worktrees leaked back into the architecture scope",
        )
    }

    @Test
    fun `data classes named Event belong to a sealed event hierarchy`() {
        // Mirror of no-flat-lifecycle-event: a `data class …Event` must be a subtype (an
        // AgentEvent), never a free-floating flat struct delivered through a callback bag.
        sourceScope()
            .classes()
            .filter { !unixPath(it.path).contains("Test/") && it.hasDataModifier && it.name.endsWith("Event") }
            .assertTrue { it.parents().isNotEmpty() }
    }

    @Test
    fun `no sealed interfaces in production except serializable wire types and private`() {
        // Mirror of no-sealed-interface with the approved carve-outs (2026-06-22): a sealed
        // hierarchy's root is a sealed CLASS, EXCEPT @Serializable polymorphic wire types (the
        // idiomatic kotlinx pattern) and private internal ones (e.g. the MCP State machine).
        // Test helpers are excluded — this is a production-surface tenet. This whole-codebase
        // check is what caught ToolChoice, which the ast-grep rule's pattern gap was missing.
        sourceScope()
            .interfaces()
            .filter { !unixPath(it.path).contains("Test/") }
            .filter { !it.hasAnnotationWithName("Serializable") && !it.hasPrivateModifier }
            .assertFalse { it.hasSealedModifier }
    }

    @Test
    fun `providers do not depend on ui or framework layer`() {
        commonMainFiles()
            .filter { unixPath(it.path).contains("/providers/") }
            .assertFalse { it.dependsOnPackage("ai.torad.aisdk.ui") }
    }

    @Test
    fun `core agent protocol and ui layers do not import provider implementations`() {
        commonMainFiles()
            .filter { !unixPath(it.path).contains("/providers/") }
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
            .filter { unixPath(it.path).contains("/protocol/") }
            .assertFalse { file ->
                file.dependsOnPackage("ai.torad.aisdk.providers") ||
                    file.dependsOnPackage("ai.torad.aisdk.ui") ||
                    file.dependsOnPackage("ai.torad.aisdk.middleware") ||
                    forbiddenAgentRuntimeTerms.any { term -> file.text.contains(term) }
            }
    }

    /**
     * This repository's own Kotlin sources, and nothing else.
     *
     * `Konsist.scopeFromProject()` parses every `.kt` under the repo root, which pulls in the
     * campaign sandboxes (see the class KDoc). `src/` is the only tree these tenets describe.
     */
    private fun sourceScope(): KoScope = Konsist.scopeFromDirectory("src")

    private fun commonMainFiles(): List<KoFileDeclaration> = sourceScope()
        .files
        .filter { unixPath(it.path).contains("/src/commonMain/") }

    /** Konsist returns OS-native separators; normalize so filters match on Windows too. */
    private fun unixPath(path: String): String = path.replace('\\', '/')

    private fun KoFileDeclaration.dependsOnPackage(packageName: String): Boolean {
        val packagePrefix = "$packageName."
        return imports.any { import ->
            import.name == packageName || import.name.startsWith(packagePrefix)
        }
    }
}
