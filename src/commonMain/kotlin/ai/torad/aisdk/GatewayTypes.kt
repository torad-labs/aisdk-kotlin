package ai.torad.aisdk

import dev.drewhamilton.poko.Poko

@Poko
/** @since 0.3.0-beta01 */
public class GatewayRequestContext(
    /** @since 0.3.0-beta01 */
    public val baseUrl: String,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String>,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewayPricing(
    /** @since 0.3.0-beta01 */
    public val input: String,
    /** @since 0.3.0-beta01 */
    public val output: String,
    /** @since 0.3.0-beta01 */
    public val cachedInputTokens: String? = null,
    /** @since 0.3.0-beta01 */
    public val cacheCreationInputTokens: String? = null,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewayLanguageModelSpecification(
    /** @since 0.3.0-beta01 */
    public val specificationVersion: String = "v3",
    /** @since 0.3.0-beta01 */
    public val provider: String,
    /** @since 0.3.0-beta01 */
    public val modelId: String,
)

/** @since 0.3.0-beta01 */
public enum class GatewayModelType {
    Embedding,
    Image,
    Language,
    Reranking,
    Video,
    ;

    internal companion object {
        internal fun fromWire(value: String?): GatewayModelType? = when (value) {
            "embedding" -> Embedding
            "image" -> Image
            "language" -> Language
            "reranking" -> Reranking
            "video" -> Video
            else -> null
        }
    }
}

@Poko
/** @since 0.3.0-beta01 */
public class GatewayLanguageModelEntry(
    /** @since 0.3.0-beta01 */
    public val id: String,
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val description: String? = null,
    /** @since 0.3.0-beta01 */
    public val pricing: GatewayPricing? = null,
    /** @since 0.3.0-beta01 */
    public val specification: GatewayLanguageModelSpecification,
    /** @since 0.3.0-beta01 */
    public val modelType: GatewayModelType? = null,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewayFetchMetadataResponse(
    /** @since 0.3.0-beta01 */
    public val models: List<GatewayLanguageModelEntry>,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewayCreditsResponse(
    /** @since 0.3.0-beta01 */
    public val balance: String,
    /** @since 0.3.0-beta01 */
    public val totalUsed: String,
)

/** @since 0.3.0-beta01 */
public enum class GatewaySpendReportGroupBy(public val wireValue: String) {
    Day("day"),
    User("user"),
    Model("model"),
    Tag("tag"),
    Provider("provider"),
    CredentialType("credential_type"),
}

/** @since 0.3.0-beta01 */
public enum class GatewaySpendReportDatePart(public val wireValue: String) {
    Day("day"),
    Hour("hour"),
}

/** @since 0.3.0-beta01 */
public enum class GatewayCredentialType(public val wireValue: String) {
    Byok("byok"),
    System("system"),
    ;

    internal companion object {
        internal fun fromWire(value: String?): GatewayCredentialType? = when (value) {
            "byok" -> Byok
            "system" -> System
            else -> null
        }
    }
}

@Poko
/** @since 0.3.0-beta01 */
public class GatewaySpendReportParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val startDate: String,
    /** @since 0.3.0-beta01 */
    public val endDate: String,
    /** @since 0.3.0-beta01 */
    public val groupBy: GatewaySpendReportGroupBy? = null,
    /** @since 0.3.0-beta01 */
    public val datePart: GatewaySpendReportDatePart? = null,
    /** @since 0.3.0-beta01 */
    public val userId: String? = null,
    /** @since 0.3.0-beta01 */
    public val model: String? = null,
    /** @since 0.3.0-beta01 */
    public val provider: String? = null,
    /** @since 0.3.0-beta01 */
    public val credentialType: GatewayCredentialType? = null,
    /** @since 0.3.0-beta01 */
    public val tags: List<String> = emptyList(),
)

/** @since 0.3.0-beta01 */
public class GatewaySpendReportParamsBuilder {
    private var startDate: String? = null
    private var endDate: String? = null
    private var groupBy: GatewaySpendReportGroupBy? = null
    private var datePart: GatewaySpendReportDatePart? = null
    private var userId: String? = null
    private var model: String? = null
    private var provider: String? = null
    private var credentialType: GatewayCredentialType? = null
    private var tags: List<String> = emptyList()

    /** @since 0.3.0-beta01 */
    public fun startDate(value: String): GatewaySpendReportParamsBuilder {
        startDate = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun endDate(value: String): GatewaySpendReportParamsBuilder {
        endDate = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun groupBy(value: GatewaySpendReportGroupBy?): GatewaySpendReportParamsBuilder {
        groupBy = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun datePart(value: GatewaySpendReportDatePart?): GatewaySpendReportParamsBuilder {
        datePart = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun userId(value: String?): GatewaySpendReportParamsBuilder {
        userId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun model(value: String?): GatewaySpendReportParamsBuilder {
        model = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun provider(value: String?): GatewaySpendReportParamsBuilder {
        provider = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun credentialType(value: GatewayCredentialType?): GatewaySpendReportParamsBuilder {
        credentialType = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun tags(value: List<String>): GatewaySpendReportParamsBuilder {
        tags = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): GatewaySpendReportParams =
        GatewaySpendReportParams(
            startDate = requireNotNull(startDate) { "GatewaySpendReportParams.startDate is required" },
            endDate = requireNotNull(endDate) { "GatewaySpendReportParams.endDate is required" },
            groupBy = groupBy,
            datePart = datePart,
            userId = userId,
            model = model,
            provider = provider,
            credentialType = credentialType,
            tags = tags,
        )
}

@Poko
/** @since 0.3.0-beta01 */
public class GatewaySpendReportRow(
    /** @since 0.3.0-beta01 */
    public val day: String? = null,
    /** @since 0.3.0-beta01 */
    public val hour: String? = null,
    /** @since 0.3.0-beta01 */
    public val user: String? = null,
    /** @since 0.3.0-beta01 */
    public val model: String? = null,
    /** @since 0.3.0-beta01 */
    public val tag: String? = null,
    /** @since 0.3.0-beta01 */
    public val provider: String? = null,
    /** @since 0.3.0-beta01 */
    public val credentialType: GatewayCredentialType? = null,
    /** @since 0.3.0-beta01 */
    public val totalCost: Double,
    /** @since 0.3.0-beta01 */
    public val marketCost: Double? = null,
    /** @since 0.3.0-beta01 */
    public val inputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val outputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val cachedInputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val cacheCreationInputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val reasoningTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val requestCount: Int? = null,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewaySpendReportResponse(
    /** @since 0.3.0-beta01 */
    public val results: List<GatewaySpendReportRow>,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewayGenerationInfoParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val id: String,
)

/** @since 0.3.0-beta01 */
public class GatewayGenerationInfoParamsBuilder {
    private var id: String? = null

    /** @since 0.3.0-beta01 */
    public fun id(value: String): GatewayGenerationInfoParamsBuilder {
        id = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): GatewayGenerationInfoParams =
        GatewayGenerationInfoParams(
            id = requireNotNull(id) { "GatewayGenerationInfoParams.id is required" },
        )
}

@Poko
/** @since 0.3.0-beta01 */
public class GatewayGenerationInfo(
    /** @since 0.3.0-beta01 */
    public val id: String,
    /** @since 0.3.0-beta01 */
    public val totalCost: Double,
    /** @since 0.3.0-beta01 */
    public val upstreamInferenceCost: Double,
    /** @since 0.3.0-beta01 */
    public val usage: Double,
    /** @since 0.3.0-beta01 */
    public val createdAt: String,
    /** @since 0.3.0-beta01 */
    public val model: String,
    /** @since 0.3.0-beta01 */
    public val isByok: Boolean,
    /** @since 0.3.0-beta01 */
    public val providerName: String,
    /** @since 0.3.0-beta01 */
    public val streamed: Boolean,
    /** @since 0.3.0-beta01 */
    public val finishReason: String,
    /** @since 0.3.0-beta01 */
    public val latency: Int,
    /** @since 0.3.0-beta01 */
    public val generationTime: Int,
    /** @since 0.3.0-beta01 */
    public val promptTokens: Int,
    /** @since 0.3.0-beta01 */
    public val completionTokens: Int,
    /** @since 0.3.0-beta01 */
    public val reasoningTokens: Int,
    /** @since 0.3.0-beta01 */
    public val cachedTokens: Int,
    /** @since 0.3.0-beta01 */
    public val cacheCreationTokens: Int,
    /** @since 0.3.0-beta01 */
    public val billableWebSearchCalls: Int,
)
