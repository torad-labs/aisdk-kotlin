// adversarial-loop — workflow template.
//
// Copy this file, fill the two slots below, then invoke with:
//   Workflow({ scriptPath: "<copy>", args: [ ...items ] })
// NEVER invoke by name: — name-invoked workflows are uninspectable and blocked.
//
// Every spawn call MUST carry an explicit model: option. Reviewers are always opus.
// (Worded without the literal call token on purpose: the model-pin guard text-scans the
//  script and counts a mention inside a comment as an unpinned call site, so a template
//  that documents the law in the obvious way fails the guard before it ever runs.)

export const meta = {
  name: 'stringly-domain-types-adversarial-loop',
  description: 'Implement Kotlin domain-type migrations, adversarially review the patches, and fix findings forward',
  phases: [
    { title: 'Premise', detail: 're-check the item against current Kotlin source and history (opus)' },
    { title: 'Implement', detail: 'implement and test one isolated patch per item (opus)' },
    { title: 'Review', detail: '3 prosecutors + 1 advocate reproduce and attack the patch (opus)' },
    { title: 'Plan', detail: 'adjudicate objections with commands and pin repair tests (opus)' },
    { title: 'Repair', detail: 'fresh implementer repairs without loyalty to the first patch (opus)' },
    { title: 'Re-review', detail: 'verify the repaired class and regression boundary (opus)' },
  ],
}

// THE DEFAULT IS FIX-FORWARD. A defect found in review does not end an item — it routes to a
// fixer-planner, which adjudicates every objection by RUNNING the command that settles it,
// converts the survivors into executable required test cases, and hands a pinned spec to a
// FRESH implementer. The only terminal states are LAND and ESCALATE-to-orchestrator.
//
// There is deliberately no DROP in the shipped enums. Rejection is still reachable and still
// valuable — it arrives as ESCALATE carrying the artifact, the plan, and the surviving
// objections, so a human closes the item with the evidence in hand rather than an agent
// closing it on its own word. Adding a DROP value back is a supported adaptation for domains
// where items are cheap and plentiful (bulk lint/codemod batches, where dropping genuinely
// closes the question). If you add it, keep the bar: a DROP must cite an instrument that was
// actually RUN and did not move. See references/fix-forward-variant.md for when each shape fits.
//
// The tell for which you want: ask what happens to the QUESTION when the item drops. If the
// question outlives the item, dropping it moves work off the ledger without doing it.

// NOTE ON VOCABULARY: every enum below is a REFERENCE SET, not a fixed API. Rename the values
// to whatever your domain already says. What must survive renaming is the expressible set —
// the schemas have to be able to report "right but scoped too small", "faithful artifact but
// wrong item", and "gates green but the instrument never moved". Those three outcomes are the
// ones a loop without them silently converts into rejections.
//
// Rename the ADJUDICATOR word too. This template says GATE; that word is reserved in some
// repos (one campaign's ledger reserves it for external reference models, and a skill-word
// leak into a landing note read as a rules violation). Pick the domain's word — gate, wall,
// adjudicator — and use it in DOMAIN_LAWS, schemas, and prompts alike.

// ============================================================================
// SLOT 1 — DOMAIN_LAWS. Binding constraints. Violating one makes a proposal
// INVALID, not merely worse. Include the scoping facts that decide whether the
// work is even enforced.
// ============================================================================
const DOMAIN_LAWS = `
REPO: /home/marcos/Documents/dev/opensource/aisdk-kotlin
BASELINE PATCH: the absolute path supplied as item.baseline_patch. It snapshots the combined source checkout immediately before this wave and is the context to preserve.
GATE: run the item's exact verify command; wave landings also run ./gradlew check; campaign close runs bash .claude/hooks/rules/ci-gate.sh.

BINDING LAWS — a patch violating any of these is INVALID:
 * Work from release/v0.3.0-beta01. Preserve the entire pre-campaign dirty patch and never commit.
 * This is a published Kotlin Multiplatform library with explicitApi() and committed JVM/KLIB ABI dumps.
 * Public construct-types use @Poko or regular classes with internal constructors plus builders/factories. No new public data classes.
 * Public API changes include intentional ABI dump changes, CHANGELOG.md, and INTERFACE_CONTRACT.md.
 * v0.3.0-beta01 is already published. New public APIs and deprecations in this campaign target 0.3.0-beta02 even though work remains on branch release/v0.3.0-beta01; V1 updates VERSION_NAME.
 * A typed compatibility API canonicalizes into one raw wire value. Never maintain independently mutable typed and raw sources of truth.
 * Closed enums/sealed types are only for SDK-owned routing or lifecycle domains. Provider-open outbound domains use named values plus custom(nonblank); inbound open domains preserve Unknown(raw) where policy is forward-compatible.
 * Enum names never define wire values. Map every wire value explicitly and preserve primitive provider JSON strings.
 * Avoid nullable same-name typed/raw overloads; null calls must not become ambiguous.
 * Keep protocol discriminators, diagnostics, MIME values, caller extension namespaces, and opaque external IDs as String unless this item proves a concrete error class and scopes a deliberate whole-surface migration.
 * Do not create a generic shared poller or PollState; provider transition semantics materially differ.
 * Match existing style. Touch only item-scoped files plus mechanically required tests/docs/API dumps.
 * Never bypass a gate or weaken an existing test. A gate defect is repaired with a red-green fixture.

VERIFIED SCOPING FACTS:
 * build.gradle.kts:48-93 enables explicit API and ABI validation.
 * build.gradle.kts:148-179 defines common production and test source sets.
 * .claude/hooks/rules/ci-gate.sh:49-159 scans production Kotlin, API readiness, rule fixtures, and public data-class budget.
 * The combined pre-campaign dirty tree passed ./gradlew check and ci-gate on 2026-07-25.
 * GoogleInteractionsMapping.kt and GoogleInteractionsStreamState.kt already contain uncommitted injected-generateId corrections; preserve them.
 * The campaign is fix-forward: a real defect becomes a repair test. Optional polish beyond the written scope becomes a follow-up proposal, not a rejection.
`

