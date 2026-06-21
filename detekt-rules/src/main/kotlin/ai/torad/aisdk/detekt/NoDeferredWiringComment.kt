package ai.torad.aisdk.detekt

// detekt-api ships kotlin-compiler-embeddable, which relocates `com.intellij.*` under
// `org.jetbrains.kotlin.com.intellij.*` — use the relocated PSI type.
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Mirror of `.claude/hooks/rules/kotlin/no-deferred-wiring-comment.yaml`.
 *
 * A comment admitting that a declaration was added without connecting its producer is the
 * fingerprint the pipeline-xray uncovered for non-integrated code: a public surface that
 * nothing populates. Connect it now or delete it — don't leave a comment promising it.
 * (The trigger phrases live in [DEFERRED] below; they are kept out of this doc comment so
 * the rule does not flag its own source.)
 */
class NoDeferredWiringComment(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoDeferredWiringComment",
        severity = Severity.Defect,
        description = "A comment admitting non-integration is the fingerprint of dead-on-arrival code; " +
            "integrate it now or remove it.",
        debt = Debt.TWENTY_MINS,
    )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        file.collectDescendantsOfType<PsiComment>().forEach { comment ->
            if (DEFERRED.containsMatchIn(comment.text)) {
                report(
                    CodeSmell(issue, Entity.from(comment), "Non-integration comment — integrate the code or delete it."),
                )
            }
        }
    }

    private companion object {
        val DEFERRED = Regex(
            "(?i)(wired|populate[ds]?) .{0,40}(later|follow-?up)|staged in as a follow-?up|" +
                "type surface is in place|loop hasn.t yet wired|hasn.t yet wired the field|wire[ds]? the field",
        )
    }
}
