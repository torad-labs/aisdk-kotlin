package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

public const val VOYAGE_VERSION: String = "1.0.4"

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class VoyageEmbeddingModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val inputType: String? = null,
    /** @since 0.3.0-beta01 */
    public val truncation: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val outputDimension: Int? = null,
    /** @since 0.3.0-beta01 */
    public val outputDtype: String? = null,
)

/** @since 0.3.0-beta01 */
public class VoyageEmbeddingModelOptionsBuilder {
    private var inputType: String? = null
    private var truncation: Boolean? = null
    private var outputDimension: Int? = null
    private var outputDtype: String? = null

    /** @since 0.3.0-beta01 */
    public fun inputType(value: String?): VoyageEmbeddingModelOptionsBuilder {
        inputType = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun truncation(value: Boolean?): VoyageEmbeddingModelOptionsBuilder {
        truncation = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun outputDimension(value: Int?): VoyageEmbeddingModelOptionsBuilder {
        outputDimension = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun outputDtype(value: String?): VoyageEmbeddingModelOptionsBuilder {
        outputDtype = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): VoyageEmbeddingModelOptions =
        VoyageEmbeddingModelOptions(
            inputType = inputType,
            truncation = truncation,
            outputDimension = outputDimension,
            outputDtype = outputDtype,
        )
}

/** @since 0.3.0-beta01 */
public fun VoyageEmbeddingModelOptions(
    block: VoyageEmbeddingModelOptionsBuilder.() -> Unit = {},
): VoyageEmbeddingModelOptions =
    VoyageEmbeddingModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class VoyageRerankingModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val returnDocuments: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val truncation: Boolean? = null,
)

/** @since 0.3.0-beta01 */
public class VoyageRerankingModelOptionsBuilder {
    private var returnDocuments: Boolean? = null
    private var truncation: Boolean? = null

    /** @since 0.3.0-beta01 */
    public fun returnDocuments(value: Boolean?): VoyageRerankingModelOptionsBuilder {
        returnDocuments = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun truncation(value: Boolean?): VoyageRerankingModelOptionsBuilder {
        truncation = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): VoyageRerankingModelOptions =
        VoyageRerankingModelOptions(
            returnDocuments = returnDocuments,
            truncation = truncation,
        )
}

/** @since 0.3.0-beta01 */
public fun VoyageRerankingModelOptions(
    block: VoyageRerankingModelOptionsBuilder.() -> Unit = {},
): VoyageRerankingModelOptions =
    VoyageRerankingModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class VoyageProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.voyageai.com/v1",
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
) {
    internal suspend fun voyagePostJson(
        client: HttpClient,
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Post,
            headers = headers,
            body = body,
            requestBodyValues = body,
            errorMessage = ::voyageErrorMessage,
        )

    internal fun voyageHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/voyage/$VOYAGE_VERSION")
    }

    internal fun voyageOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "voyage") ?: JsonObject(emptyMap())

    private fun voyageErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val message = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("detail") as? JsonPrimitive)?.contentOrNull
            ?: ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Voyage request failed ($statusCode): $message"
    }
}

/** @since 0.3.0-beta01 */
public class VoyageProviderSettingsBuilder {
    private var baseURL: String = "https://api.voyageai.com/v1"
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): VoyageProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): VoyageProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): VoyageProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): VoyageProviderSettings =
        VoyageProviderSettings(
            baseURL = baseURL,
            apiKey = apiKey,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun VoyageProviderSettings(
    block: VoyageProviderSettingsBuilder.() -> Unit = {},
): VoyageProviderSettings =
    VoyageProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class VoyageProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: VoyageProviderSettings,
) : Provider {
    override val providerId: String = "voyage"

    /** @since 0.3.0-beta01 */
    public fun embedding(modelId: ModelId): EmbeddingModel = VoyageEmbeddingModel(client, settings, modelId.value)

    /** @since 0.3.0-beta01 */
    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)

    /** @since 0.3.0-beta01 */
    public fun reranking(modelId: ModelId): RerankingModel = VoyageRerankingModel(client, settings, modelId.value)

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    override fun rerankingModel(modelId: String): RerankingModel = reranking(ModelId(modelId))
}

