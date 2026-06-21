package ai.torad.aisdk

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

public data class CompletionRequestOptions(
    val headers: Map<String, String> = emptyMap(),
    val body: Map<String, JsonElement> = emptyMap(),
)

public data class CompletionRequest(
    val api: String,
    val id: String,
    val prompt: String,
    val headers: Map<String, String> = emptyMap(),
    val body: Map<String, JsonElement> = emptyMap(),
    val streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data,
    val abortSignal: AbortSignal = AbortSignalNever,
)

public interface CompletionTransport {
    public fun complete(request: CompletionRequest): Flow<String>
}

public class DirectCompletionTransport(
    private val handler: (CompletionRequest) -> Flow<String>,
) : CompletionTransport {
    override fun complete(request: CompletionRequest): Flow<String> = handler(request)
}

public data class UseCompletionOptions(
    val api: String = "/api/completion",
    val id: String = IdGenerator.generate("completion"),
    val initialCompletion: String = "",
    val initialInput: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: Map<String, JsonElement> = emptyMap(),
    val streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data,
    val transport: CompletionTransport = DirectCompletionTransport { emptyFlow() },
    val onFinish: suspend (prompt: String, completion: String) -> Unit = { _, _ -> },
    val onError: suspend (Throwable) -> Unit = {},
)

public data class CallCompletionApiOptions(
    val api: String = "/api/completion",
    val id: String = IdGenerator.generate("completion"),
    val prompt: String,
    val headers: Map<String, String> = emptyMap(),
    val body: Map<String, JsonElement> = emptyMap(),
    val streamProtocol: CompletionStreamProtocol = CompletionStreamProtocol.Data,
    val transport: CompletionTransport,
    val abortSignal: AbortSignal = AbortSignalNever,
    val setCompletion: (String) -> Unit = {},
    val setLoading: (Boolean) -> Unit = {},
    val setError: (Throwable?) -> Unit = {},
    val onFinish: suspend (prompt: String, completion: String) -> Unit = { _, _ -> },
    val onError: suspend (Throwable) -> Unit = {},
)

public object CompletionApi {
    public suspend fun callCompletionApi(options: CallCompletionApiOptions): String? {
        val request = CompletionRequest(
            api = options.api,
            id = options.id,
            prompt = options.prompt,
            headers = options.headers,
            body = options.body,
            streamProtocol = options.streamProtocol,
            abortSignal = options.abortSignal,
        )
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
    public data class Streaming(val text: String) : CompletionPhase()
    public data class Done(val text: String) : CompletionPhase()
    public data class Failed(val text: String, val cause: Throwable) : CompletionPhase()
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
    private val options: UseCompletionOptions = UseCompletionOptions(),
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
            CallCompletionApiOptions(
                api = options.api,
                id = options.id,
                prompt = prompt,
                headers = options.headers + requestOptions.headers,
                body = options.body + requestOptions.body,
                streamProtocol = options.streamProtocol,
                transport = options.transport,
                abortSignal = controller.signal,
                setCompletion = { text ->
                    mutableState.update { it.copy(phase = CompletionPhase.Streaming(text)) }
                },
                setLoading = { isLoading ->
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
                },
                setError = { err ->
                    if (err != null) {
                        mutableState.update { s ->
                            s.copy(phase = CompletionPhase.Failed(s.completion, err))
                        }
                    }
                },
                onFinish = options.onFinish,
                onError = options.onError,
            ),
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
