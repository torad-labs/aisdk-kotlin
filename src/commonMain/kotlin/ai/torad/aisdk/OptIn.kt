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
 * Currently gates the JS-ecosystem framework-adapter shims (`react`, `svelte`,
 * `vue`, `angular`, `rsc`) and the `codemod` migration helpers. These are
 * half-ported, churning surfaces that should not be mistaken for the stable core.
 *
 * Consumers must opt in explicitly — either annotate the using declaration with
 * `@ExperimentalAiSdkApi`, or annotate the call site with
 * `@OptIn(ExperimentalAiSdkApi::class)`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an experimental AI SDK API (framework-adapter / migration surface). " +
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
