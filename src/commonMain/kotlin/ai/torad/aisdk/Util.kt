package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.math.sqrt
import kotlin.random.Random

internal fun <T> asArray(value: T): List<T> = listOf(value)
public fun <T> asArray(value: Iterable<T>): List<T> = value.toList()

internal fun <T> splitArray(values: List<T>, chunkSize: Int): List<List<T>> {
    require(chunkSize > 0) { "chunkSize must be > 0" }
    return values.chunked(chunkSize)
}

public fun cosineSimilarity(left: List<Float>, right: List<Float>): Float {
    require(left.size == right.size) { "Embedding vectors must have the same dimension" }
    require(left.isNotEmpty()) { "Embedding vectors must not be empty" }
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    for (index in left.indices) {
        val a = left[index].toDouble()
        val b = right[index].toDouble()
        dot += a * b
        leftNorm += a * a
        rightNorm += b * b
    }
    val denom = sqrt(leftNorm) * sqrt(rightNorm)
    return if (denom == 0.0) 0f else (dot / denom).toFloat()
}

internal fun embeddingFloat(value: JsonElement, provider: String): Float =
    WireDecoder.embeddingFloat(value, provider)

internal fun isDeepEqualData(left: JsonElement?, right: JsonElement?): Boolean = when {
    left === right -> true
    left == null || right == null -> false
    left is JsonNull && right is JsonNull -> true
    left is JsonPrimitive && right is JsonPrimitive -> primitiveEquals(left, right)
    left is JsonArray && right is JsonArray ->
        left.size == right.size && left.indices.all { isDeepEqualData(left[it], right[it]) }
    left is JsonObject && right is JsonObject ->
        left.keys == right.keys && left.keys.all { key -> isDeepEqualData(left[key], right[key]) }
    else -> false
}

internal fun mergeJsonObjects(vararg objects: JsonObject): JsonObject {
    val merged = linkedMapOf<String, JsonElement>()
    for (obj in objects) {
        for ((key, value) in obj) {
            val prior = merged[key]
            merged[key] = if (prior is JsonObject && value is JsonObject) {
                mergeJsonObjects(prior, value)
            } else {
                value
            }
        }
    }
    return JsonObject(merged)
}

public data class DataUrl(
    val mediaType: String,
    val base64: String,
)

public fun splitDataUrl(dataUrl: String): DataUrl {
    require(dataUrl.startsWith("data:")) { "Not a data URL" }
    val comma = dataUrl.indexOf(',')
    require(comma >= 0) { "Data URL is missing comma separator" }
    val metadata = dataUrl.substring(5, comma)
    val base64 = dataUrl.substring(comma + 1)
    require(metadata.endsWith(";base64")) { "Only base64 data URLs are supported" }
    return DataUrl(
        mediaType = metadata.removeSuffix(";base64").ifEmpty { "text/plain" },
        base64 = base64,
    )
}

public fun detectMediaType(filename: String? = null, dataUrl: String? = null, explicit: String? = null): String? {
    explicit?.let { return it }
    dataUrl?.takeIf { it.startsWith("data:") }?.let { return splitDataUrl(it).mediaType }
    val ext = filename?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
    return when (ext) {
        "txt" -> "text/plain"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        else -> null
    }
}

internal fun prepareHeaders(
    defaultHeaders: Map<String, String> = emptyMap(),
    headers: Map<String, String>? = null,
): Map<String, String> = defaultHeaders + (headers ?: emptyMap())

public data class RetryPolicy(
    val maxRetries: Int = 2,
    val baseDelayMs: Long = 100L,
    val maxDelayMs: Long = 2_000L,
)

/**
 * Retry [block] with exponential backoff, honoring server `Retry-After` headers,
 * and surfacing the full attempt history as a [RetryError] on exhaustion — the
 * contract upstream's `retryWithExponentialBackoff` exposes.
 *
 * Terminal outcomes (mirroring upstream's four throw paths):
 * - retries disabled (`maxRetries == 0`) → the bare original error, unwrapped;
 * - a non-retryable error on the *first* attempt → the bare error, unwrapped;
 * - a non-retryable error on a *later* attempt → [RetryError] (`ErrorNotRetryable`)
 *   carrying every collected error;
 * - retries exhausted → [RetryError] (`MaxRetriesExceeded`) carrying every error.
 *
 * `CancellationException` is always rethrown first; the [delay] between attempts
 * is coroutine-cancellable, so a cancelled caller stops waiting immediately
 * (the structured-concurrency equivalent of upstream's `abortSignal`).
 */
