package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ABI-shape tenet for the public top-level `StreamObjectResult` factory.
 *
 * The factory is a CONSTRUCT surface — consumers call it — so per CLAUDE.md it must not freeze a
 * flattened call-settings parameter list into the ABI. A flattened list makes adding one field
 * change both the full-arity static and the synthesized `StreamObjectResult$default` bridge, which
 * is a `NoSuchMethodError` for every already-compiled caller. Settings travel as one [CallConfig]
 * instead, which grows without touching this signature.
 *
 * Asserted through JVM reflection rather than Konsist because the frozen artifact is the emitted
 * descriptor (including the `$default` bridge), not the source declaration.
 */
class StreamObjectResultFactoryShapeTest {

    private companion object {
        /** model + output + input + config + repairText. */
        const val MAX_FACTORY_PARAMETERS = 5
    }

    @Test
    fun `the top-level StreamObjectResult factory does not flatten call settings into parameters`() {
        val factories = Class.forName("ai.torad.aisdk.StreamObjectResultKt")
            .methods
            .filter { it.name == "StreamObjectResult" }

        assertTrue(factories.isNotEmpty(), "no top-level StreamObjectResult factory found — the test target moved")
        val widest = factories.maxOf { it.parameterCount }
        assertTrue(
            widest <= MAX_FACTORY_PARAMETERS,
            "the StreamObjectResult factory takes $widest positional parameters; call settings " +
                "belong in CallConfig so the frozen signature never has to grow",
        )
    }
}
