package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtPostfixExpression

/**
 * Mirror of `.claude/hooks/rules/kotlin/no-not-null-assertion.yaml`.
 *
 * The `!!` not-null assertion throws `NullPointerException` at runtime — it converts a
 * compile-time nullability question into a runtime crash. Model absence in the type system
 * instead (a sealed/typed value, an `?:` typed fallback, or an explicit null check).
 */
class NoNotNullAssertion(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoNotNullAssertion",
        severity = Severity.Defect,
        description = "The `!!` not-null assertion throws at runtime on null; model absence in the " +
            "type system (sealed/typed value or a typed `?:` fallback) instead.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitPostfixExpression(expression: KtPostfixExpression) {
        super.visitPostfixExpression(expression)
        if (expression.operationToken == KtTokens.EXCLEXCL) {
            report(CodeSmell(issue, Entity.from(expression), issue.description))
        }
    }
}