// ============================================================================
// SLOT 2 — MECHANICS_GUIDE. The toolchain's silent traps. Write from failures
// you actually hit. Each entry should be a trap that yields a
// broken-but-plausible result rather than a loud error.
// ============================================================================
const MECHANICS_GUIDE = `
MECHANICS — broken-but-plausible traps for this Kotlin/KMP campaign.

1. WORKTREES ARE FORBIDDEN IN THIS REPO. Never run git worktree and never request workflow isolation. Each role gets a unique ordinary local clone path in its prompt. Create it with git clone --shared /home/marcos/Documents/dev/opensource/aisdk-kotlin <sandbox>, then apply item.baseline_patch and stage it inside that clone. The staged patch is context to preserve; the returned artifact is ONLY the unstaged campaign delta from git -C <sandbox> diff --binary --no-ext-diff.
2. Read every target production file end-to-end and inspect git log -p --since='45 days ago' before proposing a cause or API. Ast-grep, not grep, owns Kotlin structural search.
3. Compile/test behavior, not builder equality alone. Provider settings must reach exact request JSON/multipart/query fields and response decoding assertions.
4. Public value types follow this repository's unusual but settled ABI doctrine: produced read-only @Poko classes may have public constructors; consumer construct-types need internal constructors and DSL builders. Do not substitute generic Kotlin advice.
5. updateKotlinAbi overwrites both committed API snapshots. Include only intentional API changes and reconcile against the staged baseline; never hand-edit dumps.
6. The architecture gate may autofix registered mechanical style rules. If it changes a file, include and understand that change; never discard or bypass it.
7. Unknown provider values are policy decisions. Fail fast only where an unknown value would select a wrong endpoint, masquerade as success, or falsely time out; preserve raw unknowns where forward compatibility is intended.
8. Polling tests must assert request counts and final-fetch behavior. A repeat lambda return does not exit the polling function, and a final fetch can be skipped if terminality is checked only at loop entry.
9. Serialization compatibility is a separate gate from Kotlin type safety. Public UI lifecycle work must round-trip the existing flat wire shape and persisted messages, not merely compile a sealed hierarchy.
10. Kotlin deprecations require a version and replacement that satisfies repository rules. Do not remove a public symbol in this pre-beta campaign unless the corrected item and ABI/doc changes explicitly call for it.
11. Return one complete git patch in artifact, with written_scope and exact apply/test instructions. Do not return prose pretending to be a patch.
12. Acceptance is intentionally pragmatic: land the smallest mechanically meaningful improvement with compatibility knobs. Treat stronger ergonomics or additional provider values outside written_scope as follow-up proposals, not blockers.
13. Resource law: run only item.verify and narrowly targeted mutation tests inside agents. Do NOT run ./gradlew check, detekt over the whole repository, Kotlin/Native tasks, ci-gate.sh, or updateKotlinAbi. The orchestrator runs wave/full gates once, serially, after applying accepted patches. Reviewers execute sequentially to prevent concurrent Gradle/Kotlin Native builds.
14. Before every Gradle invocation, inspect active Gradle-wrapper processes and available memory. If another wrapper outside your named sandbox is active, wait and recheck instead of overlapping it. Never kill or modify unrelated builds. Do not start while available memory is below 12 GiB or swap is exhausted; wait for the unrelated build to release resources. Long-lived idle Gradle/Kotlin daemons alone are not active wrapper builds.
15. The baseline patch may include selected untracked source files as new-file hunks. Apply the whole patch and stage with git add -A so those files are preservation context, not part of your unstaged item artifact.
`

const SANDBOX_ROOT = '/home/marcos/Documents/dev/opensource/aisdk-kotlin/dev/campaigns/stringly-domain-types/sandboxes'

// ---------------------------------------------------------------- schemas ---

// The premise auditor writes no code. Its whole job is deciding whether the item asks for the
// right thing — the one question the gate structurally cannot answer.
const PREMISE_SCHEMA = {
  type: 'object',
  required: ['id', 'premise_verdict', 'measured_pools', 'prior_art', 'corrected_item', 'expected_delta_instrument'],
  properties: {
    id: { type: 'string' },
    premise_verdict: {
      type: 'string',
      enum: ['SOUND', 'OVER_PINNED', 'ALREADY_SETTLED', 'FACTUALLY_WRONG', 'BLOCKED_ON_PRECONDITION'],
      description: 'OVER_PINNED = the item names a narrow pool where a larger defensible one exists',
    },
    measured_pools: {
      type: 'string',
      description: 'REQUIRED. For every candidate set the item names OR IMPLIES: its actual measured size, the command that measured it, AND the size of the largest defensible alternative. Both numbers. A measured batch produced two false DROPs because a spec pinned a pool of ~21 where 553 was available and nobody measured the second number.',
    },
    prior_art: {
      type: 'string',
      description: 'REQUIRED. Existing implementations, receipts, and REVERTED attempts bearing on this item, with paths. "none found, searched X" is valid only after searching. On one five-item batch this caught three already-settled items.',
    },
    structural_assumptions: {
      type: 'string',
      description: 'Each factual claim the item makes, checked against the real tree, marked TRUE or FALSE with the command.',
    },
    corrected_item: {
      type: 'string',
      description: 'REQUIRED. The item restated so it is implementable and not over-pinned. If the premise is sound, restate unchanged and say so.',
    },
    expected_delta_instrument: {
      type: 'string',
      description: 'REQUIRED. The count that MUST move if this lands, its current value, and the expected direction/magnitude — fixed BEFORE implementation so it cannot be chosen to flatter the result.',
    },
  },
}

