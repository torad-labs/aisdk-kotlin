package consumer

import ai.torad.aisdk.Agent
import ai.torad.aisdk.LanguageModel
import ai.torad.aisdk.StructuredObjectGenerator
import ai.torad.aisdk.StructuredObjectPhase
import ai.torad.aisdk.TextGenerator
import kotlinx.coroutines.flow.first

class ResultObserverFixture {
    suspend fun run(
        agent: Agent<Unit, String>,
        model: LanguageModel,
        generator: StructuredObjectGenerator<String>,
    ): List<Any?> {
        val agentResult = agent.generate(prompt = "hello").first()
        val textResult = TextGenerator(model).generate("hello").first()
        val phase = generator.stream("hello").first()
        val idle = StructuredObjectPhase.Idle
        return listOf(agentResult, textResult, phase, idle)
    }
}
