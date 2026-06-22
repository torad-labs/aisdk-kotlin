package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.ProviderMetadata
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

public const val PRODIA_VERSION: String = "1.0.31"

public typealias ProdiaImageProviderOptions = ProdiaImageModelOptions

@Serializable
public data class ProdiaProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://inference.prodia.com/v2",
    val headers: Map<String, String> = emptyMap(),
) {
    internal fun prodiaOptions(providerOptions: ProviderOptions): JsonObject =
        providerOptions.toMap()["prodia"] as? JsonObject ?: JsonObject(emptyMap())

    internal fun prodiaHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String?>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        headers.forEach { (key, value) -> base[key] = value }
        callHeaders.forEach { (key, value) -> base[key] = value }
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/prodia/$PRODIA_VERSION")
    }

    internal suspend fun prodiaPostJsonForMultipart(
        client: HttpClient,
        url: String,
        body: JsonObject,
        accept: String,
        headers: Map<String, String>,
    ): ProdiaMultipartResult {
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, accept)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(aiSdkJson.encodeToString(JsonElement.serializer(), body))
        }
        return prodiaParseMultipartResponse(response, url)
    }

    internal suspend fun prodiaPostMultipart(
        client: HttpClient,
        url: String,
        body: JsonObject,
        input: ProdiaInputFile?,
        accept: String,
        headers: Map<String, String>,
    ): ProdiaMultipartResult {
        val response = client.request(url) {
            method = HttpMethod.Post
            header(HttpHeaders.Accept, accept)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "job",
                            aiSdkJson.encodeToString(JsonElement.serializer(), body),
                            Headers.build { append(HttpHeaders.ContentType, "application/json") },
                        )
                        input?.let { file ->
                            append(
                                "input",
                                file.bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, file.mediaType)
                                    append(HttpHeaders.ContentDisposition, "${ContentDisposition.File}; filename=\"${file.filename ?: "input.${MediaTypes.toExtension(file.mediaType)}"}\"")
                                },
                            )
                        }
                    },
                ),
            )
        }
        return prodiaParseMultipartResponse(response, url)
    }

    private suspend fun prodiaParseMultipartResponse(response: HttpResponse, url: String): ProdiaMultipartResult {
        val responseHeaders = with(HttpTransport) { response.flattenedHeaders() }
        val rawContentType = response.headers[HttpHeaders.ContentType].orEmpty()
        val bytes = response.bodyAsBytes()
        if (response.status.value !in 200..299) {
            val raw = bytes.decodeToString()
            throw ApiCallError(
                url = url,
                statusCode = response.status.value,
                rawBody = raw,
                headers = responseHeaders,
                message = "Prodia request failed (${response.status.value}): ${prodiaErrorMessage(raw)}",
            )
        }
        val boundary = Regex("""boundary=([^;\s]+)""").find(rawContentType)?.groupValues?.get(1)
            ?: throw InvalidResponseDataError(null, "Prodia response missing multipart boundary in content-type: $rawContentType")
        val parts = prodiaSplitMultipart(bytes, boundary)
        var job: JsonObject? = null
        var text: String? = null
        val files = mutableListOf<GeneratedFile>()
        for (part in parts) {
            val contentDisposition = part.headers["content-disposition"].orEmpty()
            val contentType = part.headers["content-type"].orEmpty()
            if (contentDisposition.contains("name=\"job\"")) {
                job = aiSdkJson.parseToJsonElement(part.body.decodeToString()).jsonObject
            } else if (contentDisposition.contains("name=\"output\"")) {
                when {
                    contentType.startsWith("text/") || contentDisposition.contains(".txt") -> text = part.body.decodeToString()
                    contentType.startsWith("image/") || contentType.startsWith("video/") -> files += GeneratedFile(
                        mediaType = contentType,
                        base64 = Base64Codec.encode(part.body),
                    )
                }
            } else if (contentType.startsWith("image/") || contentType.startsWith("video/")) {
                files += GeneratedFile(mediaType = contentType, base64 = Base64Codec.encode(part.body))
            }
        }
        return ProdiaMultipartResult(
            job = job ?: throw InvalidResponseDataError(null, "Prodia multipart response missing job part"),
            text = text,
            files = files,
            headers = responseHeaders,
        )
    }

    private fun prodiaSplitMultipart(data: ByteArray, boundary: String): List<ProdiaMultipartPart> {
        val boundaryBytes = "--$boundary".encodeToByteArray()
        val positions = mutableListOf<Int>()
        var searchFrom = 0
        while (searchFrom <= data.size - boundaryBytes.size) {
            val next = data.indexOfBytes(boundaryBytes, searchFrom)
            if (next < 0) break
            positions += next
            searchFrom = next + boundaryBytes.size
        }
        val parts = mutableListOf<ProdiaMultipartPart>()
        for (index in 0 until positions.lastIndex) {
            var partStart = positions[index] + boundaryBytes.size
            var partEnd = positions[index + 1]
            if (data.getOrNull(partStart) == '\r'.code.toByte() && data.getOrNull(partStart + 1) == '\n'.code.toByte()) {
                partStart += 2
            } else if (data.getOrNull(partStart) == '\n'.code.toByte()) {
                partStart += 1
            }
            if (data.getOrNull(partEnd - 2) == '\r'.code.toByte() && data.getOrNull(partEnd - 1) == '\n'.code.toByte()) {
                partEnd -= 2
            } else if (data.getOrNull(partEnd - 1) == '\n'.code.toByte()) {
                partEnd -= 1
            }
            if (partStart >= partEnd) continue
            val partData = data.copyOfRange(partStart, partEnd)
            val headerEnd = partData.headerEndIndex()
            if (headerEnd < 0) continue
            val headerSeparatorLength = if (
                partData.getOrNull(headerEnd) == '\r'.code.toByte() &&
                partData.getOrNull(headerEnd + 1) == '\n'.code.toByte()
            ) {
                4
            } else {
                2
            }
            val headers = partData.copyOfRange(0, headerEnd).decodeToString()
                .lineSequence()
                .mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }
                .toMap()
            val body = partData.copyOfRange(headerEnd + headerSeparatorLength, partData.size)
            parts += ProdiaMultipartPart(headers, body)
        }
        return parts
    }

    private fun ByteArray.indexOfBytes(needle: ByteArray, start: Int): Int {
        if (needle.isEmpty()) return start
        for (index in start..(size - needle.size)) {
            var matches = true
            for (needleIndex in needle.indices) {
                if (this[index + needleIndex] != needle[needleIndex]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private fun ByteArray.headerEndIndex(): Int {
        for (index in 0 until size - 3) {
            if (
                this[index] == '\r'.code.toByte() &&
                this[index + 1] == '\n'.code.toByte() &&
                this[index + 2] == '\r'.code.toByte() &&
                this[index + 3] == '\n'.code.toByte()
            ) {
                return index
            }
        }
        for (index in 0 until size - 1) {
            if (this[index] == '\n'.code.toByte() && this[index + 1] == '\n'.code.toByte()) return index
        }
        return -1
    }

    private fun prodiaErrorMessage(raw: String): String {
        val obj = runCatching { aiSdkJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw.ifBlank { "request failed" }
        return (obj["detail"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["error"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
    }
}

@Serializable
public data class ProdiaLanguageModelOptions(
    val aspectRatio: String? = null,
)

@Serializable
public data class ProdiaImageModelOptions(
    val steps: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val stylePreset: String? = null,
    val loras: List<String>? = null,
    val progressive: Boolean? = null,
)

@Serializable
public data class ProdiaVideoModelOptions(
    val resolution: String? = null,
)

public class ProdiaProvider(
    private val client: HttpClient,
    public val settings: ProdiaProviderSettings,
) : Provider {
    override val providerId: String = "prodia"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun image(modelId: ModelId): ImageModel = imageModel(modelId.value)
    public fun video(modelId: ModelId): VideoModel = videoModel(modelId.value)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun languageModel(modelId: String): LanguageModel = ProdiaLanguageModel(client, settings, modelId)
    override fun imageModel(modelId: String): ImageModel = ProdiaImageModel(client, settings, modelId)
    override fun videoModel(modelId: String): VideoModel = ProdiaVideoModel(client, settings, modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

/** PascalCase factory — mirrors the reference `OpenAI(...)` faux-constructor. */
public fun Prodia(
    client: HttpClient,
    settings: ProdiaProviderSettings = ProdiaProviderSettings(),
): ProdiaProvider = ProdiaProvider(client, settings)

private class ProdiaLanguageModel(
    private val client: HttpClient,
    private val settings: ProdiaProviderSettings,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = "prodia.language"

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val warnings = prodiaLanguageWarnings(params)
        val options = settings.prodiaOptions(params.providerOptions)
        val prompt = prodiaLanguagePrompt(params.messages)
        val image = prodiaLanguageImage(params.messages)
        val body = buildJsonObject {
            put("type", JsonPrimitive(modelId))
            put("config", buildJsonObject {
                put("prompt", JsonPrimitive(prompt))
                put("include_messages", JsonPrimitive(true))
                options["aspectRatio"]?.let { put("aspect_ratio", it) }
            })
        }
        val response = settings.prodiaPostMultipart(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/job?price=true",
            body = body,
            input = image,
            accept = "multipart/form-data",
            headers = settings.prodiaHeaders(params.headers),
        )
        val content = buildList {
            response.text?.takeIf { it.isNotEmpty() }?.let { add(ContentPart.Text(it)) }
            response.files.forEach { file ->
                add(ContentPart.File(file.mediaType, file.base64, file.filename, file.providerMetadata))
            }
        }
        return LanguageModelResult(
            text = response.text.orEmpty(),
            finishReason = FinishReason.Stop,
            usage = Usage(),
            warnings = warnings,
            content = content,
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("prodia" to response.jobMetadata()))),
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val result = generate(params)
        emit(StreamEvent.StreamStart(result.warnings))
        emit(StreamEvent.ResponseMetadata(modelId = result.response.modelId, headers = result.response.headers))
        if (result.text.isNotEmpty()) {
            emit(StreamEvent.TextStart("txt-0"))
            emit(StreamEvent.TextDelta("txt-0", result.text))
            emit(StreamEvent.TextEnd("txt-0"))
        }
        result.content.filterIsInstance<ContentPart.File>().forEachIndexed { index, file ->
            emit(StreamEvent.FilePart("file-$index", file.mediaType, file.base64, file.providerMetadata))
        }
        emit(StreamEvent.Finish(totalSteps = 1, finishReason = result.finishReason, usage = result.usage, providerMetadata = result.providerMetadata))
    }

    private fun prodiaLanguageWarnings(params: LanguageModelCallParams): List<CallWarning> = buildList {
        if (params.temperature != null) add(CallWarning("unsupported", "Prodia language models do not support temperature."))
        if (params.topP != null) add(CallWarning("unsupported", "Prodia language models do not support topP."))
        if (params.topK != null) add(CallWarning("unsupported", "Prodia language models do not support topK."))
        if (params.maxOutputTokens != null) add(CallWarning("unsupported", "Prodia language models do not support maxOutputTokens."))
        if (params.stopSequences.isNotEmpty()) add(CallWarning("unsupported", "Prodia language models do not support stopSequences."))
        if (params.presencePenalty != null) add(CallWarning("unsupported", "Prodia language models do not support presencePenalty."))
        if (params.frequencyPenalty != null) add(CallWarning("unsupported", "Prodia language models do not support frequencyPenalty."))
        if (params.tools.isNotEmpty()) add(CallWarning("unsupported", "Prodia language models do not support tools."))
        if (params.toolChoice != ToolChoice.Auto) add(CallWarning("unsupported", "Prodia language models do not support toolChoice."))
        if (params.responseFormat != ResponseFormat.Text) add(CallWarning("unsupported", "Prodia language models do not support responseFormat."))
    }

    private fun prodiaLanguagePrompt(messages: List<ModelMessage>): String {
        val system = messages.lastOrNull { it.role == MessageRole.System }?.content?.textContent().orEmpty()
        val user = messages.asReversed().firstOrNull { it.role == MessageRole.User }?.content
            ?.filterIsInstance<ContentPart.Text>()
            ?.joinToString("\n") { it.text }
            .orEmpty()
        return listOf(system, user).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun prodiaLanguageImage(messages: List<ModelMessage>): ProdiaInputFile? =
        messages.asReversed().firstOrNull { it.role == MessageRole.User }?.content?.firstNotNullOfOrNull { part ->
            when {
                part is ContentPart.Image -> ProdiaInputFile(part.mediaType, Base64Codec.decode(part.base64))
                part is ContentPart.File && part.mediaType.startsWith("image/") ->
                    ProdiaInputFile(part.mediaType, Base64Codec.decode(part.base64), part.filename)
                else -> null
            }
        }

    private fun List<ContentPart>.textContent(): String =
        filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
}

private class ProdiaImageModel(
    private val client: HttpClient,
    private val settings: ProdiaProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "prodia.image"
    override val maxImagesPerCall: Int = 1

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        val options = settings.prodiaOptions(params.providerOptions)
        val warnings = mutableListOf<CallWarning>()
        if (params.n != 1) warnings += CallWarning("unsupported", "Prodia image models support one image per call; n was ignored.")
        val (sizeWidth, sizeHeight) = prodiaParseSize(params.size, warnings)
        val body = buildJsonObject {
            put("type", JsonPrimitive(modelId))
            put("config", buildJsonObject {
                put("prompt", JsonPrimitive(params.prompt))
                (options["width"] ?: sizeWidth?.let(::JsonPrimitive))?.let { put("width", it) }
                (options["height"] ?: sizeHeight?.let(::JsonPrimitive))?.let { put("height", it) }
                params.seed?.let { put("seed", JsonPrimitive(it)) }
                options["steps"]?.let { put("steps", it) }
                options["stylePreset"]?.let { put("style_preset", it) }
                options["loras"]?.let { put("loras", it) }
                options["progressive"]?.let { put("progressive", it) }
            })
        }
        val response = settings.prodiaPostJsonForMultipart(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/job?price=true",
            body = body,
            accept = "multipart/form-data; image/png",
            headers = settings.prodiaHeaders(params.headers),
        )
        val image = response.files.firstOrNull { it.mediaType.startsWith("image/") }
            ?: throw NoImageGeneratedError("Prodia multipart response missing output image")
        return ImageModelResult(
            images = listOf(image),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                "prodia" to buildJsonObject {
                    put("images", JsonArray(listOf(response.jobMetadata())))
                },
            ))),
        )
    }

    private fun prodiaParseSize(size: String?, warnings: MutableList<CallWarning>): Pair<Int?, Int?> {
        if (size == null) return null to null
        val parts = size.split("x")
        val width = parts.getOrNull(0)?.toIntOrNull()
        val height = parts.getOrNull(1)?.toIntOrNull()
        return if (parts.size == 2 && width != null && height != null) {
            width to height
        } else {
            warnings += CallWarning("unsupported", "Invalid Prodia size format: $size. Expected WIDTHxHEIGHT.")
            null to null
        }
    }
}

private class ProdiaVideoModel(
    private val client: HttpClient,
    private val settings: ProdiaProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = "prodia.video"
    override val maxVideosPerCall: Int = 1

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        val options = settings.prodiaOptions(params.providerOptions)
        val body = buildJsonObject {
            put("type", JsonPrimitive(modelId))
            put("config", buildJsonObject {
                params.prompt.takeIf { it.isNotBlank() }?.let { put("prompt", JsonPrimitive(it)) }
                params.seed?.let { put("seed", JsonPrimitive(it)) }
                (options["resolution"] ?: params.resolution?.let(::JsonPrimitive))?.let { put("resolution", it) }
            })
        }
        val input = params.image?.let { ProdiaInputFile.fromGeneratedFile(client, it) }
        val response = if (input == null) {
            settings.prodiaPostJsonForMultipart(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/job?price=true",
                body = body,
                accept = "multipart/form-data; video/mp4",
                headers = settings.prodiaHeaders(params.headers),
            )
        } else {
            settings.prodiaPostMultipart(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/job?price=true",
                body = body,
                input = input,
                accept = "multipart/form-data; video/mp4",
                headers = settings.prodiaHeaders(params.headers),
            )
        }
        val video = response.files.firstOrNull { it.mediaType.startsWith("video/") }
            ?: throw NoVideoGeneratedError("Prodia multipart response missing output video")
        return VideoModelResult(
            videos = listOf(video),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                "prodia" to buildJsonObject {
                    put("videos", JsonArray(listOf(response.jobMetadata())))
                },
            ))),
        )
    }
}

