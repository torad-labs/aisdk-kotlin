package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CallSettingsContextBuilderTest {
    @Test
    fun `CallSettings and CallConfig builders keep value semantics`() {
        val settings = CallSettings {
            temperature(0.2f)
            stopSequences(listOf("stop"))
            maxRetries(1)
        }
        val equalSettings = CallSettings {
            temperature(0.2f)
            stopSequences(listOf("stop"))
            maxRetries(1)
        }
        val config = CallConfig {
            topP(0.8f)
            stopSequences(listOf("done"))
            maxRetries(0)
        }
        val equalConfig = CallConfig {
            topP(0.8f)
            stopSequences(listOf("done"))
            maxRetries(0)
        }

        assertEquals(equalSettings, settings)
        assertEquals(equalSettings.hashCode(), settings.hashCode())
        assertNotEquals(CallSettings { temperature(0.3f) }, settings)
        assertEquals(equalConfig, config)
        assertEquals(equalConfig.hashCode(), config.hashCode())
        assertNotEquals(CallConfig { topP(0.9f) }, config)
    }

    @Test
    fun `Context settings builders preserve fields and keep identity semantics`() {
        val agentSettings = AgentSettings<Unit> {
            instructions("answer tersely")
            activeTools(listOf("search"))
            maxRetries(0)
        }
        val equalShapeAgentSettings = AgentSettings<Unit> {
            instructions("answer tersely")
            activeTools(listOf("search"))
            maxRetries(0)
        }
        val stepSettings = StepSettings<String> {
            system("step system")
            experimental_context("augmented")
            presencePenalty(0.4f)
        }
        val equalShapeStepSettings = StepSettings<String> {
            system("step system")
            experimental_context("augmented")
            presencePenalty(0.4f)
        }

        assertEquals("answer tersely", agentSettings.instructions)
        assertEquals(listOf("search"), agentSettings.activeTools)
        assertEquals(0, agentSettings.maxRetries)
        assertNotEquals(equalShapeAgentSettings, agentSettings)
        assertEquals("step system", stepSettings.system)
        assertEquals("augmented", stepSettings.experimental_context)
        assertEquals(0.4f, stepSettings.presencePenalty)
        assertNotEquals(equalShapeStepSettings, stepSettings)
    }

    @Test
    fun `flagship settings builders reject negative retry counts`() {
        assertFailsWith<IllegalArgumentException> {
            CallSettings { maxRetries(-1) }
        }
        assertFailsWith<IllegalArgumentException> {
            CallConfig { maxRetries(-1) }
        }
        assertFailsWith<IllegalArgumentException> {
            AgentSettings<Unit> { maxRetries(-1) }
        }
        assertFailsWith<IllegalArgumentException> {
            StepSettings<Unit> { maxRetries(-1) }
        }
    }
}
