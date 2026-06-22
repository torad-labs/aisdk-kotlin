package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal data class BedrockHttpResponse(
    val value: JsonElement,
    val headers: Map<String, String>,
)

/**
 * Amazon Bedrock HTTP transport: SigV4/bearer header assembly, the JSON POST
 * path, the incremental `converse-stream` reader, and rich error-message
 * extraction (including clock-skew detection). Cross-cutting transport shared by
 * every Bedrock model class, so it has no single owning type.
 */
internal object BedrockHttp {
    suspend fun bedrockPostJson(
        client: HttpClient,
        url: String,
        body: JsonElement,
        settings: AmazonBedrockProviderSettings,
        extraHeaders: Map<String, String>,
        service: String = "bedrock",
        abortSignal: AbortSignal,
        parseJson: Boolean,
    ): BedrockHttpResponse {
        abortSignal.throwIfAborted()
        val encodedBody = aiSdkJson.encodeToString(JsonElement.serializer(), body)
        val headers = bedrockHeaders(settings, extraHeaders, url, encodedBody, service)
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(encodedBody)
        }
        val raw = response.bodyAsBytes().decodeToString()
        val responseHeaders = with(HttpTransport) { response.flattenedHeaders() }
        if (response.status.value !in 200..299) {
            val parsed = runCatching { aiSdkJson.parseToJsonElement(raw) }.getOrNull()
            throw ApiCallError(
                url = url,
                statusCode = response.status.value,
                rawBody = raw,
                headers = responseHeaders,
                message = bedrockErrorMessage(parsed, raw),
                requestBodyValues = body,
            )
        }
        return BedrockHttpResponse(
            value = if (parseJson && raw.isNotBlank()) aiSdkJson.parseToJsonElement(raw) else JsonObject(emptyMap()),
            headers = responseHeaders,
        )
    }

    /**
     * Streams Bedrock converse-stream payloads incrementally off the response
     * channel. For `application/vnd.amazon.eventstream` (the live wire format) it
     * reads one Smithy binary frame at a time via [BedrockEventStream]. For any
     * other content type it falls back to line framing. Non-2xx surfaces the rich
     * [APICallError] via [bedrockErrorMessage], matching [bedrockPostJson].
     */
    fun bedrockStreamPayloads(
        client: HttpClient,
        url: String,
        body: JsonElement,
        settings: AmazonBedrockProviderSettings,
        extraHeaders: Map<String, String>,
        abortSignal: AbortSignal,
        onResponse: suspend (Map<String, String>) -> Unit,
    ): Flow<String> = channelFlow {
        // channelFlow (not flow): execute{} and the channel reads may run in a
        // different coroutine than the collector on Kotlin/Native; `send` is safe
        // across that boundary where `emit` would throw "Flow invariant is violated".
        abortSignal.throwIfAborted()
        val encodedBody = aiSdkJson.encodeToString(JsonElement.serializer(), body)
        val headers = bedrockHeaders(settings, extraHeaders, url, encodedBody, "bedrock")
        val statement = client.prepareRequest(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(encodedBody)
        }
        statement.execute { response ->
            val flattened = with(HttpTransport) { response.flattenedHeaders() }
            if (response.status.value !in 200..299) {
                val raw = response.bodyAsBytes().decodeToString()
                val parsed = runCatching { aiSdkJson.parseToJsonElement(raw) }.getOrNull()
                throw ApiCallError(
                    url = url,
                    statusCode = response.status.value,
                    rawBody = raw,
                    headers = flattened,
                    message = bedrockErrorMessage(parsed, raw),
                    requestBodyValues = body,
                )
            }
            onResponse(flattened)
            val channel = response.bodyAsChannel()
            val contentType = headerValue(flattened, HttpHeaders.ContentType).orEmpty()
            if (contentType.contains("application/vnd.amazon.eventstream", ignoreCase = true)) {
                BedrockEventStream.sendFrames(this, channel)
            } else {
                while (true) {
                    val line = channel.readLine() ?: break
                    send(line)
                }
            }
        }
    }

    suspend fun bedrockHeaders(
        settings: AmazonBedrockProviderSettings,
        extra: Map<String, String>,
        url: String,
        body: String,
        service: String,
        amzDate: String = AwsSigV4.currentAwsAmzDate(),
    ): Map<String, String> {
        val headers = linkedMapOf<String, String?>()
        headers[HttpHeaders.ContentType] = ContentType.Application.Json.toString()
        settings.headers.forEach { (key, value) -> headers[key] = value }
        extra.forEach { (key, value) -> headers[key] = value }
        val headersWithUserAgent = ProviderHeaders.withUserAgentSuffix(headers, "ai-sdk/amazon-bedrock/$AMAZON_BEDROCK_VERSION")
        val apiKey = settings.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (apiKey != null) {
            return headersWithUserAgent + (HttpHeaders.Authorization to "Bearer $apiKey")
        }
        val credentials = if (settings.accessKeyId != null || settings.secretAccessKey != null || settings.credentialProvider != null) {
            val credentials = settings.credentialProvider?.invoke()
                ?: BedrockCredentials(settings.accessKeyId.orEmpty(), settings.secretAccessKey.orEmpty(), settings.sessionToken, settings.region)
            if (credentials.accessKeyId.isBlank() || credentials.secretAccessKey.isBlank()) {
                throw LoadAPIKeyError("AWS SigV4 authentication requires both accessKeyId and secretAccessKey.")
            }
            credentials
        } else {
            return headersWithUserAgent
        }
        val region = credentials.region ?: settings.region ?: "us-east-1"
        return AwsSigV4.awsSigV4SignedHeaders(method = "POST",
        url = url,
        service = service,
        region = region,
        headers = headersWithUserAgent,
        body = body,
        credentials = AwsSigV4Credentials(
            accessKeyId = credentials.accessKeyId,
            secretAccessKey = credentials.secretAccessKey,
            sessionToken = credentials.sessionToken,
        ),
        amzDate = amzDate,)
    }

    fun bedrockErrorMessage(parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject ?: return raw
        val message = (obj["message"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["error"]?.jsonObject?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj["type"] as? JsonPrimitive)?.contentOrNull
            ?: raw
        val code = (obj["__type"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["code"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["type"] as? JsonPrimitive)?.contentOrNull
        return if (isBedrockClockSkewError(code, message)) {
            "Amazon Bedrock request failed because the local clock appears to be skewed. Sync the host clock and retry. Provider message: $message"
        } else {
            message
        }
    }

    private fun isBedrockClockSkewError(code: String?, message: String): Boolean {
        val codeText = code.orEmpty()
        return codeText.contains("RequestTimeTooSkewed", ignoreCase = true) ||
            codeText.contains("RequestExpired", ignoreCase = true) ||
            message.contains("Signature expired", ignoreCase = true) ||
            message.contains("Request time too skewed", ignoreCase = true)
    }

    fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
