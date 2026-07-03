@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class CohereChatLanguageModel(
    private val client: HttpClient,
    private val settings: CohereProviderSettings,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = "cohere.chat"
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val request = CohereWireFormat.cohereChatRequest(settings, modelId, params)
        val response = settings.coherePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/chat",
            body = request.body,
            headers = settings.cohereHeaders(params.headers),
        )
        return CohereWireFormat.cohereChatResult(
            response.value.jsonObject,
            request.body,
            response.headers,
            response.value,
            request.warnings
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val request = CohereWireFormat.cohereChatRequest(settings, modelId, params, stream = true)
        val state = CohereChatStreamState(
            { CohereWireFormat.cohereToolInput(it) },
            { CohereWireFormat.cohereUsage(it) },
            { CohereWireFormat.cohereFinishReason(it) },
        )
        var sseHeaders: Map<String, String> = emptyMap()
        val rawLines = HttpTransport.streamSse(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/chat",
            method = HttpMethod.Post,
            headers = settings.cohereHeaders(params.headers) + (HttpHeaders.Accept to "text/event-stream"),
            body = request.body,
            requestBodyValues = request.body,
            errorMessage = settings::cohereErrorMessage,
            onResponse = { sseHeaders = it },
        )
        val parsedEvents = EventStreamParser.parse(
            rawLines,
            Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())),
            aiSdkJson
        )
        var streamStartEmitted = false
        var responseMetadataEmitted = false
        suspend fun emitStartAndMetadata() {
            if (!streamStartEmitted) {
                emit(StreamEvent.StreamStart(request.warnings))
                streamStartEmitted = true
            }
            if (!responseMetadataEmitted) {
                emit(StreamEvent.ResponseMetadata(headers = sseHeaders))
                responseMetadataEmitted = true
            }
        }

        parsedEvents.collect { event ->
            emitStartAndMetadata()
            when (event) {
                is ParseResult.Success -> state.accept(event.value.jsonObject).forEach { emit(it) }
                is ParseResult.Failure -> {
                    state.markError()
                    emit(StreamEvent.Error("Failed to parse Cohere stream event: ${event.error.message}", event.error))
                }
            }
        }
        emitStartAndMetadata()
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val request = CohereWireFormat.cohereChatRequest(settings, modelId, params, stream = true)
        return LanguageModelStreamResult(stream = stream(params), request = LanguageModelRequestMetadata(request.body))
    }
}
