package ai.torad.aisdk.rsc

import ai.torad.aisdk.ExperimentalAiSdkApi
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.createUiMessageStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf

@ExperimentalAiSdkApi
public class StreamableValue<T> internal constructor(
    internal val stream: Flow<T>,
)

@ExperimentalAiSdkApi
public class StreamableValueController<T>(
    initialValue: T? = null,
) {
    private val updates = MutableSharedFlow<T>(replay = 64)
    public val value: StreamableValue<T> = StreamableValue(updates)

    init {
        initialValue?.let { updates.tryEmit(it) }
    }

    public suspend fun update(value: T) {
        updates.emit(value)
    }

    public suspend fun done(value: T? = null) {
        if (value != null) updates.emit(value)
    }

    public suspend fun error(throwable: Throwable) {
        throw throwable
    }
}

@ExperimentalAiSdkApi
public fun <T> createStreamableValue(initialValue: T? = null): StreamableValueController<T> =
    StreamableValueController(initialValue)

@ExperimentalAiSdkApi
public fun <T> readStreamableValue(value: StreamableValue<T>): Flow<T> = value.stream

@ExperimentalAiSdkApi
public class StreamableUI internal constructor(
    internal val stream: Flow<UIMessage>,
)

@ExperimentalAiSdkApi
public class StreamableUIController(initialValue: UIMessage? = null) {
    private val updates = MutableSharedFlow<UIMessage>(replay = 64)
    public val value: StreamableUI = StreamableUI(updates)

    init {
        initialValue?.let { updates.tryEmit(it) }
    }

    public suspend fun update(value: UIMessage) {
        updates.emit(value)
    }

    public suspend fun done(value: UIMessage? = null) {
        if (value != null) updates.emit(value)
    }

    public suspend fun error(message: String) {
        updates.emit(
            UIMessage(
                id = "error",
                role = ai.torad.aisdk.ui.UIMessageRole.Assistant,
                parts = listOf(ai.torad.aisdk.ui.UIMessagePart.Error(message)),
            ),
        )
    }
}

@ExperimentalAiSdkApi
public fun createStreamableUI(initialValue: UIMessage? = null): StreamableUIController =
    StreamableUIController(initialValue)

@ExperimentalAiSdkApi
public fun streamUI(stream: Flow<UIMessage>): StreamableUI = StreamableUI(stream)

@ExperimentalAiSdkApi
public fun createAIUIStream(stream: Flow<UIMessage>): Flow<UIMessage> = stream

@ExperimentalAiSdkApi
public fun createAIUIStreamResponse(stream: Flow<UIMessage>): ai.torad.aisdk.ui.UIMessageStreamResponse =
    ai.torad.aisdk.ui.createUiMessageStreamResponse(stream)

@ExperimentalAiSdkApi
public class AIProvider<AI_STATE, UI_STATE>(
    initialAIState: AI_STATE,
    initialUIState: UI_STATE,
    private val onSetAIState: ((AI_STATE) -> Unit)? = null,
) {
    public var aiState: AI_STATE = initialAIState
        private set

    public var uiState: UI_STATE = initialUIState
        private set

    public fun setAIState(value: AI_STATE) {
        aiState = value
        onSetAIState?.invoke(value)
    }

    public fun setUIState(value: UI_STATE) {
        uiState = value
    }
}

@ExperimentalAiSdkApi
public fun <AI_STATE, UI_STATE> createAI(
    initialAIState: AI_STATE,
    initialUIState: UI_STATE,
    onSetAIState: ((AI_STATE) -> Unit)? = null,
): AIProvider<AI_STATE, UI_STATE> =
    AIProvider(initialAIState, initialUIState, onSetAIState)

@ExperimentalAiSdkApi
public fun <AI_STATE, UI_STATE> getAIState(provider: AIProvider<AI_STATE, UI_STATE>): AI_STATE =
    provider.aiState

@ExperimentalAiSdkApi
public fun <AI_STATE, UI_STATE> getMutableAIState(provider: AIProvider<AI_STATE, UI_STATE>): AIProvider<AI_STATE, UI_STATE> =
    provider

@ExperimentalAiSdkApi
public fun <AI_STATE, UI_STATE> useAIState(provider: AIProvider<AI_STATE, UI_STATE>): AI_STATE =
    provider.aiState

@ExperimentalAiSdkApi
public fun <AI_STATE, UI_STATE> useUIState(provider: AIProvider<AI_STATE, UI_STATE>): UI_STATE =
    provider.uiState

@ExperimentalAiSdkApi
public fun <AI_STATE, UI_STATE> useActions(provider: AIProvider<AI_STATE, UI_STATE>): AIProvider<AI_STATE, UI_STATE> =
    provider

@ExperimentalAiSdkApi
public fun <AI_STATE, UI_STATE> useSyncUIState(provider: AIProvider<AI_STATE, UI_STATE>): () -> UI_STATE =
    { provider.uiState }

@ExperimentalAiSdkApi
public fun useStreamableValue(value: StreamableValue<UIMessage>): Flow<UIMessage> =
    readStreamableValue(value)

@ExperimentalAiSdkApi
public fun createAgentUIStream(execute: suspend ai.torad.aisdk.ui.UIMessageStreamWriter.() -> Unit): Flow<UIMessage> =
    createUiMessageStream(execute = execute)
