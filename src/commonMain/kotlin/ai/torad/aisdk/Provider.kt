package ai.torad.aisdk

public interface Provider {
    public val providerId: String

    public fun languageModel(modelId: String): LanguageModel =
        throw NoSuchModelError(providerId, "language", modelId)

    public fun embeddingModel(modelId: String): EmbeddingModel =
        throw NoSuchModelError(providerId, "embedding", modelId)

    public fun imageModel(modelId: String): ImageModel =
        throw NoSuchModelError(providerId, "image", modelId)

    public fun speechModel(modelId: String): SpeechModel =
        throw NoSuchModelError(providerId, "speech", modelId)

    public fun transcriptionModel(modelId: String): TranscriptionModel =
        throw NoSuchModelError(providerId, "transcription", modelId)

    public fun rerankingModel(modelId: String): RerankingModel =
        throw NoSuchModelError(providerId, "reranking", modelId)

    public fun videoModel(modelId: String): VideoModel =
        throw NoSuchModelError(providerId, "video", modelId)
}

public class CustomProvider internal constructor(
    override val providerId: String,
    private val languageModels: Map<String, LanguageModel> = emptyMap(),
    private val embeddingModels: Map<String, EmbeddingModel> = emptyMap(),
    private val imageModels: Map<String, ImageModel> = emptyMap(),
    private val speechModels: Map<String, SpeechModel> = emptyMap(),
    private val transcriptionModels: Map<String, TranscriptionModel> = emptyMap(),
    private val rerankingModels: Map<String, RerankingModel> = emptyMap(),
    private val videoModels: Map<String, VideoModel> = emptyMap(),
    /** Resolved for any model id not in this provider's maps, before failing (v6 parity). */
    private val fallbackProvider: Provider? = null,
) : Provider {
    override fun languageModel(modelId: String): LanguageModel =
        languageModels[modelId] ?: fallbackProvider?.languageModel(modelId) ?: super.languageModel(modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel =
        embeddingModels[modelId] ?: fallbackProvider?.embeddingModel(modelId) ?: super.embeddingModel(modelId)

    override fun imageModel(modelId: String): ImageModel =
        imageModels[modelId] ?: fallbackProvider?.imageModel(modelId) ?: super.imageModel(modelId)

    override fun speechModel(modelId: String): SpeechModel =
        speechModels[modelId] ?: fallbackProvider?.speechModel(modelId) ?: super.speechModel(modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel =
        transcriptionModels[modelId] ?: fallbackProvider?.transcriptionModel(modelId)
            ?: super.transcriptionModel(modelId)

    override fun rerankingModel(modelId: String): RerankingModel =
        rerankingModels[modelId] ?: fallbackProvider?.rerankingModel(modelId) ?: super.rerankingModel(modelId)

    override fun videoModel(modelId: String): VideoModel =
        videoModels[modelId] ?: fallbackProvider?.videoModel(modelId) ?: super.videoModel(modelId)
}

public class CustomProviderBuilder internal constructor() {
    private var providerId: String? = null
    private var languageModels: Map<String, LanguageModel> = emptyMap()
    private var embeddingModels: Map<String, EmbeddingModel> = emptyMap()
    private var imageModels: Map<String, ImageModel> = emptyMap()
    private var speechModels: Map<String, SpeechModel> = emptyMap()
    private var transcriptionModels: Map<String, TranscriptionModel> = emptyMap()
    private var rerankingModels: Map<String, RerankingModel> = emptyMap()
    private var videoModels: Map<String, VideoModel> = emptyMap()
    private var fallbackProvider: Provider? = null

    public fun providerId(value: String) {
        providerId = value
    }

    public fun languageModels(value: Map<String, LanguageModel>) {
        languageModels = value
    }

    public fun languageModel(id: String, model: LanguageModel) {
        languageModels = languageModels + (id to model)
    }

    public fun embeddingModels(value: Map<String, EmbeddingModel>) {
        embeddingModels = value
    }

    public fun embeddingModel(id: String, model: EmbeddingModel) {
        embeddingModels = embeddingModels + (id to model)
    }

    public fun imageModels(value: Map<String, ImageModel>) {
        imageModels = value
    }

    public fun imageModel(id: String, model: ImageModel) {
        imageModels = imageModels + (id to model)
    }

    public fun speechModels(value: Map<String, SpeechModel>) {
        speechModels = value
    }

    public fun speechModel(id: String, model: SpeechModel) {
        speechModels = speechModels + (id to model)
    }

    public fun transcriptionModels(value: Map<String, TranscriptionModel>) {
        transcriptionModels = value
    }

    public fun transcriptionModel(id: String, model: TranscriptionModel) {
        transcriptionModels = transcriptionModels + (id to model)
    }

    public fun rerankingModels(value: Map<String, RerankingModel>) {
        rerankingModels = value
    }

    public fun rerankingModel(id: String, model: RerankingModel) {
        rerankingModels = rerankingModels + (id to model)
    }

    public fun videoModels(value: Map<String, VideoModel>) {
        videoModels = value
    }

    public fun videoModel(id: String, model: VideoModel) {
        videoModels = videoModels + (id to model)
    }

    public fun fallbackProvider(value: Provider?) {
        fallbackProvider = value
    }

    internal fun build(): CustomProvider =
        CustomProvider(
            providerId = requireNotNull(providerId) { "CustomProvider.providerId is required" },
            languageModels = languageModels,
            embeddingModels = embeddingModels,
            imageModels = imageModels,
            speechModels = speechModels,
            transcriptionModels = transcriptionModels,
            rerankingModels = rerankingModels,
            videoModels = videoModels,
            fallbackProvider = fallbackProvider,
        )
}

public fun CustomProvider(block: CustomProviderBuilder.() -> Unit): CustomProvider =
    CustomProviderBuilder().apply(block).build()

public fun Provider(
    providerId: String,
    languageModels: Map<String, LanguageModel> = emptyMap(),
    embeddingModels: Map<String, EmbeddingModel> = emptyMap(),
    imageModels: Map<String, ImageModel> = emptyMap(),
    speechModels: Map<String, SpeechModel> = emptyMap(),
    transcriptionModels: Map<String, TranscriptionModel> = emptyMap(),
    rerankingModels: Map<String, RerankingModel> = emptyMap(),
    videoModels: Map<String, VideoModel> = emptyMap(),
    fallbackProvider: Provider? = null,
): Provider = CustomProvider {
    providerId(providerId)
    languageModels(languageModels)
    embeddingModels(embeddingModels)
    imageModels(imageModels)
    speechModels(speechModels)
    transcriptionModels(transcriptionModels)
    rerankingModels(rerankingModels)
    videoModels(videoModels)
    fallbackProvider(fallbackProvider)
}

public class ProviderRegistry(
    private val providers: Map<String, Provider>,
    private val defaultProviderId: String? = null,
    private val separator: String = ":",
    /** Middleware applied to every language model resolved through this registry (v6 parity). */
    private val languageModelMiddleware: List<LanguageModelMiddleware> = emptyList(),
) : Provider {
    override val providerId: String = "registry"

    public fun provider(providerId: String): Provider =
        providers[providerId]
            ?: throw NoSuchProviderError(providerId, availableProviders = providers.keys.sorted())

    override fun languageModel(modelId: String): LanguageModel =
        WrapLanguageModel(
            resolve(modelId) { provider, localId -> provider.languageModel(localId) },
            languageModelMiddleware,
        )

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
        val (providerId, localModelId) = splitProviderModelId(modelId, separator)
        val resolvedProviderId = providerId ?: defaultProviderId ?: singleProviderId()
        return getter(provider(resolvedProviderId), localModelId)
    }

    private fun singleProviderId(): String {
        if (providers.size == 1) return providers.keys.single()
        throw InvalidArgumentError("modelId", "must include a provider prefix when more than one provider is registered")
    }

    public companion object {
        public fun createProviderRegistry(
            providers: Map<String, Provider>,
            defaultProviderId: String? = null,
            separator: String = ":",
            languageModelMiddleware: List<LanguageModelMiddleware> = emptyList(),
        ): ProviderRegistry = ProviderRegistry(providers, defaultProviderId, separator, languageModelMiddleware)

        public fun createProviderRegistry(
            vararg providers: Pair<String, Provider>,
            defaultProviderId: String? = null,
            separator: String = ":",
            languageModelMiddleware: List<LanguageModelMiddleware> = emptyList(),
        ): ProviderRegistry = ProviderRegistry(providers.toMap(), defaultProviderId, separator, languageModelMiddleware)

        public fun splitProviderModelId(modelId: String, separator: String = ":"): Pair<String?, String> {
            val colon = modelId.indexOf(separator)
            if (colon <= 0) return null to modelId
            return modelId.substring(0, colon) to modelId.substring(colon + separator.length)
        }
    }
}

public class ProviderMiddleware internal constructor(
    public val languageModelMiddlewares: List<LanguageModelMiddleware> = emptyList(),
    public val embeddingModelMiddlewares: List<EmbeddingModelMiddleware> = emptyList(),
    public val imageModelMiddlewares: List<ImageModelMiddleware> = emptyList(),
)

public class ProviderMiddlewareBuilder internal constructor() {
    private var languageModelMiddlewares: List<LanguageModelMiddleware> = emptyList()
    private var embeddingModelMiddlewares: List<EmbeddingModelMiddleware> = emptyList()
    private var imageModelMiddlewares: List<ImageModelMiddleware> = emptyList()

    public fun languageModelMiddlewares(value: List<LanguageModelMiddleware>) {
        languageModelMiddlewares = value
    }

    public fun embeddingModelMiddlewares(value: List<EmbeddingModelMiddleware>) {
        embeddingModelMiddlewares = value
    }

    public fun imageModelMiddlewares(value: List<ImageModelMiddleware>) {
        imageModelMiddlewares = value
    }

    internal fun build(): ProviderMiddleware =
        ProviderMiddleware(
            languageModelMiddlewares = languageModelMiddlewares,
            embeddingModelMiddlewares = embeddingModelMiddlewares,
            imageModelMiddlewares = imageModelMiddlewares,
        )
}

public fun ProviderMiddleware(
    block: ProviderMiddlewareBuilder.() -> Unit = {},
): ProviderMiddleware =
    ProviderMiddlewareBuilder().apply(block).build()

public fun WrapProvider(provider: Provider, middleware: ProviderMiddleware): Provider =
    object : Provider {
        override val providerId: String = provider.providerId

        override fun languageModel(modelId: String): LanguageModel =
            WrapLanguageModel(provider.languageModel(modelId), middleware.languageModelMiddlewares)

        override fun embeddingModel(modelId: String): EmbeddingModel =
            WrapEmbeddingModel(provider.embeddingModel(modelId), middleware.embeddingModelMiddlewares)

        override fun imageModel(modelId: String): ImageModel =
            WrapImageModel(provider.imageModel(modelId), middleware.imageModelMiddlewares)

        override fun speechModel(modelId: String): SpeechModel = provider.speechModel(modelId)
        override fun transcriptionModel(modelId: String): TranscriptionModel = provider.transcriptionModel(modelId)
        override fun rerankingModel(modelId: String): RerankingModel = provider.rerankingModel(modelId)
        override fun videoModel(modelId: String): VideoModel = provider.videoModel(modelId)
    }
