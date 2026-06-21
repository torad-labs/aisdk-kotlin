package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Mirror of `.claude/hooks/rules/kotlin/no-json-container-force-cast.yaml`.
 * Force-casting to a kotlinx.serialization container (`as JsonObject`) throws at runtime
 * on a shape mismatch — decode into a typed model, or use the safe `?.jsonObject` accessors.
 */
class NoJsonContainerForceCast(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoJsonContainerForceCast",
        severity = Severity.Defect,
        description = "Force-casting to a Json container (`as JsonObject`/`as JsonArray`) throws at " +
            "runtime on a shape mismatch; decode into a typed model or use the safe `jsonObject` accessors.",
        debt = Debt.TEN_MINS,
    )

    override fun visitBinaryWithTypeRHSExpression(expression: KtBinaryExpressionWithTypeRHS) {
        super.visitBinaryWithTypeRHSExpression(expression)
        val op = expression.operationReference.text
        if (op != "as" && op != "as?") return
        val type = expression.right?.text?.substringBefore("<")?.trim() ?: return
        if (type in JSON_CONTAINERS) {
            report(
                CodeSmell(issue, Entity.from(expression), "Force-cast `$op $type` — decode into a typed model instead."),
            )
        }
    }

    private companion object {
        val JSON_CONTAINERS = setOf("JsonObject", "JsonArray", "JsonPrimitive")
    }
}

/**
 * Mirror of `.claude/hooks/rules/kotlin/no-inline-json-instance.yaml`.
 * `Json { }` built inline allocates a fresh serializers module per call site; hoist a
 * single shared `Json` instance (the SDK's `aiSdkJson`) and reuse it.
 */
class NoInlineJsonInstance(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoInlineJsonInstance",
        severity = Severity.Style,
        description = "Constructing `Json { }` inline allocates a new serializers module per call; " +
            "reuse a single shared `Json` instance.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text == "Json" && expression.lambdaArguments.isNotEmpty()) {
            report(CodeSmell(issue, Entity.from(expression), issue.description))
        }
    }
}
