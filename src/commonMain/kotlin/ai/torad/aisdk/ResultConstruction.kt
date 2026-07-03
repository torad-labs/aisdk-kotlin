package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

@Suppress("LongParameterList")
internal object ResultConstruction {
    fun <TOutput> generateResult(
        rawOutput: TOutput,
        text: String,
        steps: List<StepResult>,
        finishReason: FinishReason,
        usage: Usage,
        totalUsage: Usage = usage,
        pendingApprovals: List<PendingApproval> = emptyList(),
        messages: List<ModelMessage> = emptyList(),
    ): GenerateResult<TOutput> =
        GenerateResult(
            rawOutput = rawOutput,
            text = text,
            steps = steps,
            finishReason = finishReason,
            usage = usage,
            totalUsage = totalUsage,
            pendingApprovals = pendingApprovals,
            messages = messages,
        )

    fun <TOutput> generateTextResult(
        output: TOutput,
        text: String,
        toolCalls: List<ContentPart.ToolCall>,
        finishReason: FinishReason,
        usage: Usage,
        content: List<ContentPart> = buildList {
            if (text.isNotEmpty()) add(ContentPart.Text(text))
            addAll(toolCalls)
        },
        toolResults: List<ContentPart.ToolResult> = content.filterIsInstance<ContentPart.ToolResult>(),
        reasoning: List<ContentPart.Reasoning> = content.filterIsInstance<ContentPart.Reasoning>(),
        reasoningText: String? = reasoning.takeIf { it.isNotEmpty() }?.joinToString("") { it.text },
        files: List<ContentPart.File> = content.filterIsInstance<ContentPart.File>(),
        sources: List<ContentPart.Source> = content.filterIsInstance<ContentPart.Source>(),
        totalUsage: Usage = usage,
        warnings: List<CallWarning> = emptyList(),
        request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
        response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
        providerMetadata: ProviderMetadata = ProviderMetadata.None,
        steps: List<StepResult> = emptyList(),
        rawFinishReason: String? = null,
    ): GenerateTextResult<TOutput> =
        GenerateTextResult(
            output = output,
            text = text,
            toolCalls = toolCalls,
            finishReason = finishReason,
            usage = usage,
            content = content,
            toolResults = toolResults,
            reasoning = reasoning,
            reasoningText = reasoningText,
            files = files,
            sources = sources,
            totalUsage = totalUsage,
            warnings = warnings,
            request = request,
            response = response,
            providerMetadata = providerMetadata,
            steps = steps,
            rawFinishReason = rawFinishReason,
        )

    fun stepResult(
        stepNumber: Int,
        text: String,
        reasoning: String,
        toolCalls: List<ContentPart.ToolCall>,
        toolResults: List<ContentPart.ToolResult>,
        toolApprovalRequests: List<ContentPart.ToolApprovalRequest>,
        finishReason: FinishReason,
        usage: Usage,
        warnings: List<CallWarning> = emptyList(),
        request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
        response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
        providerMetadata: ProviderMetadata = ProviderMetadata.None,
        rawFinishReason: String? = null,
        model: String? = null,
        experimentalContext: Any? = null,
    ): StepResult =
        StepResult(
            stepNumber = stepNumber,
            text = text,
            reasoning = reasoning,
            toolCalls = toolCalls,
            toolResults = toolResults,
            toolApprovalRequests = toolApprovalRequests,
            finishReason = finishReason,
            usage = usage,
            warnings = warnings,
            request = request,
            response = response,
            providerMetadata = providerMetadata,
            rawFinishReason = rawFinishReason,
            model = model,
            experimentalContext = experimentalContext,
        )

    fun <RESULT> structuredObjectFinish(
        value: RESULT?,
        error: Throwable?,
        rawValue: JsonElement?,
        warnings: List<CallWarning> = emptyList(),
    ): StructuredObjectFinish<RESULT> =
        StructuredObjectFinish(
            value = value,
            error = error,
            rawValue = rawValue,
            warnings = warnings,
        )

    fun <RESULT> structuredObjectStreamingPhase(
        partial: RESULT?,
        raw: JsonElement?,
        error: Throwable?,
        warnings: List<CallWarning> = emptyList(),
    ): StructuredObjectPhase.Streaming<RESULT> =
        StructuredObjectPhase.Streaming(
            partial = partial,
            raw = raw,
            error = error,
            warnings = warnings,
        )

    fun <RESULT> structuredObjectDonePhase(
        value: RESULT?,
        raw: JsonElement?,
        error: Throwable?,
        warnings: List<CallWarning> = emptyList(),
    ): StructuredObjectPhase.Done<RESULT> =
        StructuredObjectPhase.Done(
            value = value,
            raw = raw,
            error = error,
            warnings = warnings,
        )
}
