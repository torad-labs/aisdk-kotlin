package ai.torad.aisdk.vue

import ai.torad.aisdk.Completion
import ai.torad.aisdk.CompletionRequestOptions
import ai.torad.aisdk.ExperimentalAiSdkApi
import ai.torad.aisdk.StructuredObject
import ai.torad.aisdk.StructuredObjectOptions
import ai.torad.aisdk.UseCompletionOptions
import ai.torad.aisdk.react.Experimental_UseObjectHelpers
import ai.torad.aisdk.react.UseCompletionHelpers

@ExperimentalAiSdkApi
typealias Chat = ai.torad.aisdk.ui.Chat

@ExperimentalAiSdkApi
typealias UIMessage = ai.torad.aisdk.ui.UIMessage

@ExperimentalAiSdkApi
typealias UseCompletionOptions = ai.torad.aisdk.UseCompletionOptions

@ExperimentalAiSdkApi
typealias CompletionRequestOptions = ai.torad.aisdk.CompletionRequestOptions

@ExperimentalAiSdkApi
typealias Experimental_UseObjectOptions<RESULT, INPUT> = StructuredObjectOptions<RESULT, INPUT>

@ExperimentalAiSdkApi
fun useCompletion(options: UseCompletionOptions = UseCompletionOptions()): UseCompletionHelpers =
    UseCompletionHelpers(Completion(options))

@ExperimentalAiSdkApi
fun <RESULT, INPUT> experimental_useObject(
    options: StructuredObjectOptions<RESULT, INPUT>,
): Experimental_UseObjectHelpers<RESULT, INPUT> =
    Experimental_UseObjectHelpers(StructuredObject(options))
