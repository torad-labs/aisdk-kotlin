# Target Architecture — Kotlin-native, class-based (decision C)

**Decision:** Drop ai@6.x functional-API parity. Re-architect into an idiomatic Kotlin SDK.
Logic lives **on types** (member functions) or **in cohesive units** (classes / objects / sealed
use-cases) — never in loose top-level functions. Because parity is dropped, **we break the API
freely**: no `@Deprecated` shims, no ABI preservation, no `api/*.api` gymnastics during migration.

## The one rule that defines the structure
- The ONLY top-level callables allowed are **factory faux-constructors**: `public fun TextGenerator(...): TextGenerator`
  (PascalCase name, returns its type). Everything else — generation, helpers, utilities, conversions —
  is a member of a class/object or an extension declared **inside** a type.
- Hook (replaces `no-private-top-level-function`): **block every top-level `fun` whose name is camelCase**
  (starts lowercase); allow PascalCase factory functions. Grandfathers the 742 existing until migrated.

## Surface-by-surface target

### 1. Generation  (kills `generateText` → `generateTextImpl` delegation)
```kotlin
public fun TextGenerator(model: LanguageModel, config: CallConfig = CallConfig()): TextGenerator   // factory
public class TextGenerator internal constructor(
    private val model: LanguageModel,
    private val config: CallConfig,
) {
    public fun generate(input: GenerationInput): Flow<GenerateResult>            // Flow over suspend (T4)
    public fun <T> generate(input: GenerationInput, output: Output<T>): Flow<GenerateResult<T>>
    public fun stream(input: GenerationInput): Flow<StreamEvent>
}
public sealed class GenerationInput {                                            // single source of truth (T1)
    public data class Prompt(val text: String) : GenerationInput()
    public data class Messages(val history: NonEmptyMessages) : GenerationInput()
    public data class MessagesWithPrompt(val history: NonEmptyMessages, val prompt: String) : GenerationInput()
    internal fun toMessages(system: String?): List<ModelMessage> = ...           // behavior ON the type
}
```
- `CallConfig` is a real type (temperature/topP/… ) — kills the 16-param flattened lists.
- OPEN QUESTION 1: `generate` returns `Flow<GenerateResult>` (T4, Flow-first) vs `suspend fun generate(): GenerateResult`. Your tenet 4 says Flow; confirm.

### 2. Tools  (concept #741 — tool = schema + execute, traveling together, sealed)
```kotlin
public sealed class Tool<I, O> {
    public abstract val schema: ToolSchema
    public abstract fun execute(input: I, ctx: ToolContext): Flow<ToolResult<O>>
}
```
One sealed subclass per tool; no separate schema/handler/registry modules.

### 3. Providers  (kills throw-on-use singletons + reflective stubs)
```kotlin
public fun OpenAI(client: HttpClient, settings: OpenAISettings = OpenAISettings()): Provider  // factory
public class OpenAIProvider internal constructor(...) : Provider {
    override fun languageModel(id: ModelId): LanguageModel = ...
}
public sealed class ProviderOptions { ... ; OpenAI(...); Google(...); Raw(provider, json) }    // typed (T2)
```
No top-level `val openai` that throws. Construction is explicit.

### 4. Util.kt grab-bag (26 internal + many private free fns) → cohesive units
- headers: `value class Headers` / `HeaderSet` with `merge`, `normalize`, `withUserAgent` as **members**.
- secrets: `object ApiKeySource { fun load(...) }` (or a `SettingsResolver`).
- json: `JsonMerge` / extensions-as-members; `isDeepEqual` on a wrapper.
- `getErrorMessage` → `Throwable.errorMessage` extension (declared inside the relevant type/file scope as member-extension).
- DELETE Util.kt as a grab-bag; each function moves to where its data lives.

### 5. Errors  → sealed (gap-fill FIX-017/018)
`sealed class AiSdkException(message, cause) : RuntimeException(...)` with typed leaves; no `open class`,
no `Any?` payloads, exceptions are NOT data classes.

### 6. Wire / providers parsing
Already moving the right way (typed `WireDecoder.fail`, sealed protocol states). Keep: missing required
ids/names → typed error, never a fabricated sentinel.

## Migration approach (parity dropped → break freely)
1. Land the hook flip (block camelCase top-level fns; allow PascalCase factories) + the rest of the strict set.
2. Re-plan the FIX queue against this target: the "additive + @Deprecated" tasks collapse to direct rewrites;
   the god-file splits become "split into classes," not "files of functions."
3. Migrate surface-by-surface, each step compiling + tests green. ABI dumps become irrelevant (regenerate once at the end).

## Open decisions for you
1. **Flow vs suspend** for `generate`/provider calls (T4 says Flow; confirm you want Flow-first even for one-shot).
2. **Factory convention**: PascalCase faux-constructor `fun TextGenerator(...)` (recommended, idiomatic) vs `companion object { operator fun invoke }` vs `TextGenerator.create(...)`.
3. **Scope/pace**: re-architect the existing 742 now (big), or block-new + migrate surface-by-surface as we touch them (incremental). Recommended: incremental by surface, hook prevents backsliding.
