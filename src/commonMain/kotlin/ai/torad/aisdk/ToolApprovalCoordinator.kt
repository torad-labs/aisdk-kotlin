package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.JsonPrimitive

/**
 * Approval-gating collaborator for [ToolLoopAgent]: owns HMAC-signature
 * verification, fail-closed schema re-validation, approval-response processing,
 * and denied/approved tool output application. The agent loop delegates the
 * entire approval protocol here so it can focus on step orchestration.
 */
internal class ToolApprovalCoordinator<TContext>(
    private val approvalSecret: ByteArray?,
    private val repairer: ToolCallRepairer<TContext>,
) {
    /**
     * If the most recent tool message contains [ContentPart.ToolApprovalResponse]
     * parts, execute (or deny) the matching tool calls from the prior assistant
     * message before the next model call. [executeApprovedTool] is the agent loop's
     * own `executeTool` — passed in so the coordinator stays testable without
     * wiring the full loop.
     */
    @Suppress("LongParameterList")
    suspend fun applyToolApprovalResponses(
        out: FlowCollector<StreamEvent>,
        messages: MutableList<ModelMessage>,
        tools: ToolSet<TContext>,
        options: TContext?,
        abortSignal: AbortSignal,
        hooks: AgentCallHooks?,
        feed: TelemetryFeed?,
        executeApprovedTool: suspend (
            out: FlowCollector<StreamEvent>,
            toolDef: Tool<*, *, TContext>,
            call: ContentPart.ToolCall,
            options: TContext?,
            abortSignal: AbortSignal,
            messages: List<ModelMessage>,
            hooks: AgentCallHooks?,
            feed: TelemetryFeed?,
            preResolved: Pair<Tool<*, *, TContext>, Any?>?,
        ) -> ToolExecutionResult,
    ): Unit? {
        val lastToolMsg = messages.lastOrNull { it.role == MessageRole.Tool } ?: return null
        val approvals = lastToolMsg.content.filterIsInstance<ContentPart.ToolApprovalResponse>()
        if (approvals.isEmpty()) return null

        val priorAssistantMsg = messages.findLast { it.role == MessageRole.Assistant } ?: return null
        val priorToolCalls = priorAssistantMsg.content.filterIsInstance<ContentPart.ToolCall>()
        if (priorToolCalls.isEmpty()) return null

        // Correlate response.approvalId -> request.toolCallId, matching upstream.
        val approvalRequests = priorAssistantMsg.content.filterIsInstance<ContentPart.ToolApprovalRequest>()
        val approvalIdToCallId = approvalRequests
            .mapNotNull { req -> req.approvalId?.let { it to req.toolCallId } }
            .toMap()
        // The issued request per EFFECTIVE approval id — carries the signature the
        // v6.0.202 fail-closed re-validation checks before an approved tool executes.
        val requestsByApprovalId = approvalRequests.associateBy { it.approvalId ?: it.toolCallId }
        // Idempotency: a call already answered by a tool result must NOT re-execute.
        val alreadyResolved = resolvedToolCallIds(messages)

        // TOOL-003: index calls by toolCallId so duplicate approvals for the same id
        // are each matched deterministically to a distinct call occurrence.
        val remainingCalls = priorToolCalls.toMutableList()
        for (approval in approvals) {
            val targetCallId = approval.approvalId?.let { approvalIdToCallId[it] } ?: approval.toolCallId
            if (targetCallId in alreadyResolved) continue
            val callIndex = remainingCalls.indexOfFirst { it.toolCallId == targetCallId }
            if (callIndex == -1) continue
            val matchingCall = remainingCalls.removeAt(callIndex)
            alreadyResolved += matchingCall.toolCallId
            if (!approval.approved) {
                val denialMsg = approval.reason ?: "user denied tool execution"
                applyDenied(out, matchingCall, approval.approvalId ?: matchingCall.toolCallId, denialMsg, messages)
                continue
            }
            // v6.0.202 fail-closed re-validation: HMAC signature → input schema → fresh needsApproval.
            val effectiveApprovalKey = approval.approvalId ?: matchingCall.toolCallId
            verifySignature(requestsByApprovalId[effectiveApprovalKey], effectiveApprovalKey, matchingCall)
            val toolDef = tools.find(matchingCall.toolName)
            val decodedInput: Any? = if (toolDef != null) revalidateInput(toolDef, matchingCall) else null
            val stillNeedsApproval = toolDef != null &&
                callNeedsApproval(toolDef, matchingCall, decodedInput, options, messages.toList())
            if (toolDef == null || !stillNeedsApproval) {
                applyDenied(
                    out, matchingCall, effectiveApprovalKey,
                    approval.reason ?: "Tool \"${matchingCall.toolName}\" does not require approval",
                    messages,
                )
                continue
            }
            val approvedResult = executeApprovedTool(
                out, toolDef, matchingCall, options, abortSignal,
                messages.toList(), hooks, feed, toolDef to decodedInput,
            )
            when (approvedResult) {
                is ToolExecutionResult.Success -> applyApprovedSuccess(matchingCall, approvedResult, messages, out)
                is ToolExecutionResult.Failure ->
                    emitToolError(out, matchingCall.toolCallId, matchingCall.toolName, approvedResult.error, messages)
            }
        }
        return Unit
    }

    /**
     * Evaluate a tool's approval gate on its already-resolved (decoded/repaired)
     * input. Decode + repair happen once up front via [ToolCallRepairer.resolveCall];
     * this only runs the predicate.
     */
    suspend fun callNeedsApproval(
        toolDef: Tool<*, *, TContext>,
        call: ContentPart.ToolCall,
        typedInput: Any?,
        options: TContext?,
        messages: List<ModelMessage>,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        val gated = toolDef as Tool<Any?, Any?, TContext>
        val predicateOptions = ToolPredicateOptions(
            toolCallId = call.toolCallId,
            messages = messages,
            experimental_context = options,
        )
        return try {
            gated.needsApproval(typedInput, predicateOptions)
        } catch (ce: CancellationException) {
            throw ce
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            throw AgentError.ToolExecution(call.toolName, call.toolCallId, t)
        }
    }

    /**
     * Fail-closed HMAC check for one replayed approval (v6.0.202): with no
     * configured secret this is a no-op; with one, a missing or invalid signature
     * throws [AgentError.InvalidToolApprovalSignature].
     */
    private fun verifySignature(
        request: ContentPart.ToolApprovalRequest?,
        approvalKey: String,
        call: ContentPart.ToolCall,
    ) {
        val secret = approvalSecret ?: return
        val signature = request?.signature
            ?: throw AgentError.InvalidToolApprovalSignature(approvalKey, call.toolCallId, "missing signature")
        val valid = ToolApprovalSignature.verifyToolApprovalSignature(
            secret = secret,
            signature = signature,
            approvalId = approvalKey,
            toolCallId = call.toolCallId,
            toolName = call.toolName,
            input = call.input,
        )
        if (!valid) {
            throw AgentError.InvalidToolApprovalSignature(approvalKey, call.toolCallId, "invalid signature")
        }
    }

    /** Fail-closed schema re-validation of a replayed approval's input (v6.0.202):
     *  the client supplied this history, so the input is re-decoded against the
     *  tool's schema BEFORE execution; a mismatch throws [AgentError.InvalidToolInput]. */
    private fun revalidateInput(toolDef: Tool<*, *, TContext>, call: ContentPart.ToolCall): Any? {
        return try {
            repairer.decodeInput(toolDef, call.input)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw t as? AgentError ?: AgentError.InvalidToolInput(call.toolName, call.input.toString(), t)
        }
    }

    /** Tool-call ids already answered by a tool result anywhere in the message log. */
    private fun resolvedToolCallIds(messages: List<ModelMessage>): MutableSet<String> =
        messages.asSequence()
            .filter { it.role == MessageRole.Tool }
            .flatMap { it.content.asSequence() }
            .filterIsInstance<ContentPart.ToolResult>()
            .map { it.toolCallId }
            .toMutableSet()

    /** Approval-resume success: emit the ToolResult event and append to the message log. */
    private suspend fun applyApprovedSuccess(
        call: ContentPart.ToolCall,
        result: ToolExecutionResult.Success,
        messages: MutableList<ModelMessage>,
        out: FlowCollector<StreamEvent>,
    ) {
        out.emit(
            StreamEvent.ToolResult(
                toolCallId = call.toolCallId,
                toolName = call.toolName,
                outputJson = result.outputJson,
                output = result.output,
                modelOutput = result.modelOutput,
                isError = result.isError,
            ),
        )
        messages.add(
            ModelMessage(
                MessageRole.Tool,
                listOf(
                    ContentPart.ToolResult(
                        toolCallId = call.toolCallId,
                        toolName = call.toolName,
                        output = result.outputJson,
                        isError = result.isError,
                        modelVisible = result.modelVisible,
                    ),
                ),
            ),
        )
    }

    /** Single source for tool-error emission within the approval flow: emit a typed
     *  [StreamEvent.ToolError] AND append the matching tool message so the model sees the failure. */
    private suspend fun emitToolError(
        out: FlowCollector<StreamEvent>,
        toolCallId: String,
        toolName: String,
        error: AgentError,
        messages: MutableList<ModelMessage>,
    ) {
        val msg = error.message ?: "tool failed"
        out.emit(StreamEvent.ToolError(toolCallId, toolName, msg, error = error))
        messages.add(ToolMessage(toolCallId, toolName, JsonPrimitive(msg)))
    }

    /** Approval denials: emit the denial + ToolResult events and append to the message log. */
    private suspend fun applyDenied(
        out: FlowCollector<StreamEvent>,
        call: ContentPart.ToolCall,
        approvalId: String,
        reason: String,
        messages: MutableList<ModelMessage>,
    ) {
        val output = ToolResultOutput.ExecutionDenied(reason)
        val outputJson = with(ToolResultOutputs) { output.toJsonElement() }
        out.emit(StreamEvent.ToolOutputDenied(call.toolCallId, call.toolName, approvalId, reason))
        out.emit(
            StreamEvent.ToolResult(
                toolCallId = call.toolCallId,
                toolName = call.toolName,
                outputJson = outputJson,
                output = output,
                modelOutput = output,
                isError = true,
            ),
        )
        messages.add(
            ModelMessage(
                MessageRole.Tool,
                listOf(
                    ContentPart.ToolResult(
                        toolCallId = call.toolCallId,
                        toolName = call.toolName,
                        output = outputJson,
                        isError = true,
                        modelVisible = outputJson,
                    ),
                ),
            ),
        )
    }
}
