package ai.torad.aisdk.arch

import com.lemonappdev.konsist.api.Konsist
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

    // NOTE: a `no sealed interfaces in production` invariant is intentionally NOT added yet —
    // it surfaced 5 pre-existing sealed interfaces in commonMain (UIMessagePart, JSONRPCMessage,
    // ResponseFormat = @Serializable polymorphic wire types; ToolChoice = public config; State =
    // private state machine) that the ast-grep no-sealed-interface rule's pattern gap was missing.
    // Pending the tenet decision (carve out @Serializable/private vs strict) + the ast-grep rule fix.
}