const ARTIFACT_SCHEMA = {
  type: 'object',
  required: ['id', 'recommendation', 'written_scope', 'premise_check', 'artifact', 'integration_point', 'verification', 'known_soft_spots'],
  properties: {
    id: { type: 'string' },
    written_scope: { type: 'string', description: 'Exact behavior and files this patch claims. Include explicit non-goals so reviewers distinguish defects from follow-up scope proposals.' },
    recommendation: {
      type: 'string',
      // NO DROP by default — a dead end is a ROUTING decision, not an outcome. NEEDS_FIX sends
      // the item to the fixer-planner. LAND_WIDENED exists because its absence is what
      // manufactured two retracted DROPs: with only narrow-or-kill exits, "the item is too
      // small" has to leave as a rejection.
      enum: ['LAND', 'LAND_NARROWED', 'LAND_WIDENED', 'NEEDS_FIX'],
      description: 'LAND_WIDENED = the item as written was over-constrained; this implements the larger correct scope. NEEDS_FIX = you could not make it correct — that routes to a fixer-planner, NOT to a bin. Overclaiming is the only move that costs, because it burns a repair round.',
    },
    needs_fix_reason: {
      type: 'string',
      description: 'Required when NEEDS_FIX. What you could not make correct, what you already ruled out with which command, and your best read of the fix direction. This is an input to the planner, so specificity here buys a better repair.',
    },
    premise_check: {
      type: 'string',
      description: 'REQUIRED. Quote each constraint the item imposed; judge each sound or over-pinned WITH the measured size of both the pinned scope and the defensible one.',
    },
    artifact: {
      type: 'string',
      description: 'A COMPLETE git-apply-compatible patch produced by git diff --binary --no-ext-diff after staging item.baseline_patch. Patch only: no prose preamble or epilogue.',
    },
    integration_point: { type: 'string', description: 'Exact command to apply the patch, list of changed files, and the item verify command to run after application.' },
    bad_example: { type: 'string', description: 'input that MUST be caught; include enclosing context' },
    good_example: { type: 'string', description: 'compliant counterpart that must NOT be caught' },
    verification: {
      type: 'object',
      required: ['gate_result', 'positive_case', 'negative_case', 'cannot_fail_probe', 'expected_delta'],
      properties: {
        gate_result: { type: 'string', description: 'the gate command run and its exact output' },
        ran_it: { type: 'string', description: 'If the artifact is EXECUTABLE, proof you ran it: the command and the numbers it actually printed. Code you did not execute is not an artifact.' },
        positive_case: { type: 'string', description: 'proof the bad example IS caught' },
        negative_case: { type: 'string', description: 'proof the good example is NOT caught' },
        compliant_probe: { type: 'string', description: 'genuinely compliant shapes tried, and the result' },
        // The gate cannot detect a check that never fires, so the contract must demand the
        // proof. Measured: on one 11-item batch this was the DOMINANT defect class.
        cannot_fail_probe: {
          type: 'string',
          description: 'REQUIRED but pragmatic. Prove the key behavioral claim CAN fail with one representative production mutation and a permanent focused test that goes red. Add more mutations only when the item has materially independent branches; do not multiply Gradle runs to satisfy a numeric quota.',
        },
        // Green gates prove the suite passed, never that the change did anything. Naming the
        // instrument up front is what separates a landing from a plausible no-op.
        expected_delta: {
          type: 'string',
          description: 'REQUIRED. The instrument the premise phase named: value BEFORE, value AFTER, and whether that matches the prediction. An unmoved instrument is a FALSE LANDING even with every gate green. Correctly unmoved is a valid result when that was the prediction.',
        },
        real_occurrences: { type: 'string', description: 'hits in the real tree with file:line, or "none"' },
      },
    },
    duplication_check: { type: 'string', description: 'the exact existing owner checked, and why this is not a duplicate' },
    // Sealed confession: the builder's own doubt is the best recall instrument the loop has.
    // Withheld from reviewers until their findings are filed; the planner (contested) or the
    // orchestrator (clean landing) must account for every entry.
    known_soft_spots: {
      type: 'string',
      description: 'REQUIRED. Where YOU would attack this artifact: the weakest joints, the untested corners, the claim you are least sure of. Sealed from reviewers until after they file findings, then checked — each spot is found by a reviewer, proven safe with a command, or carried into the repair. "None" is overclaiming and reads as such.',
    },
  },
}

