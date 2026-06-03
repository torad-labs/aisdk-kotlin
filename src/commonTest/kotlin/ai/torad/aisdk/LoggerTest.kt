package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Surface tests for the [Logger] primitive added in Phase 4F #31.
 * Verifies the port shipping a noop default + a custom test
 * implementation that captures messages.
 */
class LoggerTest {

    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()
        val infos = mutableListOf<String>()
        val debugs = mutableListOf<String>()
        override fun warn(message: String, throwable: Throwable?) {
            warnings.add(message)
        }
        override fun info(message: String) {
            infos.add(message)
        }
        override fun debug(message: String) {
            debugs.add(message)
        }
    }

    @Test
    fun `given the noop logger when all severities are invoked then nothing throws`() {
        // GIVEN — the default sink consumers fall back to.
        val logger: Logger = NoopLogger

        // WHEN/THEN — every call returns Unit without raising.
        logger.warn("warn message")
        logger.warn("warn with cause", RuntimeException("x"))
        logger.info("info message")
        logger.debug("debug message")
    }

    @Test
    fun `given a capturing logger when messages flow then each severity is captured separately`() {
        // GIVEN
        val logger = CapturingLogger()

        // WHEN
        logger.warn("tool-call repair attempted")
        logger.warn("retry under construction", RuntimeException("transient"))
        logger.info("simulateStreaming fallback")
        logger.debug("loop step 3 elapsed=42ms")
        logger.debug("loop step 4 elapsed=67ms")

        // THEN
        assertEquals(listOf("tool-call repair attempted", "retry under construction"), logger.warnings)
        assertEquals(listOf("simulateStreaming fallback"), logger.infos)
        assertEquals(listOf("loop step 3 elapsed=42ms", "loop step 4 elapsed=67ms"), logger.debugs)
    }

    @Test
    fun `given the Logger interface when used in a function parameter then it accepts both impls`() {
        // GIVEN — consumers depend on the interface, not the impl.
        fun useLogger(logger: Logger) {
            logger.info("called via interface")
        }

        // WHEN/THEN — both impls are interchangeable.
        useLogger(NoopLogger)
        val captured = CapturingLogger()
        useLogger(captured)
        assertTrue(captured.infos.size == 1, "the capturing impl received the call")
    }
}