/**
 * PascalCase factory — mirrors the OpenAI reference pattern.
 * @since 0.3.0-beta01
 */
public fun Voyage(
    client: HttpClient,
    settings: VoyageProviderSettings = VoyageProviderSettings(),
): VoyageProvider = VoyageProvider(client, settings)

private class VoyageEmbeddingModel(
    private val client: HttpClient,
    private val settings: VoyageProviderSettings,
    override val modelId: String,
) : EmbeddingModel {
    override val provider: String = "voyage.embedding"
    override val maxEmbeddingsPerCall: Int = VOYAGE_MAX_EMBEDDINGS_PER_CALL
    override val supportsParallelCalls: Boolean = true

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        if (params.values.size > VOYAGE_MAX_EMBEDDINGS_PER_CALL) {
            throw InvalidArgumentError(
                "values",
                "embedding model voyage:$modelId supports at most $VOYAGE_MAX_EMBEDDINGS_PER_CALL values per call",
            )
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", JsonArray(params.values.map(::JsonPrimitive)))
            val options = settings.voyageOptions(params.providerOptions)
            options["inputType"]?.let { put("input_type", it) }
            (options["truncation"] ?: params.truncate?.let(::JsonPrimitive))?.let { put("truncation", it) }
            options["outputDimension"]?.let { put("output_dimension", it) }
            options["outputDtype"]?.let { put("output_dtype", it) }
            (options["encodingFormat"] ?: options["encoding_format"])?.let { put("encoding_format", it) }
        }
        val response = settings.voyagePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/embeddings",
            body = body,
            headers = settings.voyageHeaders(params.headers),
        )
        val value = response.value.jsonObject
        val outputDtype = voyageOutputDtype(body)
        val requestedOutputDimension = (body["output_dimension"] as? JsonPrimitive)?.intOrNull
        val embeddings = JsonAccess.arr(value, "data").orEmpty()
            .mapIndexed { index, item ->
                normalizeVoyageEmbedding(
                    value = (item as? JsonObject)?.get("embedding"),
                    outputDtype = outputDtype,
                    provider = provider,
                    path = "$.data[$index].embedding",
                )
            }
        return EmbeddingModelResult(
            embeddings = embeddings,
            usage = EmbeddingUsage(
                tokens = (JsonAccess.obj(value, "usage")?.get("total_tokens") as? JsonPrimitive)?.intOrNull
                    ?: 0,
                raw = value["usage"],
            ),
            request = LanguageModelRequestMetadata(body = body),
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
            providerMetadata = voyageEmbeddingRepresentation(
                outputDtype = outputDtype,
                requestedOutputDimension = requestedOutputDimension,
                embeddings = embeddings,
            ),
        )
    }

    private fun voyageOutputDtype(body: JsonObject): VoyageOutputDtype =
        if ("output_dtype" !in body) {
            VoyageOutputDtype.Missing
        } else {
            val raw = body.getValue("output_dtype")
            if (raw is JsonPrimitive && raw.isString) {
                VoyageOutputDtype.PresentString(raw.content)
            } else {
                VoyageOutputDtype.PresentInvalid(raw)
            }
        }

    private fun normalizeVoyageEmbedding(
        value: JsonElement?,
        outputDtype: VoyageOutputDtype,
        provider: String,
        path: String,
    ): List<Float> =
        when (value) {
            is JsonArray -> {
                val stored = value.mapIndexed { index, item ->
                    WireDecoder.embeddingFloat(item, provider, path = "$path[$index]")
                }
                // A JSON-array response carries the same PACKED integers base64 does, so the
                // bit-packed dtypes are expanded here too. Otherwise `binary` would hand back one
                // value per byte over JSON and one per dimension over base64 — the same dtype
                // yielding two different vector lengths depending on `encoding_format`.
                VoyagePackedBits.expandIfNeeded(stored, voyageEffectiveDtypeOrNull(outputDtype))
            }
            is JsonPrimitive -> if (value.isString) {
                decodeVoyageBase64Embedding(value.content, outputDtype, provider, path)
            } else {
                emptyList()
            }
            else -> emptyList()
        }

    private fun decodeVoyageBase64Embedding(
        value: String,
        outputDtype: VoyageOutputDtype,
        provider: String,
        path: String,
    ): List<Float> {
        val effectiveOutputDtype = when (outputDtype) {
            VoyageOutputDtype.Missing -> VOYAGE_OUTPUT_DTYPE_FLOAT
            is VoyageOutputDtype.PresentString -> outputDtype.value
            is VoyageOutputDtype.PresentInvalid -> WireDecoder.fail(
                provider = provider,
                operation = "embedding response",
                path = path,
                message = "present output_dtype must be a JSON string for base64 decoding",
                value = outputDtype.raw,
            )
        }
        val storage = voyageBase64Storage(effectiveOutputDtype)
            ?: WireDecoder.fail(
                provider = provider,
                operation = "embedding response",
                path = path,
                message = "cannot decode base64 for custom output_dtype '$effectiveOutputDtype'",
            )
        val bytes = try {
            Base64Codec.decode(value)
        } catch (error: IllegalArgumentException) {
            WireDecoder.fail(
                provider = provider,
                operation = "embedding response",
                path = path,
                message = "expected valid base64 for output_dtype '$effectiveOutputDtype'",
                cause = error,
            )
        }
        return when (storage) {
            VoyageBase64Storage.Float32 -> decodeVoyageFloat32(bytes, provider, path)
            VoyageBase64Storage.SignedInt8 -> bytes.map { it.toFloat() }
            VoyageBase64Storage.UnsignedInt8 -> bytes.map { (it.toInt() and 0xff).toFloat() }
            // `binary` stores the packed byte in OFFSET BINARY: the wire int8 is
            // (packed_uint8 - 128), so the offset is added back before the bits are read.
            VoyageBase64Storage.OffsetBinaryBits ->
                bytes.flatMap { VoyagePackedBits.unpack(it.toInt() + VOYAGE_BINARY_OFFSET) }
            VoyageBase64Storage.UnsignedBits ->
                bytes.flatMap { VoyagePackedBits.unpack(it.toInt() and 0xff) }
        }
    }

    private fun voyageEffectiveDtypeOrNull(outputDtype: VoyageOutputDtype): String? =
        when (outputDtype) {
            VoyageOutputDtype.Missing -> VOYAGE_OUTPUT_DTYPE_FLOAT
            is VoyageOutputDtype.PresentString -> outputDtype.value
            is VoyageOutputDtype.PresentInvalid -> null
        }

    private fun decodeVoyageFloat32(
        bytes: ByteArray,
        provider: String,
        path: String,
    ): List<Float> {
        if (bytes.size % Float.SIZE_BYTES != 0) {
            WireDecoder.fail(
                provider = provider,
                operation = "embedding response",
                path = path,
                message = "expected a float32 byte length divisible by ${Float.SIZE_BYTES}, got ${bytes.size}",
            )
        }
        return List(bytes.size / Float.SIZE_BYTES) { index ->
            val offset = index * Float.SIZE_BYTES
            // Voyage base64 float embeddings are little-endian NumPy float32 buffers.
            val bits =
                (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xff) shl 24)
            Float.fromBits(bits)
        }
    }

    private fun voyageEmbeddingRepresentation(
        outputDtype: VoyageOutputDtype,
        requestedOutputDimension: Int?,
        embeddings: List<List<Float>>,
    ): ProviderMetadata {
        val rawOutputDtype: JsonElement = when (outputDtype) {
            VoyageOutputDtype.Missing -> JsonNull
            is VoyageOutputDtype.PresentString -> JsonPrimitive(outputDtype.value)
            is VoyageOutputDtype.PresentInvalid -> outputDtype.raw
        }
        // One resolution of the effective dtype, reused. Three separate `when (outputDtype)` blocks
        // repeated the same Missing/PresentString/PresentInvalid split here and were most of this
        // function's cyclomatic complexity; `null` already means PresentInvalid downstream.
        val effectiveOutputDtype: String? = voyageEffectiveDtypeOrNull(outputDtype)
        val packing = effectiveOutputDtype?.let { voyageEmbeddingPacking(it) } ?: "unknown"
        val logicalDimension = if (effectiveOutputDtype == null) {
            requestedOutputDimension
        } else {
            voyageLogicalDimension(requestedOutputDimension, effectiveOutputDtype, embeddings)
        }
        return ProviderMetadata(
            "voyage" to buildJsonObject {
                put(
                    "embeddingRepresentation",
                    buildJsonObject {
                        put("rawOutputDtype", rawOutputDtype)
                        put("effectiveOutputDtype", effectiveOutputDtype?.let(::JsonPrimitive) ?: JsonNull)
                        put("packing", packing)
                        put("logicalDimension", logicalDimension?.let(::JsonPrimitive) ?: JsonNull)
                        put(
                            "storedElementCounts",
                            JsonArray(
                                embeddings.map {
                                    JsonPrimitive(VoyagePackedBits.storedElementCount(effectiveOutputDtype, it.size))
                                },
                            ),
                        )
                    },
                )
            },
        )
    }

    private fun voyageLogicalDimension(
        requestedOutputDimension: Int?,
        effectiveOutputDtype: String,
        embeddings: List<List<Float>>,
    ): Int? {
        if (requestedOutputDimension != null) return requestedOutputDimension
        if (voyageBase64Storage(effectiveOutputDtype) == null || embeddings.isEmpty()) return null
        val decodedDimension = embeddings.first().size
        if (decodedDimension == 0 || embeddings.any { it.size != decodedDimension }) return null
        // Bit-packed rows are expanded to one value per dimension at decode time, so the decoded
        // length already IS the logical dimension for every dtype. Multiplying by 8 here again
        // (as this did while binary/ubinary decoded one value per BYTE) would double-count.
        return decodedDimension
    }

    private fun voyageEmbeddingPacking(effectiveOutputDtype: String): String =
        when (effectiveOutputDtype) {
            VOYAGE_OUTPUT_DTYPE_FLOAT, VOYAGE_OUTPUT_DTYPE_INT8, VOYAGE_OUTPUT_DTYPE_UINT8 -> "none"
            VOYAGE_OUTPUT_DTYPE_BINARY, VOYAGE_OUTPUT_DTYPE_UBINARY -> "bit-packed"
            else -> "unknown"
        }

    private fun voyageBase64Storage(effectiveOutputDtype: String): VoyageBase64Storage? =
        when (effectiveOutputDtype) {
            VOYAGE_OUTPUT_DTYPE_FLOAT -> VoyageBase64Storage.Float32
            VOYAGE_OUTPUT_DTYPE_INT8 -> VoyageBase64Storage.SignedInt8
            VOYAGE_OUTPUT_DTYPE_UINT8 -> VoyageBase64Storage.UnsignedInt8
            VOYAGE_OUTPUT_DTYPE_BINARY -> VoyageBase64Storage.OffsetBinaryBits
            VOYAGE_OUTPUT_DTYPE_UBINARY -> VoyageBase64Storage.UnsignedBits
            else -> null
        }

    private sealed interface VoyageOutputDtype {
        object Missing : VoyageOutputDtype

        class PresentString(val value: String) : VoyageOutputDtype

        class PresentInvalid(val raw: JsonElement) : VoyageOutputDtype
    }

    private enum class VoyageBase64Storage {
        Float32,
        SignedInt8,
        UnsignedInt8,
        OffsetBinaryBits,
        UnsignedBits,
    }
}