const REVIEW_SCHEMA = {
  type: 'object',
  required: ['id', 'confirmed_holds', 'verdict', 'locus', 'route', 'evidence'],
  properties: {
    id: { type: 'string' },
    // The affirmative phase: what the reviewer REPRODUCED from the artifact's own claims
    // before hunting. This is the positive-evidence channel the orchestrator verifies
    // landings against — without it the review is purely destructive and a fluent artifact's
    // actual claims go unchecked.
    confirmed_holds: {
      type: 'string',
      description: 'REQUIRED. The artifact\'s own verification claims you re-ran and confirmed BEFORE hunting: the gate, positive/negative cases, cannot-fail probes — commands and outputs. A claim you could not reproduce is a finding: put it in evidence with a FIX_FORWARD route.',
    },
    verdict: {
      type: 'string',
      enum: ['SOUND', 'MISFIRES', 'CANNOT_FAIL', 'DUPLICATE', 'INERT', 'INVALID_UNDER_LAW',
        'UNVERIFIED_CLAIM', 'FALSE_LANDING', 'CEILING_IS_IN_THE_SPEC', 'PREMISE_FALSE',
        'REGRESSION', 'ABI_OR_WIRE_BREAK', 'TEST_GAP'],
      description: 'SOUND = no defect WITHIN the WRITTEN scope of the artifact; a sibling case beyond that boundary is a PROPOSAL and belongs in required_change under a SOUND verdict, never in the verdict itself — an unbounded class (every possible input, every possible fabrication) is unclosable by construction and drawing not-SOUND from it forever measures the prompt, not the work. CANNOT_FAIL = the check is structurally incapable of going red; worse than absent, because it reads as coverage forever. FALSE_LANDING = gates green but the expected-delta instrument never moved. CEILING_IS_IN_THE_SPEC = the artifact faithfully implements an over-constrained item; the fix is widening the item.',
    },
    locus: {
      type: 'string',
      enum: ['NO_FINDING', 'IN_SCOPE_DEFECT', 'SCOPE_PROPOSAL'],
      description: 'Does the counterexample break the artifact\'s WRITTEN scope (IN_SCOPE_DEFECT) or extend it (SCOPE_PROPOSAL)? Both are valuable and route differently: a scope proposal is a new item, never this artifact\'s failure — name the new item in required_change. Labeling the locus is what stops an extension arriving dressed as a conviction.',
    },
    // Verdict says WHAT is wrong; route says WHERE IT GOES. Kept as separate fields on purpose:
    // a reviewer asked to emit one field conflates severity with disposition, and a reviewer who
    // believes rejection is unavailable will quietly soften the verdict instead. Let the verdict
    // stay harsh and let routing be routing.
    route: {
      type: 'string',
      enum: ['ACCEPT', 'FIX_FORWARD'],
      description: 'ACCEPT only when you genuinely tried to break it and could not. Anything else is FIX_FORWARD, which is NOT a rejection — it hands the item to a fixer-planner that turns your counterexample into a required test case. A finding is a contribution, not a veto.',
    },
    evidence: { type: 'string', description: 'commands actually run and what they returned' },
    counterexample: {
      type: 'string',
      description: 'A concrete input where the artifact is wrong, or where a real defect passes undetected. This becomes a REQUIRED TEST CASE for the repair — make it EXECUTABLE and specific. A vague objection produces a vague repair.',
    },
    required_change: { type: 'string' },
  },
}

// The fixer-planner writes NO code. It adjudicates, pins, and hands off. That separation is the
// point: the reviewer finds, the planner decides, the implementer builds. Collapsing planner into
// implementer is what produces a repair that fixes the named counterexample and leaves the class open.
const PLAN_SCHEMA = {
  type: 'object',
  required: ['id', 'adjudications', 'required_test_cases', 'repair_spec', 'soft_spots_check'],
  properties: {
    id: { type: 'string' },
    adjudications: {
      type: 'array',
      description: 'One entry per reviewer objection. RUN the command that settles it — reviewers are sometimes wrong, and a wrong objection obeyed costs a whole round. An objection dismissed by argument is not adjudicated.',
      items: {
        type: 'object',
        required: ['objection', 'ruling', 'adjudicating_command', 'evidence'],
        properties: {
          objection: { type: 'string' },
          ruling: { type: 'string', enum: ['VALID', 'INVALID', 'PARTLY_VALID'] },
          adjudicating_command: { type: 'string' },
          evidence: { type: 'string' },
        },
      },
    },
    required_test_cases: {
      type: 'array',
      description: 'Every surviving objection becomes an EXECUTABLE test case. Prose gets acknowledged; a case with a must_produce gets run. Add any case the reviewers should have demanded and did not.',
      items: {
        type: 'object',
        required: ['name', 'construction', 'must_produce'],
        properties: {
          name: { type: 'string' },
          construction: { type: 'string', description: 'Exactly how to build the input, reproducibly.' },
          must_produce: { type: 'string', description: 'The exact observable required — a verdict string, a red comparison, a count.' },
        },
      },
    },
    repair_spec: { type: 'string', description: 'The pinned construction, specific enough that two implementers would build the same thing. You write NO code — you pin the design.' },
    scope_change: { type: 'string', description: 'If a premise was false or the ceiling was in the spec, the RE-SCOPED item. Fixing forward means fixing the ITEM, not only the artifact.' },
    do_not_change: { type: 'string', description: 'What already works and must not regress. Name it explicitly, or round 2 trades one defect for another.' },
    // The implementer's sealed soft spots, revealed to the planner only now that findings
    // are filed. An unaccounted soft spot is an open door.
    soft_spots_check: {
      type: 'array',
      description: 'One entry per known_soft_spots entry on the artifact. Each is FOUND_BY_REVIEW (a reviewer caught it), PROVEN_SAFE (you ran the command that closes it), or INTO_REPAIR (it joins the repair spec).',
      items: {
        type: 'object',
        required: ['soft_spot', 'disposition', 'evidence'],
        properties: {
          soft_spot: { type: 'string' },
          disposition: { type: 'string', enum: ['FOUND_BY_REVIEW', 'PROVEN_SAFE', 'INTO_REPAIR'] },
          evidence: { type: 'string' },
        },
      },
    },
  },
}

