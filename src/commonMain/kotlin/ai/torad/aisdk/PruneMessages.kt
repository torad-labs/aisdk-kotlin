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
                    ModelMessage(
                        role = message.role,
                        content = message.content.filterNot { it is ContentPart.Reasoning },
                    )
                }
            }
        }

    private fun pruneToolCalls(messages: List<ModelMessage>, rule: PruneToolCallRule): List<ModelMessage> {
        val keepLastMessagesCount = when (val type = rule.type) {
            PruneToolCallRuleType.All -> null
            PruneToolCallRuleType.BeforeLastMessage -> 1
            is PruneToolCallRuleType.BeforeLastMessages -> type.count
        }
        val retentionIndex = buildRetentionIndex(messages)
        val keptToolOccurrences = mutableSetOf<ToolOccurrenceKey>()
        val keptApprovalOccurrences = mutableSetOf<ApprovalOccurrenceKey>()
        val firstKeptMessageIndex = firstKeptMessageIndex(messages, keepLastMessagesCount)
        if (firstKeptMessageIndex != null) {
            collectKeptOccurrences(
                messages = messages,
                firstKeptMessageIndex = firstKeptMessageIndex,
                retentionIndex = retentionIndex,
                keptToolOccurrences = keptToolOccurrences,
                keptApprovalOccurrences = keptApprovalOccurrences,
            )
        }
        return messages.mapIndexed { index, message ->
            if (shouldKeepMessageUnchanged(message, index, firstKeptMessageIndex)) {
                message
            } else {
                ModelMessage(
                    role = message.role,
                    content = message.content.filterIndexed { partIndex, part ->
                        val position = PartPosition(index, partIndex)
                        val approvalOccurrence = retentionIndex.approvalOccurrences[position]
                        when (part) {
                            is ContentPart.ToolCall -> keepToolPart(
                                toolName = part.toolName,
                                keepAssociation = retentionIndex.toolOccurrences[position] in keptToolOccurrences,
                                tools = rule.tools,
                            )
                            is ContentPart.ToolResult -> keepToolPart(
                                toolName = part.toolName,
                                keepAssociation = retentionIndex.toolOccurrences[position] in keptToolOccurrences,
                                tools = rule.tools,
                            )
                            is ContentPart.ToolApprovalRequest -> keepToolPart(
                                toolName = part.toolName,
                                keepAssociation = approvalOccurrence in keptApprovalOccurrences,
                                tools = rule.tools,
                            )
                            is ContentPart.ToolApprovalResponse -> keepToolPart(
                                toolName = retentionIndex.approvalToolNames[approvalOccurrence],
                                keepAssociation = approvalOccurrence in keptApprovalOccurrences,
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

    private fun shouldKeepMessageUnchanged(
        message: ModelMessage,
        index: Int,
        firstKeptMessageIndex: Int?,
    ): Boolean =
        (message.role != MessageRole.Assistant && message.role != MessageRole.Tool) ||
            (firstKeptMessageIndex != null && index >= firstKeptMessageIndex)

    private fun keepToolPart(
        toolName: String?,
        keepAssociation: Boolean,
        tools: Set<String>?,
    ): Boolean =
        keepAssociation || (tools != null && toolName !in tools)

    private data class PartPosition(val messageIndex: Int, val partIndex: Int)

    private data class ToolOccurrenceKey(val toolCallId: String, val ordinal: Int)

    private data class ApprovalOccurrenceKey(val approvalId: String, val ordinal: Int)

    private data class RetentionIndex(
        val toolOccurrences: Map<PartPosition, ToolOccurrenceKey>,
        val approvalOccurrences: Map<PartPosition, ApprovalOccurrenceKey>,
        val approvalToolNames: Map<ApprovalOccurrenceKey, String>,
    )

    private fun firstKeptMessageIndex(messages: List<ModelMessage>, keepLastMessagesCount: Int?): Int? =
        keepLastMessagesCount?.let { count -> (messages.size - count).coerceAtLeast(0) }

    private fun collectKeptOccurrences(
        messages: List<ModelMessage>,
        firstKeptMessageIndex: Int,
        retentionIndex: RetentionIndex,
        keptToolOccurrences: MutableSet<ToolOccurrenceKey>,
        keptApprovalOccurrences: MutableSet<ApprovalOccurrenceKey>,
    ) {
        for (messageIndex in firstKeptMessageIndex..messages.lastIndex) {
            messages[messageIndex].content.forEachIndexed { partIndex, _ ->
                val position = PartPosition(messageIndex, partIndex)
                retentionIndex.toolOccurrences[position]?.let { keptToolOccurrences += it }
                retentionIndex.approvalOccurrences[position]?.let { keptApprovalOccurrences += it }
            }
        }
    }

    private fun buildRetentionIndex(messages: List<ModelMessage>): RetentionIndex {
        val toolOccurrences = mutableMapOf<PartPosition, ToolOccurrenceKey>()
        val approvalOccurrences = mutableMapOf<PartPosition, ApprovalOccurrenceKey>()
        val approvalToolNames = mutableMapOf<ApprovalOccurrenceKey, String>()
        val state = RetentionIndexBuilderState()
        messages.forEachIndexed { messageIndex, message ->
            message.content.forEachIndexed { partIndex, part ->
                val position = PartPosition(messageIndex, partIndex)
                when (part) {
                    is ContentPart.ToolCall -> toolOccurrences[position] = state.openToolCall(part.toolCallId)
                    is ContentPart.ToolResult -> toolOccurrences[position] = state.resolveToolResult(part.toolCallId)
                    is ContentPart.ToolApprovalRequest -> {
                        val key = state.openApproval(part.approvalId ?: part.toolCallId)
                        approvalOccurrences[position] = key
                        approvalToolNames[key] = part.toolName
                    }
                    is ContentPart.ToolApprovalResponse ->
                        approvalOccurrences[position] = state.resolveApproval(part.approvalId ?: part.toolCallId)
                    is ContentPart.Text,
                    is ContentPart.Reasoning,
                    is ContentPart.Source,
                    is ContentPart.File,
                    is ContentPart.Image,
                    -> Unit
                }
            }
        }
        return RetentionIndex(toolOccurrences, approvalOccurrences, approvalToolNames)
    }

    private class RetentionIndexBuilderState {
        private val toolOrdinalById = mutableMapOf<String, Int>()
        private val unmatchedToolResultOrdinalById = mutableMapOf<String, Int>()
        private val pendingToolOccurrencesById = mutableMapOf<String, MutableList<ToolOccurrenceKey>>()
        private val approvalOrdinalById = mutableMapOf<String, Int>()
        private val unmatchedApprovalResponseOrdinalById = mutableMapOf<String, Int>()
        private val pendingApprovalOccurrencesById = mutableMapOf<String, MutableList<ApprovalOccurrenceKey>>()

        fun openToolCall(toolCallId: String): ToolOccurrenceKey {
            val key = ToolOccurrenceKey(toolCallId, nextOrdinal(toolOrdinalById, toolCallId))
            pendingToolOccurrencesById.getOrPut(toolCallId) { mutableListOf() } += key
            return key
        }

        fun resolveToolResult(toolCallId: String): ToolOccurrenceKey =
            popFirst(pendingToolOccurrencesById, toolCallId)
                ?: ToolOccurrenceKey(toolCallId, -1 - nextOrdinal(unmatchedToolResultOrdinalById, toolCallId))

        fun openApproval(approvalId: String): ApprovalOccurrenceKey {
            val key = ApprovalOccurrenceKey(approvalId, nextOrdinal(approvalOrdinalById, approvalId))
            pendingApprovalOccurrencesById.getOrPut(approvalId) { mutableListOf() } += key
            return key
        }

        fun resolveApproval(approvalId: String): ApprovalOccurrenceKey =
            popFirst(pendingApprovalOccurrencesById, approvalId)
                ?: ApprovalOccurrenceKey(approvalId, -1 - nextOrdinal(unmatchedApprovalResponseOrdinalById, approvalId))
    }

    private fun nextOrdinal(ordinals: MutableMap<String, Int>, id: String): Int {
        val ordinal = ordinals[id] ?: 0
        ordinals[id] = ordinal + 1
        return ordinal
    }

    private fun <T> popFirst(pending: MutableMap<String, MutableList<T>>, id: String): T? {
        val values = pending[id] ?: return null
        val value = values.removeAt(0)
        if (values.isEmpty()) pending.remove(id)
        return value
    }
}