public suspend fun <T> retryWithExponentialBackoff(
    policy: RetryPolicy = RetryPolicy(),
    shouldRetry: (Throwable) -> Boolean = { true },
    block: suspend (attempt: Int) -> T,
): T {
    val errors = mutableListOf<Throwable>()
    var nextDelay = policy.baseDelayMs
    while (true) {
        // The caught failure is not swallowed: a retryable error is retained in
        // `errors` (and resurfaced via RetryError on exhaustion); a terminal error
        // is rethrown by classifyRetryFailure. The retry-and-continue path here is
        // the only one that returns rather than throws.
        @Suppress("SwallowedException")
        val waitMs = try {
            return block(errors.size)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            classifyRetryFailure(t, policy, shouldRetry, errors, nextDelay)
        }
        if (waitMs > 0) delay(waitMs)
        nextDelay = (nextDelay * 2).coerceAtMost(policy.maxDelayMs)
    }
}

/**
 * Decide what to do with a failed attempt: either return the delay before the
 * next retry, or throw a terminal error. Mirrors upstream's four throw paths
 * (see [retryWithExponentialBackoff] KDoc). Mutates [errors] by appending [t].
 */
@Suppress("ThrowsCount") // The terminal throws ARE the retry contract; collapsing them would obscure it.
private fun classifyRetryFailure(
    t: Throwable,
    policy: RetryPolicy,
    shouldRetry: (Throwable) -> Boolean,
    errors: MutableList<Throwable>,
    exponentialBackoffDelay: Long,
): Long {
    if (policy.maxRetries == 0) throw t // retries disabled: never wrap
    errors += t
    val tryNumber = errors.size // 1 = first failure
    if (tryNumber > policy.maxRetries) {
        throw RetryError(
            "Failed after $tryNumber attempts. Last error: ${t.message}",
            RetryErrorReason.MaxRetriesExceeded,
            errors.toList(),
        )
    }
    if (!shouldRetry(t)) {
        if (tryNumber == 1) throw t // first-try non-retryable: unwrapped
        throw RetryError(
            "Failed after $tryNumber attempts with non-retryable error: '${t.message}'",
            RetryErrorReason.ErrorNotRetryable,
            errors.toList(),
        )
    }
    return retryDelayMs(t, exponentialBackoffDelay)
}

/** Milliseconds per second, used for Retry-After delta-seconds → ms conversion. */
private const val MILLIS_PER_SECOND: Long = 1_000L

/**
 * Upper bound (60 s) on a server-supplied `Retry-After` delay we'll honor as-is —
 * matching upstream's `getRetryDelayInMs`. A larger value is only honored if it's
 * still below the current exponential delay; otherwise we fall back to backoff.
 * (The previous code clamped this to `maxDelayMs`=2 s, so a `Retry-After: 30`
 * collapsed to 2 s and immediately re-tripped the rate limit — a retry storm.)
 */
private const val MAX_RETRY_AFTER_MS: Long = 60L * 1000L

/**
 * The delay before the next attempt: the server's `Retry-After` when present and
 * reasonable (0 ≤ ms < 60 s, or below the exponential delay), else the
 * [exponentialBackoffDelay]. Mirrors upstream `getRetryDelayInMs`.
 */
private fun retryDelayMs(t: Throwable, exponentialBackoffDelay: Long): Long {
    val serverMs = retryAfterDelayMs(t) ?: return exponentialBackoffDelay
    return if (serverMs in 0 until MAX_RETRY_AFTER_MS || serverMs < exponentialBackoffDelay) {
        serverMs
    } else {
        exponentialBackoffDelay
    }
}

/**
 * Extracts the server-requested retry delay from [APICallError.responseHeaders].
 *
 * Supports:
 * - `retry-after-ms`: milliseconds integer (non-standard but widely used)
 * - `retry-after`: delta-seconds integer per RFC 7231 §7.1.3
 *
 * HTTP-date form of `Retry-After` is not supported (kotlinx-datetime is not
 * a declared dependency); it falls back to null so jittered backoff applies.
 *
 * Returns null when the error is not an [APICallError] or no usable header is
 * present.
 */
