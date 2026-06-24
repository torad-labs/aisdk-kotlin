package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Guardrail for the cancellation hole detekt's built-in SuspendFunSwallowedCancellation
 * cannot currently enforce in this KMP classpath-less detekt setup.
 *
 * stdlib runCatching catches Throwable and packages CancellationException into a Result.
 * In suspend code that fakes a recoverable failure and breaks structured cancellation.
 */
class NoRunCatchingInSuspendFunction(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoRunCatchingInSuspendFunction",
        severity = Severity.Defect,
        description = "Do not call stdlib runCatching inside suspend functions; it swallows CancellationException.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "runCatching") return
        val function = expression.getStrictParentOfType<KtNamedFunction>() ?: return
        if (function.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "runCatching in suspend function '${function.name ?: "<anonymous>"}' swallows CancellationException; use explicit try/catch that rethrows cancellation.",
                ),
            )
        }
    }
}
