package ai.torad.aisdk

import kotlin.ExperimentalStdlibApi
import kotlin.jvm.JvmExposeBoxed
import kotlin.jvm.JvmInline

@JvmInline
@OptIn(ExperimentalStdlibApi::class)
@JvmExposeBoxed
public value class ProviderId(public val value: String) {
    init {
        require(value.isNotBlank()) { "ProviderId must not be blank." }
        require(':' !in value) { "ProviderId must not contain `:`." }
    }

    override fun toString(): String = value

    public companion object {
        /** @since 0.3.0-beta01 */
        public operator fun invoke(value: String): ProviderId = of(value)

        @JvmExposeBoxed
        @AiSdkJvmStatic
        /** @since 0.3.0-beta01 */
        public fun of(value: String): ProviderId = ProviderId(value)
    }
}

@JvmInline
@OptIn(ExperimentalStdlibApi::class)
@JvmExposeBoxed
public value class ModelId(public val value: String) {
    init {
        require(value.isNotBlank()) { "ModelId must not be blank." }
    }

    override fun toString(): String = value

    public companion object {
        /** @since 0.3.0-beta01 */
        public operator fun invoke(value: String): ModelId = of(value)

        @JvmExposeBoxed
        @AiSdkJvmStatic
        /** @since 0.3.0-beta01 */
        public fun of(value: String): ModelId = ModelId(value)
    }
}

/** @since 0.3.0-beta01 */
public fun ParseModelRef(value: String): ModelRef {
    val (providerId, modelId) = SplitProviderModelId(value)
    return ModelRef(
        modelId = ModelId(modelId),
        providerId = providerId?.let(::ProviderId),
    )
}

/** @since 0.3.0-beta01 */
public data class ModelRef(
    val modelId: ModelId,
    val providerId: ProviderId? = null,
) {
    val qualifiedName: String
        get() = providerId?.let { "${it.value}:${modelId.value}" } ?: modelId.value

    override fun toString(): String = qualifiedName
}

/**
 * Factory helpers for the value-class identifiers. These cannot be expressed as
 * PascalCase faux-constructors because a same-signature `fun ProviderId(String)` /
 * `fun ModelId(String)` would conflict with the value-class primary constructors,
 * so they live as members of this object.
 * @since 0.3.0-beta01
 */
public object ModelIdentifiers {
    /** @since 0.3.0-beta01 */
    public fun providerId(value: String): ProviderId = ProviderId(value)

    /** @since 0.3.0-beta01 */
    public fun modelId(value: String): ModelId = ModelId(value)
}

/** @since 0.3.0-beta01 */
public fun ModelRef(value: String): ModelRef = ParseModelRef(value)

/**
 * Build a [ModelRef] from typed ids.
 *
 * Uses the positional primary constructor (`modelId` first). A previous body that
 * re-invoked `ModelRef(modelId = …, providerId = …)` resolved back to this factory
 * and stack-overflowed.
 * @since 0.3.0-beta01
 */
public fun ModelRef(providerId: ProviderId, modelId: ModelId): ModelRef =
    ModelRef(modelId, providerId)

/** @since 0.3.0-beta01 */
public fun ModelRef(providerId: String, modelId: String): ModelRef =
    ModelRef(ModelId(modelId), ProviderId(providerId))
