package ai.torad.aisdk.codemod

import ai.torad.aisdk.ExperimentalAiSdkApi

@ExperimentalAiSdkApi
public data class CodemodRule(
    val id: String,
    val description: String,
    val apply: (String) -> String,
)

@ExperimentalAiSdkApi
public data class CodemodResult(
    val output: String,
    val appliedRules: List<String>,
) {
    val changed: Boolean get() = appliedRules.isNotEmpty()
}

@ExperimentalAiSdkApi
public object AiSdkCodemods {
    public val replaceDataStreamWithUiMessageStream: CodemodRule = CodemodRule(
        id = "replace-datastream-to-uimessagestream",
        description = "Rename v5 data-stream helpers to v6 UI message stream helpers.",
    ) { source ->
        source
            .replace("toDataStreamResponse", "toUIMessageStreamResponse")
            .replace("pipeDataStreamToResponse", "pipeUIMessageStreamToResponse")
            .replace("DataStream", "UIMessageStream")
            .replace("data stream", "UI message stream")
    }

    public val replaceUseChatInputWithState: CodemodRule = CodemodRule(
        id = "replace-usechat-input-with-state",
        description = "Remove v5 useChat input helpers from destructuring so hosts manage input state explicitly.",
    ) { source ->
        source
            .replace("input, handleInputChange, handleSubmit, ", "")
            .replace(", input, handleInputChange, handleSubmit", "")
            .replace("input, handleInputChange, handleSubmit", "")
    }

    public val rewriteFrameworkImports: CodemodRule = CodemodRule(
        id = "rewrite-framework-imports",
        description = "Rewrite upstream framework package imports to AISDK Kotlin facade package comments for migration notes.",
    ) { source ->
        source
            .replace("@ai-sdk/react", "ai.torad.aisdk.react")
            .replace("@ai-sdk/vue", "ai.torad.aisdk.vue")
            .replace("@ai-sdk/svelte", "ai.torad.aisdk.svelte")
            .replace("@ai-sdk/angular", "ai.torad.aisdk.angular")
            .replace("@ai-sdk/rsc", "ai.torad.aisdk.rsc")
    }

    public val all: List<CodemodRule> = listOf(
        replaceDataStreamWithUiMessageStream,
        replaceUseChatInputWithState,
        rewriteFrameworkImports,
    )
}

@ExperimentalAiSdkApi
public fun applyAiSdkCodemods(
    source: String,
    rules: List<CodemodRule> = AiSdkCodemods.all,
): CodemodResult {
    var current = source
    val applied = mutableListOf<String>()
    for (rule in rules) {
        val next = rule.apply(current)
        if (next != current) {
            applied += rule.id
            current = next
        }
    }
    return CodemodResult(current, applied)
}
