package ai.torad.aisdk

enum class PruneReasoning {
    All,
    BeforeLastMessage,
    None,
}

enum class PruneEmptyMessages {
    Keep,
    Remove,
}

sealed class PruneToolCalls {
    data object None : PruneToolCalls()
    data object All : PruneToolCalls()
    data object BeforeLastMessage : PruneToolCalls()
    data class BeforeLastMessages(val count: Int) : PruneToolCalls()
    data class Rules(val rules: List<PruneToolCallRule>) : PruneToolCalls()
}

data class PruneToolCallRule(
    val type: PruneToolCallRuleType,
    val tools: Set<String>? = null,
)

sealed class PruneToolCallRuleType {
    data object All : PruneToolCallRuleType()
    data object BeforeLastMessage : PruneToolCallRuleType()
    data class BeforeLastMessages(val count: Int) : PruneToolCallRuleType()
}

fun pruneMessages(
    messages: List<ModelMessage>,
    reasoning: PruneReasoning = PruneReasoning.None,
    toolCalls: PruneToolCalls = PruneToolCalls.None,
    emptyMessages: PruneEmptyMessages = PruneEmptyMessages.Remove,
): List<ModelMessage> {
    var pruned = pruneReasoning(messages, reasoning)
    val rules = when (toolCalls) {
        PruneToolCalls.None -> emptyList()
        PruneToolCalls.All -> listOf(PruneToolCallRule(PruneToolCallRuleType.All))
        PruneToolCalls.BeforeLastMessage -> listOf(PruneToolCallRule(PruneToolCallRuleType.BeforeLastMessage))
        is PruneToolCalls.BeforeLastMessages ->
            listOf(PruneToolCallRule(PruneToolCallRuleType.BeforeLastMessages(toolCalls.count)))
        is PruneToolCalls.Rules -> toolCalls.rules
    }
    for (rule in rules) {
        pruned = pruneToolCalls(pruned, rule)
    }
    return if (emptyMessages == PruneEmptyMessages.Remove) {
        pruned.filter { it.content.isNotEmpty() }
    } else {
        pruned
    }
}

private fun pruneReasoning(messages: List<ModelMessage>, reasoning: PruneReasoning): List<ModelMessage> =
    if (reasoning == PruneReasoning.None) {
        messages
    } else {
        messages.mapIndexed { index, message ->
            if (message.role != MessageRole.Assistant ||
                (reasoning == PruneReasoning.BeforeLastMessage && index == messages.lastIndex)
            ) {
                message
            } else {
                message.copy(content = message.content.filterNot { it is ContentPart.Reasoning })
            }
        }
    }

private fun pruneToolCalls(messages: List<ModelMessage>, rule: PruneToolCallRule): List<ModelMessage> {
    val keepLastMessagesCount = when (val type = rule.type) {
        PruneToolCallRuleType.All -> null
        PruneToolCallRuleType.BeforeLastMessage -> 1
        is PruneToolCallRuleType.BeforeLastMessages -> type.count
    }
    val keptToolCallIds = mutableSetOf<String>()
    val keptApprovalIds = mutableSetOf<String>()
    keepLastMessagesCount?.let { count ->
        messages.takeLast(count).flatMap { it.content }.forEach { part ->
            when (part) {
                is ContentPart.ToolCall -> keptToolCallIds += part.toolCallId
                is ContentPart.ToolResult -> keptToolCallIds += part.toolCallId
                is ContentPart.ToolApprovalRequest -> keptApprovalIds += part.approvalId ?: part.toolCallId
                is ContentPart.ToolApprovalResponse -> keptApprovalIds += part.approvalId ?: part.toolCallId
                else -> Unit
            }
        }
    }
    return messages.mapIndexed { index, message ->
        if ((message.role != MessageRole.Assistant && message.role != MessageRole.Tool) ||
            (keepLastMessagesCount != null && index >= messages.size - keepLastMessagesCount)
        ) {
            message
        } else {
            val toolCallIdToName = mutableMapOf<String, String>()
            val approvalIdToToolName = mutableMapOf<String, String>()
            message.copy(
                content = message.content.filter { part ->
                    when (part) {
                        is ContentPart.ToolCall -> {
                            toolCallIdToName[part.toolCallId] = part.toolName
                            keepToolPart(
                                toolName = part.toolName,
                                id = part.toolCallId,
                                keptIds = keptToolCallIds,
                                tools = rule.tools,
                            )
                        }
                        is ContentPart.ToolResult -> keepToolPart(
                            toolName = part.toolName,
                            id = part.toolCallId,
                            keptIds = keptToolCallIds,
                            tools = rule.tools,
                        )
                        is ContentPart.ToolApprovalRequest -> {
                            approvalIdToToolName[part.approvalId ?: part.toolCallId] = part.toolName
                            keepToolPart(
                                toolName = part.toolName,
                                id = part.approvalId ?: part.toolCallId,
                                keptIds = keptApprovalIds,
                                tools = rule.tools,
                            )
                        }
                        is ContentPart.ToolApprovalResponse -> keepToolPart(
                            toolName = approvalIdToToolName[part.approvalId ?: part.toolCallId],
                            id = part.approvalId ?: part.toolCallId,
                            keptIds = keptApprovalIds,
                            tools = rule.tools,
                        )
                        else -> true
                    }
                },
            )
        }
    }
}

private fun keepToolPart(
    toolName: String?,
    id: String,
    keptIds: Set<String>,
    tools: Set<String>?,
): Boolean =
    id in keptIds || (tools != null && toolName !in tools)