// SLOT 3 (optional) — PROSECUTOR lenses. Keep them ORTHOGONAL; identical reviewers
// find identical things. Add a third for high-risk batches. The panel also carries one
// ADVOCATE (see reviewersFor) — the stance split is not optional: it is what keeps the
// verdict count a measurement instead of an echo of the prompt.
const LENSES = [
  `YOUR LENS — BEHAVIOR AND STATE TRANSITIONS. Apply the patch in an isolated checkout, run its
focused tests, and trace the production path end to end. Attack request counts, terminal/final-fetch
behavior, malformed/missing/unknown provider values, precedence, and operation-specific required
fields. Construct at least one neighbouring transition or wire response the patch did not name.
A builder test without a request or response-path assertion is TEST_GAP.`,

  `YOUR LENS — PUBLIC API, ABI, SERIALIZATION, AND WIRE COMPATIBILITY. Apply the patch and run
compilation plus the ABI check where relevant. Inspect generated request JSON/query/multipart and
serialized persisted forms. Hunt nullable overload ambiguity, changed JVM/KLIB descriptors, public
data classes, enum-name-derived wire values, dual typed/raw sources of truth, dropped Unknown/raw
evidence, and accidental closed modeling of provider-open values. For UI work, prove the existing
flat wire shape round-trips.`,

  `YOUR LENS — SCOPE, REGRESSION, AND MINIMALITY. Diff against the staged item baseline,
not just committed HEAD. Verify every changed line belongs to this item and every baseline change
survives. Read adjacent provider paths so a local type does not wrongly spread into image handling,
a generic poller, MIME enums, protocol discriminators, or opaque identifiers. Distinguish an actual
in-scope defect from optional polish: compatibility knobs and staged support are expected, while a
scope extension is a SCOPE_PROPOSAL and must not block this patch.`,
]

// Round-2 lenses are DIFFERENT on purpose. Do not reuse the round-1 set: the repair job is not to
// re-litigate the original objection, it is to check whether the fix closed the CLASS.
const LENSES_ROUND2 = [
  `YOUR LENS — REPAIR CLASS CLOSURE. Apply the repaired patch and run every required test case.
Then create a neighbouring provider response, operation, precedence combination, or serialization
round trip that was not named. Confirm the repair fixed the class rather than hard-coding the reported
case. Optional ergonomics outside written_scope remain proposals, not defects.`,

  `YOUR LENS — REGRESSION, ABI, AND BASELINE PRESERVATION. Confirm every do_not_change behavior,
focused test, ABI expectation, primitive wire value, deprecated bridge, and pre-campaign dirty edit
still holds. Review the full patch against the staged baseline. A repair that fixes an objection by
weakening validation, dropping Unknown/raw evidence, changing wire shape, or losing existing work is
REGRESSION or ABI_OR_WIRE_BREAK.`,
]

// ------------------------------------------------------------------ script ---

// One repair round is usually right. The bound is what makes ESCALATE reachable rather than
// theoretical — without it an item can loop forever and never reach a human.
const MAX_REPAIR_ROUNDS = 1

// args may arrive as a JSON-encoded string depending on how it was passed; accept both.
const items = typeof args === 'string' ? JSON.parse(args) : (Array.isArray(args) ? args : [])
if (!items.length) throw new Error('adversarial-loop: no items received in args')

log(`adversarial-loop (fix-forward): ${items.length} items, ${LENSES.length} prosecutors + 1 advocate, up to ${MAX_REPAIR_ROUNDS} repair round(s)`)

