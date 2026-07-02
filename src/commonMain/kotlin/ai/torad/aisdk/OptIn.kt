package ai.torad.aisdk

import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.CONSTRUCTOR
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.annotation.AnnotationTarget.PROPERTY
import kotlin.annotation.AnnotationTarget.PROPERTY_GETTER
import kotlin.annotation.AnnotationTarget.PROPERTY_SETTER
import kotlin.annotation.AnnotationTarget.TYPEALIAS

/**
 * Marks a declaration as **experimental**: a surface whose shape may change or be
 * removed in any release without going through the normal deprecation cycle.
 *
 * Currently gates surfaces that mirror upstream experimental APIs or are still
 * churning before the stable core contract freezes.
 *
 * Consumers must opt in explicitly — either annotate the using declaration with
 * `@ExperimentalAiSdkApi`, or annotate the call site with
 * `@OptIn(ExperimentalAiSdkApi::class)`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an experimental AI SDK API. " +
        "It may change or be removed without notice. Opt in with @OptIn(ExperimentalAiSdkApi::class).",
)
@Retention(BINARY)
@Target(CLASS, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, CONSTRUCTOR, TYPEALIAS)
public annotation class ExperimentalAiSdkApi

/**
 * Marks a declaration as **internal** to the AI SDK implementation: visible across
 * module boundaries for technical reasons (KMP source sets, inlining), but not part
 * of the supported public contract. It may change or be removed at any time.
 *
 * Consumers must not rely on internal declarations. Opt in only when extending or
 * testing the SDK itself, via `@OptIn(InternalAiSdkApi::class)`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an internal AI SDK API and is not part of the public contract. " +
        "It may change or be removed without notice. Opt in with @OptIn(InternalAiSdkApi::class).",
)
@Retention(BINARY)
@Target(CLASS, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, CONSTRUCTOR, TYPEALIAS)
public annotation class InternalAiSdkApi

/**
 * Marks direct [LanguageModel] execution as low-level provider access.
 *
 * Application prompts should normally go through an [Agent] or another
 * high-level SDK API so tool loops, middleware, telemetry, persistence, and
 * output handling stay in one place. Calling the model directly is still
 * supported for provider authors, tests, and deliberately low-level integrations,
 * but it must be explicit via `@OptIn(LowLevelLanguageModelApi::class)`.
 *
 * Public concrete [LanguageModel] implementations should put this marker on
 * their execution overrides so direct calls on the concrete type stay gated too.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Direct LanguageModel execution is a low-level provider API. " +
        "Use Agent.generate/Agent.stream or a high-level SDK API for application prompts, " +
        "or opt in with @OptIn(LowLevelLanguageModelApi::class).",
)
@Retention(BINARY)
@Target(CLASS, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, CONSTRUCTOR, TYPEALIAS)
public annotation class LowLevelLanguageModelApi
