package ai.torad.aisdk

import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

fun <T> asArray(value: T): List<T> = listOf(value)
fun <T> asArray(value: Iterable<T>): List<T> = value.toList()

fun <T> splitArray(values: List<T>, chunkSize: Int): List<List<T>> {
    require(chunkSize > 0) { "chunkSize must be > 0" }
    return values.chunked(chunkSize)
}

fun cosineSimilarity(left: List<Float>, right: List<Float>): Float {
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

fun isDeepEqualData(left: JsonElement?, right: JsonElement?): Boolean = when {
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

fun mergeJsonObjects(vararg objects: JsonObject): JsonObject {
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

data class DataUrl(
    val mediaType: String,
    val base64: String,
)

fun splitDataUrl(dataUrl: String): DataUrl {
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

fun detectMediaType(filename: String? = null, dataUrl: String? = null, explicit: String? = null): String? {
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

fun prepareHeaders(
    defaultHeaders: Map<String, String> = emptyMap(),
    headers: Map<String, String>? = null,
): Map<String, String> = defaultHeaders + (headers ?: emptyMap())

data class RetryPolicy(
    val maxRetries: Int = 2,
    val baseDelayMs: Long = 100L,
    val maxDelayMs: Long = 2_000L,
)

suspend fun <T> retryWithExponentialBackoff(
    policy: RetryPolicy = RetryPolicy(),
    shouldRetry: (Throwable) -> Boolean = { true },
    block: suspend (attempt: Int) -> T,
): T {
    var attempt = 0
    var nextDelay = policy.baseDelayMs
    while (true) {
        try {
            return block(attempt)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (attempt >= policy.maxRetries || !shouldRetry(t)) throw t
            if (nextDelay > 0) delay(nextDelay)
            nextDelay = (nextDelay * 2).coerceAtMost(policy.maxDelayMs)
            attempt += 1
        }
    }
}

class SerialJobExecutor {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

data class IdGenerator(
    val prefix: String = "id",
    val size: Int = 16,
    val alphabet: String = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
) {
    fun generate(): String {
        require(size > 0) { "size must be > 0" }
        require(alphabet.isNotEmpty()) { "alphabet must not be empty" }
        val suffix = buildString {
            repeat(size) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
        return if (prefix.isBlank()) suffix else "${prefix}_$suffix"
    }
}

fun createIdGenerator(
    prefix: String = "id",
    size: Int = 16,
    alphabet: String = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
): IdGenerator = IdGenerator(prefix, size, alphabet)

fun generateId(prefix: String = "id"): String = createIdGenerator(prefix = prefix).generate()

fun mergeAbortSignals(vararg signals: AbortSignal): AbortSignal {
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

fun abortSignalFromJobs(vararg jobs: Job): AbortSignal =
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
