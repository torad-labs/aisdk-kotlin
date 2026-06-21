package ai.torad.aisdk.middleware

import ai.torad.aisdk.LanguageModelMiddleware
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.LanguageModelTool
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.StreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * Appends per-tool input examples to the tool description. For providers
 * that don't natively accept `examples` on tool definitions (most of them
 * besides Anthropic), this lets the example pairs influence the model
 * via the description text instead.
 *
 * Mirrors v6's `addToolInputExamplesMiddleware`. Pass a map of tool name
 * → list of example input JSON strings. Examples are appended in
 * "Example: <json>" form below the existing description.
 */
public fun AddToolInputExamplesMiddleware(
    examplesByTool: Map<String, List<String>>,
): LanguageModelMiddleware = object : LanguageModelMiddleware {
    override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult =
        context.doGenerate(context.params.copy(tools = augment(context.params.tools)))

    override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> =
        context.doStream(context.params.copy(tools = augment(context.params.tools)))

    private fun augment(tools: List<LanguageModelTool>): List<LanguageModelTool> = tools.map { tool ->
        val examples = examplesByTool[tool.name] ?: return@map tool
        if (examples.isEmpty()) return@map tool
        val appendix = examples.joinToString(separator = "\n") { "Example: $it" }
        tool.copy(description = "${tool.description}\n\n$appendix")
    }
}
