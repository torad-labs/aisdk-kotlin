package ai.torad.aisdk.ui

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmSynthetic

private const val ADD_TOOL_RESULT_REPLACEMENT = "addToolOutput(toolCallId, output, toolName)"

@Poko
/** @since 0.3.0-beta01 */
public class ChatRequest internal constructor(
    /** @since 0.3.0-beta01 */
    public val messages: List<UIMessage>,
    /** @since 0.3.0-beta01 */
    public val body: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
)

/** @since 0.3.0-beta01 */
public class ChatRequestBuilder {
    private var messages: List<UIMessage>? = null
    private var body: Map<String, JsonElement> = emptyMap()
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun messages(value: List<UIMessage>): ChatRequestBuilder {
        messages = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun body(value: Map<String, JsonElement>): ChatRequestBuilder {
        body = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): ChatRequestBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): ChatRequest =
        ChatRequest(
            messages = requireNotNull(messages) { "ChatRequest.messages is required" },
            body = body,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun ChatRequest(
    block: ChatRequestBuilder.() -> Unit = {},
): ChatRequest =
    ChatRequestBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public interface ChatTransport {
    /** @since 0.3.0-beta01 */
    public fun sendMessages(request: ChatRequest): Flow<UIMessage>

    /** @since 0.3.0-beta01 */
    public fun reconnectToStream(chatId: String, headers: Map<String, String> = emptyMap()): Flow<UIMessage>? = null
}

/** @since 0.3.0-beta01 */
public class DirectChatTransport(
    private val handler: (ChatRequest) -> Flow<UIMessage>,
) : ChatTransport {
    override fun sendMessages(request: ChatRequest): Flow<UIMessage> = handler(request)
}

/** @since 0.3.0-beta01 */
public class DefaultChatTransport(
    private val delegate: ChatTransport,
) : ChatTransport {
    override fun sendMessages(request: ChatRequest): Flow<UIMessage> =
        delegate.sendMessages(request)

    override fun reconnectToStream(chatId: String, headers: Map<String, String>): Flow<UIMessage>? =
        delegate.reconnectToStream(chatId, headers)
}

/** @since 0.3.0-beta01 */
public class TextStreamChatTransport(
    private val handler: (ChatRequest) -> Flow<String>,
    private val assistantMessageId: (ChatRequest) -> String = { request ->
        UiMessageStreams.getResponseUiMessageId(request.messages)
    },
) : ChatTransport {
    override fun sendMessages(request: ChatRequest): Flow<UIMessage> =
        TransformTextToUiMessageStream(handler(request), assistantMessageId(request))
}

/**
 * This enum may gain variants in future releases. Consumers must not rely on
 * exhaustiveness — include an `else` branch when matching.
 * @since 0.3.0-beta01
 */
public enum class ChatStatus {
    Ready,
    Submitted,
    Streaming,
    Error,
}

private const val TOOL_APPROVAL_RESPONSE_ID_PREFIX = "tool_approval_"

private fun NextApprovalIndexAfter(messages: List<UIMessage>): Int =
    messages.mapNotNull { message ->
        message.id.removePrefix(TOOL_APPROVAL_RESPONSE_ID_PREFIX)
            .takeIf { it != message.id }
            ?.toIntOrNull()
    }.maxOrNull()?.plus(1) ?: 1

@OptIn(ExperimentalAtomicApi::class)
/** @since 0.3.0-beta01 */
public class Chat(
    /** @since 0.3.0-beta01 */
    public val id: String = "chat",
    initialMessages: List<UIMessage> = emptyList(),
    private val transport: ChatTransport,
) {
    // All chat state — messages, status, error, and the approval-id cursor —
    // lives in a single atomic holder. Every read-modify-write goes through
    // [applyState], so concurrent appends, upserts, and status transitions
    // never interleave into a torn state.
    private val internalState = MutableStateFlow(
        InternalState(
            messages = initialMessages.toList(),
            nextApprovalIndex = NextApprovalIndexAfter(initialMessages),
        ),
    )

    // Observable state view — always reflects the latest InternalState.
    private val _state = MutableStateFlow(internalState.value.toChatState())

    /** @since 0.3.0-beta01 */
    public val state: StateFlow<ChatState> = _state.asStateFlow()

    // Cross-thread visibility via AtomicReference: an in-flight sendMessage/regenerate
    // collector reads this to decide whether it is still the active operation
    // before writing state.
    private val currentOpRef = AtomicReference<Any?>(null)
    private val currentOpJobRef = AtomicReference<Job?>(null as Job?)

    /** @since 0.3.0-beta01 */
    public val status: ChatStatus
        get() = internalState.value.status

    /** @since 0.3.0-beta01 */
    public val error: Throwable?
        get() = internalState.value.error

    /** @since 0.3.0-beta01 */
    public val messages: List<UIMessage>
        get() = internalState.value.messages

    internal fun toState(): ChatState = ChatState(
        id = id,
        messages = messages,
        status = status,
        error = error,
    )

    // Atomically updates internalState and syncs the public StateFlow.
    private fun applyState(block: InternalState.() -> InternalState): InternalState =
        internalState.updateAndGet(block).also { _state.value = it.toChatState() }

    private fun InternalState.toChatState(): ChatState = ChatState(
        id = this@Chat.id,
        messages = messages,
        status = status,
        error = error,
    )

    /** @since 0.3.0-beta01 */
    public fun setMessages(messages: List<UIMessage>) {
        UiMessageStreams.validateUiMessages(messages)
        applyState {
            copy(
                messages = messages.toList(),
                nextApprovalIndex = NextApprovalIndexAfter(messages),
            )
        }
    }

    /** @since 0.3.0-beta01 */
    public fun clearError() {
        applyState {
            copy(
                error = null,
                status = if (status == ChatStatus.Error) ChatStatus.Ready else status,
            )
        }
    }

    /** @since 0.3.0-beta01 */
    public fun addToolApprovalResponse(
        toolCallId: String,
        approved: Boolean,
        reason: String? = null,
        approvalId: String? = null,
    ) {
        val responsePart = UIMessagePart.ToolUI(
            toolCallId = toolCallId,
            toolName = "approval",
            state = if (approved) ToolCallState.OutputAvailable else ToolCallState.OutputDenied,
            output = JsonPrimitive(approvalId ?: toolCallId),
            error = reason,
            approvalId = approvalId ?: toolCallId,
        )
        appendToolMessage(responsePart)
    }

    /** @since 0.3.0-beta01 */
    public fun addToolOutput(
        toolCallId: String,
        output: JsonElement,
        toolName: String = "tool",
    ) {
        appendToolMessage(
            UIMessagePart.ToolUI(
                toolCallId = toolCallId,
                toolName = toolName,
                state = ToolCallState.OutputAvailable,
                output = output,
            ),
        )
    }

    @Deprecated(
        "Deprecated in 0.3.0-beta01. Use addToolOutput instead.",
        ReplaceWith(ADD_TOOL_RESULT_REPLACEMENT),
    )
    /** @since 0.3.0-beta01 */
    public fun addToolResult(
        toolCallId: String,
        output: JsonElement,
        toolName: String = "tool",
    ): Unit = addToolOutput(toolCallId, output, toolName)

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public fun sendMessage(
        message: UIMessage,
        body: Map<String, JsonElement> = emptyMap(),
    ): Flow<UIMessage> = sendInternal(body) { it + message }

    /** @since 0.3.0-beta01 */
    @JvmSynthetic public fun regenerate(body: Map<String, JsonElement> = emptyMap()): Flow<UIMessage> {
        // Re-run from the existing history with the trailing assistant turn(s) dropped. Do NOT
        // re-append the last user message — it is already present, and appending it (as the old
        // code did via sendMessage) duplicated its id and sent a doubled user turn to the model.
        if (internalState.value.messages.none { it.role == UIMessageRole.User }) return emptyFlow()
        return sendInternal(body) { msgs -> msgs.dropLastWhile { it.role == UIMessageRole.Assistant } }
    }

    private fun sendInternal(
        body: Map<String, JsonElement>,
        transformMessages: (List<UIMessage>) -> List<UIMessage>,
    ): Flow<UIMessage> = operationFlow {
        val request = applyState {
            copy(messages = transformMessages(messages), status = ChatStatus.Submitted, error = null)
        }.let {
            ChatRequest {
                messages(it.messages)
                body(body)
            }
        }
        transport.sendMessages(request)
    }

    // A resumed stream is a live turn like any other: its emissions must land in chat state
    // (otherwise the next send's request omits the turn the user just read) and it must drive
    // status so isStreaming-driven UI is correct for the whole resumption.
    private fun resumeInternal(source: Flow<UIMessage>): Flow<UIMessage> = operationFlow {
        applyState { copy(status = ChatStatus.Submitted, error = null) }
        source
    }

    /**
     * Runs one turn with the upstream collection in a coroutine this class OWNS, so [stop]
     * aborts the request without cancelling the consumer's coroutine. The turn used to run
     * directly in the collector's coroutine and [stop] cancelled that Job — tearing down
     * everything the caller had sequenced after `collect` and leaving that coroutine unable
     * to run another turn. Upstream `chat.ts` only aborts its AbortController, and the sibling
     * stop() APIs (CompletionApi, StructuredObjectApi) likewise never touch a caller's Job.
     *
     * UNDISPATCHED so the operation is claimed and the Submitted state published synchronously
     * on subscription, exactly as before. RENDEZVOUS keeps the turn in lockstep with the
     * consumer, so emissions and state updates stay ordered as they were.
     */
    private fun operationFlow(startTurn: () -> Flow<UIMessage>): Flow<UIMessage> = channelFlow {
        val op = Any()
        // A child's CancellationException never reaches its parent, so a cancellation the
        // TRANSPORT raised (a real outcome the caller must see) is carried out by hand.
        // stop()-driven cancellation of this turn is not carried out — that is the point.
        val transportCancellation = CompletableDeferred<CancellationException?>()
        val turn = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val opJob = startOperation(op)
                collectIntoState(startTurn(), op, opJob)
                transportCancellation.complete(null)
            } catch (t: CancellationException) {
                transportCancellation.complete(t.takeIf { currentCoroutineContext().isActive })
                throw t
            }
        }
        turn.invokeOnCompletion { transportCancellation.complete(null) }
        turn.join()
        transportCancellation.await()?.let { throw it }
    }.buffer(Channel.RENDEZVOUS)