private class VoyageRerankingModel(
    private val client: HttpClient,
    private val settings: VoyageProviderSettings,
    override val modelId: String,
) : RerankingModel {
    override val provider: String = "voyage.reranking"

    override suspend fun rerank(params: RerankingParams): RerankingModelResult {
        val response = settings.voyagePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/rerank",
            body = buildJsonObject {
                put("model", JsonPrimitive(modelId))
                put("query", JsonPrimitive(params.query))
                put("documents", JsonArray(params.documents.map(::JsonPrimitive)))
                params.topN?.let { put("top_k", JsonPrimitive(it)) }
                val options = settings.voyageOptions(params.providerOptions)
                options["returnDocuments"]?.let { put("return_documents", it) }
                options["truncation"]?.let { put("truncation", it) }
            },
            headers = settings.voyageHeaders(params.headers),
        )
        val value = response.value.jsonObject
        val results = (JsonAccess.arr(value, "data")).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = (obj["relevance_score"] as? JsonPrimitive)?.floatOrNull ?: 0f,
                index = index,
            )
        }
        val totalTokens = (JsonAccess.obj(value, "usage")?.get("total_tokens") as? JsonPrimitive)?.intOrNull
            ?: 0
        return RerankingModelResult(
            results = results,
            usage = Usage(promptTokens = totalTokens, completionTokens = 0),
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
        )
    }
}

