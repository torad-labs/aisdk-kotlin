package ai.torad.aisdk.rsc

import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.createUiMessageStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf

class StreamableValue<T> internal constructor(
    internal val stream: Flow<T>,
)

class StreamableValueController<T>(
    initialValue: T? = null,
) {
    private val updates = MutableSharedFlow<T>(replay = 64)
    val value: StreamableValue<T> = StreamableValue(updates)

    init {
        initialValue?.let { updates.tryEmit(it) }
    }

    suspend fun update(value: T) {
        updates.emit(value)
    }

    suspend fun done(value: T? = null) {
        if (value != null) updates.emit(value)
    }

    suspend fun error(throwable: Throwable) {
        throw throwable
    }
}

fun <T> createStreamableValue(initialValue: T? = null): StreamableValueController<T> =
    StreamableValueController(initialValue)

fun <T> readStreamableValue(value: StreamableValue<T>): Flow<T> = value.stream

class StreamableUI internal constructor(
    internal val stream: Flow<UIMessage>,
)

class StreamableUIController(initialValue: UIMessage? = null) {
    private val updates = MutableSharedFlow<UIMessage>(replay = 64)
    val value: StreamableUI = StreamableUI(updates)

    init {
        initialValue?.let { updates.tryEmit(it) }
    }

    suspend fun update(value: UIMessage) {
        updates.emit(value)
    }

    suspend fun done(value: UIMessage? = null) {
        if (value != null) updates.emit(value)
    }

    suspend fun error(message: String) {
        updates.emit(
            UIMessage(
                id = "error",
                role = ai.torad.aisdk.ui.UIMessageRole.Assistant,
                parts = listOf(ai.torad.aisdk.ui.UIMessagePart.Error(message)),
            ),
        )
    }
}

fun createStreamableUI(initialValue: UIMessage? = null): StreamableUIController =
    StreamableUIController(initialValue)

fun streamUI(stream: Flow<UIMessage>): StreamableUI = StreamableUI(stream)

fun createAIUIStream(stream: Flow<UIMessage>): Flow<UIMessage> = stream

fun createAIUIStreamResponse(stream: Flow<UIMessage>): ai.torad.aisdk.ui.UIMessageStreamResponse =
    ai.torad.aisdk.ui.createUiMessageStreamResponse(stream)

class AIProvider<AI_STATE, UI_STATE>(
    initialAIState: AI_STATE,
    initialUIState: UI_STATE,
    private val onSetAIState: ((AI_STATE) -> Unit)? = null,
) {
    var aiState: AI_STATE = initialAIState
        private set

    var uiState: UI_STATE = initialUIState
        private set

    fun setAIState(value: AI_STATE) {
        aiState = value
        onSetAIState?.invoke(value)
    }

    fun setUIState(value: UI_STATE) {
        uiState = value
    }
}

fun <AI_STATE, UI_STATE> createAI(
    initialAIState: AI_STATE,
    initialUIState: UI_STATE,
    onSetAIState: ((AI_STATE) -> Unit)? = null,
): AIProvider<AI_STATE, UI_STATE> =
    AIProvider(initialAIState, initialUIState, onSetAIState)

fun <AI_STATE, UI_STATE> getAIState(provider: AIProvider<AI_STATE, UI_STATE>): AI_STATE =
    provider.aiState

fun <AI_STATE, UI_STATE> getMutableAIState(provider: AIProvider<AI_STATE, UI_STATE>): AIProvider<AI_STATE, UI_STATE> =
    provider

fun <AI_STATE, UI_STATE> useAIState(provider: AIProvider<AI_STATE, UI_STATE>): AI_STATE =
    provider.aiState

fun <AI_STATE, UI_STATE> useUIState(provider: AIProvider<AI_STATE, UI_STATE>): UI_STATE =
    provider.uiState

fun <AI_STATE, UI_STATE> useActions(provider: AIProvider<AI_STATE, UI_STATE>): AIProvider<AI_STATE, UI_STATE> =
    provider

fun <AI_STATE, UI_STATE> useSyncUIState(provider: AIProvider<AI_STATE, UI_STATE>): () -> UI_STATE =
    { provider.uiState }

fun useStreamableValue(value: StreamableValue<UIMessage>): Flow<UIMessage> =
    readStreamableValue(value)

fun createAgentUIStream(execute: suspend ai.torad.aisdk.ui.UIMessageStreamWriter.() -> Unit): Flow<UIMessage> =
    createUiMessageStream(execute = execute)
