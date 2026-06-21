package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Mirror of `.claude/hooks/rules/kotlin/no-float-equality-comparison.yaml`.
 * Exact `==`/`!=` against a floating-point literal is unreliable (rounding); compare within
 * a tolerance. Without type resolution this matches the literal-operand form the ast-grep
 * rule also targets.
 */
class NoFloatEqualityComparison(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoFloatEqualityComparison",
        severity = Severity.Style,
        description = "Exact `==`/`!=` on a floating-point value is unreliable; compare within a tolerance " +
            "(abs(a - b) < epsilon).",
        debt = Debt.TEN_MINS,
    )

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        val op = expression.operationToken
        if (op != KtTokens.EQEQ && op != KtTokens.EXCLEQ) return
        if (isFloatLiteral(expression.left) || isFloatLiteral(expression.right)) {
            report(CodeSmell(issue, Entity.from(expression), "Exact floating-point comparison — use a tolerance."))
        }
    }

    private fun isFloatLiteral(expression: KtExpression?): Boolean {
        val text = (expression as? KtConstantExpression)?.text ?: return false
        return text.endsWith("f") || text.endsWith("F") || (text.contains('.') && text.toDoubleOrNull() != null)
    }
}
