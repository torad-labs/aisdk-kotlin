data class ToolCall(val toolCallId: String)

class GroupedToolCalls(
    val calls: List<ToolCall>,
) {
    val byId: Map<String, List<ToolCall>> = calls.groupBy { it.toolCallId }
}
