package ai.torad.aisdk

import kotlinx.serialization.json.JsonObject

internal object StreamEventTelemetryRedaction {
    @Suppress("CyclomaticComplexMethod")
    fun sanitize(event: StreamEvent, settings: TelemetrySettings, redactor: Redactor): StreamEvent =
        when (event) {
            is StreamEvent.TextDelta -> if (settings.recordOutputs) {
                event.copy(text = redactor.redactText(event.text), providerMetadata = ProviderMetadata.None)
            } else {
                event.copy(text = "", providerMetadata = ProviderMetadata.None)
            }
            is StreamEvent.ReasoningDelta -> if (settings.recordOutputs) {
                event.copy(text = redactor.redactText(event.text), providerMetadata = ProviderMetadata.None)
            } else {
                event.copy(text = "", providerMetadata = ProviderMetadata.None)
            }
            is StreamEvent.ToolCall ->
                event.copy(
                    inputJson = if (settings.recordInputs) {
                        redactor.redactJson(event.inputJson)
                    } else {
                        JsonObject(emptyMap())
                    },
                    providerMetadata = ProviderMetadata.None,
                )
            is StreamEvent.ToolResult -> toolResult(event, settings, redactor)
            is StreamEvent.ToolError -> event.copy(
                message = redactor.redactText(event.message),
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.Error -> event.copy(
                message = redactor.redactText(event.message),
                cause = event.cause?.let {
                    RuntimeException(it.message?.let(redactor::redactText) ?: (it::class.simpleName ?: "Throwable"))
                },
            )
            is StreamEvent.FilePart -> event.copy(base64 = "", providerMetadata = ProviderMetadata.None)
            is StreamEvent.ResponseMetadata -> responseMetadata(event, settings, redactor)
            is StreamEvent.SourcePart -> sourcePart(event, settings, redactor)
            is StreamEvent.ToolInputDelta -> toolInputDelta(event, settings, redactor)
            is StreamEvent.ToolApprovalRequest -> toolApprovalRequest(event, settings, redactor)
            is StreamEvent.Raw -> raw(event, settings, redactor)
            is StreamEvent.StepStart -> event.copy(providerMetadata = ProviderMetadata.None)
            is StreamEvent.TextStart -> event.copy(providerMetadata = ProviderMetadata.None)
            is StreamEvent.TextEnd -> event.copy(providerMetadata = ProviderMetadata.None)
            is StreamEvent.ReasoningStart -> event.copy(providerMetadata = ProviderMetadata.None)
            is StreamEvent.ReasoningEnd -> event.copy(providerMetadata = ProviderMetadata.None)
            is StreamEvent.ToolInputStart -> event.copy(providerMetadata = ProviderMetadata.None)
            is StreamEvent.ToolInputEnd -> event.copy(providerMetadata = ProviderMetadata.None)
            is StreamEvent.ToolOutputDenied -> event.copy(
                reason = event.reason?.let(redactor::redactText),
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.StepFinish -> event.copy(
                usage = event.usage.sanitizedForTelemetry(),
                providerMetadata = ProviderMetadata.None,
            )
            is StreamEvent.Finish -> event.copy(
                usage = event.usage.sanitizedForTelemetry(),
                providerMetadata = ProviderMetadata.None,
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
    ): StreamEvent.ResponseMetadata = event.copy(
        headers = redactor.redactHeaders(event.headers),
        body = event.body?.takeIf { settings.recordOutputs }?.let(redactor::redactJson),
    )

    private fun sourcePart(
        event: StreamEvent.SourcePart,
        settings: TelemetrySettings,
        redactor: Redactor,
    ): StreamEvent.SourcePart =
        if (settings.recordOutputs) {
            event.copy(
                url = null,
                title = event.title?.let(redactor::redactText),
                providerMetadata = ProviderMetadata.None,
            )
        } else {
            event.copy(url = null, title = null, mediaType = null, providerMetadata = ProviderMetadata.None)
        }

    private fun toolInputDelta(
        event: StreamEvent.ToolInputDelta,
        settings: TelemetrySettings,
        redactor: Redactor,
    ): StreamEvent.ToolInputDelta = event.copy(
        delta = if (settings.recordInputs) redactor.redactText(event.delta) else "",
        providerMetadata = ProviderMetadata.None,
    )

    private fun toolApprovalRequest(
        event: StreamEvent.ToolApprovalRequest,
        settings: TelemetrySettings,
        redactor: Redactor,
    ): StreamEvent.ToolApprovalRequest = event.copy(
        inputJson = if (settings.recordInputs) {
            redactor.redactJson(event.inputJson)
        } else {
            JsonObject(emptyMap())
        },
        signature = null,
        providerMetadata = ProviderMetadata.None,
    )

    private fun raw(event: StreamEvent.Raw, settings: TelemetrySettings, redactor: Redactor): StreamEvent.Raw =
        if (settings.recordInputs && settings.recordOutputs) {
            event.copy(rawValue = redactor.redactJson(event.rawValue))
        } else {
            event.copy(rawValue = JsonObject(emptyMap()))
        }

    private fun Usage.sanitizedForTelemetry(): Usage = copy(raw = null)
}
