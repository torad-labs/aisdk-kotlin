package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtThrowExpression

/**
 * Mirror of `.claude/hooks/rules/kotlin/prefer-typed-error-over-error-call.yaml`.
 * `error(...)` throws a raw `IllegalStateException`; this SDK models failures with the
 * sealed `AiSdkException` hierarchy so callers can handle them exhaustively.
 */
class PreferTypedErrorOverErrorCall(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "PreferTypedErrorOverErrorCall",
        severity = Severity.Style,
        description = "Prefer a typed `AiSdkException` subtype over `error(...)`, which throws a raw " +
            "IllegalStateException callers cannot handle exhaustively.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text == "error") {
            report(CodeSmell(issue, Entity.from(expression), issue.description))
        }
    }
}

/**
 * Mirror of `.claude/hooks/rules/kotlin/prefer-typed-error-over-checknotnull.yaml`.
 * `checkNotNull(...)` throws a raw `IllegalStateException`; prefer a typed failure.
 */
class PreferTypedErrorOverCheckNotNull(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "PreferTypedErrorOverCheckNotNull",
        severity = Severity.Style,
        description = "Prefer a typed `AiSdkException` subtype over `checkNotNull(...)`, which throws a " +
            "raw IllegalStateException.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text == "checkNotNull") {
            report(CodeSmell(issue, Entity.from(expression), issue.description))
        }
    }
}

/**
 * Mirror of `.claude/hooks/rules/kotlin/prefer-typed-error-over-generic-throw.yaml`.
 * Throwing a generic JDK exception bypasses the sealed `AiSdkException` contract.
 */
class PreferTypedErrorOverGenericThrow(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "PreferTypedErrorOverGenericThrow",
        severity = Severity.Style,
        description = "Throw a typed `AiSdkException` subtype, not a generic JDK exception, so callers " +
            "can handle failures exhaustively.",
        debt = Debt.TEN_MINS,
    )

    override fun visitThrowExpression(expression: KtThrowExpression) {
        super.visitThrowExpression(expression)
        val callee = (expression.thrownExpression as? KtCallExpression)?.calleeExpression?.text ?: return
        if (callee in GENERIC_EXCEPTIONS) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "Throwing `$callee` — use a typed AiSdkException subtype instead.",
                ),
            )
        }
    }

    private companion object {
        val GENERIC_EXCEPTIONS = setOf(
            "RuntimeException",
            "Exception",
            "Error",
            "Throwable",
            "IllegalStateException",
            "IllegalArgumentException",
            "UnsupportedOperationException",
            "NullPointerException",
        )
    }
}
