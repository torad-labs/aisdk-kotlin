package ai.torad.aisdk

import ai.torad.aisdk.codemod.applyAiSdkCodemods
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodemodParityTest {
    @Test
    fun `codemods rewrite stream helper names framework imports and v5 useChat input helpers`() {
        val source = """
            import { useChat } from '@ai-sdk/react'
            const { input, handleInputChange, handleSubmit, messages } = useChat()
            return result.toDataStreamResponse()
            pipeDataStreamToResponse(stream, response)
        """.trimIndent()

        val result = applyAiSdkCodemods(source)

        assertTrue(result.changed)
        assertTrue("replace-datastream-to-uimessagestream" in result.appliedRules)
        assertTrue("replace-usechat-input-with-state" in result.appliedRules)
        assertTrue("rewrite-framework-imports" in result.appliedRules)
        assertTrue("ai.torad.aisdk.react" in result.output)
        assertTrue("toUIMessageStreamResponse" in result.output)
        assertTrue("pipeUIMessageStreamToResponse" in result.output)
        assertTrue("input, handleInputChange, handleSubmit" !in result.output)
    }

    @Test
    fun `codemods report unchanged source`() {
        val source = "val stable = true"

        val result = applyAiSdkCodemods(source)

        assertEquals(source, result.output)
        assertEquals(emptyList(), result.appliedRules)
    }
}
