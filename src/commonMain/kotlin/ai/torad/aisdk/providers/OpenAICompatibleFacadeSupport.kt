package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class ProviderCapabilities(
    val includeUsage: Boolean = false,
    val supportsStructuredOutputs: Boolean = false,
)

// Shared support for the OpenAI-compatible provider facades. Holds the settings
// builder + usage helpers (FacadeSupport) and the raw HTTP transport used by the
// image/reranking models (FacadeHttp). Extracted verbatim from
// OpenAICompatibleProviderFacades.kt; loose helpers become object members so the
// repo policy against top-level camelCase functions is satisfied.
internal object FacadeSupport {
    @Suppress("LongParameterList")
    fun compatibleSettings(
        name: String,
        version: String,
        baseURL: String,
        apiKey: String?,
        headers: Map<String, String>,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
        transformChatRequestBody: ((JsonObject) -> JsonObject)? = null,
        convertUsage: ((JsonElement?) -> Usage)? = null,
        transformChatResponse: ((JsonObject) -> JsonObject)? = null,
    ): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings(
            name = name,
            baseUrl = baseURL.trimEnd('/'),
            apiKey = apiKey,
            headers = ProviderHeaders.withUserAgentSuffix(headers, "ai-sdk/$name/$version"),
            includeUsage = capabilities.includeUsage,
            supportsStructuredOutputs = capabilities.supportsStructuredOutputs,
            transformChatRequestBody = transformChatRequestBody,
            convertUsage = convertUsage,
            transformChatResponse = transformChatResponse,
        )

    fun usageFromParts(
        promptTokens: Int,
        completionTokens: Int,
        cacheRead: Int,
        reasoningTokens: Int,
        raw: JsonElement?,
    ): Usage = Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = promptTokens,
            noCache = promptTokens - cacheRead,
            cacheRead = cacheRead,
        ),
        outputTokens = Usage.OutputTokenBreakdown(
            total = completionTokens,
            text = completionTokens - reasoningTokens,
            reasoning = reasoningTokens,
        ),
        raw = raw,
    )

    fun JsonObject.intField(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull ?: 0

    fun JsonObject.nestedIntField(objectName: String, fieldName: String): Int =
        (this[objectName] as? JsonObject)?.intField(fieldName) ?: 0

    fun textFromContentParts(content: JsonArray): String =
        content.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            obj.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
        }.joinToString("")
}

// Raw JSON/binary HTTP transport for the facade image & reranking models.
internal object FacadeHttp {
    class ProviderFacadeBinaryResponse(
        val bytes: ByteArray,
        val headers: Map<String, String>,
    ) {
        fun toGeneratedFile(modelId: String): GeneratedFile =
            GeneratedFile(
                mediaType = headers.headerValue(HttpHeaders.ContentType) ?: "image/png",
                base64 = Base64Codec.encode(bytes),
                filename = "$modelId.png",
            )
    }

    suspend fun postFacadeJson(
        client: HttpClient,
        url: String,
        body: JsonElement,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Post,
            headers = headers,
            body = body,
            requestBodyValues = body,
            errorMessage = ::providerFacadeErrorMessage,
        )

    suspend fun postFacadeBinary(
        client: HttpClient,
        url: String,
        body: JsonElement,
        headers: Map<String, String>,
    ): ProviderFacadeBinaryResponse {
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(aiSdkJson.encodeToString(JsonElement.serializer(), body))
        }
        return response.parseFacadeBinary(url)
    }

    suspend fun getFacadeBinary(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
    ): ProviderFacadeBinaryResponse {
        val response = client.request(url) {
            method = HttpMethod.Get
            headers.forEach { (name, value) -> header(name, value) }
        }
        return response.parseFacadeBinary(url)
    }

    suspend fun HttpResponse.parseFacadeBinary(url: String): ProviderFacadeBinaryResponse {
        val bytes = bodyAsBytes()
        val flattened = with(HttpTransport) { flattenedHeaders() }
        if (status.value !in 200..299) {
            val raw = bytes.decodeToString()
            val parsed = runCatching { aiSdkJson.parseToJsonElement(raw) }.getOrNull()
            throw ApiCallError(
                url = url,
                statusCode = status.value,
                rawBody = raw,
                headers = flattened,
                message = providerFacadeErrorMessage(status.value, parsed, raw),
            )
        }
        return ProviderFacadeBinaryResponse(bytes = bytes, headers = flattened)
    }

    fun providerFacadeHeaders(
        apiKey: String?,
        headers: Map<String, String>,
        callHeaders: Map<String, String> = emptyMap(),
        userAgent: String,
    ): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, userAgent)
    }

    fun providerSpecificOptions(options: Map<String, JsonElement>, provider: String): JsonObject =
        options[provider] as? JsonObject ?: JsonObject(emptyMap())

    fun JsonObjectBuilder.putProviderSpecificOptions(options: Map<String, JsonElement>, provider: String) {
        for ((key, value) in providerSpecificOptions(options, provider)) {
            put(key, value)
        }
    }

    fun String.stripDataUriPrefix(): String =
        replace(Regex("^data:[^;]+;base64,"), "")

    fun providerFacadeErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
            ?: return "Provider request failed ($statusCode): ${raw.ifBlank { "request failed" }}"
        val error = obj["error"]
        val detail = obj["detail"]
        val message = when {
            error is JsonPrimitive -> error.contentOrNull ?: raw
            error is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            detail is JsonObject -> detail["error"]?.jsonPrimitive?.contentOrNull ?: detail.toString()
            else -> raw.ifBlank { "request failed" }
        }
        return "Provider request failed ($statusCode): $message"
    }

    fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
