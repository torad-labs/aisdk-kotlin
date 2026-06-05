package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    val id: String = generateId("completion"),
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
    val id: String = generateId("completion"),
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
    } catch (t: Throwable) {
        options.setError(t)
        options.onError(t)
        null
    } finally {
        options.setLoading(false)
    }
}

public class Completion(
    private val options: UseCompletionOptions = UseCompletionOptions(),
) {
    public val id: String = options.id
    public val api: String = options.api
    public val streamProtocol: CompletionStreamProtocol = options.streamProtocol

    public var completion: String = options.initialCompletion
        private set

    public var input: String = options.initialInput

    public var error: Throwable? = null
        private set

    public var loading: Boolean = false
        private set

    private var abortController: AbortController? = null

    public fun setCompletion(completion: String) {
        this.completion = completion
    }

    public suspend fun complete(
        prompt: String,
        requestOptions: CompletionRequestOptions = CompletionRequestOptions(),
    ): String? {
        val controller = AbortController()
        abortController = controller
        return callCompletionApi(
            CallCompletionApiOptions(
                api = options.api,
                id = options.id,
                prompt = prompt,
                headers = options.headers + requestOptions.headers,
                body = options.body + requestOptions.body,
                streamProtocol = options.streamProtocol,
                transport = options.transport,
                abortSignal = controller.signal,
                setCompletion = { completion = it },
                setLoading = { loading = it },
                setError = { error = it },
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
        loading = false
    }
}
