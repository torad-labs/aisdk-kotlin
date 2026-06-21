package ai.torad.aisdk

import ai.torad.aisdk.middleware.DefaultSettingsMiddleware
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MiddlewareContractTest {
    private class CapturingModel : LanguageModel {
        override val modelId = "inner/model"
        override val provider = "inner"
        var seen: LanguageModelCallParams? = null
        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            seen = params
            return LanguageModelResult(text = "ok", finishReason = FinishReason.Stop, usage = Usage())
        }
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = emptyFlow()
    }

    @Test
    fun `transformParams runs before the downstream call`() = runTest {
        val inner = CapturingModel()
        val mw = object : LanguageModelMiddleware {
            override suspend fun transformParams(
                operation: MiddlewareOperation,
                params: LanguageModelCallParams,
                model: LanguageModel,
            ): LanguageModelCallParams = params.copy(temperature = 0.42f)
        }
        val wrapped = WrapLanguageModel(inner, listOf(mw))
        wrapped.generate(LanguageModelCallParams(messages = listOf(UserMessage("hi"))))
        assertEquals(0.42f, inner.seen?.temperature, "transformParams applied before generate")
    }

    @Test
    fun `override hooks change the presented identity`() {
        val inner = CapturingModel()
        val mw = object : LanguageModelMiddleware {
            override fun overrideModelId(model: LanguageModel) = "outer/model"
            override fun overrideProvider(model: LanguageModel) = "outer"
        }
        val wrapped = WrapLanguageModel(inner, listOf(mw))
        assertEquals("outer/model", wrapped.modelId)
        assertEquals("outer", wrapped.provider)
    }

    @Test
    fun `defaultSettingsMiddleware fills tools toolChoice and headers via transformParams`() = runTest {
        val inner = CapturingModel()
        val tool = LanguageModelTool(name = "t", description = "d", parametersSchemaJson = "{}")
        val wrapped = WrapLanguageModel(
            inner,
            listOf(
                DefaultSettingsMiddleware(
                    tools = listOf(tool),
                    toolChoice = ToolChoice.Required,
                    headers = mapOf("x-default" to "1"),
                    temperature = 0.7f,
                ),
            ),
        )
        wrapped.generate(LanguageModelCallParams(messages = listOf(UserMessage("hi"))))
        assertEquals(listOf("t"), inner.seen?.tools?.map { it.name })
        assertEquals(ToolChoice.Required, inner.seen?.toolChoice)
        assertEquals("1", inner.seen?.headers?.get("x-default"))
        assertEquals(0.7f, inner.seen?.temperature)
    }
}
