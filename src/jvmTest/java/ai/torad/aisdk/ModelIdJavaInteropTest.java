package ai.torad.aisdk;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ModelIdJavaInteropTest {
    @Test
    public void modelIdFactoryIsCallableFromJava() {
        ModelId id = ModelId.of("gpt-4");
        assertEquals("gpt-4", id.getValue());
    }
}
