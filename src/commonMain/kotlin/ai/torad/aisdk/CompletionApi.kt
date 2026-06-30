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

public enum class CompletionStreamProtocol(public val wireValue: String) {
    Data("data"),
    Text("text"),
}

@Poko
public class CompletionRequestOptions internal constructor(
    public val headers: Map<String, String> = emptyMap(),
    public val body: Map<String, JsonElement> = emptyMap(),
)

public class CompletionRequestOptionsBuilder {
    private var headers: Map<String, String> = emptyMap()
    private var body: Map<String, JsonElement> = emptyMap()

    public fun headers(value: Map<String, String>): CompletionRequestOptionsBuilder {
        headers = value
        return this
    }

    public fun body(value: Map<String, JsonElement>): CompletionRequestOptionsBuilder {
        body = value
        return this
    }

    public fun build(): CompletionRequestOptions =
        CompletionRequestOptions(
            headers = headers,
            body = body,
        )
}

public fun CompletionRequestOptions(
    block: CompletionRequestOptionsBuilder.() -> Unit = {},
): CompletionRequestOptions =
    CompletionRequestOptionsBuilder().apply(block).build()

public class CompletionRequest internal constructor(
    public val api: String,
    public val id: String,
    public val prompt: String,
    public val headers: Map<String, String> = emptyMap(),
    public val body: Map<String, JsonElement> = emptyMap(),
    public val streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data,
    public val abortSignal: AbortSignal = AbortSignalNever,
)

public class CompletionRequestBuilder {
    private var api: String? = null
    private var id: String? = null
    private var prompt: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var body: Map<String, JsonElement> = emptyMap()
    private var streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data
    private var abortSignal: AbortSignal = AbortSignalNever

    public fun api(value: String): CompletionRequestBuilder {
        api = value
        return this
    }

    public fun id(value: String): CompletionRequestBuilder {
        id = value
        return this
    }

    public fun prompt(value: String): CompletionRequestBuilder {
        prompt = value
        return this
    }

    public fun headers(value: Map<String, String>): CompletionRequestBuilder {
        headers = value
        return this
    }

    public fun body(value: Map<String, JsonElement>): CompletionRequestBuilder {
        body = value
        return this
    }

    public fun streamProtocol(value: CompletionStreamProtocol): CompletionRequestBuilder {
        streamProtocol = value
        return this
    }

    public fun abortSignal(value: AbortSignal): CompletionRequestBuilder {
        abortSignal = value
        return this
    }

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

public fun CompletionRequest(
    block: CompletionRequestBuilder.() -> Unit = {},
): CompletionRequest =
    CompletionRequestBuilder().apply(block).build()

public interface CompletionTransport {
    public fun complete(request: CompletionRequest): Flow<String>
}

internal class DirectCompletionTransport(
    private val handler: (CompletionRequest) -> Flow<String>,
) : CompletionTransport {
    override fun complete(request: CompletionRequest): Flow<String> = handler(request)
}

public class UseCompletionOptions internal constructor(
    public val api: String = "/api/completion",
    public val id: String = IdGenerator.generate("completion"),
    public val initialCompletion: String = "",
    public val initialInput: String = "",
    public val headers: Map<String, String> = emptyMap(),
    public val body: Map<String, JsonElement> = emptyMap(),
    public val streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data,
    public val transport: CompletionTransport = DirectCompletionTransport { emptyFlow() },
    public val onFinish: suspend (prompt: String, completion: String) -> Unit = { _, _ -> },
    public val onError: suspend (Throwable) -> Unit = {},
)

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

    public fun api(value: String): UseCompletionOptionsBuilder {
        api = value
        return this
    }

    public fun id(value: String): UseCompletionOptionsBuilder {
        id = value
        return this
    }

    public fun initialCompletion(value: String): UseCompletionOptionsBuilder {
        initialCompletion = value
        return this
    }

    public fun initialInput(value: String): UseCompletionOptionsBuilder {
        initialInput = value
        return this
    }

    public fun headers(value: Map<String, String>): UseCompletionOptionsBuilder {
        headers = value
        return this
    }

    public fun body(value: Map<String, JsonElement>): UseCompletionOptionsBuilder {
        body = value
        return this
    }

    public fun streamProtocol(value: CompletionStreamProtocol): UseCompletionOptionsBuilder {
        streamProtocol = value
        return this
    }

    public fun transport(value: CompletionTransport): UseCompletionOptionsBuilder {
        transport = value
        return this
    }

