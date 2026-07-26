export const meta = {
  name: 'stringly-domain-types-premise-audit',
  description: 'Audit every campaign item before implementation and correct over-pinned or already-settled premises',
  phases: [
    { title: 'Premise', detail: 'attack item wording, prior art, pools, and expected deltas (opus)' },
  ],
}

const DOMAIN_LAWS = `
REPO: /home/marcos/Documents/dev/opensource/aisdk-kotlin
MECHANICAL ADJUDICATORS:
 * focused JVM provider/UI tests for each item
 * ./gradlew check after each wave
 * bash .claude/hooks/rules/ci-gate.sh at campaign close

VERIFIED SHARED FACTS:
 * build.gradle.kts:48-93 enables KMP explicitApi() and committed ABI validation.
 * build.gradle.kts:148-179 defines commonMain/commonTest and the target hierarchy.
 * .claude/hooks/rules/ci-gate.sh:49-159 is the repository architecture gate.
 * The combined pre-campaign dirty tree passed ./gradlew check and ci-gate on 2026-07-25.
 * Public construct-types use @Poko or regular classes with internal constructors and builders; no new public data classes.
 * Public API changes require updateKotlinAbi plus CHANGELOG.md and INTERFACE_CONTRACT.md.
 * Closed types are for SDK-owned routing/lifecycle domains. Provider-open outbound vocabularies retain custom(nonblank); inbound open vocabularies retain Unknown(raw) when forward compatibility requires it.
 * Existing dirty-tree changes are active work and must be preserved. GoogleInteractionsMapping.kt and GoogleInteractionsStreamState.kt already have injected-generateId corrections.
 * Explicit keep-as-String decisions in full-sweep.md are binding unless a concrete behavior defect proves otherwise.
`

const PREMISE_SCHEMA = {
  type: 'object',
  required: ['id', 'premise_verdict', 'prior_art', 'structural_assumptions', 'measured_pools', 'corrected_item', 'expected_delta_instrument', 'acceptance_floor'],
  properties: {
    id: { type: 'string' },
    premise_verdict: {
      type: 'string',
      enum: ['SOUND', 'OVER_PINNED', 'ALREADY_SETTLED', 'FACTUALLY_WRONG', 'BLOCKED_ON_PRECONDITION'],
    },
    prior_art: { type: 'string', description: 'Existing/reverted attempts with paths and git-history commands; or searches proving none found.' },
    structural_assumptions: { type: 'string', description: 'Each load-bearing claim checked against current code with file:line and commands.' },
    measured_pools: { type: 'string', description: 'Every implied vocabulary/candidate pool: actual size and largest defensible alternative, with commands.' },
    corrected_item: { type: 'string', description: 'A minimal implementable item. Prefer narrowing/widening and compatibility knobs over killing useful work.' },
    expected_delta_instrument: { type: 'string', description: 'Pre-change observable, baseline value, predicted direction, and command.' },
    acceptance_floor: { type: 'string', description: 'The least strict mechanically meaningful landing: required behavior/tests versus optional follow-up polish or knobs.' },
  },
}

const items = typeof args === 'string' ? JSON.parse(args) : (Array.isArray(args) ? args : [])
if (!items.length) throw new Error('premise-audit: no items')

log(`Premise-auditing ${items.length} full-sweep items before implementation waves`)

const results = await parallel(items.map((item) => () => agent(
  `You are the PREMISE AUDITOR for one Kotlin/KMP library campaign item. You write NO code and change NO files.

A finding is success. Attack the ITEM, not a hypothetical implementation. Read the target files end-to-end and inspect recent history with git log -p before judging. Re-run every command you cite.

${DOMAIN_LAWS}

ITEM:
${JSON.stringify(item, null, 1)}

Required sequence:
1. Search current code and recent history for prior art, partial landings, and reverted attempts.
2. Check every factual claim against the current combined worktree; names are not evidence.
3. Measure every named or implied vocabulary/pool and the largest defensible alternative.
4. Define an expected-delta instrument before implementation.
5. Restate the item into the smallest useful mechanically testable landing.

Do NOT be perfectionist. Most provider integrations need compatibility bridges, precedence knobs, and staged support. Missing optional polish is not a reason to kill an item. Prefer a corrected minimal landing with explicit follow-up boundaries. ALREADY_SETTLED or FACTUALLY_WRONG requires a path plus a command whose output mechanically proves it. BLOCKED_ON_PRECONDITION is for a real prerequisite whose absence makes implementation dishonest, not for design preference.

Return evidence only. Do not write files.`,
  { model: 'opus', label: `premise:${item.id}`, phase: 'Premise', schema: PREMISE_SCHEMA },
)))

return results.filter(Boolean)
