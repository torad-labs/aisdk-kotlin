package ai.torad.aisdk;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public final class BuilderFluentInteropTest {
    @Test
    public void callSettingsBuilderChainsAndBuildsFromJava() {
        CallSettings settings = new CallSettingsBuilder()
            .temperature(0.7f)
            .maxRetries(3)
            .stopSequence("END")
            .stopSequences(Arrays.asList("STOP", "DONE"))
            .build();

        assertEquals(Float.valueOf(0.7f), settings.getTemperature());
        assertEquals(3, settings.getMaxRetries());
        assertEquals(Arrays.asList("END", "STOP", "DONE"), settings.getStopSequences());
    }

    @Test
    public void toolExecutionPolicyBuilderChainsAndBuildsFromJava() {
        ToolExecutionPolicy policy = new ToolExecutionPolicyBuilder()
            .maxParallelToolCalls(2)
            .maxToolCallsPerStep(9)
            .progressBufferCapacity(4)
            .build();

        assertEquals(2, policy.getMaxParallelToolCalls());
        assertEquals(9, policy.getMaxToolCallsPerStep());
        assertEquals(4, policy.getProgressBufferCapacity());
    }
}