    public fun onFinish(value: suspend (prompt: String, completion: String) -> Unit): UseCompletionOptionsBuilder {
        onFinish = value
        return this
    }

    public fun onError(value: suspend (Throwable) -> Unit): UseCompletionOptionsBuilder {
        onError = value
        return this
    }

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

public fun UseCompletionOptions(
    block: UseCompletionOptionsBuilder.() -> Unit = {},
): UseCompletionOptions =
    UseCompletionOptionsBuilder().apply(block).build()

public class CallCompletionApiOptions internal constructor(
    public val api: String = "/api/completion",
    public val id: String = IdGenerator.generate("completion"),
    public val prompt: String,
    public val headers: Map<String, String> = emptyMap(),
    public val body: Map<String, JsonElement> = emptyMap(),
    public val streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data,
    public val transport: CompletionTransport,
    public val abortSignal: AbortSignal = AbortSignalNever,
    public val setCompletion: (String) -> Unit = {},
    public val setLoading: (Boolean) -> Unit = {},
    public val setError: (Throwable?) -> Unit = {},
    public val onFinish: suspend (prompt: String, completion: String) -> Unit = { _, _ -> },
    public val onError: suspend (Throwable) -> Unit = {},
)

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

    public fun api(value: String): CallCompletionApiOptionsBuilder {
        api = value
        return this
    }

    public fun id(value: String): CallCompletionApiOptionsBuilder {
        id = value
        return this
    }

    public fun prompt(value: String): CallCompletionApiOptionsBuilder {
        prompt = value
        return this
    }

    public fun headers(value: Map<String, String>): CallCompletionApiOptionsBuilder {
        headers = value
        return this
    }

    public fun body(value: Map<String, JsonElement>): CallCompletionApiOptionsBuilder {
        body = value
        return this
    }

    public fun streamProtocol(value: CompletionStreamProtocol): CallCompletionApiOptionsBuilder {
        streamProtocol = value
        return this
    }

    public fun transport(value: CompletionTransport): CallCompletionApiOptionsBuilder {
        transport = value
        return this
    }

    public fun abortSignal(value: AbortSignal): CallCompletionApiOptionsBuilder {
        abortSignal = value
        return this
    }

    public fun setCompletion(value: (String) -> Unit): CallCompletionApiOptionsBuilder {
        setCompletion = value
        return this
    }

    public fun setLoading(value: (Boolean) -> Unit): CallCompletionApiOptionsBuilder {
        setLoading = value
        return this
    }

    public fun setError(value: (Throwable?) -> Unit): CallCompletionApiOptionsBuilder {
        setError = value
        return this
    }

    public fun onFinish(value: suspend (prompt: String, completion: String) -> Unit): CallCompletionApiOptionsBuilder {
        onFinish = value
        return this
    }

    public fun onError(value: suspend (Throwable) -> Unit): CallCompletionApiOptionsBuilder {
        onError = value
        return this
    }

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

public fun CallCompletionApiOptions(
    block: CallCompletionApiOptionsBuilder.() -> Unit = {},
): CallCompletionApiOptions =
    CallCompletionApiOptionsBuilder().apply(block).build()

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

public sealed class CompletionPhase {
    public data object Idle : CompletionPhase()
    @Poko
    public class Streaming(public val text: String) : CompletionPhase()
    @Poko
    public class Done(public val text: String) : CompletionPhase()
    @Poko
    public class Failed(public val text: String, public val cause: Throwable) : CompletionPhase()
}

public data class CompletionState(
    val input: String = "",
    val phase: CompletionPhase = CompletionPhase.Idle,
) {
    public val completion: String
        get() = when (val p = phase) {
            is CompletionPhase.Idle -> ""
            is CompletionPhase.Streaming -> p.text
            is CompletionPhase.Done -> p.text
            is CompletionPhase.Failed -> p.text
        }
    public val error: Throwable?
        get() = (phase as? CompletionPhase.Failed)?.cause
    public val loading: Boolean
        get() = phase is CompletionPhase.Streaming
}

public class Completion(
    private val options: UseCompletionOptions = UseCompletionOptions(block = {}),
) {
    public val id: String = options.id
    public val api: String = options.api
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

    public val state: StateFlow<CompletionState> = mutableState.asStateFlow()

    public val completion: String get() = mutableState.value.completion
    public val input: String get() = mutableState.value.input
    public val error: Throwable? get() = mutableState.value.error
    public val loading: Boolean get() = mutableState.value.loading

    private var abortController: AbortController? = null

    public fun setInput(input: String) {
        mutableState.update { it.copy(input = input) }
    }

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
