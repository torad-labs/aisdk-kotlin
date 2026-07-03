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
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Guardrail for the cancellation hole detekt's built-in SuspendFunSwallowedCancellation
 * cannot currently enforce in this KMP classpath-less detekt setup.
 *
 * stdlib runCatching catches Throwable and packages CancellationException into a Result.
 * In suspend code that fakes a recoverable failure and breaks structured cancellation.
 *
 * This rule also scans common coroutine-builder bodies for the type-free case it
 * can prove: runCatching that calls a suspend function declared in the same file.
 * Imported or receiver suspend calls still need type resolution to identify
 * reliably; genuinely pure Result wrapping inside a builder remains allowed.
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
        val function = expression.getStrictParentOfType<KtNamedFunction>()
        if (function?.hasModifier(KtTokens.SUSPEND_KEYWORD) == true) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "runCatching in suspend function '${function.name ?: "<anonymous>"}' swallows CancellationException; use explicit try/catch that rethrows cancellation.",
                ),
            )
            return
        }
        val builderName = expression.enclosingCoroutineBuilderName() ?: return
        if (!expression.callsKnownSuspendFunction()) return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "runCatching in coroutine builder '$builderName' swallows CancellationException; use explicit try/catch that rethrows cancellation.",
            ),
        )
    }

    private fun KtCallExpression.callsKnownSuspendFunction(): Boolean {
        val body = runCatchingBody() ?: return false
        val suspendNames = containingKtFile.collectDescendantsOfType<KtNamedFunction>()
            .filter { it.hasModifier(KtTokens.SUSPEND_KEYWORD) }
            .mapNotNull { it.name }
            .toSet()
        if (suspendNames.isEmpty()) return false
        return body.collectDescendantsOfType<KtCallExpression>()
            .any { it.calleeExpression?.text in suspendNames }
    }

    private fun KtCallExpression.runCatchingBody(): KtLambdaExpression? =
        lambdaArguments.firstOrNull()?.getLambdaExpression()
            ?: valueArguments.asSequence()
                .mapNotNull { it.getArgumentExpression() as? KtLambdaExpression }
                .firstOrNull()

    private fun KtCallExpression.enclosingCoroutineBuilderName(): String? {
        var lambda = getStrictParentOfType<KtLambdaExpression>() ?: return null
        while (true) {
            val call = lambda.parentCallExpression()
            val callName = call?.calleeExpression?.text
            if (callName in coroutineBuilderNames) return callName
            lambda = call?.getStrictParentOfType() ?: return null
        }
    }

    private fun KtLambdaExpression.parentCallExpression(): KtCallExpression? {
        val firstParent = parent
        return when (firstParent) {
            is KtCallExpression -> firstParent
            is KtLambdaArgument -> firstParent.parent as? KtCallExpression
            is KtValueArgument -> (firstParent.parent as? KtValueArgumentList)?.parent as? KtCallExpression
            else -> null
        }
    }

    private companion object {
        val coroutineBuilderNames = setOf(
            "launch",
            "async",
            "withContext",
            "coroutineScope",
            "supervisorScope",
        )
    }
}
