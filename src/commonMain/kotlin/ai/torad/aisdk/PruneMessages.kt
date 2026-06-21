package ai.torad.aisdk

public enum class PruneReasoning {
    All,
    BeforeLastMessage,
    None,
}

public enum class PruneEmptyMessages {
    Keep,
    Remove,
}

public sealed class PruneToolCalls {
    public data object None : PruneToolCalls()
    public data object All : PruneToolCalls()
    public data object BeforeLastMessage : PruneToolCalls()
    public data class BeforeLastMessages(val count: Int) : PruneToolCalls()
    public data class Rules(val rules: List<PruneToolCallRule>) : PruneToolCalls()
}

public data class PruneToolCallRule(
    val type: PruneToolCallRuleType,
    val tools: Set<String>? = null,
)

public sealed class PruneToolCallRuleType {
    public data object All : PruneToolCallRuleType()
    public data object BeforeLastMessage : PruneToolCallRuleType()
    public data class BeforeLastMessages(val count: Int) : PruneToolCallRuleType()
}

public object MessagePruning {
    public fun pruneMessages(
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
                    is ContentPart.Text,
                    is ContentPart.Reasoning,
                    is ContentPart.Source,
                    is ContentPart.File,
                    is ContentPart.Image,
                    -> Unit
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
                            is ContentPart.Text,
                            is ContentPart.Reasoning,
                            is ContentPart.Source,
                            is ContentPart.File,
                            is ContentPart.Image,
                            -> true
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
}
