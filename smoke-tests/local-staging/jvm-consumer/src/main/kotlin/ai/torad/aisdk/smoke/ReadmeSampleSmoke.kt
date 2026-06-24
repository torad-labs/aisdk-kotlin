package ai.torad.aisdk.smoke

import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.LanguageModel
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.StepCountIs
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.Tool
import ai.torad.aisdk.ToolLoopAgent
import ai.torad.aisdk.ToolSet
import ai.torad.aisdk.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.builtins.serializer

private object SmokeLanguageModel : LanguageModel {
    override val modelId: String = "smoke/local"
    override val provider: String = "smoke"

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult = LanguageModelResult(
        text = "ok",
        finishReason = FinishReason.Stop,
        usage = Usage(),
    )

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flowOf(
        StreamEvent.StreamStart(),
        StreamEvent.Finish(totalSteps = 1, finishReason = FinishReason.Stop, usage = Usage()),
    )
}

private class ReadmeSampleSmokeAgent : ToolLoopAgent<Unit, String>(
    model = SmokeLanguageModel,
    instructions = "Be brief.",
    tools = ToolSet(
        Tool(
            name = "echo",
            description = "Echo the input.",
            inputSerializer = String.serializer(),
            outputSerializer = String.serializer(),
            executor = { input -> input },
        ),
    ),
    stopWhen = StepCountIs(3),
)

public fun main() {
    val agent = ReadmeSampleSmokeAgent()
    check(agent.tools.names() == listOf("echo"))
    check(agent.model.modelId == "smoke/local")
}