private fun retryAfterDelayMs(t: Throwable): Long? {
    val headers = (t as? APICallError)?.responseHeaders ?: return null
    // Canonical header names after normalization in flattenedHeaders().
    val delayMs = headers["retry-after-ms"]?.toLongOrNull()?.takeIf { it > 0 }
        ?: headers["retry-after"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            ?.let { it * MILLIS_PER_SECOND }
    // HTTP-date form of retry-after is not parseable without kotlinx-datetime; falls through to null.
    return delayMs
}

internal class SerialJobExecutor {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

public data class IdGenerator(
    val prefix: String? = null,
    val size: Int = 16,
    val alphabet: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
    val separator: String = "-",
    val random: Random = Random.Default,
) {
    public fun generate(): String {
        require(size > 0) { "size must be > 0" }
        require(alphabet.isNotEmpty()) { "alphabet must not be empty" }
        require(separator !in alphabet) {
            "The separator \"$separator\" must not be part of the alphabet \"$alphabet\"."
        }
        val suffix = buildString {
            repeat(size) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
        return prefix?.let { "$it$separator$suffix" } ?: suffix
    }
}

public fun createIdGenerator(
    prefix: String? = null,
    size: Int = 16,
    alphabet: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
    separator: String = "-",
    random: Random = Random.Default,
): IdGenerator = IdGenerator(prefix, size, alphabet, separator, random)

public fun generateId(prefix: String? = null, random: Random = Random.Default): String =
    createIdGenerator(prefix = prefix, random = random).generate()

public fun mergeAbortSignals(vararg signals: AbortSignal): AbortSignal {
    if (signals.isEmpty()) return AbortSignalNever
    return object : AbortSignal {
        override val isAborted: Boolean
            get() = signals.any { it.isAborted }

        override fun throwIfAborted() {
            signals.forEach { it.throwIfAborted() }
        }

        override fun register(onAbort: () -> Unit): AbortSignal.AbortRegistration {
            val registrations = signals.map { it.register(onAbort) }
            return object : AbortSignal.AbortRegistration {
                override fun cancel() {
                    registrations.forEach { it.cancel() }
                }
            }
        }
    }
}

public fun abortSignalFromJobs(vararg jobs: Job): AbortSignal =
    mergeAbortSignals(*jobs.map { abortSignalFromJob(it) }.toTypedArray())

private fun primitiveEquals(left: JsonPrimitive, right: JsonPrimitive): Boolean {
    val leftPrimitive = left.jsonPrimitive
    val rightPrimitive = right.jsonPrimitive
    return when {
        leftPrimitive.booleanOrNull != null || rightPrimitive.booleanOrNull != null ->
            leftPrimitive.booleanOrNull == rightPrimitive.booleanOrNull
        leftPrimitive.doubleOrNull != null || rightPrimitive.doubleOrNull != null ->
            leftPrimitive.doubleOrNull == rightPrimitive.doubleOrNull
        else -> leftPrimitive.content == rightPrimitive.content
    }
}

internal fun combineHeaders(vararg headers: Map<String, String?>?): Map<String, String?> =
    headers.fold(linkedMapOf()) { acc, current ->
        current?.let { acc.putAll(it) }
        acc
    }

internal fun normalizeHeaders(headers: Map<String, String?>?): Map<String, String> =
    headers.orEmpty()
        .filterValues { it != null }
        .mapKeys { it.key.lowercase() }
        .mapValues { it.value.orEmpty() }

internal fun withUserAgentSuffix(
    headers: Map<String, String?>?,
    vararg userAgentSuffixParts: String,
): Map<String, String> {
    val normalized = normalizeHeaders(headers).toMutableMap()
    val suffix = userAgentSuffixParts.filter { it.isNotBlank() }
    normalized["user-agent"] = (listOfNotNull(normalized["user-agent"]?.takeIf { it.isNotBlank() }) + suffix)
        .joinToString(" ")
    return normalized
}

/**
 * Shared provider-header builder: applies the provider-specific [auth] scheme,
 * then layers static [settingsHeaders] and per-call [callHeaders], and finally
 * appends [userAgentSuffix] to the User-Agent (or just normalizes when null).
 * Only the auth scheme varies between providers, so it is passed as a lambda.
 */
internal fun buildProviderHeaders(
    settingsHeaders: Map<String, String>,
    callHeaders: Map<String, String>,
    userAgentSuffix: String?,
    auth: (MutableMap<String, String?>) -> Unit,
): Map<String, String> {
    val base = linkedMapOf<String, String?>()
    auth(base)
    base.putAll(settingsHeaders)
    base.putAll(callHeaders)
    return userAgentSuffix
        ?.let { withUserAgentSuffix(base, it) }
        ?: normalizeHeaders(base)
}

internal fun withoutTrailingSlash(url: String?): String? = url?.removeSuffix("/")

internal fun removeUndefinedEntries(values: Map<String, JsonElement?>): Map<String, JsonElement> =
    values.filterValues { it != null }.mapValues { it.value ?: JsonNull }

internal fun getErrorMessage(error: Throwable?): String = error?.message ?: "unknown error"

internal fun getErrorMessage(error: Any?): String = when (error) {
    null -> "unknown error"
    is String -> error
    is Throwable -> getErrorMessage(error)
    else -> error.toString()
}

internal fun loadApiKey(
    apiKey: String?,
    environmentVariableName: String,
    apiKeyParameterName: String = "apiKey",
    description: String,
    environment: Map<String, String> = emptyMap(),
): String =
    apiKey
        ?: environment[environmentVariableName]
        ?: throw LoadAPIKeyError(
            "$description API key is missing. Pass it using the '$apiKeyParameterName' parameter " +
                "or provide $environmentVariableName through the host environment map.",
        )

internal fun loadSetting(
    settingValue: String?,
    environmentVariableName: String,
    settingName: String,
    description: String,
    environment: Map<String, String> = emptyMap(),
): String =
    settingValue
        ?: environment[environmentVariableName]
        ?: throw LoadSettingError(
            "$description setting is missing. Pass it using the '$settingName' parameter " +
                "or provide $environmentVariableName through the host environment map.",
        )

internal fun loadOptionalSetting(
    settingValue: String?,
    environmentVariableName: String,
    environment: Map<String, String> = emptyMap(),
): String? =
    settingValue ?: environment[environmentVariableName]

internal fun mediaTypeToExtension(mediaType: String): String {
    val subtype = mediaType.lowercase().substringAfter('/', missingDelimiterValue = "")
    return when (subtype) {
        "mpeg" -> "mp3"
        "x-wav" -> "wav"
        "opus" -> "ogg"
        "mp4" -> "m4a"
        "x-m4a" -> "m4a"
        else -> subtype
    }
}

internal fun stripFileExtension(filename: String): String =
    filename.substringBefore('.', filename)

public fun isUrlSupported(
    mediaType: String,
    url: String,
    supportedUrls: Map<String, List<Regex>>,
): Boolean {
    val lowerMediaType = mediaType.lowercase()
    val lowerUrl = url.lowercase()
    return supportedUrls.asSequence()
        .map { (key, regexes) ->
            val prefix = when (val lowerKey = key.lowercase()) {
                "*", "*/*" -> ""
                else -> lowerKey.replace("*", "")
            }
            prefix to regexes
        }
        .filter { (prefix, _) -> lowerMediaType.startsWith(prefix) }
        .flatMap { (_, regexes) -> regexes.asSequence() }
        .any { regex -> regex.containsMatchIn(lowerUrl) }
}

public class DownloadError(
    public val url: String,
    message: String,
    public val statusCode: Int? = null,
    public val statusText: String? = null,
    cause: Throwable? = null,
) : AiSdkException(message, cause)

public fun validateDownloadUrl(url: String) {
    val parsed = parseUrl(url) ?: throw DownloadError(url, "Invalid URL: $url")
    if (parsed.scheme == "data") return
    if (parsed.scheme != "http" && parsed.scheme != "https") {
        throw DownloadError(url, "URL scheme must be http, https, or data, got ${parsed.scheme}:")
    }
    if (parsed.hostname.isBlank()) {
        throw DownloadError(url, "URL must have a hostname")
    }
    val host = parsed.hostname.lowercase().trim('[', ']')
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) {
        throw DownloadError(url, "URL with hostname ${parsed.hostname} is not allowed")
    }
    if (isIPv4(host) && isPrivateIPv4(host)) {
        throw DownloadError(url, "URL with IP address $host is not allowed")
    }
    if (isPrivateIPv6(host)) {
        throw DownloadError(url, "URL with IPv6 address ${parsed.hostname} is not allowed")
    }
}

private data class ParsedUrl(val scheme: String, val hostname: String)

private fun parseUrl(url: String): ParsedUrl? {
    val match = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$").find(url) ?: return null
    val scheme = match.groupValues[1].lowercase()
    if (scheme == "data") return ParsedUrl(scheme, "")
    val rest = match.groupValues[2]
    if (!rest.startsWith("//")) return null
    val authority = rest.removePrefix("//").substringBefore('/').substringBefore('?').substringBefore('#')
    val host = if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        authority.substringAfter('@').substringBefore(':')
    }
    return ParsedUrl(scheme, host)
}

private fun isIPv4(hostname: String): Boolean {
    val parts = hostname.split('.')
    return parts.size == 4 && parts.all { part ->
        val number = part.toIntOrNull()
        number != null && number in 0..255 && number.toString() == part
    }
}

private fun isPrivateIPv4(ip: String): Boolean {
    val parts = ip.split('.').map { it.toInt() }
    val first = parts[0]
    val second = parts[1]
    return first == 0 ||
        first == 10 ||
        first == 127 ||
        (first == 169 && second == 254) ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168)
}

