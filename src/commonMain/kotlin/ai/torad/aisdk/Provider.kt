package ai.torad.aisdk

interface Provider {
    val providerId: String

    fun languageModel(modelId: String): LanguageModel =
        throw NoSuchModelError(providerId, "language", modelId)

    fun embeddingModel(modelId: String): EmbeddingModel =
        throw NoSuchModelError(providerId, "embedding", modelId)

    fun imageModel(modelId: String): ImageModel =
        throw NoSuchModelError(providerId, "image", modelId)

    fun speechModel(modelId: String): SpeechModel =
        throw NoSuchModelError(providerId, "speech", modelId)

    fun transcriptionModel(modelId: String): TranscriptionModel =
        throw NoSuchModelError(providerId, "transcription", modelId)

    fun rerankingModel(modelId: String): RerankingModel =
        throw NoSuchModelError(providerId, "reranking", modelId)

    fun videoModel(modelId: String): VideoModel =
        throw NoSuchModelError(providerId, "video", modelId)
}

data class CustomProvider(
    override val providerId: String,
    private val languageModels: Map<String, LanguageModel> = emptyMap(),
    private val embeddingModels: Map<String, EmbeddingModel> = emptyMap(),
    private val imageModels: Map<String, ImageModel> = emptyMap(),
    private val speechModels: Map<String, SpeechModel> = emptyMap(),
    private val transcriptionModels: Map<String, TranscriptionModel> = emptyMap(),
    private val rerankingModels: Map<String, RerankingModel> = emptyMap(),
    private val videoModels: Map<String, VideoModel> = emptyMap(),
) : Provider {
    override fun languageModel(modelId: String): LanguageModel =
        languageModels[modelId] ?: super.languageModel(modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel =
        embeddingModels[modelId] ?: super.embeddingModel(modelId)

    override fun imageModel(modelId: String): ImageModel =
        imageModels[modelId] ?: super.imageModel(modelId)

    override fun speechModel(modelId: String): SpeechModel =
        speechModels[modelId] ?: super.speechModel(modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel =
        transcriptionModels[modelId] ?: super.transcriptionModel(modelId)

    override fun rerankingModel(modelId: String): RerankingModel =
        rerankingModels[modelId] ?: super.rerankingModel(modelId)

    override fun videoModel(modelId: String): VideoModel =
        videoModels[modelId] ?: super.videoModel(modelId)
}

fun customProvider(
    providerId: String,
    languageModels: Map<String, LanguageModel> = emptyMap(),
    embeddingModels: Map<String, EmbeddingModel> = emptyMap(),
    imageModels: Map<String, ImageModel> = emptyMap(),
    speechModels: Map<String, SpeechModel> = emptyMap(),
    transcriptionModels: Map<String, TranscriptionModel> = emptyMap(),
    rerankingModels: Map<String, RerankingModel> = emptyMap(),
    videoModels: Map<String, VideoModel> = emptyMap(),
): Provider = CustomProvider(
    providerId = providerId,
    languageModels = languageModels,
    embeddingModels = embeddingModels,
    imageModels = imageModels,
    speechModels = speechModels,
    transcriptionModels = transcriptionModels,
    rerankingModels = rerankingModels,
    videoModels = videoModels,
)

class ProviderRegistry(
    private val providers: Map<String, Provider>,
    private val defaultProviderId: String? = null,
) : Provider {
    override val providerId: String = "registry"

    fun provider(providerId: String): Provider =
        providers[providerId] ?: throw NoSuchProviderError(providerId)

    override fun languageModel(modelId: String): LanguageModel =
        resolve(modelId) { provider, localId -> provider.languageModel(localId) }

    override fun embeddingModel(modelId: String): EmbeddingModel =
        resolve(modelId) { provider, localId -> provider.embeddingModel(localId) }

    override fun imageModel(modelId: String): ImageModel =
        resolve(modelId) { provider, localId -> provider.imageModel(localId) }

    override fun speechModel(modelId: String): SpeechModel =
        resolve(modelId) { provider, localId -> provider.speechModel(localId) }

    override fun transcriptionModel(modelId: String): TranscriptionModel =
        resolve(modelId) { provider, localId -> provider.transcriptionModel(localId) }

    override fun rerankingModel(modelId: String): RerankingModel =
        resolve(modelId) { provider, localId -> provider.rerankingModel(localId) }

    override fun videoModel(modelId: String): VideoModel =
        resolve(modelId) { provider, localId -> provider.videoModel(localId) }

    private fun <T> resolve(modelId: String, getter: (Provider, String) -> T): T {
        val (providerId, localModelId) = splitProviderModelId(modelId)
        val resolvedProviderId = providerId ?: defaultProviderId ?: singleProviderId()
        return getter(provider(resolvedProviderId), localModelId)
    }

    private fun singleProviderId(): String {
        if (providers.size == 1) return providers.keys.single()
        throw InvalidArgumentError("modelId", "must include a provider prefix when more than one provider is registered")
    }
}

fun createProviderRegistry(
    providers: Map<String, Provider>,
    defaultProviderId: String? = null,
): ProviderRegistry = ProviderRegistry(providers, defaultProviderId)

fun createProviderRegistry(
    vararg providers: Pair<String, Provider>,
    defaultProviderId: String? = null,
): ProviderRegistry = ProviderRegistry(providers.toMap(), defaultProviderId)

fun splitProviderModelId(modelId: String): Pair<String?, String> {
    val colon = modelId.indexOf(':')
    if (colon <= 0) return null to modelId
    return modelId.substring(0, colon) to modelId.substring(colon + 1)
}

data class ProviderMiddleware(
    val languageModelMiddlewares: List<LanguageModelMiddleware> = emptyList(),
    val embeddingModelMiddlewares: List<EmbeddingModelMiddleware> = emptyList(),
    val imageModelMiddlewares: List<ImageModelMiddleware> = emptyList(),
)

fun wrapProvider(provider: Provider, middleware: ProviderMiddleware): Provider =
    object : Provider {
        override val providerId: String = provider.providerId

        override fun languageModel(modelId: String): LanguageModel =
            wrapLanguageModel(provider.languageModel(modelId), middleware.languageModelMiddlewares)

        override fun embeddingModel(modelId: String): EmbeddingModel =
            wrapEmbeddingModel(provider.embeddingModel(modelId), middleware.embeddingModelMiddlewares)

        override fun imageModel(modelId: String): ImageModel =
            wrapImageModel(provider.imageModel(modelId), middleware.imageModelMiddlewares)

        override fun speechModel(modelId: String): SpeechModel = provider.speechModel(modelId)
        override fun transcriptionModel(modelId: String): TranscriptionModel = provider.transcriptionModel(modelId)
        override fun rerankingModel(modelId: String): RerankingModel = provider.rerankingModel(modelId)
        override fun videoModel(modelId: String): VideoModel = provider.videoModel(modelId)
    }
