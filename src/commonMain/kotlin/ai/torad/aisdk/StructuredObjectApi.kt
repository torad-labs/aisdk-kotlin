package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

public data class DeepPartial<T>(val value: T?)

public data class StructuredObjectRequest<INPUT>(
    val api: String,
    val id: String,
    val input: INPUT,
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
)

public interface StructuredObjectTransport<INPUT> {
    public fun submit(request: StructuredObjectRequest<INPUT>): Flow<String>
}

public class DirectStructuredObjectTransport<INPUT>(
    private val handler: (StructuredObjectRequest<INPUT>) -> Flow<String>,
) : StructuredObjectTransport<INPUT> {
    override fun submit(request: StructuredObjectRequest<INPUT>): Flow<String> = handler(request)
}

public data class StructuredObjectFinish<RESULT>(
    val value: RESULT?,
    val error: Throwable?,
    val rawValue: JsonElement?,
)

public data class StructuredObjectOptions<RESULT, INPUT>(
    val api: String,
    val schema: Schema<RESULT>,
    val id: String = IdGenerator.generate("object"),
    val initialValue: RESULT? = null,
    val headers: Map<String, String> = emptyMap(),
    val transport: StructuredObjectTransport<INPUT> = DirectStructuredObjectTransport { emptyFlow() },
    val onFinish: suspend (StructuredObjectFinish<RESULT>) -> Unit = {},
    val onError: suspend (Throwable) -> Unit = {},
)

public sealed class StructuredObjectPhase<out RESULT> {
    public data object Idle : StructuredObjectPhase<Nothing>()
    public data class Streaming<out RESULT>(
        val partial: RESULT?,
        val raw: JsonElement?,
        val error: Throwable?,
    ) : StructuredObjectPhase<RESULT>()
    public data class Done<out RESULT>(
        val value: RESULT?,
        val raw: JsonElement?,
        val error: Throwable?,
    ) : StructuredObjectPhase<RESULT>()
}

public class StructuredObject<RESULT, INPUT>(
    private val options: StructuredObjectOptions<RESULT, INPUT>,
) {
    public val id: String = options.id
    public val api: String = options.api

    private val mutableState = MutableStateFlow<StructuredObjectPhase<RESULT>>(
        if (options.initialValue != null) {
            StructuredObjectPhase.Done(options.initialValue, null, null)
        } else {
            StructuredObjectPhase.Idle
        },
    )

    public val state: StateFlow<StructuredObjectPhase<RESULT>> = mutableState.asStateFlow()

    public val value: RESULT?
        get() = when (val p = mutableState.value) {
            is StructuredObjectPhase.Streaming -> p.partial
            is StructuredObjectPhase.Done -> p.value
            StructuredObjectPhase.Idle -> null
        }

    public val objectValue: RESULT?
        get() = value

    public val rawValue: JsonElement?
        get() = when (val p = mutableState.value) {
            is StructuredObjectPhase.Streaming -> p.raw
            is StructuredObjectPhase.Done -> p.raw
            StructuredObjectPhase.Idle -> null
        }

    public val error: Throwable?
        get() = when (val p = mutableState.value) {
            is StructuredObjectPhase.Streaming -> p.error
            is StructuredObjectPhase.Done -> p.error
            StructuredObjectPhase.Idle -> null
        }

    public val loading: Boolean
        get() = mutableState.value is StructuredObjectPhase.Streaming

    private var abortController: AbortController? = null

    public suspend fun submit(input: INPUT) {
        clearObject()
        val controller = AbortController()
        abortController = controller
        mutableState.value = StructuredObjectPhase.Streaming(null, null, null)
        val request = StructuredObjectRequest(
            api = options.api,
            id = options.id,
            input = input,
            headers = options.headers,
            abortSignal = controller.signal,
        )
        val accumulated = StringBuilder()
        var latestRaw: JsonElement? = null
        var currentValue: RESULT? = null
        try {
            options.transport.submit(request).collect { chunk ->
                controller.signal.throwIfAborted()
                accumulated.append(chunk)
                val parsed = parsePartialJson(accumulated.toString()).value ?: return@collect
                if (!JsonOps.isDeepEqual(latestRaw, parsed)) {
                    latestRaw = parsed
                    var validationError: Throwable? = null
                    when (val validated = safeValidateTypes(parsed, options.schema)) {
                        is ValidationResult.Success -> {
                            currentValue = validated.value
                        }
                        is ValidationResult.Failure -> {
                            validationError = validated.error
                        }
                    }
                    mutableState.value = StructuredObjectPhase.Streaming(currentValue, latestRaw, validationError)
                }
            }
            val finalRaw = latestRaw
            val finalError = if (finalRaw == null) {
                TypeValidationError.wrap(null, IllegalArgumentException("Structured object response did not contain JSON"))
            } else {
                when (val validated = safeValidateTypes(finalRaw, options.schema)) {
                    is ValidationResult.Success -> {
                        currentValue = validated.value
                        null
                    }
                    is ValidationResult.Failure -> validated.error
                }
            }
            mutableState.value = StructuredObjectPhase.Done(currentValue, finalRaw, finalError)
            options.onFinish(StructuredObjectFinish(currentValue, finalError, finalRaw))
        } catch (abort: AbortError) {
            // Preserve partial state on abort — finally block converts Streaming to Done.
        } catch (t: Throwable) {
            val current = mutableState.value
            val partial = (current as? StructuredObjectPhase.Streaming)?.partial ?: currentValue
            val raw = (current as? StructuredObjectPhase.Streaming)?.raw ?: latestRaw
            mutableState.value = StructuredObjectPhase.Done(partial, raw, t)
            options.onError(t)
        } finally {
            mutableState.update { p ->
                if (p is StructuredObjectPhase.Streaming) {
                    StructuredObjectPhase.Done(p.partial, p.raw, p.error)
                } else {
                    p
                }
            }
            abortController = null
        }
    }

    public fun stop() {
        abortController?.abort()
        abortController = null
        mutableState.update { p ->
            if (p is StructuredObjectPhase.Streaming) {
                StructuredObjectPhase.Done(p.partial, p.raw, p.error)
            } else {
                p
            }
        }
    }

    public fun clear() {
        stop()
        clearObject()
    }

    private fun clearObject() {
        mutableState.value = StructuredObjectPhase.Idle
    }
}