private fun isPrivateIPv6(ip: String): Boolean {
    val normalized = ip.lowercase()
    if (normalized == "::1" || normalized == "::") return true
    if (normalized.startsWith("fc") || normalized.startsWith("fd")) return true
    if (normalized.startsWith("fe80")) return true
    if (normalized.startsWith("::ffff:")) {
        val mapped = normalized.removePrefix("::ffff:")
        if (isIPv4(mapped)) return isPrivateIPv4(mapped)
    }
    return false
}

public sealed interface ParseResult<out T> {
    public data class Success<T>(val value: T) : ParseResult<T>
    public data class Failure(val error: Throwable, val text: String) : ParseResult<Nothing>
}

internal fun <T> parseJsonEventStream(
    text: String,
    schema: Schema<T>,
    json: Json = Json,
): List<ParseResult<T>> =
    serverSentEventData(text).mapNotNull { data ->
        if (data == "[DONE]") null else safeParseJson(data, schema, json)
    }

public fun <T> parseJsonEventStream(
    chunks: Flow<String>,
    schema: Schema<T>,
    json: Json = Json,
): Flow<ParseResult<T>> = flow {
    var buffer = ""
    var eventData = mutableListOf<String>()
    suspend fun flush() {
        if (eventData.isEmpty()) return
        val data = eventData.joinToString("\n")
        eventData = mutableListOf()
        if (data != "[DONE]") emit(safeParseJson(data, schema, json))
    }
    chunks.collect { chunk ->
        buffer += chunk
        while (true) {
            val newline = buffer.indexOf('\n')
            if (newline < 0) break
            val rawLine = buffer.substring(0, newline).removeSuffix("\r")
            buffer = buffer.substring(newline + 1)
            if (rawLine.isEmpty()) {
                flush()
            } else if (rawLine.startsWith("data:")) {
                eventData += rawLine.removePrefix("data:").trimStart()
            }
        }
    }
    if (buffer.isNotEmpty()) {
        val rawLine = buffer.removeSuffix("\r")
        if (rawLine.startsWith("data:")) eventData += rawLine.removePrefix("data:").trimStart()
    }
    flush()
}

