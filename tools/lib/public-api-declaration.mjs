// The ONE definition of "a line that declares public API" for the @since census.
//
// Shared by tools/check-public-api-since-budget.mjs (the blocking gate) and
// tools/add-public-api-since-tags.mjs (the tagger). They used to carry two hand-copied
// regexes — identical, and identically wrong: neither matched `public suspend fun`,
// `public const val`, `public fun <T> …`, `public typealias`, `public operator fun`, or
// `public inline fun`, so whole declaration forms were invisible to BOTH the census and
// the tagger, and the budget's "0/0 missing" was seeded by a count that never looked
// (AR-38's original premise — falsified 2026-08-01). One module, importable by both,
// makes that copy-drift unrepresentable.
//
// Deliberate exclusions, not oversights:
//   - `override`: an override's identity and @since belong to the declaration it
//     overrides; requiring a second tag on every implementor is noise, not coverage.
//   - `actual`: the expect/common side owns the KDoc (and public `expect` is banned
//     outright by the no-public-expect-declaration LAW rule).
//
// The keyword alternation lists `fun\s+interface` before `fun` on purpose: with `fun`
// first, `public fun interface StopCondition` matches keyword `fun` and then captures
// the name "interface".
export const declaration =
  /^\s*public\s+(?:(?:data|sealed|abstract|open|final|suspend|const|inline|operator|infix|tailrec|external|expect|lateinit|value|annotation|inner|enum|companion)\s+)*(?:fun\s+interface|class|interface|object|typealias|fun|val|var)\s+(?:<.*>\s+)?([A-Za-z_][A-Za-z0-9_]*)/;
