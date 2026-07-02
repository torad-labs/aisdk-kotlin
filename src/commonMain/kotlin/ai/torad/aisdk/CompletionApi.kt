package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/** @since 0.3.0-beta01 */
public enum class CompletionStreamProtocol(public val wireValue: String) {
    Data("data"),
    Text("text"),
}

@Poko
/** @since 0.3.0-beta01 */
public class CompletionRequestOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val body: Map<String, JsonElement> = emptyMap(),
)

/** @since 0.3.0-beta01 */
public class CompletionRequestOptionsBuilder {
    private var headers: Map<String, String> = emptyMap()
    private var body: Map<String, JsonElement> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): CompletionRequestOptionsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun body(value: Map<String, JsonElement>): CompletionRequestOptionsBuilder {
        body = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): CompletionRequestOptions =
        CompletionRequestOptions(
            headers = headers,
            body = body,
        )
}

/** @since 0.3.0-beta01 */
public fun CompletionRequestOptions(
    block: CompletionRequestOptionsBuilder.() -> Unit = {},
): CompletionRequestOptions =
    CompletionRequestOptionsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class CompletionRequest internal constructor(
    /** @since 0.3.0-beta01 */
    public val api: String,
    /** @since 0.3.0-beta01 */
    public val id: String,
    /** @since 0.3.0-beta01 */
    public val prompt: String,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val body: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data,
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
)

/** @since 0.3.0-beta01 */
public class CompletionRequestBuilder {
    private var api: String? = null
    private var id: String? = null
    private var prompt: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var body: Map<String, JsonElement> = emptyMap()
    private var streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data
    private var abortSignal: AbortSignal = AbortSignalNever

