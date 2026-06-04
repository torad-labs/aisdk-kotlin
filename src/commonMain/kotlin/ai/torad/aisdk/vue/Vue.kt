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
public typealias Chat = ai.torad.aisdk.ui.Chat

@ExperimentalAiSdkApi
public typealias UIMessage = ai.torad.aisdk.ui.UIMessage

@ExperimentalAiSdkApi
public typealias UseCompletionOptions = ai.torad.aisdk.UseCompletionOptions

@ExperimentalAiSdkApi
public typealias CompletionRequestOptions = ai.torad.aisdk.CompletionRequestOptions

@ExperimentalAiSdkApi
public typealias Experimental_UseObjectOptions<RESULT, INPUT> = StructuredObjectOptions<RESULT, INPUT>

@ExperimentalAiSdkApi
public fun useCompletion(options: UseCompletionOptions = UseCompletionOptions()): UseCompletionHelpers =
    UseCompletionHelpers(Completion(options))

@ExperimentalAiSdkApi
public fun <RESULT, INPUT> experimental_useObject(
    options: StructuredObjectOptions<RESULT, INPUT>,
): Experimental_UseObjectHelpers<RESULT, INPUT> =
    Experimental_UseObjectHelpers(StructuredObject(options))
