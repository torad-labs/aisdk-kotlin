package ai.torad.aisdk

/** @since 0.3.0-beta01 */
public sealed class GenerationInput {

    /** @since 0.3.0-beta01 */
    public data class Prompt(val text: String) : GenerationInput()

    /** @since 0.3.0-beta01 */
    public data class Messages(val history: NonEmptyMessages) : GenerationInput()

    /** @since 0.3.0-beta01 */
    public data class MessagesWithPrompt(
        val history: NonEmptyMessages,
        val prompt: String,
    ) : GenerationInput()

    /** @since 0.3.0-beta01 */
    public class NonEmptyMessages private constructor(
        /** @since 0.3.0-beta01 */
        public val values: List<ModelMessage>,
    ) {
        public companion object {
            /** @since 0.3.0-beta01 */
            public fun of(first: ModelMessage, vararg rest: ModelMessage): NonEmptyMessages =
                NonEmptyMessages(listOf(first) + rest)

            /** @since 0.3.0-beta01 */
            public fun from(messages: Iterable<ModelMessage>): NonEmptyMessages {
                val list = messages.toList()
                require(list.isNotEmpty()) { "messages must contain at least one message" }
                return NonEmptyMessages(list)
            }
        }
    }

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

    public companion object {
        /** @since 0.3.0-beta01 */
        public fun from(prompt: String?, messages: List<ModelMessage>): GenerationInput = when {
            prompt != null && messages.isNotEmpty() ->
                MessagesWithPrompt(NonEmptyMessages.from(messages), prompt)
            prompt != null -> Prompt(prompt)
            messages.isNotEmpty() -> Messages(NonEmptyMessages.from(messages))
            else -> throw IllegalArgumentException(
                "GenerationInput requires prompt text or non-empty messages",
            )
        }
    }
}