    /** @since 0.3.0-beta01 */
    public fun api(value: String): CompletionRequestBuilder {
        api = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun id(value: String): CompletionRequestBuilder {
        id = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun prompt(value: String): CompletionRequestBuilder {
        prompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): CompletionRequestBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun body(value: Map<String, JsonElement>): CompletionRequestBuilder {
        body = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun streamProtocol(value: CompletionStreamProtocol): CompletionRequestBuilder {
        streamProtocol = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): CompletionRequestBuilder {
        abortSignal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): CompletionRequest =
        CompletionRequest(
            api = requireNotNull(api) { "CompletionRequest.api is required" },
            id = requireNotNull(id) { "CompletionRequest.id is required" },
            prompt = requireNotNull(prompt) { "CompletionRequest.prompt is required" },
            headers = headers,
            body = body,
            streamProtocol = streamProtocol,
            abortSignal = abortSignal,
        )
}

/** @since 0.3.0-beta01 */
public fun CompletionRequest(
    block: CompletionRequestBuilder.() -> Unit = {},
): CompletionRequest =
    CompletionRequestBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public interface CompletionTransport {
    /** @since 0.3.0-beta01 */
    public fun complete(request: CompletionRequest): Flow<String>
}

internal class DirectCompletionTransport(
    private val handler: (CompletionRequest) -> Flow<String>,
) : CompletionTransport {
    override fun complete(request: CompletionRequest): Flow<String> = handler(request)
}

/** @since 0.3.0-beta01 */
public class UseCompletionOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val api: String = "/api/completion",
    /** @since 0.3.0-beta01 */
    public val id: String = IdGenerator.generate("completion"),
    /** @since 0.3.0-beta01 */
    public val initialCompletion: String = "",
    /** @since 0.3.0-beta01 */
    public val initialInput: String = "",
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val body: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data,
    /** @since 0.3.0-beta01 */
    public val transport: CompletionTransport = DirectCompletionTransport { emptyFlow() },
    /** @since 0.3.0-beta01 */
    public val onFinish: suspend (prompt: String, completion: String) -> Unit = { _, _ -> },
    /** @since 0.3.0-beta01 */
    public val onError: suspend (Throwable) -> Unit = {},
)

/** @since 0.3.0-beta01 */
public class UseCompletionOptionsBuilder {
    private var api: String = "/api/completion"
    private var id: String = IdGenerator.generate("completion")
    private var initialCompletion: String = ""
    private var initialInput: String = ""
    private var headers: Map<String, String> = emptyMap()
    private var body: Map<String, JsonElement> = emptyMap()
    private var streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data
    private var transport: CompletionTransport = DirectCompletionTransport { emptyFlow() }
    private var onFinish: suspend (prompt: String, completion: String) -> Unit = { _, _ -> }
    private var onError: suspend (Throwable) -> Unit = {}

    /** @since 0.3.0-beta01 */
    public fun api(value: String): UseCompletionOptionsBuilder {
        api = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun id(value: String): UseCompletionOptionsBuilder {
        id = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun initialCompletion(value: String): UseCompletionOptionsBuilder {
        initialCompletion = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun initialInput(value: String): UseCompletionOptionsBuilder {
        initialInput = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): UseCompletionOptionsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun body(value: Map<String, JsonElement>): UseCompletionOptionsBuilder {
        body = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun streamProtocol(value: CompletionStreamProtocol): UseCompletionOptionsBuilder {
        streamProtocol = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun transport(value: CompletionTransport): UseCompletionOptionsBuilder {
        transport = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun onFinish(value: suspend (prompt: String, completion: String) -> Unit): UseCompletionOptionsBuilder {
        onFinish = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun onError(value: suspend (Throwable) -> Unit): UseCompletionOptionsBuilder {
        onError = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): UseCompletionOptions =
        UseCompletionOptions(
            api = api,
            id = id,
            initialCompletion = initialCompletion,
            initialInput = initialInput,
            headers = headers,
            body = body,
            streamProtocol = streamProtocol,
            transport = transport,
            onFinish = onFinish,
            onError = onError,
        )
}

/** @since 0.3.0-beta01 */
public fun UseCompletionOptions(
    block: UseCompletionOptionsBuilder.() -> Unit = {},
): UseCompletionOptions =
    UseCompletionOptionsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class CallCompletionApiOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val api: String = "/api/completion",
    /** @since 0.3.0-beta01 */
    public val id: String = IdGenerator.generate("completion"),
    /** @since 0.3.0-beta01 */
    public val prompt: String,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val body: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data,
    /** @since 0.3.0-beta01 */
    public val transport: CompletionTransport,
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
    /** @since 0.3.0-beta01 */
    public val setCompletion: (String) -> Unit = {},
    /** @since 0.3.0-beta01 */
    public val setLoading: (Boolean) -> Unit = {},
    /** @since 0.3.0-beta01 */
    public val setError: (Throwable?) -> Unit = {},
    /** @since 0.3.0-beta01 */
    public val onFinish: suspend (prompt: String, completion: String) -> Unit = { _, _ -> },
    /** @since 0.3.0-beta01 */
    public val onError: suspend (Throwable) -> Unit = {},
)

/** @since 0.3.0-beta01 */
public class CallCompletionApiOptionsBuilder {
    private var api: String = "/api/completion"
    private var id: String = IdGenerator.generate("completion")
    private var prompt: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var body: Map<String, JsonElement> = emptyMap()
    private var streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data
    private var transport: CompletionTransport? = null
    private var abortSignal: AbortSignal = AbortSignalNever
    private var setCompletion: (String) -> Unit = {}
    private var setLoading: (Boolean) -> Unit = {}
    private var setError: (Throwable?) -> Unit = {}
    private var onFinish: suspend (prompt: String, completion: String) -> Unit = { _, _ -> }
    private var onError: suspend (Throwable) -> Unit = {}

    /** @since 0.3.0-beta01 */
    public fun api(value: String): CallCompletionApiOptionsBuilder {
        api = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun id(value: String): CallCompletionApiOptionsBuilder {
        id = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun prompt(value: String): CallCompletionApiOptionsBuilder {
        prompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): CallCompletionApiOptionsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun body(value: Map<String, JsonElement>): CallCompletionApiOptionsBuilder {
        body = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun streamProtocol(value: CompletionStreamProtocol): CallCompletionApiOptionsBuilder {
        streamProtocol = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun transport(value: CompletionTransport): CallCompletionApiOptionsBuilder {
        transport = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): CallCompletionApiOptionsBuilder {
        abortSignal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun setCompletion(value: (String) -> Unit): CallCompletionApiOptionsBuilder {
        setCompletion = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun setLoading(value: (Boolean) -> Unit): CallCompletionApiOptionsBuilder {
        setLoading = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun setError(value: (Throwable?) -> Unit): CallCompletionApiOptionsBuilder {
        setError = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun onFinish(value: suspend (prompt: String, completion: String) -> Unit): CallCompletionApiOptionsBuilder {
        onFinish = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun onError(value: suspend (Throwable) -> Unit): CallCompletionApiOptionsBuilder {
        onError = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): CallCompletionApiOptions =
        CallCompletionApiOptions(
            api = api,
            id = id,
            prompt = requireNotNull(prompt) { "CallCompletionApiOptions.prompt is required" },
            headers = headers,
            body = body,
            streamProtocol = streamProtocol,
            transport = requireNotNull(transport) { "CallCompletionApiOptions.transport is required" },
            abortSignal = abortSignal,
            setCompletion = setCompletion,
            setLoading = setLoading,
            setError = setError,
            onFinish = onFinish,
            onError = onError,
        )
}

/** @since 0.3.0-beta01 */
public fun CallCompletionApiOptions(
    block: CallCompletionApiOptionsBuilder.() -> Unit = {},
): CallCompletionApiOptions =
    CallCompletionApiOptionsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public object CompletionApi {
    public suspend fun callCompletionApi(options: CallCompletionApiOptions): String? {
        val request = CompletionRequest {
            api(options.api)
            id(options.id)
            prompt(options.prompt)
            headers(options.headers)
            body(options.body)
            streamProtocol(options.streamProtocol)
            abortSignal(options.abortSignal)
        }
        val builder = StringBuilder()
        options.setLoading(true)
        options.setError(null)
        return try {
            options.transport.complete(request).collect { delta ->
                options.abortSignal.throwIfAborted()
                builder.append(delta)
                options.setCompletion(builder.toString())
            }
            val completion = builder.toString()
            options.onFinish(options.prompt, completion)
            completion
        } catch (abort: AbortError) {
            builder.toString().ifEmpty { null }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            options.setError(t)
            options.onError(t)
            null
        } finally {
            options.setLoading(false)
        }
    }
}

/** @since 0.3.0-beta01 */
public sealed class CompletionPhase {
    /** @since 0.3.0-beta01 */
    public data object Idle : CompletionPhase()

    @Poko
    /** @since 0.3.0-beta01 */
    public class Streaming(public val text: String) : CompletionPhase()

    @Poko
    /** @since 0.3.0-beta01 */
    public class Done(public val text: String) : CompletionPhase()

    @Poko
    /** @since 0.3.0-beta01 */
    public class Failed(public val text: String, public val cause: Throwable) : CompletionPhase()
}

/** @since 0.3.0-beta01 */
public data class CompletionState(
    val input: String = "",
    val phase: CompletionPhase = CompletionPhase.Idle,
) {
    /** @since 0.3.0-beta01 */
    public val completion: String
        get() = when (val p = phase) {
            is CompletionPhase.Idle -> ""
            is CompletionPhase.Streaming -> p.text
            is CompletionPhase.Done -> p.text
            is CompletionPhase.Failed -> p.text
        }

    /** @since 0.3.0-beta01 */
    public val error: Throwable?
        get() = (phase as? CompletionPhase.Failed)?.cause

    /** @since 0.3.0-beta01 */
    public val loading: Boolean
        get() = phase is CompletionPhase.Streaming
}

/** @since 0.3.0-beta01 */
public class Completion(
    private val options: UseCompletionOptions = UseCompletionOptions(block = {}),
) {
    /** @since 0.3.0-beta01 */
    public val id: String = options.id

    /** @since 0.3.0-beta01 */
    public val api: String = options.api

    /** @since 0.3.0-beta01 */
    public val streamProtocol: CompletionStreamProtocol = options.streamProtocol

    private val mutableState = MutableStateFlow(
        CompletionState(
            input = options.initialInput,
            phase = if (options.initialCompletion.isEmpty()) {
                CompletionPhase.Idle
            } else {
                CompletionPhase.Done(options.initialCompletion)
            },
        ),
    )

    /** @since 0.3.0-beta01 */
    public val state: StateFlow<CompletionState> = mutableState.asStateFlow()

    /** @since 0.3.0-beta01 */
    public val completion: String get() = mutableState.value.completion

    /** @since 0.3.0-beta01 */
    public val input: String get() = mutableState.value.input

    /** @since 0.3.0-beta01 */
    public val error: Throwable? get() = mutableState.value.error

    /** @since 0.3.0-beta01 */
    public val loading: Boolean get() = mutableState.value.loading

    private var abortController: AbortController? = null

    /** @since 0.3.0-beta01 */
    public fun setInput(input: String) {
        mutableState.update { it.copy(input = input) }
    }

    /** @since 0.3.0-beta01 */
    public fun setCompletion(completion: String) {
        mutableState.update { it.copy(phase = CompletionPhase.Done(completion)) }
    }

    public suspend fun complete(
        prompt: String,
        requestOptions: CompletionRequestOptions = CompletionRequestOptions(),
    ): String? {
        val controller = AbortController()
        abortController = controller
        return CompletionApi.callCompletionApi(
            CallCompletionApiOptions {
                api(options.api)
                id(options.id)
                prompt(prompt)
                headers(options.headers + requestOptions.headers)
                body(options.body + requestOptions.body)
                streamProtocol(options.streamProtocol)
                transport(options.transport)
                abortSignal(controller.signal)
                setCompletion { text ->
                    mutableState.update { it.copy(phase = CompletionPhase.Streaming(text)) }
                }
                setLoading { isLoading ->
                    mutableState.update { s ->
                        if (isLoading) {
                            s.copy(phase = CompletionPhase.Streaming(s.completion))
                        } else {
                            s.copy(
                                // Preserve terminal states: stop() sets Done to keep the partial
                                // text, and setLoading(false) firing afterward must not erase it.
                                phase = when (val p = s.phase) {
                                    is CompletionPhase.Streaming -> CompletionPhase.Done(p.text)
                                    is CompletionPhase.Idle,
                                    is CompletionPhase.Done,
                                    is CompletionPhase.Failed,
                                    -> p
                                },
                            )
                        }
                    }
                }
                setError { err ->
                    if (err != null) {
                        mutableState.update { s ->
                            s.copy(phase = CompletionPhase.Failed(s.completion, err))
                        }
                    }
                }
                onFinish(options.onFinish)
                onError(options.onError)
            },
        ).also {
            abortController = null
        }
    }

    public suspend fun handleSubmit(): String? =
        input.takeIf { it.isNotEmpty() }?.let { complete(it) }

    /** @since 0.3.0-beta01 */
    public fun stop() {
        abortController?.abort()
        abortController = null
        mutableState.update { s ->
            s.copy(
                phase = when (val p = s.phase) {
                    is CompletionPhase.Streaming -> CompletionPhase.Done(p.text)
                    is CompletionPhase.Idle,
                    is CompletionPhase.Done,
                    is CompletionPhase.Failed,
                    -> p
                },
            )
        }
    }
}
