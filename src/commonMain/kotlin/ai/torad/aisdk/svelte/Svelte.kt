package ai.torad.aisdk.svelte

import ai.torad.aisdk.ExperimentalAiSdkApi
import ai.torad.aisdk.StructuredObjectOptions

@ExperimentalAiSdkApi
typealias Chat = ai.torad.aisdk.ui.Chat

@ExperimentalAiSdkApi
typealias UIMessage = ai.torad.aisdk.ui.UIMessage

@ExperimentalAiSdkApi
typealias Completion = ai.torad.aisdk.Completion

@ExperimentalAiSdkApi
typealias CompletionOptions = ai.torad.aisdk.UseCompletionOptions

@ExperimentalAiSdkApi
typealias Experimental_StructuredObject<RESULT, INPUT> = ai.torad.aisdk.StructuredObject<RESULT, INPUT>

@ExperimentalAiSdkApi
typealias Experimental_StructuredObjectOptions<RESULT, INPUT> = StructuredObjectOptions<RESULT, INPUT>

@ExperimentalAiSdkApi
class AIContext(
    val completions: MutableMap<String, Completion> = linkedMapOf(),
    val structuredObjects: MutableMap<String, ai.torad.aisdk.StructuredObject<*, *>> = linkedMapOf(),
)

@ExperimentalAiSdkApi
fun createAIContext(): AIContext = AIContext()
