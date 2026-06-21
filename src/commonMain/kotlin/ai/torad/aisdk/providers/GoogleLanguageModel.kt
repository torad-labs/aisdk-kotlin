package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.GoogleHttp.googleHeaders
import ai.torad.aisdk.providers.GoogleHttp.googlePostJson
import ai.torad.aisdk.providers.GoogleHttp.googleStreamSse
import ai.torad.aisdk.providers.GoogleWire.googleGenerateContentBody
import ai.torad.aisdk.providers.GoogleWire.googleLanguageResult
import ai.torad.aisdk.providers.GoogleWire.googlePartMetadata
import ai.torad.aisdk.providers.GoogleWire.googleSources
import ai.torad.aisdk.providers.GoogleWire.googleUsage
import ai.torad.aisdk.providers.GoogleWire.mapGoogleFinishReason
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
import kotlinx.serialization.json.jsonPrimitive

internal class GoogleGenerativeAILanguageModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = settings.name
    override val supportedUrls: Map<String, List<String>> = mapOf(
        "*" to listOf(
            "^${settings.baseURL.trimEnd('/')}/files/.*$",
            "^https://(?:www\\.)?youtube\\.com/watch\\?v=[\\w-]+(?:&[\\w=&.-]*)?$",
            "^https://youtu\\.be/[\\w-]+(?:\\?[\\w=&.-]*)?$",
        ),
    )

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = googleGenerateContentBody(modelId, settings, params, stream = false)
        val response = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:generateContent",
            body = prepared.body,
            headers = googleHeaders(settings, params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        return googleLanguageResult(response.value.jsonObject, prepared.body, response.headers, response.value, prepared.warnings, settings)
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = googleGenerateContentBody(modelId, settings, params, stream = true)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = GoogleStreamState(settings.generateId)
        val rawLines = googleStreamSse(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:streamGenerateContent?alt=sse",
            body = prepared.body,
            headers = googleHeaders(settings, params.headers) + (HttpHeaders.Accept to "text/event-stream"),
            abortSignal = params.abortSignal,
        )
        EventStreamParser.parse(rawLines, Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())), aiSdkJson).collect { event ->
            when (event) {
                is ParseResult.Success -> state.accept(event.value.jsonObject).forEach { emit(it) }
                is ParseResult.Failure -> emit(StreamEvent.Error("Failed to parse Google stream event: ${event.error.message}"))
            }
        }
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = googleGenerateContentBody(modelId, settings, params, stream = true)
        return LanguageModelStreamResult(stream = stream(params), request = LanguageModelRequestMetadata(prepared.body))
    }
}

private class GoogleStreamState(
    private val generateId: () -> String,
) {
    private var finishReason = FinishReason.Other
    private var rawFinishReason: String? = null
    private var usage = Usage()
    private var textId: String? = null
    private var reasoningId: String? = null
    private var blockCounter = 0
    private var hasToolCalls = false

    fun accept(value: JsonObject): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        value["usageMetadata"]?.let { usage = googleUsage(it) }
        val candidate = value["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return events
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        for ((index, part) in parts.withIndex()) {
            val obj = try {
                WireDecoder.objectValue(part, "google", "generateContent stream part", "$.candidates[0].content.parts[$index]")
            } catch (error: WireDecodeException) {
                return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
            }
            val text = try {
                WireDecoder.optionalString(obj, "text", "google", "generateContent stream part", "$.candidates[0].content.parts[$index]")
            } catch (error: WireDecodeException) {
                return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
            }
            text?.let {
                if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                    if (textId != null) {
                        events += StreamEvent.TextEnd(textId.orEmpty())
                        textId = null
                    }
                    if (reasoningId == null) {
                        reasoningId = (blockCounter++).toString()
                        events += StreamEvent.ReasoningStart(reasoningId.orEmpty(), googlePartMetadata(obj)?.let { pm -> ProviderMetadata.Raw(JsonObject(pm)) } ?: ProviderMetadata.None)
                    }
                    events += StreamEvent.ReasoningDelta(reasoningId.orEmpty(), it, googlePartMetadata(obj)?.let { pm -> ProviderMetadata.Raw(JsonObject(pm)) } ?: ProviderMetadata.None)
                } else {
                    if (reasoningId != null) {
                        events += StreamEvent.ReasoningEnd(reasoningId.orEmpty())
                        reasoningId = null
                    }
                    if (textId == null) {
                        textId = (blockCounter++).toString()
                        events += StreamEvent.TextStart(textId.orEmpty(), googlePartMetadata(obj)?.let { pm -> ProviderMetadata.Raw(JsonObject(pm)) } ?: ProviderMetadata.None)
                    }
                    events += StreamEvent.TextDelta(textId.orEmpty(), it, googlePartMetadata(obj)?.let { pm -> ProviderMetadata.Raw(JsonObject(pm)) } ?: ProviderMetadata.None)
                }
            }
            obj["functionCall"]?.let { callElement ->
                val call = try {
                    WireDecoder.objectValue(callElement, "google", "generateContent stream part", "$.candidates[0].content.parts[$index].functionCall")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                val id = call["id"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate()
                val name = try {
                    WireDecoder.requiredString(call, "name", "google", "generateContent stream part", "$.candidates[0].content.parts[$index].functionCall")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                val input = call["args"] ?: JsonObject(emptyMap())
                hasToolCalls = true
                val partMetadata = googlePartMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None
                events += StreamEvent.ToolInputStart(id, name, partMetadata)
                events += StreamEvent.ToolInputDelta(id, input.toString(), partMetadata)
                events += StreamEvent.ToolInputEnd(id, partMetadata)
                events += StreamEvent.ToolCall(id, name, input, partMetadata)
            }
            obj["inlineData"]?.let { dataElement ->
                val data = try {
                    WireDecoder.objectValue(dataElement, "google", "generateContent stream part", "$.candidates[0].content.parts[$index].inlineData")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                events += StreamEvent.FilePart(
                    id = IdGenerator.generate(),
                    mediaType = data["mimeType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream",
                    base64 = try {
                        WireDecoder.requiredString(data, "data", "google", "generateContent stream part", "$.candidates[0].content.parts[$index].inlineData")
                    } catch (error: WireDecodeException) {
                        return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                    },
                    providerMetadata = googlePartMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None,
                )
            }
        }
        googleSources(candidate, generateId).forEach { source ->
            events += StreamEvent.SourcePart(
                id = source.providerMetadata.toMap()["google"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate(),
                sourceType = source.sourceType,
                url = source.url,
                title = source.title,
                providerMetadata = source.providerMetadata,
            )
        }
        candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let {
            rawFinishReason = it
            finishReason = mapGoogleFinishReason(it, hasToolCalls)
        }
        return events
    }

    fun finish(): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        textId?.let { events += StreamEvent.TextEnd(it) }
        reasoningId?.let { events += StreamEvent.ReasoningEnd(it) }
        events += StreamEvent.Finish(1, finishReason, usage, rawFinishReason = rawFinishReason)
        return events
    }
}
