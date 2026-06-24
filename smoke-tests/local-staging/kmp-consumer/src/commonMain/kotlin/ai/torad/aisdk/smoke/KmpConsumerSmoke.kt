package ai.torad.aisdk.smoke

import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.UserMessage

public fun kmpSmokeApiSurface(): String {
    val params = LanguageModelCallParams(messages = listOf(UserMessage("hello")))
    check(params.messages.size == 1)
    return FinishReason.Stop.name
}
