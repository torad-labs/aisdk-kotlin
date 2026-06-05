package ai.torad.aisdk.svelte

import ai.torad.aisdk.ExperimentalAiSdkApi
import ai.torad.aisdk.StructuredObjectOptions

@ExperimentalAiSdkApi
public typealias Chat = ai.torad.aisdk.ui.Chat

@ExperimentalAiSdkApi
public typealias UIMessage = ai.torad.aisdk.ui.UIMessage

@ExperimentalAiSdkApi
public typealias Completion = ai.torad.aisdk.Completion

@ExperimentalAiSdkApi
public typealias CompletionOptions = ai.torad.aisdk.UseCompletionOptions

@ExperimentalAiSdkApi
public typealias Experimental_StructuredObject<RESULT, INPUT> = ai.torad.aisdk.StructuredObject<RESULT, INPUT>

@ExperimentalAiSdkApi
public typealias Experimental_StructuredObjectOptions<RESULT, INPUT> = StructuredObjectOptions<RESULT, INPUT>

@ExperimentalAiSdkApi
public class AIContext(
    public val completions: MutableMap<String, Completion> = linkedMapOf(),
    public val structuredObjects: MutableMap<String, ai.torad.aisdk.StructuredObject<*, *>> = linkedMapOf(),
)

@ExperimentalAiSdkApi
public fun createAIContext(): AIContext = AIContext()
