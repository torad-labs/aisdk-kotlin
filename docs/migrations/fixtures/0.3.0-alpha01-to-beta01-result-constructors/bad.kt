package consumer

import ai.torad.aisdk.CallWarning
import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.GenerateResult
import ai.torad.aisdk.GenerateTextResult
import ai.torad.aisdk.StepResult
import ai.torad.aisdk.StructuredObjectFinish
import ai.torad.aisdk.StructuredObjectPhase
import ai.torad.aisdk.Usage
import kotlinx.serialization.json.JsonPrimitive

class OldResultConstructorFixture {
    fun run() {
        GenerateResult<String>(
            rawOutput = "answer",
            text = "answer",
            steps = emptyList(),
            finishReason = FinishReason.Stop,
            usage = Usage(),
        )
        GenerateTextResult(
            output = "answer",
            text = "answer",
            toolCalls = emptyList(),
            finishReason = FinishReason.Stop,
            usage = Usage(),
        )
        StepResult(
            stepNumber = 1,
            text = "answer",
            reasoning = "",
            toolCalls = emptyList(),
            toolResults = emptyList(),
            toolApprovalRequests = emptyList(),
            finishReason = FinishReason.Stop,
            usage = Usage(),
        )
        StructuredObjectFinish(
            value = "answer",
            error = null,
            rawValue = JsonPrimitive("answer"),
            warnings = listOf(CallWarning("other", "kept")),
        )
        StructuredObjectPhase.Streaming(
            partial = "partial",
            raw = JsonPrimitive("partial"),
            error = null,
        )
        StructuredObjectPhase.Done<String>(
            value = "answer",
            raw = JsonPrimitive("answer"),
            error = null,
        )
    }
}
