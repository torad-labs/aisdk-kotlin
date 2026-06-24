package ai.torad.aisdk.smoke

import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.UserMessage

public class AndroidConsumerSmoke {
    public fun messageCount(): Int = LanguageModelCallParams(
        messages = listOf(UserMessage("hello")),
    ).messages.size
}
