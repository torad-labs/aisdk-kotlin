package ai.torad.aisdk

import ai.torad.aisdk.middleware.defaultSettingsMiddleware
import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

class MiddlewareDefaultsTest {

    private class CaptureMiddleware(val onObserved: (LanguageModelCallParams) -> Unit) : LanguageModelMiddleware {
        override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult {
            onObserved(context.params)
            return context.doGenerate(context.params)
        }
        override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> {
            onObserved(context.params)
            return context.doStream(context.params)
        }
    }

    @Test
    fun `defaults_are_applied_when_call_omits_them`() = runTest {
        var observed: LanguageModelCallParams? = null
        val wrapped = wrapLanguageModel(
            mockLanguageModelTextOnly("ok"),
            listOf(
                defaultSettingsMiddleware(temperature = 0.7f, maxOutputTokens = 200),
                CaptureMiddleware { observed = it },
            ),
        )
        wrapped.generate(LanguageModelCallParams(messages = listOf(userMessage("hi"))))
        val captured = assertNotNull(observed)
        assertEquals(0.7f, captured.temperature)
        assertEquals(200, captured.maxOutputTokens)
    }

    @Test
    fun `explicit_call_params_override_defaults`() = runTest {
        var observed: LanguageModelCallParams? = null
        val wrapped = wrapLanguageModel(
            mockLanguageModelTextOnly("ok"),
            listOf(
                defaultSettingsMiddleware(temperature = 0.7f),
                CaptureMiddleware { observed = it },
            ),
        )
        wrapped.generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("hi")),
                temperature = 0.2f,
            )
        )
        val captured = assertNotNull(observed)
        assertEquals(0.2f, captured.temperature, "call-site temperature wins over default")
    }

    @Test
    fun `default middleware applies penalties and response format when omitted`() = runTest {
        var observed: LanguageModelCallParams? = null
        val defaultResponseFormat = ResponseFormat.Json(schemaName = "DefaultJson")
        val wrapped = wrapLanguageModel(
            mockLanguageModelTextOnly("ok"),
            listOf(
                defaultSettingsMiddleware(
                    presencePenalty = 0.3f,
                    frequencyPenalty = 0.6f,
                    responseFormat = defaultResponseFormat,
                ),
                CaptureMiddleware { observed = it },
            ),
        )

        wrapped.generate(LanguageModelCallParams(messages = listOf(userMessage("hi"))))

        val captured = assertNotNull(observed)
        assertEquals(0.3f, captured.presencePenalty)
        assertEquals(0.6f, captured.frequencyPenalty)
        assertEquals(defaultResponseFormat, captured.responseFormat)
    }

    @Test
    fun `explicit response format overrides default middleware response format`() = runTest {
        var observed: LanguageModelCallParams? = null
        val explicit = ResponseFormat.Json(schemaName = "ExplicitJson")
        val wrapped = wrapLanguageModel(
            mockLanguageModelTextOnly("ok"),
            listOf(
                defaultSettingsMiddleware(responseFormat = ResponseFormat.Json(schemaName = "DefaultJson")),
                CaptureMiddleware { observed = it },
            ),
        )

        wrapped.generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("hi")),
                responseFormat = explicit,
            ),
        )

        assertEquals(explicit, assertNotNull(observed).responseFormat)
    }
}
