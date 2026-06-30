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
        @JvmExposeBoxed
        @AiSdkJvmStatic
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
        @JvmExposeBoxed
        @AiSdkJvmStatic
        public fun of(value: String): ModelId = ModelId(value)
    }
}

public data class ModelRef(
    val modelId: ModelId,
    val providerId: ProviderId? = null,
) {
    val qualifiedName: String
        get() = providerId?.let { "${it.value}:${modelId.value}" } ?: modelId.value

    override fun toString(): String = qualifiedName

    public companion object {
        public fun parse(value: String): ModelRef {
            val (providerId, modelId) = ProviderRegistry.splitProviderModelId(value)
            return ModelRef(
                modelId = ModelId(modelId),
                providerId = providerId?.let(::ProviderId),
            )
        }
    }
}

/**
 * Factory helpers for the value-class identifiers. These cannot be expressed as
 * PascalCase faux-constructors because a same-signature `fun ProviderId(String)` /
 * `fun ModelId(String)` would conflict with the value-class primary constructors,
 * so they live as members of this object.
 */
public object ModelIdentifiers {
    public fun providerId(value: String): ProviderId = ProviderId(value)

    public fun modelId(value: String): ModelId = ModelId(value)
}

public fun ModelRef(value: String): ModelRef = ModelRef.parse(value)

public fun ModelRef(providerId: ProviderId, modelId: ModelId): ModelRef =
    ModelRef(modelId = modelId, providerId = providerId)

public fun ModelRef(providerId: String, modelId: String): ModelRef =
    ModelRef(ProviderId(providerId), ModelId(modelId))

/**
 * Typed, value-class- and [ModelRef]-aware accessors over [Provider]. These are
 * member-extensions: callers reach them via member import or `with(ProviderModels) { ... }`.
 */
public object ProviderModels {
    public fun Provider.provider(providerId: ProviderId): Provider =
        when (this) {
            is ProviderRegistry -> provider(providerId.value)
            else -> if (this.providerId == providerId.value) this else throw NoSuchProviderError(providerId.value)
        }

    public fun Provider.languageModel(modelId: ModelId): LanguageModel =
        languageModel(modelId.value)

    public fun Provider.embeddingModel(modelId: ModelId): EmbeddingModel =
        embeddingModel(modelId.value)

    public fun Provider.imageModel(modelId: ModelId): ImageModel =
        imageModel(modelId.value)

    public fun Provider.speechModel(modelId: ModelId): SpeechModel =
        speechModel(modelId.value)

    public fun Provider.transcriptionModel(modelId: ModelId): TranscriptionModel =
        transcriptionModel(modelId.value)

    public fun Provider.rerankingModel(modelId: ModelId): RerankingModel =
        rerankingModel(modelId.value)

    public fun Provider.videoModel(modelId: ModelId): VideoModel =
        videoModel(modelId.value)

    public fun Provider.languageModel(ref: ModelRef): LanguageModel =
        resolve(ref) { languageModel(it) }

    public fun Provider.embeddingModel(ref: ModelRef): EmbeddingModel =
        resolve(ref) { embeddingModel(it) }

    public fun Provider.imageModel(ref: ModelRef): ImageModel =
        resolve(ref) { imageModel(it) }

    public fun Provider.speechModel(ref: ModelRef): SpeechModel =
        resolve(ref) { speechModel(it) }

    public fun Provider.transcriptionModel(ref: ModelRef): TranscriptionModel =
        resolve(ref) { transcriptionModel(it) }

    public fun Provider.rerankingModel(ref: ModelRef): RerankingModel =
        resolve(ref) { rerankingModel(it) }

    public fun Provider.videoModel(ref: ModelRef): VideoModel =
        resolve(ref) { videoModel(it) }

    private inline fun <T> Provider.resolve(ref: ModelRef, getter: Provider.(String) -> T): T =
        when {
            this is ProviderRegistry -> getter(ref.qualifiedName)
            ref.providerId == null || ref.providerId.value == providerId -> getter(ref.modelId.value)
            else -> throw NoSuchProviderError(ref.providerId.value)
        }
}
