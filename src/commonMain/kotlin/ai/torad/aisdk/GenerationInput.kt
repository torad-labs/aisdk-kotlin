package ai.torad.aisdk

/**
 * Non-empty message history wrapper for [GenerationInput.Messages] /
 * [GenerationInput.MessagesWithPrompt]. Guarantees history-backed inputs are never empty.
 * @since 0.3.0-beta01
 */
public fun GenerationInputNonEmptyMessages(
    first: ModelMessage,
    vararg rest: ModelMessage,
): GenerationInput.NonEmptyMessages = GenerationInput.NonEmptyMessages(listOf(first) + rest)

/** @since 0.3.0-beta01 */
public fun GenerationInputNonEmptyMessages(messages: Iterable<ModelMessage>): GenerationInput.NonEmptyMessages {
    val list = messages.toList()
    require(list.isNotEmpty()) { "messages must contain at least one message" }
    return GenerationInput.NonEmptyMessages(list)
}

/** @since 0.3.0-beta01 */
public fun GenerationInput(prompt: String?, messages: List<ModelMessage>): GenerationInput = when {
    prompt != null && messages.isNotEmpty() ->
        GenerationInput.MessagesWithPrompt(GenerationInputNonEmptyMessages(messages), prompt)
    prompt != null -> GenerationInput.Prompt(prompt)
    messages.isNotEmpty() -> GenerationInput.Messages(GenerationInputNonEmptyMessages(messages))
    else -> throw IllegalArgumentException(
        "GenerationInput requires prompt text or non-empty messages",
    )
}

/**
 * Prompt material accepted by high-level generators.
 *
 * Use [Prompt] for a single user prompt, [Messages] for an already-built
 * conversation history, and [MessagesWithPrompt] when appending a new user
 * prompt to existing model messages. The high-level generator converts these
 * values into [ModelMessage] lists before calling a provider.
 * @since 0.3.0-beta01
 */
public sealed class GenerationInput {

    /**
     * A single user prompt.
     * @since 0.3.0-beta01
     */
    public data class Prompt(val text: String) : GenerationInput()

    /**
     * A non-empty message history passed through without adding a new prompt.
     * @since 0.3.0-beta01
     */
    public data class Messages(val history: NonEmptyMessages) : GenerationInput()

    /**
     * A non-empty message history plus a final user prompt appended at call
     * preparation time.
     * @since 0.3.0-beta01
     */
    public data class MessagesWithPrompt(
        val history: NonEmptyMessages,
        val prompt: String,
    ) : GenerationInput()

    /**
     * Wrapper that guarantees history-backed inputs are never empty.
     * @since 0.3.0-beta01
     */
    public class NonEmptyMessages internal constructor(
        /** @since 0.3.0-beta01 */
        public val values: List<ModelMessage>,
    )

    internal fun toMessages(system: String?): List<ModelMessage> = buildList {
        if (system != null) add(SystemMessage(system))
        when (this@GenerationInput) {
            is Prompt -> add(UserMessage(text))
            is Messages -> addAll(history.values)
            is MessagesWithPrompt -> {
                addAll(history.values)
                add(UserMessage(prompt))
            }
        }
    }
}
