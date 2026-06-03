package ai.torad.aisdk.vue

import ai.torad.aisdk.Completion
import ai.torad.aisdk.CompletionRequestOptions
import ai.torad.aisdk.StructuredObject
import ai.torad.aisdk.StructuredObjectOptions
import ai.torad.aisdk.UseCompletionOptions
import ai.torad.aisdk.react.Experimental_UseObjectHelpers
import ai.torad.aisdk.react.UseCompletionHelpers

typealias Chat = ai.torad.aisdk.ui.Chat
typealias UIMessage = ai.torad.aisdk.ui.UIMessage
typealias UseCompletionOptions = ai.torad.aisdk.UseCompletionOptions
typealias CompletionRequestOptions = ai.torad.aisdk.CompletionRequestOptions
typealias Experimental_UseObjectOptions<RESULT, INPUT> = StructuredObjectOptions<RESULT, INPUT>

fun useCompletion(options: UseCompletionOptions = UseCompletionOptions()): UseCompletionHelpers =
    UseCompletionHelpers(Completion(options))

fun <RESULT, INPUT> experimental_useObject(
    options: StructuredObjectOptions<RESULT, INPUT>,
): Experimental_UseObjectHelpers<RESULT, INPUT> =
    Experimental_UseObjectHelpers(StructuredObject(options))
