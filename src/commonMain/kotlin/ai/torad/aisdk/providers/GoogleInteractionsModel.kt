@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.GoogleHttp.googlePostJson
import ai.torad.aisdk.providers.GoogleHttp.googleStreamSse
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsFinishReason
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsMetadata
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsRequestBody
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsResult
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsTerminal
import ai.torad.aisdk.providers.GoogleInteractions.googleInteractionsUsage
import ai.torad.aisdk.providers.GoogleInteractions.googlePollInteraction
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class GoogleInteractionsLanguageModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    private val modelInput: GoogleInteractionsModelInput,
) : LanguageModel {
    override val modelId: String = modelInput.name
    override val provider: String = "${settings.name}.interactions"
    override val supportedUrls: Map<String, List<String>> = mapOf(
        "image/*" to listOf("^https?://.+"),
        "application/pdf" to listOf("^https?://.+"),
        "audio/*" to listOf("^https?://.+"),
        "video/*" to listOf(
            "^https?://(www\\.)?youtube\\.com/watch\\?v=.+",
            "^https?://youtu\\.be/.+",
            "^gs://.+",
        ),
    )

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = googleInteractionsRequestBody(modelInput, params, stream = false)
        val response = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/interactions",
            body = prepared.body,
            headers = settings.googleInteractionsHeaders(params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        var body = response.value.jsonObject
        if (modelInput !is GoogleInteractionsModelInput.Model && !googleInteractionsTerminal((body["status"] as? JsonPrimitive)?.contentOrNull)) {
            body = googlePollInteraction(
                client = client,
                settings = settings,
                interactionId = (body["id"] as? JsonPrimitive)?.contentOrNull
                    ?: throw InvalidResponseDataError(body, "google.interactions: background response did not include an interaction id."),
                headers = settings.googleInteractionsHeaders(params.headers),
                abortSignal = params.abortSignal,
                timeoutMillis = prepared.pollingTimeoutMillis,
            ).value.jsonObject
        }
        return googleInteractionsResult(body, prepared.body, response.headers, response.value, prepared.warnings, settings)
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = googleInteractionsRequestBody(modelInput, params, stream = true)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = GoogleInteractionsStreamState(settings.generateId)
        if (prepared.isBackground) {
            val post = googlePostJson(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/interactions",
                body = prepared.body,
                headers = settings.googleInteractionsHeaders(params.headers),
                abortSignal = params.abortSignal,
                parseJson = true,
            )
            val postBody = post.value.jsonObject
            if (googleInteractionsTerminal((postBody["status"] as? JsonPrimitive)?.contentOrNull)) {
                state.synthesize(postBody).forEach { emit(it) }
            } else {
                val interactionId = (postBody["id"] as? JsonPrimitive)?.contentOrNull
                    ?: throw InvalidResponseDataError(postBody, "google.interactions: background response did not include an interaction id.")
                val rawLines = googleStreamSseGet(
                    client = client,
                    url = "${settings.baseURL.trimEnd('/')}/interactions/$interactionId?stream=true",
                    headers = settings.googleInteractionsHeaders(params.headers) + (HttpHeaders.Accept to "text/event-stream"),
                    abortSignal = params.abortSignal,
                )
                with(GoogleInteractions) { collectGoogleInteractions(rawLines, state) }
            }
        } else {
            val rawLines = googleStreamSse(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/interactions",
                body = prepared.body,
                headers = settings.googleInteractionsHeaders(params.headers) + (HttpHeaders.Accept to "text/event-stream"),
                abortSignal = params.abortSignal,
            )
            with(GoogleInteractions) { collectGoogleInteractions(rawLines, state) }
        }
        state.finishIfNeeded().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = googleInteractionsRequestBody(modelInput, params, stream = true)
        return LanguageModelStreamResult(stream = stream(params), request = LanguageModelRequestMetadata(prepared.body))
    }

    /** Streaming counterpart of the background-interaction GET poll: reads the SSE body incrementally. */
    private fun googleStreamSseGet(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
    ): Flow<String> = flow {
        abortSignal.throwIfAborted()
        emitAll(
            HttpTransport.streamSse(
                client = client,
                url = url,
                method = HttpMethod.Get,
                headers = headers,
                body = null,
                json = aiSdkJson,
                errorMessage = GoogleHttp.googleErrorExtractor,
            ),
        )
    }
}

internal data class GoogleInteractionsPreparedRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
    val pollingTimeoutMillis: Long?,
    val isBackground: Boolean,
)

internal data class GoogleInteractionsConvertedInput(
    val input: JsonArray,
    val systemInstruction: String?,
    val warnings: List<CallWarning>,
)

internal data class GoogleInteractionsParsedContent(
    val content: List<ContentPart>,
    val hasFunctionCall: Boolean,
)

