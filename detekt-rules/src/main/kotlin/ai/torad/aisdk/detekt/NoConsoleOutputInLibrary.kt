package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Mirror of `.claude/hooks/rules/kotlin/no-console-output-in-library.yaml`.
 * A library must not write to stdout/stderr — diagnostics belong on the injected `Logger`
 * so the host application controls sinks and levels. Configure `excludes` in detekt.yml to
 * scope this to non-test sources.
 */
class NoConsoleOutputInLibrary(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoConsoleOutputInLibrary",
        severity = Severity.Defect,
        description = "A library must not write to the console; route diagnostics through the injected Logger.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        if (callee != "println" && callee != "print") return
        // Skip member calls (`receiver.print()`); only the bare top-level kotlin.io functions.
        val parent = expression.parent
        val isMemberCall = parent is KtDotQualifiedExpression && parent.selectorExpression == expression
        if (!isMemberCall) {
            report(CodeSmell(issue, Entity.from(expression), "`$callee(...)` writes to the console — use the Logger."))
        }
    }
}
