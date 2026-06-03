package ai.torad.aisdk.codemod

data class CodemodRule(
    val id: String,
    val description: String,
    val apply: (String) -> String,
)

data class CodemodResult(
    val output: String,
    val appliedRules: List<String>,
) {
    val changed: Boolean get() = appliedRules.isNotEmpty()
}

object AiSdkCodemods {
    val replaceDataStreamWithUiMessageStream = CodemodRule(
        id = "replace-datastream-to-uimessagestream",
        description = "Rename v5 data-stream helpers to v6 UI message stream helpers.",
    ) { source ->
        source
            .replace("toDataStreamResponse", "toUIMessageStreamResponse")
            .replace("pipeDataStreamToResponse", "pipeUIMessageStreamToResponse")
            .replace("DataStream", "UIMessageStream")
            .replace("data stream", "UI message stream")
    }

    val replaceUseChatInputWithState = CodemodRule(
        id = "replace-usechat-input-with-state",
        description = "Remove v5 useChat input helpers from destructuring so hosts manage input state explicitly.",
    ) { source ->
        source
            .replace("input, handleInputChange, handleSubmit, ", "")
            .replace(", input, handleInputChange, handleSubmit", "")
            .replace("input, handleInputChange, handleSubmit", "")
    }

    val rewriteFrameworkImports = CodemodRule(
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

    val all: List<CodemodRule> = listOf(
        replaceDataStreamWithUiMessageStream,
        replaceUseChatInputWithState,
        rewriteFrameworkImports,
    )
}

fun applyAiSdkCodemods(
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