// Documented Voyage batch limit is 1,000 inputs per embeddings call.
private const val VOYAGE_MAX_EMBEDDINGS_PER_CALL: Int = 1_000
private const val VOYAGE_OUTPUT_DTYPE_FLOAT: String = "float"
private const val VOYAGE_OUTPUT_DTYPE_INT8: String = "int8"
private const val VOYAGE_OUTPUT_DTYPE_UINT8: String = "uint8"
private const val VOYAGE_OUTPUT_DTYPE_BINARY: String = "binary"
private const val VOYAGE_OUTPUT_DTYPE_UBINARY: String = "ubinary"

/** Offset-binary bias for `output_dtype: binary`; documented as 128 for signed 8-bit integers. */
private const val VOYAGE_BINARY_OFFSET: Int = 128

/** Single-bit dimensions packed into one wire byte, for `output_dtype` `binary` / `ubinary`. */
private const val VOYAGE_BITS_PER_PACKED_BYTE: Int = 8

/** Widens a signed `Byte`/`Int` to its unsigned 0..255 value. */
private const val UNSIGNED_BYTE_MASK: Int = 0xff

/**
 * Bit-packing arithmetic for Voyage's `binary` / `ubinary` embeddings, which carry 8 single-bit
 * dimensions per wire byte — the returned integer list is 1/8 of the real dimension.
 *
 * Voyage's own unpack example reads each bit as a boolean, so a set bit decodes to `1f` and a
 * clear bit to `0f` for BOTH dtypes; the only difference is the signedness of the wire byte, which
 * callers normalise before calling in (`binary` is offset binary, so add [VOYAGE_BINARY_OFFSET]).
 */
