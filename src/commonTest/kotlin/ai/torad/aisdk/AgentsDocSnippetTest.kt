package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Compiles and executes the agents wiki snippets against the real agent APIs. */
class AgentsDocSnippetTest {
    @Serializable
    data class AppContext(val workspaceId: String? = null)

    @Serializable
    data class SearchInput(val query: String)

    @Serializable
    data class SearchResult(val title: String)

    @Serializable
    data class Query(val q: String)

    @Serializable
    data class LookupResult(val summary: String)

    @Serializable
    data class SendInput(val recipientId: String, val text: String)

    @Serializable
    data class SendResult(val sent: Boolean)

    @Serializable
    data class ResearchInput(val prompt: String)

    private class SupportAgent(
        model: LanguageModel,
        tools: ToolSet<AppContext>,
        prepareCall: (suspend PrepareCallScope<AppContext>.() -> AgentSettings<AppContext>)? = null,
        prepareStep: (suspend PrepareStepScope<AppContext>.() -> StepSettings<AppContext>)? = null,
    ) : ToolLoopAgent<AppContext, String>(
        model = model,
        instructions = "Answer using project tools when needed.",
        tools = tools,
        stopWhen = AnyOf(
            StepCountIs(8),
            HasToolCall("finalizeAnswer"),
        ),
        prepareCall = prepareCall,
        prepareStep = prepareStep,
        callOptionsSchema = serializer<AppContext>(),
    )

    @Test
    fun `agents wiki tool-loop and preparation snippets compile and run`() = runTest {
        val searchDocs = Tool<SearchInput, List<SearchResult>, AppContext>(
            name = "searchDocs",
            description = "Search product documentation.",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { input ->
            listOf(SearchResult("Result for ${input.query}"))
        }
        val createTicket = Tool<SendInput, SendResult, AppContext>(
            name = "createTicket",
            description = "Create a ticket.",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            SendResult(sent = true)
        }
        val cheapModel = MockLanguageModelTextOnly("Use the cheap model for the first step.")
        val strongModel = MockLanguageModelTextOnly("Use the strong model later.")
        val agent = SupportAgent(
            model = MockLanguageModelTextOnly("fallback"),
            tools = ToolSet(searchDocs, createTicket),
            prepareCall = {
                AgentSettings {
                    instructions(instructions + "\nUse workspace ${options?.workspaceId}.")
                    providerOptions(
                        ProviderOptions.ofPairs(
                            "openai" to buildJsonObject {
                                put("reasoningEffort", JsonPrimitive("medium"))
                            },
                        ),
                    )
                }
            },
            prepareStep = {
                StepSettings {
                    model(if (stepNumber == 1) cheapModel else strongModel)
                    activeTools(if (stepNumber == 1) listOf("classify") else null)
                }
            },
        )

        val result = agent.generate(prompt = "Summarize the docs.", options = AppContext("workspace-1")).first()
        val prepared = AgentSettings<AppContext> {
            instructions("Use workspace workspace-1.")
            providerOptions(
                ProviderOptions.ofPairs(
                    "openai" to buildJsonObject {
                        put("reasoningEffort", JsonPrimitive("medium"))
                    },
                ),
            )
        }

        assertEquals("Use the cheap model for the first step.", result.text)
        assertEquals(listOf("searchDocs", "createTicket"), agent.tools.names())
        assertEquals(
            "medium",
            prepared.providerOptions.toMap().getValue("openai").jsonObject
                .getValue("reasoningEffort").jsonPrimitive.content,
        )
    }

    @Test
    fun `agents wiki streaming tool snippet compiles and executes preliminary then final output`() = runTest {
        val lookup = StreamingTool<Query, LookupResult, AppContext>(
            name = "lookup",
            description = "Search records and stream progress.",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { query ->
            flow {
                emit(LookupResult("fast ${query.q}"))
                emit(LookupResult("full ${query.q}"))
            }
        }
        val results = ExecuteTool(
            lookup,
            Query("records"),
            ToolExecutionContext(
                context = AppContext("workspace-1"),
                abortSignal = AbortSignalNever,
                stepNumber = 1,
                messages = emptyList(),
                toolCallId = "lookup_1",
            ),
        ).toList()

        assertEquals("fast records", assertIs<ExecuteToolResult.Preliminary<LookupResult>>(results[0]).output.summary)
        assertEquals("full records", assertIs<ExecuteToolResult.Final<LookupResult>>(results[1]).output.summary)
    }

    @Test
    fun `agents wiki approval resume snippet compiles and runs`() = runTest {
        var sent = false
        val sendMessage = Tool<SendInput, SendResult, AppContext>(
            name = "sendMessage",
            description = "Send a message to a user.",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { input, options ->
                input.text.length > 100 || options.experimental_context?.workspaceId == null
            },
        ) { _ ->
            sent = true
            SendResult(sent = true)
        }
        val agent = SupportAgent(
            model = MockLanguageModelToolThenText(
                toolName = "sendMessage",
                toolInput = buildJsonObject {
                    put("recipientId", JsonPrimitive("user-1"))
                    put("text", JsonPrimitive("hello"))
                },
                finalText = "sent",
            ),
            tools = ToolSet(sendMessage),
        )
        val context = AppContext(workspaceId = null)
        val prompt = "Send the user a message."
        val first = agent.generate(prompt = prompt, options = context).first()

        val responses = first.pendingApprovals.map { pending ->
            ToolApprovalResponseMessage(
                toolCallId = pending.toolCallId,
                approved = true,
                approvalId = pending.approvalId,
            )
        }

        val resumed = agent.generate(
            messages = first.messages + responses,
            options = context,
        ).first()

        assertEquals(false, first.pendingApprovals.isEmpty())
        assertTrue(sent)
        assertEquals("sent", resumed.text)
        assertTrue(resumed.pendingApprovals.isEmpty())
    }

    @Test
    fun `agents wiki subagent snippet compiles and forwards context and cancellation`() = runTest {
        val researchAgent = SupportAgent(
            model = MockLanguageModelTextOnly("research complete"),
            tools = ToolSet(),
        )
        val appContext = AppContext("workspace-1")
        val researchTool = Tool<ResearchInput, String, AppContext>(
            name = "deepResearch",
            description = "Run a focused research agent.",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { input ->
            researchAgent.generate(
                prompt = input.prompt,
                options = context,
                abortSignal = abortSignal,
            ).first().text
        }

        val result = ExecuteTool(
            researchTool,
            ResearchInput("Find docs."),
            ToolExecutionContext(
                context = appContext,
                abortSignal = AbortSignalNever,
                stepNumber = 1,
                messages = emptyList(),
                toolCallId = "research_1",
            ),
        ).toList().single()

        assertEquals("research complete", assertIs<ExecuteToolResult.Final<String>>(result).output)
    }
}
