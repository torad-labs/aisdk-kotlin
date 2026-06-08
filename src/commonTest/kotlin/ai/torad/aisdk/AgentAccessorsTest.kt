package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Validates Phase 4F #33 + #40 — surface accessors on
 * [Agent] (`id`, `version`, `tools`) and [LanguageModel] (`provider`,
 * `supportedUrls`). Per historical parity gaps #33 + #40.
 *
 * These let consumers inspect the agent without casting to a concrete
 * [ToolLoopAgent] and let routing layers make provider-aware decisions
 * without parsing `modelId`.
 */
class AgentAccessorsTest {

    @Test
    fun `given a ToolLoopAgent when accessed via the Agent interface then tools accessor exposes the toolset`() {
        // GIVEN
        val tools = toolSetOf<Unit>()
        val agent: Agent<Unit, String> = ToolLoopAgent(
            model = MockLanguageModel(responses = emptyList()),
            instructions = "",
            tools = tools,
        )

        // WHEN/THEN — accessor exposes the same toolset reference.
        assertEquals(tools, agent.tools)
    }

    @Test
    fun `given a default-id agent when read then id is agent and version is null`() {
        // GIVEN — no explicit overrides.
        val agent: Agent<Unit, String> = ToolLoopAgent(
            model = MockLanguageModel(responses = emptyList()),
            instructions = "",
            tools = toolSetOf(),
        )

        // WHEN/THEN
        assertEquals("agent", agent.id, "interface default is 'agent'")
        assertNull(agent.version, "interface default is null version")
    }

    @Test
    fun `given a MockLanguageModel when read then provider is mock and supportedUrls is empty`() {
        // GIVEN
        val model: LanguageModel = MockLanguageModel(responses = emptyList())

        // WHEN/THEN
        assertEquals("mock", model.provider)
        assertTrue(model.supportedUrls.isEmpty(), "Mock provider declares no URL support")
    }

    @Test
    fun `given a custom provider tag when MockLanguageModel is constructed then provider tag flows through`() {
        // GIVEN
        val model: LanguageModel = MockLanguageModel(
            modelId = "mock/special",
            provider = "synthetic",
            responses = emptyList(),
        )

        // WHEN/THEN — routing layers can read this without parsing modelId.
        assertEquals("synthetic", model.provider)
        assertEquals("mock/special", model.modelId)
    }
}
