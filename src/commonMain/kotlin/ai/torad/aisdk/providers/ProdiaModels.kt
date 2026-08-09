@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

internal class ProdiaLanguageModel(
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
            put(
                "config",
                buildJsonObject {
                    put("prompt", JsonPrimitive(prompt))
                    put("include_messages", JsonPrimitive(true))
                    options["aspectRatio"]?.let { put("aspect_ratio", it) }
                }
            )
        }
        val response = settings.prodiaMultipart(
            settings.prodiaPostMultipart(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/job?price=true",
                body = body,
                input = image,
                accept = "multipart/form-data",
                headers = settings.prodiaHeaders(params.headers),
            )
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
        emit(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = result.finishReason,
                usage = result.usage,
                providerMetadata = result.providerMetadata
            )
        )
    }

    private fun prodiaLanguageWarnings(params: LanguageModelCallParams): List<CallWarning> = buildList {
        if (params.temperature != null) add(
            CallWarning("unsupported", "Prodia language models do not support temperature.")
        )
        if (params.topP != null) add(CallWarning("unsupported", "Prodia language models do not support topP."))
        if (params.topK != null) add(CallWarning("unsupported", "Prodia language models do not support topK."))
        if (params.maxOutputTokens != null) add(
            CallWarning("unsupported", "Prodia language models do not support maxOutputTokens.")
        )
        if (params.stopSequences.isNotEmpty()) add(
            CallWarning("unsupported", "Prodia language models do not support stopSequences.")
        )
        if (params.presencePenalty != null) add(
            CallWarning("unsupported", "Prodia language models do not support presencePenalty.")
        )
        if (params.frequencyPenalty != null) add(
            CallWarning("unsupported", "Prodia language models do not support frequencyPenalty.")
        )
        if (params.tools.isNotEmpty()) add(CallWarning("unsupported", "Prodia language models do not support tools."))
        if (params.toolChoice != ToolChoice.Auto) add(
            CallWarning("unsupported", "Prodia language models do not support toolChoice.")
        )
        if (params.responseFormat != ResponseFormat.Text) add(
            CallWarning("unsupported", "Prodia language models do not support responseFormat.")
        )
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

internal class ProdiaImageModel(
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
            put(
                "config",
                buildJsonObject {
                    put("prompt", JsonPrimitive(params.prompt))
                    (options["width"] ?: sizeWidth?.let(::JsonPrimitive))?.let { put("width", it) }
                    (options["height"] ?: sizeHeight?.let(::JsonPrimitive))?.let { put("height", it) }
                    params.seed?.let { put("seed", JsonPrimitive(it)) }
                    options["steps"]?.let { put("steps", it) }
                    options["stylePreset"]?.let { put("style_preset", it) }
                    options["loras"]?.let { put("loras", it) }
                    options["progressive"]?.let { put("progressive", it) }
                }
            )
        }
        val response = settings.prodiaMultipart(
            settings.prodiaRequest(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/job?price=true",
                headers = settings.prodiaHeaders(params.headers),
                body = body,
                accept = "multipart/form-data; image/png",
            )
        )
        val image = response.files.firstOrNull { it.mediaType.startsWith("image/") }
            ?: throw NoImageGeneratedError("Prodia multipart response missing output image")
        return ImageModelResult(
            images = listOf(image),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
            providerMetadata = ProviderMetadata.Raw(
                JsonObject(
                    mapOf(
                        "prodia" to buildJsonObject {
                            put("images", JsonArray(listOf(response.jobMetadata())))
                        },
                    )
                )
            ),
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

internal class ProdiaVideoModel(
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
            put(
                "config",
                buildJsonObject {
                    params.prompt.takeIf { it.isNotBlank() }?.let { put("prompt", JsonPrimitive(it)) }
                    params.seed?.let { put("seed", JsonPrimitive(it)) }
                    (options["resolution"] ?: params.resolution?.let(::JsonPrimitive))?.let { put("resolution", it) }
                }
            )
        }
        val input = params.image?.let { FromGeneratedFile(client, it) }
        val headers = settings.prodiaHeaders(params.headers)
        // Docs offer /job/async for long-running video jobs; sync /job can time out.
        val useAsync = (options["async"] as? JsonPrimitive)?.contentOrNull?.lowercase() != "false"
        if (useAsync) return generateAsync(body, input, headers)
        val response = settings.prodiaMultipart(
            submitJob(
                url = "${settings.baseURL.trimEnd('/')}/job?price=true",
                body = body,
                input = input,
                accept = "multipart/form-data; video/mp4",
                headers = headers,
            )
        )
        val video = response.files.firstOrNull { it.mediaType.startsWith("video/") }
            ?: throw NoVideoGeneratedError("Prodia multipart response missing output video")
        return videoResult(video, response.headers, response.job)
    }

    /**
     * Async jobs answer the POST with `201 Created` and the job JSON while they are still
     * processing; the caller polls `job.state.current` and then downloads the output file.
     */
    private suspend fun generateAsync(
        body: JsonObject,
        input: ProdiaInputFile?,
        headers: Map<String, String>,
    ): VideoModelResult {
        val asyncBase = "${settings.baseURL.trimEnd('/')}/job/async"
        val submitted = submitJob(
            url = "$asyncBase?price=true",
            body = body,
            input = input,
            accept = "application/json",
            headers = headers,
        ).json() as? JsonObject ?: JsonObject(emptyMap())
        val jobId = (submitted["id"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(submitted, "Prodia async job response is missing id")
        val jobUrl = "$asyncBase/$jobId"
        val state = awaitJobState(
            jobUrl = jobUrl,
            headers = headers,
            initialState = (JsonAccess.obj(submitted, "state")?.get("current") as? JsonPrimitive)?.contentOrNull,
        )
        if (state != "processed") throw NoVideoGeneratedError("Prodia async job $jobId ended in state: $state")
        val job = settings.prodiaRequest(client, "$jobUrl/job.json", headers).json() as? JsonObject
            ?: JsonObject(emptyMap())
        val output = downloadOutput(jobUrl, headers)
        val video = GeneratedFile(
            mediaType = output.contentType.substringBefore(';').trim().takeIf { it.startsWith("video/") }
                ?: "video/mp4",
            base64 = Base64Codec.encode(output.bytes),
        )
        return videoResult(video, output.headers, job)
    }

    private suspend fun submitJob(
        url: String,
        body: JsonObject,
        input: ProdiaInputFile?,
        accept: String,
        headers: Map<String, String>,
    ): ProdiaRawResponse =
        if (input == null) {
            settings.prodiaRequest(client = client, url = url, headers = headers, body = body, accept = accept)
        } else {
            settings.prodiaPostMultipart(
                client = client,
                url = url,
                body = body,
                input = input,
                accept = accept,
                headers = headers,
            )
        }

    private suspend fun downloadOutput(jobUrl: String, headers: Map<String, String>): ProdiaRawResponse {
        val filename = (settings.prodiaRequest(client, "$jobUrl/output", headers).json() as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.firstOrNull { isProdiaVideoOutputName(it) }
            ?: throw NoVideoGeneratedError("Prodia async job produced no video output")
        return settings.prodiaRequest(client, "$jobUrl/output/$filename", headers)
    }

    /**
     * The output listing is server-controlled and its entries are interpolated into a request path
     * that carries the caller's credentials, so a bare extension check is not enough: `..` or a
     * separator would walk the request off the job namespace and send the auth headers to whatever
     * the normalized path resolves to. Require a plain filename that also looks like a video.
     */
    private fun isProdiaVideoOutputName(candidate: String): Boolean {
        val isPlainFilename = '/' !in candidate && '\\' !in candidate && candidate != "." && candidate != ".."
        return isPlainFilename && MediaTypes.detect(filename = candidate)?.startsWith("video/") == true
    }

    private suspend fun awaitJobState(jobUrl: String, headers: Map<String, String>, initialState: String?): String {
        var state = initialState.orEmpty()
        repeat(PRODIA_ASYNC_MAX_POLL_ATTEMPTS) {
            if (state == "processed" || state == "failed") return state
            delay(PRODIA_ASYNC_POLL_INTERVAL_MILLIS)
            state = settings.prodiaRequest(client, "$jobUrl/job.state.current", headers).bytes.decodeToString().trim()
        }
        throw NoVideoGeneratedError("Prodia async job polling timed out while the job was still $state")
    }

    private fun videoResult(video: GeneratedFile, headers: Map<String, String>, job: JsonObject): VideoModelResult =
        VideoModelResult(
            videos = listOf(video),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = headers),
            providerMetadata = ProviderMetadata.Raw(
                JsonObject(
                    mapOf(
                        "prodia" to buildJsonObject {
                            put("videos", JsonArray(listOf(ProdiaJob.metadata(job))))
                        },
                    )
                )
            ),
        )
}

// The plain-text job.state.current poll is cheap; the docs recommend a 1-2s cadence and
// async results expire an hour after completion, so cap the wait well below that.
private const val PRODIA_ASYNC_POLL_INTERVAL_MILLIS: Long = 2_000L
private const val PRODIA_ASYNC_MAX_POLL_ATTEMPTS: Int = 450