private object VoyagePackedBits {
    /** Most-significant bit first, matching the documented `01001101` packing order. */
    fun unpack(packedByte: Int): List<Float> =
        (VOYAGE_BITS_PER_PACKED_BYTE - 1 downTo 0)
            .map { bit -> if ((packedByte shr bit) and 1 == 1) 1f else 0f }

    /**
     * Expand a JSON-array row, which carries the same packed integers base64 does. Without this a
     * given dtype would yield one value per byte over JSON and one per dimension over base64.
     */
    fun expandIfNeeded(stored: List<Float>, effectiveOutputDtype: String?): List<Float> =
        when (effectiveOutputDtype) {
            VOYAGE_OUTPUT_DTYPE_BINARY -> stored.flatMap { unpack(it.toInt() + VOYAGE_BINARY_OFFSET) }
            VOYAGE_OUTPUT_DTYPE_UBINARY -> stored.flatMap { unpack(it.toInt() and UNSIGNED_BYTE_MASK) }
            else -> stored
        }

    /**
     * How many elements Voyage actually sent for a row, given the decoded length. Equal for the
     * byte-per-value dtypes; one eighth for the bit-packed ones.
     */
    fun storedElementCount(effectiveOutputDtype: String?, decodedSize: Int): Int =
        when (effectiveOutputDtype) {
            VOYAGE_OUTPUT_DTYPE_BINARY, VOYAGE_OUTPUT_DTYPE_UBINARY ->
                decodedSize / VOYAGE_BITS_PER_PACKED_BYTE
            else -> decodedSize
        }
}