async function reviewersFor(artifact, item, lenses, phase, round) {
  // THE SEAL: known_soft_spots is the implementer's confession, withheld until findings are
  // filed so it cannot prime them. Strip it before the artifact reaches ANY reviewer; the
  // planner and the returned bundle carry the unsealed artifact.
  const { known_soft_spots, ...sealed } = artifact
  const shown = JSON.stringify(sealed, null, 1)
  const repairNote = round > 1
    ? '\nThis is a REPAIR at re-review: the strongest case must cover the CLASS the repair claimed to close, not just the named counterexamples.'
    : ''
  const prosecutors = lenses.map((lens, i) => () => agent(
    `You are PROSECUTOR ${i + 1} of ${lenses.length} on a split-stance panel. Your job is to
find why this is wrong. Assume it IS wrong and hunt for the reason — but the hunt comes
SECOND. First, the affirmative phase: re-run the artifact's own verification claims (the
gate, the positive and negative cases, the cannot-fail probes) and record exactly what
HOLDS in confirmed_holds, with commands and outputs. A claim you cannot reproduce is a
finding, not a footnote.

You are deliberately NOT given the implementer's reasoning — only its output and claims.
Verify every claim yourself. Do not trust the evidence strings; re-run them. "I verified X at
file:line" is a claim, not proof: open the file. Finding nothing is valid only after you have
genuinely tried to break it. WORKTREES ARE FORBIDDEN. Materialize the exact artifact in the
ordinary clone ${SANDBOX_ROOT}/${item.sandbox_tag}-review-r${round}-p${i + 1}-${item.id}:
clone the repo there, apply and stage item.baseline_patch, save artifact.artifact as
a patch, then git apply it. Review and test that resulting code, not the patch prose. Never write
the source checkout and never run git worktree.

LABEL THE LOCUS of every finding: IN_SCOPE_DEFECT if your counterexample breaks the
artifact's WRITTEN scope, SCOPE_PROPOSAL if it extends it. A scope proposal is a new item,
not this artifact's failure — say what the new item should be in required_change.

FIX-FORWARD: your verdict does not end this item. Anything other than ACCEPT routes it to a
fixer-planner, which turns your counterexample into a REQUIRED TEST CASE for a fresh
implementer. So make the counterexample EXECUTABLE and specific — a vague objection produces a
vague repair. A finding is a contribution, not a veto.

${DOMAIN_LAWS}
${MECHANICS_GUIDE}

THE ARTIFACT UNDER REVIEW (the implementer's known_soft_spots are sealed from you until
your findings are filed — do not ask for them):
${shown}

${lens}

Report the verdict you can DEFEND with commands you actually ran. Vague review is worse
than none. Do not write files.`,
    { model: 'opus', effort: 'xhigh', label: `prosecute${i + 1}r${round}:${item.id}`, phase, schema: REVIEW_SCHEMA },
  ))
  const advocate = () => agent(
    `You are the ADVOCATE on a split-stance panel — the constructive half. Assume this
artifact is RIGHT and try to PROVE it: construct the strongest verified case for it that
evidence you produce can support. Re-run every claim yourself — the gate, the positive
and negative cases, the cannot-fail probes — and record exactly what holds in
confirmed_holds, with commands and outputs.

This is the harder job, not the easier one, and it is not rubber-stamping: the strongest
case must be built from YOUR evidence, not from the artifact's fluency — well-written
claims read correct. WORKTREES ARE FORBIDDEN. Before constructing the case, create the ordinary
clone ${SANDBOX_ROOT}/${item.sandbox_tag}-review-r${round}-advocate-${item.id}, apply and stage
item.baseline_patch, save artifact.artifact as a patch, and git apply it there. Never write
the source checkout and never run git worktree. Run the claims against the materialized code.
Where your construction FAILS — a claim you cannot reproduce, a
boundary the artifact's own success conditions do not cover — that failure is your
finding, and it is the most damaging finding this panel produces: the artifact fails even
under FRIENDLY construction. Make the counterexample executable; it becomes a required
test case.

Verdict semantics for you: SOUND means you actually built and verified the strongest case
within the written scope (locus NO_FINDING). Any break in the construction is a verdict
naming the break with route FIX_FORWARD and the locus labeled. A SOUND you could not
construct is overclaiming — report the break instead.

You are deliberately NOT given the implementer's reasoning, and its known_soft_spots are
sealed from you — your construction must be independent.${repairNote}

${DOMAIN_LAWS}
${MECHANICS_GUIDE}

THE ARTIFACT UNDER REVIEW:
${shown}

Report what you can DEFEND with commands you actually ran. Do not write files.`,
    { model: 'opus', effort: 'xhigh', label: `advocate-r${round}:${item.id}`, phase, schema: REVIEW_SCHEMA },
  )
  const reviews = []
  for (const prosecutor of prosecutors) reviews.push(await prosecutor())
  reviews.push(await advocate())
  return reviews
}