    private suspend fun startOperation(op: Any): Job? {
        val opJob = currentCoroutineContext()[Job]
        currentOpJobRef.store(opJob)
        currentOpRef.store(op)
        return opJob
    }

    private suspend fun ProducerScope<UIMessage>.collectIntoState(
        source: Flow<UIMessage>,
        op: Any,
        opJob: Job?,
    ) {
        try {
            source.collect { response ->
                if (currentOpRef.load() === op) {
                    applyState { copy(status = ChatStatus.Streaming).withUpsert(response) }
                }
                send(response)
            }
            if (currentOpRef.load() === op) {
                applyState { copy(status = ChatStatus.Ready) }
            }
        } catch (t: CancellationException) {
            if (currentOpRef.load() === op) {
                applyState { copy(error = null, status = ChatStatus.Ready) }
            }
            throw t
        } catch (t: Throwable) {
            if (currentOpRef.load() === op) {
                applyState { copy(error = t, status = ChatStatus.Error) }
            }
            throw t
        } finally {
            if (currentOpJobRef.load() === opJob) {
                currentOpJobRef.store(null)
            }
        }
    }

    /** @since 0.3.0-beta01 */
    public fun stop() {
        currentOpJobRef.load()?.cancel()
        currentOpJobRef.store(null)
        currentOpRef.store(null)
        applyState { copy(status = ChatStatus.Ready) }
    }

