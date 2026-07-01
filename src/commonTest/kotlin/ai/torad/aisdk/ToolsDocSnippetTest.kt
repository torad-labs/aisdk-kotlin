package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Compiles and executes the tools wiki snippets against the real tool APIs. */
class ToolsDocSnippetTest {
    @Serializable
    data class AppContext(val canCreateTickets: Boolean = true)

    @Serializable
    data class SearchInput(val query: String, val limit: Int = 5)

    @Serializable
    data class SearchHit(val title: String, val url: String, val snippet: String)

    @Serializable
    data class CreateTicket(val priority: String)

    @Serializable
    data class Ticket(val id: String)

    @Serializable
    data class IssueInput(val id: String)

    @Serializable
    data class IssueSnapshot(val body: String)

    @Serializable
    data class DraftInput(val subject: String)

    @Serializable
    data class Draft(val body: String)

    private class SupportAgent(model: LanguageModel, tools: ToolSet<AppContext>) :
        ToolLoopAgent<AppContext, String>(
            model = model,
            instructions = "Use documentation search before answering SDK questions.",
            tools = tools,
            stopWhen = AnyOf(StepCountIs(8), RepeatedToolCallLoop(3)),
        )

    @Test
    fun `tools wiki typed tool and agent snippets compile and run`() = runTest {
        val searchDocs = Tool<SearchInput, List<SearchHit>, AppContext>(
            name = "searchDocs",
            description = "Search product documentation by query.",
            inputExamples = listOf("""{"query":"TextGenerator streamResult adapters","limit":3}"""),
        ) { input ->
            listOf(SearchHit("Streams", "https://docs.example/streams", "Use streamResult for adapters: ${input.query}"))
        }
        val createTicket = Tool<CreateTicket, Ticket, AppContext>(
            name = "createTicket",
            description = "Create a customer support ticket.",
        ) { input ->
            Ticket(id = "ticket-${input.priority}")
        }
        val supportAgent = SupportAgent(
            MockLanguageModelToolThenText(
                toolName = "searchDocs",
                toolInput = buildJsonObject {
                    put("query", JsonPrimitive("persist chat messages"))
                    put("limit", JsonPrimitive(3))
                },
                finalText = "Persist chat messages in your message log.",
            ),
            ToolSet(searchDocs, createTicket),
        )

        val result = supportAgent.generate(
            prompt = "How do I persist chat messages?",
            options = AppContext(),
        ).first()

        assertEquals("Persist chat messages in your message log.", result.text)
        assertEquals(listOf("searchDocs", "createTicket"), supportAgent.tools.names())
        assertTrue(supportAgent.tools.descriptors.single { it.name == "searchDocs" }.description.contains("TextGenerator streamResult adapters"))
    }

    @Test
    fun `tools wiki approval model-output streaming dynamic and lifecycle snippets compile`() = runTest {
        val predicateOptions = ToolPredicateOptions<AppContext> {
            toolCallId("call_1")
            messages(emptyList())
            experimental_context(AppContext(canCreateTickets = false))
        }
        val createTicket = Tool<CreateTicket, Ticket, AppContext>(
            name = "createTicket",
            description = "Create a customer support ticket.",
            needsApproval = { input, options ->
                input.priority == "urgent" ||
                    options.experimental_context?.canCreateTickets != true
            },
        ) { input ->
            Ticket(id = "ticket-${input.priority}")
        }
        assertEquals(true, createTicket.needsApproval(CreateTicket("urgent"), predicateOptions))

        val searchDocs = Tool<SearchInput, List<SearchHit>, AppContext>(
            name = "searchDocs",
            description = "Search product documentation.",
            toModelOutput = { hits, _ ->
                ToolResultOutput.Text(
                    hits.joinToString("\n") { "- ${it.title}: ${it.snippet}" },
                )
            },
        ) { input ->
            listOf(SearchHit("Tools", "https://docs.example/tools", "Result for ${input.query}"))
        }
        val modelOutput = searchDocs.toModelOutput(
            listOf(SearchHit("Tools", "https://docs.example/tools", "Use tools for actions.")),
            predicateOptions,
        )
        assertEquals("- Tools: Use tools for actions.", assertIs<ToolResultOutput.Text>(modelOutput).text)

        val context = ToolExecutionContext(
            context = AppContext(),
            abortSignal = AbortSignalNever,
            stepNumber = 1,
            messages = emptyList(),
            toolCallId = "lookup_1",
        )
        val lookupIssue = StreamingTool<IssueInput, IssueSnapshot, AppContext>(
            name = "lookupIssue",
            description = "Fetch issue details and stream progress.",
        ) { input ->
            flow {
                emit(IssueSnapshot("cached ${input.id}"))
                emit(IssueSnapshot("full ${input.id}"))
            }
        }
        val lookupResults = ExecuteTool(lookupIssue, IssueInput("ISSUE-1"), context).toList()
        assertEquals("cached ISSUE-1", assertIs<ExecuteToolResult.Preliminary<IssueSnapshot>>(lookupResults[0]).output.body)
        assertEquals("full ISSUE-1", assertIs<ExecuteToolResult.Final<IssueSnapshot>>(lookupResults[1]).output.body)

        val external = DynamicTool<AppContext>(
            name = "externalAction",
            description = "Call an externally registered action.",
            inputSchemaJson = """{"type":"object"}""",
        ) { input ->
            buildJsonObject { put("echo", input.jsonObject.getValue("value")) }
        }
        val externalResult = ExecuteTool(
            external,
            buildJsonObject { put("value", JsonPrimitive("ok")) },
            context,
        ).toList().single()
        assertEquals("ok", assertIs<ExecuteToolResult.Final<JsonElement>>(externalResult).output.jsonObject
            .getValue("echo").jsonPrimitive.content)

        val providerTool = ProviderExecutedTool<JsonElement, JsonElement, AppContext>(
            name = "providerSearch",
            description = "Search from the provider side.",
            inputSerializer = JsonElement.serializer(),
            outputSerializer = JsonElement.serializer(),
        )
        val providerExecutedCustom = Tool<JsonElement, JsonElement, AppContext>(
            name = "providerCustom",
            description = "Custom provider-hosted action.",
            schemaOptions = ToolSchemaOptions {
                providerExecuted(true)
            },
        ) { input ->
            input
        }
        assertEquals(true, providerTool.providerExecuted)
        assertEquals(true, providerExecutedCustom.providerExecuted)

        val lifecycle = mutableListOf<String>()
        val draftEmail = Tool<DraftInput, Draft, AppContext>(
            name = "draftEmail",
            description = "Draft an email.",
            onInputStart = { id -> lifecycle += "start:$id" },
            onInputDelta = { id, delta -> lifecycle += "delta:$id:$delta" },
            onInputAvailable = { callId, input -> lifecycle += "available:$callId:${input.subject}" },
        ) { input ->
            Draft("Draft for ${input.subject}")
        }
        draftEmail.onInputStart("stream_1")
        draftEmail.onInputDelta("stream_1", """{"subject":"Hi"}""")
        draftEmail.onInputAvailable("call_1", DraftInput("Hi"))
        assertEquals(
            listOf("start:stream_1", """delta:stream_1:{"subject":"Hi"}""", "available:call_1:Hi"),
            lifecycle,
        )
    }
}
