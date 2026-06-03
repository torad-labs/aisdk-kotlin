package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement

data class DeepPartial<T>(val value: T?)

data class StructuredObjectRequest<INPUT>(
    val api: String,
    val id: String,
    val input: INPUT,
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
)

interface StructuredObjectTransport<INPUT> {
    fun submit(request: StructuredObjectRequest<INPUT>): Flow<String>
}

class DirectStructuredObjectTransport<INPUT>(
    private val handler: (StructuredObjectRequest<INPUT>) -> Flow<String>,
) : StructuredObjectTransport<INPUT> {
    override fun submit(request: StructuredObjectRequest<INPUT>): Flow<String> = handler(request)
}

data class StructuredObjectFinish<RESULT>(
    val value: RESULT?,
    val error: Throwable?,
    val rawValue: JsonElement?,
)

data class StructuredObjectOptions<RESULT, INPUT>(
    val api: String,
    val schema: Schema<RESULT>,
    val id: String = generateId("object"),
    val initialValue: RESULT? = null,
    val headers: Map<String, String> = emptyMap(),
    val transport: StructuredObjectTransport<INPUT> = DirectStructuredObjectTransport { emptyFlow() },
    val onFinish: suspend (StructuredObjectFinish<RESULT>) -> Unit = {},
    val onError: suspend (Throwable) -> Unit = {},
)

class StructuredObject<RESULT, INPUT>(
    private val options: StructuredObjectOptions<RESULT, INPUT>,
) {
    val id: String = options.id
    val api: String = options.api

    var value: RESULT? = options.initialValue
        private set

    val objectValue: RESULT?
        get() = value

    var rawValue: JsonElement? = null
        private set

    var error: Throwable? = null
        private set

    var loading: Boolean = false
        private set

    private var abortController: AbortController? = null

    suspend fun submit(input: INPUT) {
        clearObject()
        loading = true
        val controller = AbortController()
        abortController = controller
        val request = StructuredObjectRequest(
            api = options.api,
            id = options.id,
            input = input,
            headers = options.headers,
            abortSignal = controller.signal,
        )
        val accumulated = StringBuilder()
        var latestRaw: JsonElement? = null
        try {
            options.transport.submit(request).collect { chunk ->
                controller.signal.throwIfAborted()
                accumulated.append(chunk)
                val parsed = parsePartialJson(accumulated.toString()).value ?: return@collect
                if (!isDeepEqualData(latestRaw, parsed)) {
                    latestRaw = parsed
                    rawValue = parsed
                    when (val validated = safeValidateTypes(parsed, options.schema)) {
                        is ValidationResult.Success -> {
                            value = validated.value
                            error = null
                        }
                        is ValidationResult.Failure -> {
                            error = validated.error
                        }
                    }
                }
            }
            val finalRaw = latestRaw
            val finalError = if (finalRaw == null) {
                TypeValidationError.wrap(null, IllegalArgumentException("Structured object response did not contain JSON"))
            } else {
                when (val validated = safeValidateTypes(finalRaw, options.schema)) {
                    is ValidationResult.Success -> {
                        value = validated.value
                        null
                    }
                    is ValidationResult.Failure -> validated.error
                }
            }
            error = finalError
            options.onFinish(StructuredObjectFinish(value, finalError, finalRaw))
        } catch (abort: AbortError) {
            // Preserve partial state on abort, matching framework package behavior.
        } catch (t: Throwable) {
            error = t
            options.onError(t)
        } finally {
            loading = false
            abortController = null
        }
    }

    fun stop() {
        abortController?.abort()
        abortController = null
        loading = false
    }

    fun clear() {
        stop()
        clearObject()
    }

    private fun clearObject() {
        value = null
        rawValue = null
        error = null
    }
}
