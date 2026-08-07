package ai.torad.aisdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage tenet for [CallConfig.withResponseFormat].
 *
 * The structured-output entry points derive the response format from the requested `Output` and
 * carry the caller's remaining settings across by rebuilding the config field by field. A field
 * missing from that rebuild is a setting the caller passed and the request never sees — silent at
 * the call site, invisible in a diff, and only observable as a provider request that quietly
 * ignores a header or a timeout.
 *
 * So this pins the rebuild twice: every field is preserved with a distinguishable value, and the
 * field SET itself is pinned, because preservation alone cannot notice a field that neither the
 * copy nor this test knows about yet.
 */
class CallConfigCopyCoverageTest {

    private companion object {
        /**
         * Every public read-only property of [CallConfig]. Adding a field here without adding it
         * to [CallConfig.withResponseFormat] is the mistake this test exists to catch.
         */
        val EXPECTED_FIELDS = setOf(
            "temperature",
            "topP",
            "topK",
            "maxOutputTokens",
            "stopSequences",
            "seed",
            "providerOptions",
            "abortSignal",
            "presencePenalty",
            "frequencyPenalty",
            "responseFormat",
            "headers",
            "timeout",
            "maxRetries",
        )
    }

    private fun populated(): CallConfig = CallConfig {
        temperature(0.25f)
        topP(0.75f)
        topK(11)
        maxOutputTokens(321)
        stopSequences(listOf("halt"))
        seed(4242)
        providerOptions(ProviderOptions("acme" to JsonObject(mapOf("k" to JsonPrimitive("v")))))
        abortSignal(AbortController().signal)
        presencePenalty(0.5f)
        frequencyPenalty(1.5f)
        responseFormat(ResponseFormat.Text)
        headers(mapOf("x-trace" to "abc"))
        timeout(30.seconds)
        maxRetries(7)
    }

    /**
     * Reflected public property names, so a new field cannot slip past unnoticed. Accessors for
     * value-class types (`timeout: Duration?`) are emitted twice — once plain, once name-mangled
     * as `getTimeout-FghU774` — so the mangled twin is dropped rather than counted as a field.
     */
    private fun publicFieldNames(): Set<String> =
        CallConfig::class.java.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }
            .map { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
            .filter { it != "class" && !it.contains('-') }
            .toSet()

    @Test
    fun `CallConfig has exactly the fields the response-format copy knows how to carry`() {
        assertEquals(
            EXPECTED_FIELDS,
            publicFieldNames(),
            "CallConfig's field set changed. Add the field to CallConfig.withResponseFormat too, " +
                "or structured-output calls will silently drop it, then update EXPECTED_FIELDS.",
        )
    }

    @Test
    fun `the response-format copy preserves every other caller setting`() {
        val source = populated()
        val derived = ResponseFormat.Json(schemaName = "person")

        val copy = source.withResponseFormat(derived)

        assertEquals(derived, copy.responseFormat, "the requested format must be the one applied")
        assertEquals(source.temperature, copy.temperature)
        assertEquals(source.topP, copy.topP)
        assertEquals(source.topK, copy.topK)
        assertEquals(source.maxOutputTokens, copy.maxOutputTokens)
        assertEquals(source.stopSequences, copy.stopSequences)
        assertEquals(source.seed, copy.seed)
        assertEquals(source.providerOptions, copy.providerOptions)
        assertEquals(source.abortSignal, copy.abortSignal)
        assertEquals(source.presencePenalty, copy.presencePenalty)
        assertEquals(source.frequencyPenalty, copy.frequencyPenalty)
        assertEquals(source.headers, copy.headers)
        assertEquals(source.timeout, copy.timeout)
        assertEquals(source.maxRetries, copy.maxRetries)
    }
}
