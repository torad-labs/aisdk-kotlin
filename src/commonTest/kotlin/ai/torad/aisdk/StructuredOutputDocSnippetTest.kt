@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Compiles and executes the structured-output wiki snippets. */
class StructuredOutputDocSnippetTest {
    @Serializable
    data class Recipe(
        val name: String,
        val ingredients: List<String>,
        val steps: List<String>,
    )

    @Serializable
    data class Finding(val title: String, val severity: String)

    @Serializable
    data class ChecklistItem(val title: String, val done: Boolean)

    @Test
    fun `structured output variants compile and decode real values`() = runTest {
        val recipe = TextGenerator(
            MockLanguageModelTextOnly(
                """
                {"name":"Soup","ingredients":["water"],"steps":["heat"]}
                """.trimIndent(),
            ),
        ).generate(
            GenerationInput.Prompt("Generate a simple soup recipe."),
            Output.obj(serializer<Recipe>(), name = "Recipe"),
        ).first().output

        val findings = TextGenerator(
            MockLanguageModelTextOnly("""[{"title":"Latency","severity":"high"}]"""),
        ).generate(
            GenerationInput.Prompt("List likely issues in this bug report."),
            Output.array(serializer<Finding>(), name = "FindingList"),
        ).first().output

        val priority = TextGenerator(MockLanguageModelTextOnly(""""high"""")).generate(
            GenerationInput.Prompt("Classify this ticket: production checkout is down."),
            Output.choice("low", "medium", "high", name = "Priority"),
        ).first().output

        val json = TextGenerator(MockLanguageModelTextOnly("""{"summary":"checkout down"}""")).generate(
            GenerationInput.Prompt("Return a small JSON object with the issue summary."),
            Output.json(name = "IssueSummary"),
        ).first().output

        assertEquals(Recipe("Soup", listOf("water"), listOf("heat")), recipe)
        assertEquals(listOf(Finding("Latency", "high")), findings)
        assertEquals("high", priority)
        assertEquals("checkout down", json.jsonObject.getValue("summary").jsonPrimitive.content)
    }

    @Test
    fun `structured object generator snippet streams to a final typed value`() = runTest {
        val schema = Schemas.jsonSchema(
            schema = Output.array(serializer<ChecklistItem>()).schema,
            validate = { element ->
                aiSdkOutputJson.decodeFromJsonElement(
                    ListSerializer(serializer<ChecklistItem>()),
                    element,
                )
            },
        )

        val phases = StructuredObjectGenerator(
            model = streamingModel(
                """[{"title":"Review docs",""",
                """"done":true}]""",
            ),
            schema = schema,
        ).stream(GenerationInput.Prompt("Create a release checklist.")).toList()

        val done = phases.last()
        assertIs<StructuredObjectPhase.Done<List<ChecklistItem>>>(done)
        assertEquals(listOf(ChecklistItem("Review docs", true)), done.value)
        assertNull(done.error)
    }

    private fun streamingModel(vararg deltas: String): LanguageModel = object : LanguageModel {
        override val modelId: String = "test/structured-output-doc"

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
            LanguageModelResult(
                text = deltas.joinToString(""),
                finishReason = FinishReason.Stop,
                usage = Usage(),
            )

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            emit(StreamEvent.TextStart("text"))
            deltas.forEach { emit(StreamEvent.TextDelta("text", it)) }
            emit(StreamEvent.TextEnd("text"))
            emit(StreamEvent.Finish(totalSteps = 1, finishReason = FinishReason.Stop, usage = Usage()))
        }
    }
}
