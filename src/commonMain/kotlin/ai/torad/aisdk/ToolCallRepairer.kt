package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/**
 * Decode-and-repair collaborator for [ToolLoopAgent]. Owns the
 * decode → optional-repair → retry pipeline so the agent loop sees a clean
 * [resolveCall] abstraction without inline repair recursion.
 */
internal class ToolCallRepairer<TContext>(
    private val repairFunction: ToolCallRepairFunction<TContext>?,
    private val tools: ToolSet<TContext>,
) {
    @Suppress("UNCHECKED_CAST")
    fun decodeInput(tool: Tool<*, *, *>, input: JsonElement): Any? {
        val ser = tool.inputSerializer as KSerializer<Any?>
        return WireDecoder.decode(ser, input, provider = "tool", operation = "${tool.name} input")
    }

    /**
     * Resolve a call's input: plain decode, then — on failure — a SINGLE repair
     * attempt via [repairFunction]. Returns (tool, typedInput, wasRepaired); throws
     * a typed [AgentError] when the input cannot be decoded even after repair. A
     * repaired call may re-route to a different tool, so the returned tool is not
     * necessarily [toolDef]. This is the ONE place a tool call is decoded/repaired
     * — both the approval-categorization pass and [ToolLoopAgent.executeTool] consume
     * its result, so repair runs at most once per call.
     */
    suspend fun resolveCall(
        toolDef: Tool<*, *, TContext>,
        call: ContentPart.ToolCall,
        messages: List<ModelMessage>,
    ): Triple<Tool<*, *, TContext>, Any?, Boolean> {
        val plainError: Throwable = try {
            return Triple(toolDef, decodeInput(toolDef, call.input), false)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            CancellationExceptions.asCancellationExceptionOrNull(e)?.let { throw it }
            e
        }
        tryRepair(toolDef, call, plainError, messages)?.let { (repairedTool, repairedInput) ->
            return Triple(repairedTool, repairedInput, true)
        }
        throw if (repairFunction != null) {
            AgentError.ToolCallRepairFailed(call.toolName, originalError = plainError, repairError = null)
        } else {
            AgentError.InvalidToolInput(call.toolName, call.input.toString(), plainError)
        }
    }

    /** Single-attempt repair pass — null if no repair fn, repair gave up, the
     *  rerouted tool isn't in the set, or the repaired input still doesn't decode.
     *  Caller throws the original exception when this returns null. */
    private suspend fun tryRepair(
        originalToolDef: Tool<*, *, TContext>,
        call: ContentPart.ToolCall,
        originalError: Throwable,
        messages: List<ModelMessage>,
    ): Pair<Tool<*, *, TContext>, Any?>? {
        val repair = repairFunction ?: return null
        val corrected = try {
            repair.invoke(call, originalError, messages, tools)
        } catch (ce: CancellationException) {
            throw ce
        } catch (@Suppress("TooGenericExceptionCaught") repairError: Throwable) {
            throw AgentError.ToolCallRepairFailed(call.toolName, originalError = originalError, repairError = repairError)
        } ?: return null
        val toolDef = if (corrected.toolName != call.toolName) {
            tools.find(corrected.toolName)
        } else {
            originalToolDef
        }
        // toolDef == null (repair re-routed to a tool not in the set) is the ONLY null return —
        // the caller turns that into ToolCallRepairFailed(repairError = null). When the repaired
        // input itself fails to decode, surface that decode error as repairError instead of
        // swallowing it via getOrNull() (which mislabels it "repair: returned null").
        return toolDef?.let { def ->
            try {
                def to decodeInput(def, corrected.input)
            } catch (ce: CancellationException) {
                throw ce
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                throw AgentError.ToolCallRepairFailed(call.toolName, originalError = originalError, repairError = t)
            }
        }
    }
}
