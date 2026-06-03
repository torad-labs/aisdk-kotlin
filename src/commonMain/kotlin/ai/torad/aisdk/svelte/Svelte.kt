package ai.torad.aisdk.svelte

import ai.torad.aisdk.StructuredObjectOptions

typealias Chat = ai.torad.aisdk.ui.Chat
typealias UIMessage = ai.torad.aisdk.ui.UIMessage
typealias Completion = ai.torad.aisdk.Completion
typealias CompletionOptions = ai.torad.aisdk.UseCompletionOptions
typealias Experimental_StructuredObject<RESULT, INPUT> = ai.torad.aisdk.StructuredObject<RESULT, INPUT>
typealias Experimental_StructuredObjectOptions<RESULT, INPUT> = StructuredObjectOptions<RESULT, INPUT>

class AIContext(
    val completions: MutableMap<String, Completion> = linkedMapOf(),
    val structuredObjects: MutableMap<String, ai.torad.aisdk.StructuredObject<*, *>> = linkedMapOf(),
)

fun createAIContext(): AIContext = AIContext()
