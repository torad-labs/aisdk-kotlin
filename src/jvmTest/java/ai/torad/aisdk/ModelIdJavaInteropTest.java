package ai.torad.aisdk;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ModelIdJavaInteropTest {
    @Test
    public void modelIdFactoryIsCallableFromJava() {
        ModelId id = ModelId.of("gpt-4");
        assertEquals("gpt-4", id.getValue());
    }

    @Test
    public void remainingValueClassFactoriesAreCallableFromJava() {
        ProviderId providerId = ProviderId.of("openai");
        ToolCallId toolCallId = ToolCallId.of("call_1");
        ToolName toolName = ToolName.of("weather");
        ApprovalId approvalId = ApprovalId.of("approval_1");

        assertEquals("openai", providerId.getValue());
        assertEquals("call_1", toolCallId.getValue());
        assertEquals("weather", toolName.getValue());
        assertEquals("approval_1", approvalId.getValue());
    }

    @Test
    public void modelRefValueClassAccessorsAreCallableFromJava() {
        ModelRef ref = new ModelRef(ModelId.of("gpt-4"), ProviderId.of("openai"));

        assertEquals("gpt-4", ref.getModelId().getValue());
        assertEquals("openai", ref.getProviderId().getValue());
        assertEquals("gpt-4", ref.component1().getValue());
        assertEquals("openai", ref.component2().getValue());
        assertEquals("openai:gpt-4", ref.getQualifiedName());

        ModelRef copied = ref.copy(ModelId.of("gpt-4.1"), ProviderId.of("openai"));
        assertEquals("gpt-4.1", copied.getModelId().getValue());
        assertEquals("openai", copied.getProviderId().getValue());
    }
}