const results = await pipeline(
  items,

  // Phase 1 — PREMISE was completed once over the WHOLE backlog before implementation waves.
  // Re-running it here duplicates the most expensive read/search phase and leaves the workflow
  // progress meter at zero until those redundant agents finish. Consume the persisted audited
  // record supplied by the orchestrator; fail closed if a wave omitted it.
  (item) => {
    if (!item.premise_audit || item.premise_audit.id !== item.id) {
      throw new Error(`adversarial-loop: ${item.id} is missing its persisted premise audit`)
    }
    return item.premise_audit
  },

  // Phase 2 — IMPLEMENT, from the CORRECTED item.
  (premise, item) => !premise ? null : agent(
    `You are the IMPLEMENTER for one item. Produce a LANDABLE artifact.
You do NOT review your own work — separate adversarial reviewers will try to break it,
so overclaiming will be caught.

FIX-FORWARD LOOP: there is no DROP. If you cannot make the unit correct, return NEEDS_FIX with
the reason and your best read of the fix direction — that routes to a fixer-planner, not to a
bin. Overclaiming is the only move that actually costs here, because it gets caught downstream
and burns a repair round.

${DOMAIN_LAWS}
${MECHANICS_GUIDE}

YOUR ITEM:
${JSON.stringify(item, null, 1)}

A PREMISE AUDIT HAS ALREADY RUN ON THIS ITEM. Treat its findings as binding input, not
suggestions, and build against its corrected item wherever that differs from the original
wording. It can be wrong too — confirm its structural findings yourself:
${JSON.stringify(premise, null, 1)}

Treat the item's sketch as a HYPOTHESIS, not a spec.

CRITICAL — the exit a narrower schema does not have. If the item's own constraint turns out to be
the ceiling, the correct answer is LAND_WIDENED: implement the larger defensible scope and report
what you widened, with both measured pool sizes. Do NOT reach for NEEDS_FIX because the item as
literally worded cannot work — widening is the fix, and a measured run produced two rejections
that were both retracted for exactly this reason.

DO THIS:
 1. WORKTREES ARE FORBIDDEN. Create the ordinary clone ${SANDBOX_ROOT}/${item.sandbox_tag}-implement-${item.id} from the repo. Work only there. Apply item.baseline_patch and stage it; the staged tree is context to preserve and your campaign edits remain unstaged. Never write the source checkout and never run git worktree.
 2. Read every target production file end-to-end and inspect its recent git history. Confirm the
    problem is genuinely unowned and every structural assumption against the actual tree/AST.
 3. Build the smallest useful corrected item. Add focused behavior tests that reach the actual
    request/response/serialization path. Compatibility bridges and explicit precedence knobs are
    expected; optional ergonomics beyond written_scope are not blockers.
 4. Run the item's verify command and any narrower tests needed while iterating.
 5. Prove the behavioral change can fail: temporarily reverse or remove the key production branch,
    run the focused test to red, then restore it and run green. Report both commands/results.
 6. Run the expected-delta instrument. Report before and after; an unmoved instrument is honest
    NEEDS_FIX unless the prediction was deliberately zero.
 7. Generate artifact with git diff --binary --no-ext-diff. Confirm git diff --cached still equals
    item.baseline_patch. Put ONLY the unstaged patch in artifact.artifact and include exact apply/test
    instructions plus changed files in integration_point.
 8. Disclose your known_soft_spots — where YOU would attack this artifact: the weakest joints,
    the untested corners, the claim you are least sure of. They are sealed from the reviewers
    until findings are filed, then checked one by one. "None" is overclaiming and will be
    read as such.

DO NOT WRITE ANY FILE IN THE SOURCE CHECKOUT or touch a serial resource named in the laws. All
writes, builds, and tests belong inside your named ordinary clone. Never use a worktree. Return the
artifact as DATA; the orchestrator applies accepted patches serially with the gate between each.`,
    { model: 'opus', effort: 'xhigh', label: `implement:${item.id}`, phase: 'Implement', schema: ARTIFACT_SCHEMA },
  ),

  // Phase 3 — REVIEW.
  (artifact, item) => artifact
    ? reviewersFor(artifact, item, LENSES, 'Review', 1).then((reviews) => ({
        item: item.id, artifact, reviews: (reviews || []).filter(Boolean),
      }))
    : { item: item.id, artifact: null, reviews: [] },

  // Phase 4 — PLAN -> REPAIR -> RE-REVIEW, only when contested. This is the fix-forward stage:
  // a defect routes here instead of ending the item.
  async (r, item) => {
    // `x.reviews.length === 0` is load-bearing: see the note at the round-2 check. Zero
    // completed reviewers must never read as zero objections, or a dead review stage lands
    // the artifact unexamined.
    const contested = (x) => !x.artifact
      || x.artifact.recommendation === 'NEEDS_FIX'
      || x.reviews.length === 0
      || x.reviews.some((v) => v.route === 'FIX_FORWARD')
    if (!contested(r)) return { ...r, outcome: 'LAND_CLEAN' }
    if (!r.artifact) return { ...r, outcome: 'ESCALATE', escalate_reason: 'implementer returned nothing' }
    if (r.reviews.length === 0) {
      return { ...r, outcome: 'ESCALATE',
        escalate_reason: 'UNREVIEWED: every round-1 reviewer died (quota/crash/timeout); zero verdicts exist. Re-run the review — do not read silence as assent.' }
    }

    const objections = r.reviews.filter((v) => v.route === 'FIX_FORWARD')

    const plan = await agent(
      `You are the FIXER-PLANNER for one item. An artifact was produced and adversarial reviewers
found problems with it. Convert those findings into a PINNED REPAIR PLAN a fresh implementer can
execute without re-deriving anything.

YOU WRITE NO CODE. You adjudicate, you pin, you hand off. The reviewer finds, you decide, the
implementer builds.

YOUR FOUR JOBS:
 1. ADJUDICATE every objection by RUNNING the command that settles it. Reviewers are sometimes
    wrong, and a wrong objection obeyed costs a whole round. Rule each VALID, INVALID or
    PARTLY_VALID with the transcript that proves it.
 2. Convert every surviving objection into an EXECUTABLE REQUIRED TEST CASE: how to build the
    input, and the exact observable required. Add any case the reviewers should have demanded
    and did not.
 3. PIN THE REPAIR, specific enough that two implementers would build the same thing. Name what
    must NOT change. If a premise was false or the ceiling was in the spec, RE-SCOPE the item —
    fixing forward means fixing the item, not only the artifact.
 4. UNSEAL the implementer's known_soft_spots (on the artifact below — they were withheld from
    the reviewers) and account for every entry: FOUND_BY_REVIEW (a reviewer caught it),
    PROVEN_SAFE (run the command that closes it), or INTO_REPAIR (it joins the repair spec).

${DOMAIN_LAWS}
${MECHANICS_GUIDE}

THE ARTIFACT:
${JSON.stringify(r.artifact, null, 1)}

THE OBJECTIONS (${objections.length} of ${r.reviews.length} reviewers routed FIX_FORWARD):
${JSON.stringify(objections, null, 1)}

WORKTREES ARE FORBIDDEN. To adjudicate against real code, create the ordinary clone ${SANDBOX_ROOT}/${item.sandbox_tag}-plan-${item.id}, apply and stage item.baseline_patch, then apply artifact.artifact there. Run every adjudicating command in that clone. Do not change production or test files, never write the source checkout, and never run git worktree.`,
      { model: 'opus', effort: 'xhigh', label: `plan:${item.id}`, phase: 'Plan', schema: PLAN_SCHEMA },
    )

    if (!plan) return { ...r, outcome: 'ESCALATE', escalate_reason: 'planner returned nothing' }

    const repaired = await agent(
      `You are a FRESH IMPLEMENTER executing a pinned repair plan. A previous version of this
artifact was found defective. You owe that version NO LOYALTY — rewrite as much as the plan
requires. Do not defend it.

THE BAR IS HIGHER THAN THE ORIGINAL, not lower. A repairer who knows the counterexample tends to
fix exactly that input and leave the general case open; the round-2 reviewers are briefed to look
for precisely that and will construct neighbouring cases the plan did not name. Close the CLASS.

RESOLVE EACH ADJUDICATION EXPLICITLY in your verification: quote it, state what you did, show the
command proving it. An objection the planner ruled INVALID must be refuted with a command, not an
argument.

EVERY required_test_case is an input to RUN, not prose to consider. Your artifact is not landable
until every one produces its stated must_produce.

${DOMAIN_LAWS}
${MECHANICS_GUIDE}

THE REPAIR PLAN (pinned; follow it):
${JSON.stringify(plan, null, 1)}

THE PRIOR ARTIFACT (reference only — you owe it nothing):
${JSON.stringify(r.artifact, null, 1)}

WORKTREES ARE FORBIDDEN. Create the ordinary clone ${SANDBOX_ROOT}/${item.sandbox_tag}-repair-${item.id}, apply and stage item.baseline_patch there, and rebuild the corrected item from the pinned plan without applying the prior artifact unless the plan explicitly preserves a hunk. Keep campaign edits unstaged; run every required test plus the item gate; prove red then green; and return ONLY git diff --binary --no-ext-diff as artifact.artifact. Confirm the cached diff remains exactly the baseline. Never write the source checkout and never run git worktree.`,
      { model: 'opus', effort: 'xhigh', label: `repair:${item.id}`, phase: 'Repair', schema: ARTIFACT_SCHEMA },
    )

    if (!repaired) {
      return { ...r, plan, outcome: 'ESCALATE', escalate_reason: 'repair implementer returned nothing' }
    }

    const reviews2 = (await reviewersFor(repaired, item, LENSES_ROUND2, 'Re-review', 2) || []).filter(Boolean)
    // NO REVIEWERS IS NOT NO OBJECTIONS. `[].some(...)` is false, so an empty review set
    // reads as "nothing contested" and lands the artifact having been reviewed by no one.
    // Measured: a real run lost both round-2 reviewers to a quota error and reported
    // LAND_REPAIRED on an artifact with zero completed reviews — a false landing produced by
    // the loop itself, in exactly the class the loop exists to catch. Agents die (quota,
    // crash, timeout), so treat an absent verdict as unverified, never as assent.
    const unreviewed = reviews2.length === 0
    const stillContested = repaired.recommendation === 'NEEDS_FIX'
      || unreviewed
      || reviews2.some((v) => v.route === 'FIX_FORWARD')

    return {
      item: item.id,
      artifact: repaired,
      prior_artifact: r.artifact,
      reviews: reviews2,
      prior_reviews: r.reviews,
      plan,
      outcome: stillContested ? 'ESCALATE' : 'LAND_REPAIRED',
      escalate_reason: !stillContested ? undefined
        : unreviewed
          ? 'UNREVIEWED: every round-2 reviewer died (quota/crash/timeout), so this artifact carries ZERO verdicts. Not a pass — re-run the review before landing.'
          : 'survived one repair round and reviewers still route FIX_FORWARD — orchestrator decision required',
    }
  },
)

