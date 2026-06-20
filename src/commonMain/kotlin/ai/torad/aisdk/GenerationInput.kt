package ai.torad.aisdk

public sealed class GenerationInput {

    public data class Prompt(val text: String) : GenerationInput()

    public data class Messages(val history: NonEmptyMessages) : GenerationInput()

    public data class MessagesWithPrompt(
        val history: NonEmptyMessages,
        val prompt: String,
    ) : GenerationInput()

    public class NonEmptyMessages private constructor(
        public val values: List<ModelMessage>,
    ) {
        public companion object {
            public fun of(first: ModelMessage, vararg rest: ModelMessage): NonEmptyMessages =
                NonEmptyMessages(listOf(first) + rest)

            public fun from(messages: Iterable<ModelMessage>): NonEmptyMessages {
                val list = messages.toList()
                require(list.isNotEmpty()) { "messages must contain at least one message" }
                return NonEmptyMessages(list)
            }
        }
    }

    internal fun toMessages(system: String?): List<ModelMessage> = buildList {
        if (system != null) add(systemMessage(system))
        when (this@GenerationInput) {
            is Prompt -> add(userMessage(text))
            is Messages -> addAll(history.values)
            is MessagesWithPrompt -> {
                addAll(history.values)
                add(userMessage(prompt))
            }
        }
    }

    public companion object {
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
