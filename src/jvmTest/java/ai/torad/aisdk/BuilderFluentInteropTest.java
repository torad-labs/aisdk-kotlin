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

    @Test
    public void coreChunkBuildersChainAndBuildFromJava() {
        CallConfig config = new CallConfigBuilder()
            .temperature(0.2f)
            .maxRetries(4)
            .stopSequences(Arrays.asList("END"))
            .build();

        assertEquals(Float.valueOf(0.2f), config.getTemperature());
        assertEquals(4, config.getMaxRetries());
        assertEquals(Arrays.asList("END"), config.getStopSequences());

        IdGenerator generator = new IdGeneratorBuilder()
            .prefix("msg")
            .size(24)
            .separator("_")
            .build();

        assertEquals("msg", generator.getPrefix());
        assertEquals(24, generator.getSize());
        assertEquals("_", generator.getSeparator());

        TelemetrySettings telemetry = new TelemetrySettingsBuilder()
            .functionId("fn")
            .recordInputs(true)
            .recordOutputs(true)
            .build();

        assertEquals("fn", telemetry.getFunctionId());
        assertEquals(true, telemetry.getRecordInputs());
        assertEquals(true, telemetry.getRecordOutputs());
    }
}