const done = results.filter(Boolean).filter((r) => r && r.item)
const byOutcome = (o) => done.filter((r) => r.outcome === o)
const clean = byOutcome('LAND_CLEAN')
const repaired = byOutcome('LAND_REPAIRED')
const escalated = byOutcome('ESCALATE')

// Surfaced separately because they are the two findings a loop most easily rounds to "sound":
// an artifact that is faithful to an over-constrained item, and one whose gates went green while
// the instrument never moved.
const ceilingInSpec = done.filter((r) => (r.reviews || []).some((v) => v.verdict === 'CEILING_IS_IN_THE_SPEC'))
const falseLandings = done.filter((r) => (r.reviews || []).some((v) => v.verdict === 'FALSE_LANDING'))

log(`clean=${clean.length}  repaired=${repaired.length}  escalated=${escalated.length}`
  + `  ceiling_in_spec=${ceilingInSpec.length}  false_landings=${falseLandings.length}  (no DROP in this loop)`)

return {
  land_clean: clean.map((r) => ({ id: r.item, artifact: r.artifact })),
  land_repaired: repaired.map((r) => ({ id: r.item, artifact: r.artifact, plan: r.plan })),
  escalated: escalated.map((r) => ({
    id: r.item,
    reason: r.escalate_reason,
    artifact: r.artifact,
    plan: r.plan,
    objections: (r.reviews || []).filter((v) => v.route === 'FIX_FORWARD'),
  })),
  ceiling_in_spec: ceilingInSpec.map((r) => r.item),
  false_landings: falseLandings.map((r) => r.item),
  all: done,
}
