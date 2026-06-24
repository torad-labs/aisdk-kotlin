package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Registers the `torad-aisdk` custom ruleset with detekt. Discovered at runtime via
 * `META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider`.
 *
 * Each rule here mirrors an ast-grep PreToolUse hook rule under
 * `.claude/hooks/rules/kotlin/`, re-expressed as a detekt rule so the same architectural
 * tenet is enforced for every developer in the IDE + `./gradlew check`, not only for
 * Claude's edits. Rules are configured (active/inactive) under the `torad-aisdk` block in
 * `detekt.yml`.
 */
class ToradRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "torad-aisdk"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            NoNotNullAssertion(config),
            NoJsonContainerForceCast(config),
            NoInlineJsonInstance(config),
            NoDeferredWiringComment(config),
            NoConsoleOutputInLibrary(config),
            NoFloatEqualityComparison(config),
            PreferTypedErrorOverErrorCall(config),
            PreferTypedErrorOverCheckNotNull(config),
            PreferTypedErrorOverGenericThrow(config),
            NoRunCatchingInSuspendFunction(config),
        ),
    )
}
