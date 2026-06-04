package ai.torad.aisdk

data class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProviderId must not be blank." }
        require(':' !in value) { "ProviderId must not contain `:`." }
    }

    override fun toString(): String = value
}

data class ModelId(val value: String) {
    init {
        require(value.isNotBlank()) { "ModelId must not be blank." }
    }

    override fun toString(): String = value
}

data class ModelRef(
    val modelId: ModelId,
    val providerId: ProviderId? = null,
) {
    val qualifiedName: String
        get() = providerId?.let { "${it.value}:${modelId.value}" } ?: modelId.value

    override fun toString(): String = qualifiedName

    companion object {
        fun parse(value: String): ModelRef {
            val (providerId, modelId) = splitProviderModelId(value)
            return ModelRef(
                modelId = ModelId(modelId),
                providerId = providerId?.let(::ProviderId),
            )
        }
    }
}

fun providerId(value: String): ProviderId = ProviderId(value)

fun modelId(value: String): ModelId = ModelId(value)

fun modelRef(value: String): ModelRef = ModelRef.parse(value)

fun modelRef(providerId: ProviderId, modelId: ModelId): ModelRef =
    ModelRef(modelId = modelId, providerId = providerId)

fun modelRef(providerId: String, modelId: String): ModelRef =
    modelRef(ProviderId(providerId), ModelId(modelId))

fun Provider.provider(providerId: ProviderId): Provider =
    when (this) {
        is ProviderRegistry -> provider(providerId.value)
        else -> if (this.providerId == providerId.value) this else throw NoSuchProviderError(providerId.value)
    }

fun Provider.languageModel(modelId: ModelId): LanguageModel =
    languageModel(modelId.value)

fun Provider.embeddingModel(modelId: ModelId): EmbeddingModel =
    embeddingModel(modelId.value)

fun Provider.imageModel(modelId: ModelId): ImageModel =
    imageModel(modelId.value)

fun Provider.speechModel(modelId: ModelId): SpeechModel =
    speechModel(modelId.value)

fun Provider.transcriptionModel(modelId: ModelId): TranscriptionModel =
    transcriptionModel(modelId.value)

fun Provider.rerankingModel(modelId: ModelId): RerankingModel =
    rerankingModel(modelId.value)

fun Provider.videoModel(modelId: ModelId): VideoModel =
    videoModel(modelId.value)

fun Provider.languageModel(ref: ModelRef): LanguageModel =
    resolve(ref) { languageModel(it) }

fun Provider.embeddingModel(ref: ModelRef): EmbeddingModel =
    resolve(ref) { embeddingModel(it) }

fun Provider.imageModel(ref: ModelRef): ImageModel =
    resolve(ref) { imageModel(it) }

fun Provider.speechModel(ref: ModelRef): SpeechModel =
    resolve(ref) { speechModel(it) }

fun Provider.transcriptionModel(ref: ModelRef): TranscriptionModel =
    resolve(ref) { transcriptionModel(it) }

fun Provider.rerankingModel(ref: ModelRef): RerankingModel =
    resolve(ref) { rerankingModel(it) }

fun Provider.videoModel(ref: ModelRef): VideoModel =
    resolve(ref) { videoModel(it) }

private inline fun <T> Provider.resolve(ref: ModelRef, getter: Provider.(String) -> T): T =
    when {
        this is ProviderRegistry -> getter(ref.qualifiedName)
        ref.providerId == null || ref.providerId.value == providerId -> getter(ref.modelId.value)
        else -> throw NoSuchProviderError(ref.providerId.value)
    }
