@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.OpenAI
import ai.torad.aisdk.providers.OpenAIProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Upstream's OpenAI chat model advertises an http(s) pattern for `image` media
 * (`openai-chat-language-model.ts`), while the completion model deliberately
 * advertises nothing (`openai-completion-language-model.ts`). The advertised
 * contract is what `PromptConversion.convertToLanguageModelPrompt` reads to
 * decide whether a remote asset has to be downloaded and inlined.
 */
class OpenAIChatSupportedUrlsTest {
    @Test
    fun `chat model advertises http image urls and completion model advertises none`() {
        val provider = OpenAI(
            HttpClient(MockEngine { respond(content = "", status = HttpStatusCode.OK) }),
            OpenAIProviderSettings { apiKey("test-api-key") },
        )

        assertEquals(listOf("^https?://.*$"), provider.chat("gpt-4o").supportedUrls["image/*"])
        assertEquals(emptyMap(), provider.completion("gpt-3.5-turbo-instruct").supportedUrls)
    }
}
