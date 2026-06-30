package ai.torad.aisdk

import kotlinx.serialization.json.JsonObject

internal object StreamEventTelemetryRedaction {
    @Suppress("CyclomaticComplexMethod")
    fun sanitize(event: StreamEvent, settings: TelemetrySettings, redactor: Redactor): StreamEvent =
        when (event) {
            is StreamEvent.TextDelta -> if (settings.recordOutputs) {
                StreamEvent.TextDelta(
                    id = event.id,
                    text = redactor.redactText(event.text),
                    providerMetadata = ProviderMetadata.None,
                )
            } else {
                StreamEvent.TextDelta(
                    id = event.id,
                    text = "",
                    providerMetadata = ProviderMetadata.None,
                )
            }
            is StreamEvent.ReasoningDelta -> if (settings.recordOutputs) {
                StreamEvent.ReasoningDelta(
                    id = event.id,
                    text = redactor.redactText(event.text),
                    providerMetadata = ProviderMetadata.None,
                )
            } else {
                StreamEvent.ReasoningDelta(
                    id = event.id,
                    text = "",
                    providerMetadata = ProviderMetadata.None,
                )
            }
            is StreamEvent.ToolCall ->
                StreamEvent.ToolCall(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    inputJson = if (settings.recordInputs) {
                        redactor.redactJson(event.inputJson)
                    } else {
                        JsonObject(emptyMap())
                    },
                    providerMetadata = ProviderMetadata.None,
                )
            is StreamEvent.ToolResult -> toolResult(event, settings, redactor)
            is StreamEvent.ToolError -> StreamEvent.ToolError(
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                message = redactor.redactText(event.message),
                error = event.error,
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.Error -> StreamEvent.Error(
                message = redactor.redactText(event.message),
                cause = event.cause?.let {
                    RuntimeException(it.message?.let(redactor::redactText) ?: (it::class.simpleName ?: "Throwable"))
                },
            )
            is StreamEvent.FilePart -> StreamEvent.FilePart(
                id = event.id,
                mediaType = event.mediaType,
                base64 = "",
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.ResponseMetadata -> responseMetadata(event, settings, redactor)
            is StreamEvent.SourcePart -> sourcePart(event, settings, redactor)
            is StreamEvent.ToolInputDelta -> toolInputDelta(event, settings, redactor)
            is StreamEvent.ToolApprovalRequest -> toolApprovalRequest(event, settings, redactor)
            is StreamEvent.Raw -> raw(event, settings, redactor)
            is StreamEvent.StepStart -> StreamEvent.StepStart(
                stepNumber = event.stepNumber,
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.TextStart -> StreamEvent.TextStart(
                id = event.id,
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.TextEnd -> StreamEvent.TextEnd(
                id = event.id,
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.ReasoningStart -> StreamEvent.ReasoningStart(
                id = event.id,
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.ReasoningEnd -> StreamEvent.ReasoningEnd(
                id = event.id,
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.ToolInputStart -> StreamEvent.ToolInputStart(
                id = event.id,
                toolName = event.toolName,
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.ToolInputEnd -> StreamEvent.ToolInputEnd(
                id = event.id,
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.ToolOutputDenied -> StreamEvent.ToolOutputDenied(
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                approvalId = event.approvalId,
                reason = event.reason?.let(redactor::redactText),
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.StepFinish -> StreamEvent.StepFinish(
                stepNumber = event.stepNumber,
                finishReason = event.finishReason,
                usage = event.usage.sanitizedForTelemetry(),
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.Finish -> StreamEvent.Finish(
                totalSteps = event.totalSteps,
                finishReason = event.finishReason,
                usage = event.usage.sanitizedForTelemetry(),
                providerMetadata = ProviderMetadata.None,
                rawFinishReason = event.rawFinishReason,
            )
            is StreamEvent.StreamStart, StreamEvent.Abort, -> event
        }

    private fun toolResult(
        event: StreamEvent.ToolResult,
        settings: TelemetrySettings,
        redactor: Redactor,
    ): StreamEvent.ToolResult =
        StreamEvent.ToolResult(
            toolCallId = event.toolCallId,
            toolName = event.toolName,
            outputJson = if (settings.recordOutputs) {
                redactor.redactJson(event.outputJson)
            } else {
                JsonObject(emptyMap())
            },
            preliminary = event.preliminary,
        )

    private fun responseMetadata(
        event: StreamEvent.ResponseMetadata,
        settings: TelemetrySettings,
        redactor: Redactor,
    ): StreamEvent.ResponseMetadata = StreamEvent.ResponseMetadata(
        id = event.id,
        timestampMillis = event.timestampMillis,
        modelId = event.modelId,
        headers = redactor.redactHeaders(event.headers),
        body = event.body?.takeIf { settings.recordOutputs }?.let(redactor::redactJson),
    )

    private fun sourcePart(
        event: StreamEvent.SourcePart,
        settings: TelemetrySettings,
        redactor: Redactor,
    ): StreamEvent.SourcePart =
        if (settings.recordOutputs) {
            StreamEvent.SourcePart(
                id = event.id,
                sourceType = event.sourceType,
                url = null,
                title = event.title?.let(redactor::redactText),
                mediaType = event.mediaType,
                providerMetadata = ProviderMetadata.None,
            )
        } else {
            StreamEvent.SourcePart(
                id = event.id,
                sourceType = event.sourceType,
                url = null,
                title = null,
                mediaType = null,
                providerMetadata = ProviderMetadata.None,
            )
        }

    private fun toolInputDelta(
        event: StreamEvent.ToolInputDelta,
        settings: TelemetrySettings,
        redactor: Redactor,
    ): StreamEvent.ToolInputDelta = StreamEvent.ToolInputDelta(
        id = event.id,
        delta = if (settings.recordInputs) redactor.redactText(event.delta) else "",
        providerMetadata = ProviderMetadata.None,
    )

    private fun toolApprovalRequest(
        event: StreamEvent.ToolApprovalRequest,
        settings: TelemetrySettings,
        redactor: Redactor,
    ): StreamEvent.ToolApprovalRequest = StreamEvent.ToolApprovalRequest(
        toolCallId = event.toolCallId,
        toolName = event.toolName,
        inputJson = if (settings.recordInputs) {
            redactor.redactJson(event.inputJson)
        } else {
            JsonObject(emptyMap())
        },
        approvalId = event.approvalId,
        signature = null,
        providerMetadata = ProviderMetadata.None,
    )

    private fun raw(event: StreamEvent.Raw, settings: TelemetrySettings, redactor: Redactor): StreamEvent.Raw =
        if (settings.recordInputs && settings.recordOutputs) {
            StreamEvent.Raw(rawValue = redactor.redactJson(event.rawValue))
        } else {
            StreamEvent.Raw(rawValue = JsonObject(emptyMap()))
        }

    private fun Usage.sanitizedForTelemetry(): Usage = copy(raw = null)
}