internal class ProdiaInputFile(
    val mediaType: String,
    val bytes: ByteArray,
    val filename: String? = null,
) {
    companion object {
        internal suspend fun fromGeneratedFile(client: HttpClient, file: GeneratedFile): ProdiaInputFile {
            val bytes = file.url?.takeIf { it.isNotBlank() }
                ?.let { url ->
                    // Ktor isn't configured with expectSuccess, so check the status manually (every
                    // sibling download helper does) — otherwise a 404/500 error page is uploaded as
                    // the input image, surfacing as a confusing downstream Prodia rejection.
                    val response = client.request(url)
                    val body = response.bodyAsBytes()
                    if (response.status.value !in 200..299) {
                        throw ApiCallError(
                            url = url,
                            statusCode = response.status.value,
                            rawBody = body.decodeToString(),
                            headers = with(HttpTransport) { response.flattenedHeaders() },
                            message = "Prodia input file download failed with status ${response.status.value}",
                        )
                    }
                    body
                }
                ?: Base64Codec.decode(file.base64)
            return ProdiaInputFile(file.mediaType, bytes, file.filename)
        }
    }
}

private class ProdiaMultipartPart(
    val headers: Map<String, String>,
    val body: ByteArray,
)

internal data class ProdiaMultipartResult(
    val job: JsonObject,
    val text: String?,
    val files: List<GeneratedFile>,
    val headers: Map<String, String>,
) {
    internal fun jobMetadata(): JsonObject = buildJsonObject {
        (job["id"] as? JsonPrimitive)?.contentOrNull?.let { put("jobId", JsonPrimitive(it)) }
        ((job["config"] as? JsonObject)?.get("seed") as? JsonPrimitive)?.intOrNull?.let {
            put("seed", JsonPrimitive(it))
        }
        ((job["metrics"] as? JsonObject)?.get("elapsed") as? JsonPrimitive)?.doubleOrNull?.let {
            put("elapsed", JsonPrimitive(it))
        }
        ((job["metrics"] as? JsonObject)?.get("ips") as? JsonPrimitive)?.doubleOrNull?.let {
            put("iterationsPerSecond", JsonPrimitive(it))
        }
        (job["created_at"] as? JsonPrimitive)?.contentOrNull?.let { put("createdAt", JsonPrimitive(it)) }
        (job["updated_at"] as? JsonPrimitive)?.contentOrNull?.let { put("updatedAt", JsonPrimitive(it)) }
        val price = job["price"]
        if (price !is JsonNull) {
            ((price as? JsonObject)?.get("dollars") as? JsonPrimitive)?.doubleOrNull?.let {
                put("dollars", JsonPrimitive(it))
            }
        }
    }
}