private fun serverSentEventData(text: String): List<String> {
    val events = mutableListOf<String>()
    val current = mutableListOf<String>()
    fun flush() {
        if (current.isNotEmpty()) {
            events += current.joinToString("\n")
            current.clear()
        }
    }
    text.lineSequence().forEach { raw ->
        val line = raw.removeSuffix("\r")
        when {
            line.isEmpty() -> flush()
            line.startsWith("data:") -> current += line.removePrefix("data:").trimStart()
        }
    }
    flush()
    return events
}

private fun <T> safeParseJson(text: String, schema: Schema<T>, json: Json): ParseResult<T> =
    try {
        val element = json.parseToJsonElement(text)
        @Suppress("UNCHECKED_CAST")
        ParseResult.Success(schema.validate?.invoke(element) ?: (element as T))
    } catch (error: SerializationException) {
        ParseResult.Failure(error, text)
    } catch (error: IllegalArgumentException) {
        ParseResult.Failure(error, text)
    }

public fun convertBase64ToByteArray(base64String: String): ByteArray =
    Base64.Default.decode(base64String.replace('-', '+').replace('_', '/'))

public fun convertByteArrayToBase64(array: ByteArray): String =
    Base64.Default.encode(array)

internal typealias Uint8Array = ByteArray

internal fun convertBase64ToUint8Array(base64String: String): Uint8Array =
    convertBase64ToByteArray(base64String)

internal fun convertUint8ArrayToBase64(array: Uint8Array): String =
    convertByteArrayToBase64(array)

internal fun convertToBase64(value: String): String = value

public fun convertToBase64(value: ByteArray): String = convertByteArrayToBase64(value)

/**
 * RFC-3986 percent-encoding (unreserved chars kept). Shared by providers that
 * build query/path segments; AwsSigV4 keeps its stricter signing variant.
 */
internal fun urlEncode(value: String): String =
    buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            if (char.isLetterOrDigit() || char in setOf('-', '_', '.', '~')) {
                append(char)
            } else {
                append('%')
                append(unsigned.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
