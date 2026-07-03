data class ToolCall(val toolCallId: String)

class CollapsedToolCalls(
    val calls: List<ToolCall>,
) {
    val byId: Map<String, ToolCall> = calls.associateBy { it.toolCallId }
}