    /** @since 0.3.0-beta01 */
    public fun reconnectToStream(headers: Map<String, String> = emptyMap()): Flow<UIMessage>? =
        transport.reconnectToStream(id, headers)?.let(::resumeInternal)

    /** @since 0.3.0-beta01 */
    @JvmSynthetic public fun resumeStream(headers: Map<String, String> = emptyMap()): Flow<UIMessage> =
        reconnectToStream(headers) ?: emptyFlow()

    /** @since 0.3.0-beta01 */
    public fun asSession(): ChatSession = ChatSession(this)

    private fun appendToolMessage(part: UIMessagePart.ToolUI) {
        applyState {
            val (msgId, nextIndex) = nextApprovalResponseId()
            copy(
                messages = messages + UIMessage(
                    id = msgId,
                    role = UIMessageRole.User,
                    parts = listOf(part),
                ),
                nextApprovalIndex = nextIndex,
            )
        }
    }

    private data class InternalState(
        val messages: List<UIMessage> = emptyList(),
        val status: ChatStatus = ChatStatus.Ready,
        val error: Throwable? = null,
        val nextApprovalIndex: Int = 1,
    ) {
        fun withUpsert(message: UIMessage): InternalState {
            val index = messages.indexOfFirst { it.id == message.id }
            val nextMessages = if (index >= 0) {
                messages.toMutableList().also { it[index] = message }
            } else {
                messages + message
            }
            return copy(messages = nextMessages)
        }

        fun nextApprovalResponseId(): Pair<String, Int> {
            val existingIds = messages.mapTo(mutableSetOf()) { it.id }
            var index = nextApprovalIndex
            while (true) {
                val candidate = "$TOOL_APPROVAL_RESPONSE_ID_PREFIX${index++}"
                if (candidate !in existingIds) return candidate to index
            }
        }
    }
}
